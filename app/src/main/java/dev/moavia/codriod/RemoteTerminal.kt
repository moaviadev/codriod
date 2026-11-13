package dev.moavia.codriod

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.jcraft.jsch.ChannelShell
import com.jcraft.jsch.JSch
import com.jcraft.jsch.JSchException
import com.jcraft.jsch.Session
import com.jcraft.jsch.UserInfo
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalViewClient
import dev.moavia.codriod.git.CodriodHostKeys
import dev.moavia.codriod.git.UnknownHostKey
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.UUID

/** What kind of remote shell a saved profile launches. */
enum class RemoteShellPreset(val id: String, val label: String, val startupCommand: String, val hint: String) {
    WINDOWS_POWERSHELL("win-ps", "Windows PowerShell", "powershell.exe -NoLogo", "Windows 10/11 with OpenSSH Server enabled"),
    WINDOWS_CMD("win-cmd", "Windows CMD", "cmd.exe", "Windows 10/11 with OpenSSH Server enabled"),
    LINUX("linux", "Linux shell", "", "Any Linux server running sshd");

    companion object {
        fun byId(id: String?) = entries.firstOrNull { it.id == id } ?: LINUX
    }
}

/**
 * A saved remote connection. Never contains a password or private key: secrets are supplied for a
 * single connection and only kept in memory.
 */
data class RemoteProfile(
    val id: String = UUID.randomUUID().toString(),
    val label: String,
    val host: String,
    val port: Int = 22,
    val username: String,
    val preset: RemoteShellPreset = RemoteShellPreset.LINUX,
    val startupCommand: String = preset.startupCommand
)

/** Authentication material for one connection. It is not written to disk. */
sealed interface SshSecret {
    data class Password(val password: String) : SshSecret
    data class PrivateKey(val pem: String, val passphrase: String? = null) : SshSecret
}

sealed interface RemoteStatus {
    data object NotConnected : RemoteStatus
    data object Connecting : RemoteStatus
    data object Connected : RemoteStatus
    data object Disconnected : RemoteStatus
    data class Failed(val message: String, val unknownHost: UnknownHostKey? = null) : RemoteStatus
}

/** Persists non-secret connection settings as JSON in app-private storage. */
class RemoteProfileStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("remote-terminals", Context.MODE_PRIVATE)
    private val key = "profiles"

    fun list(): List<RemoteProfile> = parseProfiles(prefs.getString(key, null))

    internal companion object {
        fun parseProfiles(raw: String?): List<RemoteProfile> {
            if (raw.isNullOrBlank()) return emptyList()
            return runCatching {
                val array = JSONArray(raw)
                (0 until array.length()).mapNotNull { index ->
                    val item = array.optJSONObject(index) ?: return@mapNotNull null
                    val host = item.optString("host")
                    val username = item.optString("username")
                    if (host.isBlank() || username.isBlank()) null else {
                        val preset = RemoteShellPreset.byId(item.optString("preset"))
                        RemoteProfile(
                            id = item.optString("id").ifBlank { UUID.randomUUID().toString() },
                            label = item.optString("label").ifBlank { "$username@$host" },
                            host = host,
                            port = item.optInt("port", 22).coerceIn(1, 65535),
                            username = username,
                            preset = preset,
                            startupCommand = item.optString("startupCommand").ifBlank { preset.startupCommand }
                        )
                    }
                }
            }.getOrDefault(emptyList())
        }
    }

    fun save(profile: RemoteProfile) {
        val cleaned = profile.copy(
            label = profile.label.trim().ifEmpty { "${profile.username}@${profile.host}" },
            host = profile.host.trim(),
            username = profile.username.trim(),
            port = profile.port.coerceIn(1, 65535)
        )
        write(list().filterNot { it.id == cleaned.id } + cleaned)
    }

    fun delete(id: String) = write(list().filterNot { it.id == id })

    private fun write(profiles: List<RemoteProfile>) {
        val array = JSONArray()
        profiles.sortedBy { it.label.lowercase() }.forEach { profile ->
            array.put(JSONObject().apply {
                put("id", profile.id)
                put("label", profile.label)
                put("host", profile.host)
                put("port", profile.port)
                put("username", profile.username)
                put("preset", profile.preset.id)
                put("startupCommand", profile.startupCommand)
            })
        }
        prefs.edit().putString(key, array.toString()).apply()
    }
}

/** Validates a remote profile and explains problems without leaking secrets. */
fun validateRemoteProfile(profile: RemoteProfile): String? {
    if (profile.host.isBlank()) return "Enter the remote host name or IP address"
    if (!profile.host.matches(Regex("[A-Za-z0-9._:-]{1,253}"))) return "Host name contains invalid characters"
    if (profile.username.isBlank() || profile.username.length > 64 || profile.username.any { it.isWhitespace() || it.isISOControl() }) {
        return "Enter a valid user name"
    }
    if (profile.port !in 1..65535) return "Port must be between 1 and 65535"
    return null
}

/**
 * A real SSH terminal. Connects to the remote machine through JSch, opens a shell channel with a
 * pseudo-terminal and streams bytes to and from the terminal emulator. Android never runs
 * PowerShell or Linux natively; everything happens on the remote machine.
 */
