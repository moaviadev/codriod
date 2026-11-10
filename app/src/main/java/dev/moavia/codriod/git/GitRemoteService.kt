package dev.moavia.codriod.git

import com.jcraft.jsch.ChannelShell
import com.jcraft.jsch.JSch
import com.jcraft.jsch.JSchException
import com.jcraft.jsch.Session
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.TransportCommand
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.StoredConfig
import org.eclipse.jgit.transport.CredentialsProvider
import org.eclipse.jgit.transport.RefSpec
import org.eclipse.jgit.transport.RemoteRefUpdate
import org.eclipse.jgit.transport.RemoteSession
import org.eclipse.jgit.transport.SshSessionFactory
import org.eclipse.jgit.transport.SshTransport
import org.eclipse.jgit.transport.URIish
import org.eclipse.jgit.transport.ssh.jsch.JschSession
import org.eclipse.jgit.util.FS
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException

class GitAuthException(val kind: Kind, message: String, val unknownHost: UnknownHostKey? = null) : IOException(message) {
    enum class Kind { UnknownHost, HostKeyChanged, AuthFailed, NoIdentity, HttpsNeedsSsh, Network, Rejected, Other }
}

data class ToolScan(val gitCli: String?, val githubCli: String?, val sshCli: String?) {
    val summary get() = buildString {
        append("Built-in Git engine: JGit (always available).\n")
        append("Built-in SSH: yes (keys are created on this phone).\n")
        append("git command: ").append(gitCli ?: "not installed").append('\n')
        append("GitHub CLI (gh): ").append(githubCli ?: "not installed").append('\n')
        append("ssh command: ").append(sshCli ?: "not installed")
    }
}

data class RemoteResult(val message: String, val updatedFiles: Boolean = false)

/**
 * Network Git for one Android user. Authentication is the user's own SSH key; Codriod has no OAuth app,
 * no tokens and no server. HTTPS works anonymously (public repositories); private HTTPS GitHub remotes
 * are switched to the equivalent SSH address with the user's consent.
 */
