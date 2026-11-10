package dev.moavia.codriod.git

import com.jcraft.jsch.JSch
import com.jcraft.jsch.KeyPair
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.Properties
import java.util.UUID

/** Encrypts private key bytes at rest. The Android app uses a non-exportable Android Keystore key. */
interface KeyVault {
    val description: String
    fun seal(plain: ByteArray): ByteArray
    fun open(sealed: ByteArray): ByteArray
}

/** App-private storage only. Used in tests and on Android versions without a usable Keystore. */
object PlainVault : KeyVault {
    override val description = "App-private storage"
    override fun seal(plain: ByteArray) = plain.copyOf()
    override fun open(sealed: ByteArray) = sealed.copyOf()
}

/** Public facts about a key. Never contains private key material. */
data class SshIdentity(
    val id: String,
    val label: String,
    val host: String,
    val publicKey: String,
    val fingerprint: String,
    val created: Long,
    val verifiedUser: String? = null,
    val verifiedAt: Long = 0L
)

/** Private and public key bytes handed only to the SSH transport, never to UI or agents. */
class SshKeyMaterial internal constructor(val name: String, internal val privateKey: ByteArray, internal val publicKey: ByteArray)

/**
 * SSH identities owned by the person using this Android user profile. Keys are generated on the device,
 * private keys never leave it, and the user adds the public key to their own GitHub account.
 */
class SshIdentityStore(private val directory: File, private val vault: KeyVault) {
    companion object {
        private const val META = "identity.properties"
        private const val PRIVATE = "private.key.sealed"
        private const val PUBLIC = "public.key"
        private const val DEFAULT = "default"
        private val ID = Regex("[a-f0-9-]{36}")
    }

    val vaultDescription get() = vault.description

    @Synchronized
    fun list(): List<SshIdentity> = directory.listFiles().orEmpty().filter { it.isDirectory && it.name.matches(ID) }
        .mapNotNull { runCatching { load(it) }.getOrNull() }.sortedBy { it.created }

    fun get(id: String): SshIdentity? = list().firstOrNull { it.id == id }

    @Synchronized
    fun defaultIdentity(): SshIdentity? {
        val chosen = File(directory, DEFAULT).takeIf { it.isFile }?.readText()?.trim()
        val all = list()
        return all.firstOrNull { it.id == chosen } ?: all.firstOrNull()
    }

    @Synchronized
    fun setDefault(id: String) {
        require(get(id) != null) { "Unknown SSH key" }
        File(directory, DEFAULT).writeText(id)
    }

    /** Generates an ECDSA P-256 key. GitHub accepts it and every supported Android version can sign with it. */
    @Synchronized
    fun create(label: String, host: String = "github.com"): SshIdentity {
        val name = label.trim().ifEmpty { "GitHub" }
        require(name.length <= 60 && name.none { it.isISOControl() }) { "Use a short key name" }
        require(host.matches(Regex("[A-Za-z0-9.-]{1,253}"))) { "Invalid host" }
        check(directory.isDirectory || directory.mkdirs()) { "Cannot create key storage" }
        val id = UUID.randomUUID().toString()
        val folder = File(directory, id)
        check(folder.mkdir()) { "Cannot create key storage" }
        try {
            val jsch = JSch()
            val pair = KeyPair.genKeyPair(jsch, KeyPair.ECDSA, 256)
            val comment = "codriod-" + name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').ifEmpty { "key" }
            val publicOut = ByteArrayOutputStream()
            pair.writePublicKey(publicOut, comment)
            val privateOut = ByteArrayOutputStream()
            pair.writePrivateKey(privateOut)
            val fingerprint = CodriodHostKeys.fingerprint(pair.publicKeyBlob)
            pair.dispose()
            val privateBytes = privateOut.toByteArray()
            File(folder, PRIVATE).writeBytes(vault.seal(privateBytes))
            privateBytes.fill(0)
            val publicKey = publicOut.toString(Charsets.US_ASCII.name()).trim()
            File(folder, PUBLIC).writeText(publicKey + "\n")
            val identity = SshIdentity(id, name, host.lowercase(), publicKey, fingerprint, System.currentTimeMillis())
            save(identity)
            if (File(directory, DEFAULT).let { !it.isFile || get(it.readText().trim()) == null }) setDefault(id)
            return identity
        } catch (error: Exception) {
            folder.deleteRecursively()
            throw error
        }
    }

    @Synchronized
    fun markVerified(id: String, user: String) {
        val identity = get(id) ?: throw IOException("Unknown SSH key")
        save(identity.copy(verifiedUser = user, verifiedAt = System.currentTimeMillis()))
    }

    @Synchronized
    fun delete(id: String) {
        require(id.matches(ID)) { "Unknown SSH key" }
        val folder = File(directory, id)
        File(folder, PRIVATE).takeIf { it.isFile }?.let { file ->
            // Overwrite before deleting; flash storage may still keep old blocks, which is why keys are sealed.
            runCatching { file.writeBytes(ByteArray(file.length().toInt())) }
        }
        folder.deleteRecursively()
        val default = File(directory, DEFAULT)
        if (default.isFile && default.readText().trim() == id) default.delete()
    }

    /** For the SSH transport only. */
    internal fun material(id: String): SshKeyMaterial {
        require(id.matches(ID)) { "Unknown SSH key" }
        val folder = File(directory, id)
        val sealed = File(folder, PRIVATE).takeIf { it.isFile }?.readBytes() ?: throw IOException("SSH key is missing")
        return SshKeyMaterial("codriod-$id", vault.open(sealed), File(folder, PUBLIC).readBytes())
    }

    private fun load(folder: File): SshIdentity {
        val props = Properties().apply { File(folder, META).inputStream().use { load(it) } }
        return SshIdentity(folder.name, props.getProperty("label"), props.getProperty("host", "github.com"),
            File(folder, PUBLIC).readText().trim(), props.getProperty("fingerprint"),
            props.getProperty("created", "0").toLong(), props.getProperty("verifiedUser"),
            props.getProperty("verifiedAt", "0").toLong())
    }

    private fun save(identity: SshIdentity) {
        val props = Properties().apply {
            setProperty("label", identity.label)
            setProperty("host", identity.host)
            setProperty("fingerprint", identity.fingerprint)
            setProperty("created", identity.created.toString())
            identity.verifiedUser?.let { setProperty("verifiedUser", it) }
            setProperty("verifiedAt", identity.verifiedAt.toString())
        }
        File(File(directory, identity.id), META).outputStream().use { props.store(it, "Codriod SSH identity (public data)") }
    }
}