class SshTerminalBackend(
    private val profile: RemoteProfile,
    private val secret: SshSecret,
    private val hostKeys: CodriodHostKeys,
    private val connectTimeoutMs: Int = 15_000
) : TerminalExecutionBackend {
    override val displayName: String = profile.preset.label

    var status: RemoteStatus by mutableStateOf(RemoteStatus.NotConnected)
        private set

    private var jschSession: Session? = null
    private var shellChannel: ChannelShell? = null
    private var remoteToTerminal: PipedOutputStream? = null
    private var terminalToRemote: PipedInputStream? = null

    @Volatile private var closed = false

    override fun createSession(
        viewClient: TerminalViewClient,
        sessionClient: TerminalSessionClient
    ): TerminalSession {
        closed = false
        val terminalInput: PipedInputStream
        val terminalOutput: PipedOutputStream
        try {
            val toTerminalOut = PipedOutputStream()
            terminalInput = PipedInputStream(toTerminalOut, 64 * 1024)
            terminalOutput = PipedOutputStream()
            terminalToRemote = PipedInputStream(terminalOutput, 64 * 1024)
            remoteToTerminal = toTerminalOut
        } catch (error: Exception) {
            throw IllegalStateException("Could not create SSH stream: ${error.message}", error)
        }

        val terminal = TerminalSession(3000, sessionClient)
        terminal.setRemoteIo(
            terminalInput,
            terminalOutput,
            { terminate() },
            { columns, rows, cellWidth, cellHeight ->
                runCatching { shellChannel?.setPtySize(columns, rows, cellWidth, cellHeight) }
            }
        )
        status = RemoteStatus.Connecting
        connectAsync(terminal)
        return terminal
    }

    private fun connectAsync(terminal: TerminalSession) {
        val input = terminalToRemote ?: return
        val output = remoteToTerminal ?: return
        Thread({
            try {
                hostKeys.resetLastResult()
                val jsch = JSch()
                jsch.setHostKeyRepository(hostKeys)
                if (secret is SshSecret.PrivateKey) {
                    val pem = secret.pem.toByteArray(Charsets.UTF_8)
                    jsch.addIdentity("codriod-remote", pem, null, secret.passphrase?.toByteArray(Charsets.UTF_8))
                }
                val session = jsch.getSession(profile.username, profile.host, profile.port)
                session.setConfig("StrictHostKeyChecking", "ask")
                session.setConfig("PreferredAuthentications", "publickey,password,keyboard-interactive")
                if (secret is SshSecret.Password) session.setPassword(secret.password)
                session.setUserInfo(object : UserInfo {
                    override fun getPassphrase(): String? = (secret as? SshSecret.PrivateKey)?.passphrase
                    override fun getPassword(): String? = (secret as? SshSecret.Password)?.password
                    override fun promptPassword(message: String?): Boolean = getPassword() != null
                    override fun promptPassphrase(message: String?): Boolean = true
                    override fun promptYesNo(message: String?): Boolean = true
                    override fun showMessage(message: String?) = Unit
                })
                session.setTimeout(connectTimeoutMs)
                session.connect(connectTimeoutMs)

                val channel = session.openChannel("shell") as ChannelShell
                channel.setPtyType("xterm-256color", 80, 24, 0, 0)
                channel.setPty(true)
                channel.setInputStream(input, true)
                channel.setOutputStream(output, true)
                channel.connect(connectTimeoutMs)

                jschSession = session
                shellChannel = channel
                if (closed) {
                    runCatching { channel.disconnect() }
                    runCatching { session.disconnect() }
                    return@Thread
                }
                status = RemoteStatus.Connected
                val startup = profile.startupCommand.trim()
                if (startup.isNotEmpty()) {
                    output.write((startup + "\r").toByteArray(Charsets.UTF_8))
                    output.flush()
                }
            } catch (error: Exception) {
                if (closed) return@Thread
                val unknown = hostKeys.lastUnknown
                val changed = hostKeys.lastChanged
                val message = when {
                    unknown != null -> "Unknown SSH host key for ${profile.host}. Verify its fingerprint before connecting."
                    changed != null -> "The SSH host key for $changed changed since it was trusted. Connection refused."
                    error is JSchException && error.message?.contains("Auth", ignoreCase = true) == true ->
                        "Authentication failed. Check the user name and credentials."
                    error is JSchException && error.message?.contains("timeout", ignoreCase = true) == true ->
                        "Connection timed out after ${connectTimeoutMs / 1000}s."
                    else -> error.message ?: "SSH connection failed"
                }
                status = RemoteStatus.Failed(message, unknown)
                runCatching {
                    output.write("\r\n\u001b[31m[SSH] $message\u001b[0m\r\n".toByteArray(Charsets.UTF_8))
                    output.flush()
                }
                runCatching { output.close() }
                terminal.remoteFinished(1)
            }
        }, "CodriodSshConnect").apply { isDaemon = true }.start()
    }

    /** Trusts the host key the last connection reported and leaves reconnect to the user. */
    fun trustReportedHostKey() {
        val unknown = (status as? RemoteStatus.Failed)?.unknownHost ?: return
        hostKeys.trust(unknown)
        status = RemoteStatus.NotConnected
    }

    fun terminate() {
        closed = true
        val wasConnected = status is RemoteStatus.Connected || status is RemoteStatus.Connecting
        status = RemoteStatus.Disconnected
        runCatching { shellChannel?.disconnect() }
        runCatching { jschSession?.disconnect() }
        shellChannel = null
        jschSession = null
        runCatching { remoteToTerminal?.close() }
        runCatching { terminalToRemote?.close() }
        remoteToTerminal = null
        terminalToRemote = null
        if (wasConnected) status = RemoteStatus.Disconnected
    }

    fun acknowledgeFailure() {
        if (status is RemoteStatus.Failed) status = RemoteStatus.NotConnected
    }

    /** Called when the remote transport ends (server closed the shell or the network dropped). */
    fun markDisconnected() {
        if (status is RemoteStatus.Connected) status = RemoteStatus.Disconnected
    }

    companion object {
        fun knownHostsFile(context: Context): File = File(context.applicationContext.filesDir, "ssh-known-hosts")
    }
}