class GitRemoteService(private val identities: SshIdentityStore, private val hostKeys: CodriodHostKeys,
    private val timeoutSeconds: Int = 30) {

    companion object {
        const val IDENTITY_KEY = "sshIdentity"
        private val GITHUB_HTTPS = Regex("^https://(?:[^@/]+@)?github\\.com/([A-Za-z0-9_.-]+)/([A-Za-z0-9_.-]+?)(?:\\.git)?/?$")
        private val SCP_LIKE = Regex("^([A-Za-z0-9_.-]+)@([A-Za-z0-9.-]+):(?!/)(.+)$")

        /** https://github.com/owner/repo(.git) → git@github.com:owner/repo.git, or null if not a GitHub HTTPS URL. */
        fun githubSshUrl(url: String): String? = GITHUB_HTTPS.matchEntire(url.trim())?.let { match ->
            "git@github.com:${match.groupValues[1]}/${match.groupValues[2]}.git"
        }

        fun isSsh(url: String) = url.startsWith("ssh://") || SCP_LIKE.matches(url.trim())

        fun validateRemoteUrl(url: String, allowLocal: Boolean = false): String {
            val value = url.trim()
            require(value.isNotEmpty() && value.length <= 400 && value.none { it.isWhitespace() || it.isISOControl() }) {
                "Enter a repository URL"
            }
            require(!value.startsWith("-")) { "Invalid repository URL" }
            val ok = value.startsWith("https://") || isSsh(value) || (allowLocal && value.startsWith("file://"))
            require(ok) { "Use an https:// or SSH (git@host:owner/repo.git) URL" }
            require(!Regex("^https://[^/]*:[^/]*@").containsMatchIn(value)) {
                "Do not put passwords or tokens in the URL. Use SSH login instead."
            }
            return value
        }

        /** Parses GitHub's `ssh -T` greeting. */
        fun parseGitHubGreeting(text: String): String? =
            Regex("Hi ([A-Za-z0-9](?:[A-Za-z0-9-]{0,38}))(?:/[A-Za-z0-9_.-]+)?! You've successfully authenticated").find(text)?.groupValues?.get(1)

        /** Looks for command-line tools on PATH. Stock Android ships none of them. */
        fun detectTools(path: String? = System.getenv("PATH")): ToolScan {
            val dirs = (path ?: "").split(':').filter { it.isNotBlank() } + listOf("/system/bin", "/system/xbin", "/vendor/bin")
            fun find(name: String) = dirs.distinct().map { File(it, name) }.firstOrNull { it.isFile && it.canExecute() }?.path
            return ToolScan(find("git"), find("gh"), find("ssh"))
        }
    }

    // ---- configuration -------------------------------------------------------------------------------

    fun remoteUrl(root: File): String? = open(root).use { it.repository.config.getString("remote", "origin", "url") }

    fun setRemoteUrl(root: File, url: String) = open(root).use { git ->
        val value = validateRemoteUrl(url)
        val config = git.repository.config
        config.setString("remote", "origin", "url", value)
        if (config.getStringList("remote", "origin", "fetch").isEmpty())
            config.setString("remote", "origin", "fetch", "+refs/heads/*:refs/remotes/origin/*")
        config.save()
    }

    fun setAuthor(root: File, name: String?, email: String?) = open(root).use { git ->
        val config = git.repository.config
        name?.trim()?.takeIf { it.isNotEmpty() }?.let {
            require(it.length <= 80 && it.none(Char::isISOControl)) { "Invalid author name" }
            config.setString("user", null, "name", it)
        }
        email?.trim()?.takeIf { it.isNotEmpty() }?.let {
            require(it.matches(Regex("[^\\s@]+@[^\\s@]+\\.[^\\s@]+"))) { "Invalid author email" }
            config.setString("user", null, "email", it)
        }
        config.save()
    }

    /** Which of the user's SSH keys this repository uses; stored in the repository's own config. */
    fun identityFor(root: File): SshIdentity? {
        val chosen = runCatching { open(root).use { it.repository.config.getString("codriod", null, IDENTITY_KEY) } }.getOrNull()
        return chosen?.let(identities::get) ?: identities.defaultIdentity()
    }

    fun setIdentity(root: File, id: String) = open(root).use { git ->
        require(identities.get(id) != null) { "Unknown SSH key" }
        git.repository.config.apply { setString("codriod", null, IDENTITY_KEY, id); save() }
    }

    // ---- network operations --------------------------------------------------------------------------

    fun clone(url: String, target: File, identityId: String? = null, allowLocal: Boolean = false): RemoteResult {
        val value = validateRemoteUrl(url, allowLocal)
        require(target.isDirectory && target.list().isNullOrEmpty()) { "Clone target must be an empty folder" }
        val identity = identityId?.let(identities::get) ?: identities.defaultIdentity()
        transport(value, identity) {
            Git.cloneRepository().setURI(value).setDirectory(target).setCloneAllBranches(false)
                .configure(identity).call().use { git ->
                    // An empty remote has no branch yet; start on "main" like new local projects.
                    if (git.repository.resolve(Constants.HEAD) == null) git.repository.updateRef(Constants.HEAD).link("refs/heads/main")
                    identity?.let { git.repository.config.apply { setString("codriod", null, IDENTITY_KEY, it.id); save() } }
                }
        }
        return RemoteResult("Cloned ${displayUrl(value)}", updatedFiles = true)
    }

    fun fetch(root: File): RemoteResult = open(root).use { git ->
        val (url, identity) = remoteFor(git, root)
        val result = transport(url, identity) { git.fetch().setRemote("origin").configure(identity).call() }
        val updates = result.trackingRefUpdates.size
        RemoteResult(if (updates == 0) "Already up to date with origin" else "Fetched $updates updated branch(es)")
    }

    fun pull(root: File): RemoteResult = open(root).use { git ->
        val (url, identity) = remoteFor(git, root)
        val branch = git.repository.branch
        val result = transport(url, identity) {
            git.pull().setRemote("origin").setRemoteBranchName(branch).configure(identity).call()
        }
        if (!result.isSuccessful) {
            val merge = result.mergeResult
            throw GitAuthException(GitAuthException.Kind.Rejected, when {
                merge?.conflicts?.isNotEmpty() == true -> "Pull stopped: conflicts in ${merge.conflicts.keys.take(5).joinToString()}"
                else -> "Pull did not complete: ${merge?.mergeStatus ?: result.rebaseResult?.status ?: "unknown"}"
            })
        }
        val status = result.mergeResult?.mergeStatus?.toString()?.lowercase()?.replace('_', ' ') ?: "done"
        RemoteResult("Pulled origin/$branch: $status", updatedFiles = true)
    }

    fun push(root: File): RemoteResult = open(root).use { git ->
        val (url, identity) = remoteFor(git, root)
        require(git.repository.resolve(Constants.HEAD) != null) { "Commit something before pushing" }
        val branch = git.repository.branch
        val results = transport(url, identity) {
            git.push().setRemote("origin").setRefSpecs(RefSpec("refs/heads/$branch:refs/heads/$branch"))
                .configure(identity).call()
        }
        val updates = results.flatMap { it.remoteUpdates }
        val failed = updates.filter { it.status !in listOf(RemoteRefUpdate.Status.OK, RemoteRefUpdate.Status.UP_TO_DATE) }
        if (failed.isNotEmpty()) {
            val update = failed.first()
            throw GitAuthException(GitAuthException.Kind.Rejected, when (update.status) {
                RemoteRefUpdate.Status.REJECTED_NONFASTFORWARD -> "Push rejected: origin/$branch has new commits. Pull first."
                else -> "Push rejected (${update.status}): ${update.message ?: ""}".trim()
            })
        }
        val config: StoredConfig = git.repository.config
        if (config.getString("branch", branch, "remote") == null) {
            config.setString("branch", branch, "remote", "origin")
            config.setString("branch", branch, "merge", "refs/heads/$branch")
            config.save()
        }
        RemoteResult(if (updates.all { it.status == RemoteRefUpdate.Status.UP_TO_DATE }) "origin/$branch is already up to date"
            else "Pushed $branch to origin")
    }

    /** Lists remote refs without changing anything: proves the user can read the repository. */
    fun testAccess(url: String, identityId: String? = null, allowLocal: Boolean = false): RemoteResult {
        val value = validateRemoteUrl(url, allowLocal)
        val identity = identityId?.let(identities::get) ?: identities.defaultIdentity()
        val refs = transport(value, identity) { Git.lsRemoteRepository().setRemote(value).setHeads(true).configure(identity).call() }
        return RemoteResult("Access OK: ${displayUrl(value)} has ${refs.size} branch(es)" +
            if (refs.isNotEmpty()) " (${refs.take(5).joinToString { it.name.removePrefix("refs/heads/") }})" else "")
    }

    /** Equivalent of `ssh -T git@github.com`: proves GitHub accepts the key and returns the account name. */
    fun verifyGitHub(identityId: String): String {
        val identity = identities.get(identityId) ?: throw GitAuthException(GitAuthException.Kind.NoIdentity, "Create an SSH key first")
        val output = ByteArrayOutputStream()
        hostKeys.resetLastResult()
        var session: Session? = null
        try {
            session = newSession(identity, "git", "github.com", 22)
            val channel = session.openChannel("shell") as ChannelShell
            channel.setPty(false)
            channel.setOutputStream(output, true)
            channel.setExtOutputStream(output, true)
            channel.connect(timeoutSeconds * 1000)
            val deadline = System.currentTimeMillis() + timeoutSeconds * 1000L
            while (!channel.isClosed && System.currentTimeMillis() < deadline) {
                if (parseGitHubGreeting(output.toString(Charsets.UTF_8.name())) != null) break
                Thread.sleep(100)
            }
            channel.disconnect()
        } catch (error: JSchException) {
            throw translate(error)
        } finally {
            session?.disconnect()
        }
        val user = parseGitHubGreeting(output.toString(Charsets.UTF_8.name()))
            ?: throw GitAuthException(GitAuthException.Kind.AuthFailed, "GitHub did not confirm the key")
        identities.markVerified(identityId, user)
        return user
    }

    // ---- internals -------------------------------------------------------------------------------------

    private fun open(root: File): Git = try { Git.open(root) } catch (_: Exception) {
        throw IllegalStateException("Initialize Git for this project first")
    }

    private fun remoteFor(git: Git, root: File): Pair<String, SshIdentity?> {
        val url = git.repository.config.getString("remote", "origin", "url")
            ?: throw IllegalStateException("Set the origin remote URL first")
        return url to identityFor(root)
    }

    private fun <C : TransportCommand<C, *>> C.configure(identity: SshIdentity?): C {
        setTimeout(timeoutSeconds)
        setCredentialsProvider(NoCredentials)
        setTransportConfigCallback { transport ->
            if (transport is SshTransport) transport.sshSessionFactory = SessionFactory(identity)
        }
        return this
    }

    private fun <T> transport(url: String, identity: SshIdentity?, block: () -> T): T {
        hostKeys.resetLastResult()
        if (isSsh(url) && identity == null)
            throw GitAuthException(GitAuthException.Kind.NoIdentity, "Create an SSH key and add it to your account first")
        return try { block() } catch (error: GitAuthException) { throw error }
        catch (error: Exception) { throw translate(error, url) }
    }

    private fun translate(error: Throwable, url: String? = null): GitAuthException {
        hostKeys.lastUnknown?.let {
            return GitAuthException(GitAuthException.Kind.UnknownHost,
                "Unknown server ${it.host}. Confirm its fingerprint ${it.fingerprint} before connecting.", it)
        }
        hostKeys.lastChanged?.let {
            return GitAuthException(GitAuthException.Kind.HostKeyChanged,
                "The host key for $it changed. Connection refused to protect your account.")
        }
        val text = generateSequence(error) { it.cause }.mapNotNull { it.message }.joinToString(" | ")
        return when {
            text.contains("Auth fail", true) || text.contains("Auth cancel", true) || text.contains("publickey", true) ->
                GitAuthException(GitAuthException.Kind.AuthFailed,
                    "The server rejected your SSH key. Add the public key to your account, then try again.")
            url != null && url.startsWith("https://") && (text.contains("not authorized", true) ||
                text.contains("Authentication is required", true) || text.contains("401") || text.contains("403") ||
                text.contains("CredentialsProvider", true)) ->
                GitAuthException(GitAuthException.Kind.HttpsNeedsSsh, githubSshUrl(url)?.let {
                    "This HTTPS remote needs a login. Codriod does not take passwords or tokens; switch the remote to SSH ($it)."
                } ?: "This HTTPS remote needs a login. Codriod does not take passwords or tokens; use the SSH address instead.")
            text.contains("UnknownHost", true) || text.contains("timed out", true) || text.contains("Connection refused", true) ||
                text.contains("Network is unreachable", true) || text.contains("cannot open git-upload-pack", true) ->
                GitAuthException(GitAuthException.Kind.Network, "Could not reach the server: ${error.message ?: text}".take(300))
            else -> GitAuthException(GitAuthException.Kind.Other, (error.message ?: text).take(300))
        }
    }

    private fun displayUrl(url: String) = url.replace(Regex("^https://[^@/]+@"), "https://")

    private fun newSession(identity: SshIdentity?, user: String, host: String, port: Int): Session {
        val jsch = JSch()
        jsch.hostKeyRepository = hostKeys
        identity?.let {
            val material = identities.material(it.id)
            try { jsch.addIdentity(material.name, material.privateKey, material.publicKey, null) }
            finally { material.privateKey.fill(0) }
        }
        val session = jsch.getSession(user, host, if (port > 0) port else 22)
        session.setConfig("StrictHostKeyChecking", "yes")
        session.setConfig("PreferredAuthentications", "publickey")
        session.setConfig("HashKnownHosts", "no")
        session.connect(timeoutSeconds * 1000)
        return session
    }

    private inner class SessionFactory(private val identity: SshIdentity?) : SshSessionFactory() {
        override fun getSession(uri: URIish, credentialsProvider: CredentialsProvider?, fs: FS?, tms: Int): RemoteSession =
            JschSession(newSession(identity, uri.user ?: "git", uri.host, uri.port), uri)
        override fun getType() = "codriod-jsch"
    }

    /** Never asks for, or supplies, usernames, passwords or tokens. */
    private object NoCredentials : CredentialsProvider() {
        override fun isInteractive() = false
        override fun supports(vararg items: org.eclipse.jgit.transport.CredentialItem?) = false
        override fun get(uri: URIish?, vararg items: org.eclipse.jgit.transport.CredentialItem?) = false
    }
}
