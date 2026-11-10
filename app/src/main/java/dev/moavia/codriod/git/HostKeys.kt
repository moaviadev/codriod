package dev.moavia.codriod.git

import com.jcraft.jsch.HostKey
import com.jcraft.jsch.HostKeyRepository
import com.jcraft.jsch.UserInfo
import java.io.File
import java.security.MessageDigest

data class UnknownHostKey(val host: String, val type: String, val keyBase64: String, val fingerprint: String)

/**
 * Host key checking for Git over SSH.
 *
 * GitHub's published host keys are pinned, so a GitHub connection is verified without asking the user.
 * Any other server is refused until the user confirms its fingerprint (trust on first use), and a
 * changed key is always refused.
 */
class CodriodHostKeys(private val trustedFile: File) : HostKeyRepository {
    data class Entry(val host: String, val type: String, val keyBase64: String)

    companion object {
        /** From GitHub's "GitHub's SSH key fingerprints" documentation. */
        val GITHUB = listOf(
            Entry("github.com", "ssh-ed25519", "AAAAC3NzaC1lZDI1NTE5AAAAIOMqqnkVzrm0SdG6UOoqKLsabgH5C9okWi0dh2l9GKJl"),
            Entry("github.com", "ecdsa-sha2-nistp256", "AAAAE2VjZHNhLXNoYTItbmlzdHAyNTYAAAAIbmlzdHAyNTYAAABBBEmKSENjQEezOmxkZMy7opKgwFB9nkt5YRrYMjNuG5N87uRgg6CLrbo5wAdT/y6v0mKV0U2w0WZ2YB/++Tpockg="),
            Entry("github.com", "ssh-rsa", "AAAAB3NzaC1yc2EAAAADAQABAAABgQCj7ndNxQowgcQnjshcLrqPEiiphnt+VTTvDP6mHBL9j1aNUkY4Ue1gvwnGLVlOhGeYrnZaMgRK6+PKCUXaDbC7qtbW8gIkhL7aGCsOr/C56SJMy/BCZfxd1nWzAOxSDPgVsmerOBYfNqltV9/hWCqBywINIR+5dIg6JTJ72pcEpEjcYgXkE2YEFXV1JHnsKgbLWNlhScqb2UmyRkQyytRLtL+38TGxkxCflmO+5Z8CSSNY7GidjMIZ7Q4zMjA2n1nGrlTDkzwDCsw+wqFPGQA179cnfGWOWRVruj16z6XyvxvjJwbz0wQZ75XK5tKSb7FNyeIEs4TT4jk+S4dhPeAUC5y+bDYirYgM4GC7uEnztnZyaVWQ7B381AK4Qdrwt51ZqExKbQpTUNn+EjqoTwvqNj4kqx5QUCI0ThS/YkOxJCXmPUWZbhjpCg56i+2aB6CmK2JGhn57K5mj0MNdBXA4/WnwH6XoPWJzK5Nyu2zB3nAZp+S5hpQs+p1vN1/wsjk=")
        )

        fun fingerprint(key: ByteArray): String =
            "SHA256:" + Base64Codec.encode(MessageDigest.getInstance("SHA-256").digest(key), padding = false)

        /** The algorithm name is the first SSH string in the key blob. */
        fun keyType(key: ByteArray): String {
            if (key.size < 4) return "unknown"
            val length = ((key[0].toInt() and 0xff) shl 24) or ((key[1].toInt() and 0xff) shl 16) or
                ((key[2].toInt() and 0xff) shl 8) or (key[3].toInt() and 0xff)
            if (length <= 0 || length > key.size - 4) return "unknown"
            return String(key, 4, length, Charsets.US_ASCII)
        }
    }

    /** Set when the last connection met a server whose key is not yet trusted. */
    @Volatile var lastUnknown: UnknownHostKey? = null
        private set
    @Volatile var lastChanged: String? = null
        private set

    @Synchronized
    private fun userEntries(): List<Entry> = if (!trustedFile.isFile) emptyList() else
        trustedFile.readLines().mapNotNull { line ->
            val parts = line.trim().split(' ')
            if (parts.size >= 3 && !line.startsWith("#")) Entry(parts[0], parts[1], parts[2]) else null
        }

    private fun entries(): List<Entry> = GITHUB + userEntries().filter { it.host != "github.com" }

    override fun check(host: String, key: ByteArray): Int {
        val type = keyType(key)
        val encoded = Base64Codec.encode(key)
        val known = entries().filter { it.host.equals(host, ignoreCase = true) }
        if (known.any { it.type == type && it.keyBase64 == encoded }) return HostKeyRepository.OK
        if (known.any { it.type == type }) {
            lastChanged = host
            return HostKeyRepository.CHANGED
        }
        lastUnknown = UnknownHostKey(host, type, encoded, fingerprint(key))
        return HostKeyRepository.NOT_INCLUDED
    }

    /** Called only after the user has confirmed [unknown]'s fingerprint. GitHub keys can't be overridden. */
    @Synchronized
    fun trust(unknown: UnknownHostKey) {
        require(!unknown.host.equals("github.com", ignoreCase = true)) { "GitHub host keys are pinned" }
        require(unknown.host.none { it.isWhitespace() } && unknown.type.none { it.isWhitespace() }) { "Invalid host" }
        val kept = userEntries().filterNot { it.host.equals(unknown.host, true) && it.type == unknown.type }
        trustedFile.parentFile?.mkdirs()
        trustedFile.writeText((kept + Entry(unknown.host, unknown.type, unknown.keyBase64))
            .joinToString("") { "${it.host} ${it.type} ${it.keyBase64}\n" })
        if (lastUnknown == unknown) lastUnknown = null
    }

    fun resetLastResult() { lastUnknown = null; lastChanged = null }

    override fun add(hostkey: HostKey, ui: UserInfo?) = Unit // Only trust() may add keys.
    override fun remove(host: String, type: String?) = Unit
    override fun remove(host: String, type: String?, key: ByteArray?) = Unit
    override fun getKnownHostsRepositoryID(): String = "codriod-known-hosts"
    override fun getHostKey(): Array<HostKey> = hostKeys(null, null)
    override fun getHostKey(host: String?, type: String?): Array<HostKey> = hostKeys(host, type)

    private fun hostKeys(host: String?, type: String?): Array<HostKey> = entries()
        .filter { (host == null || it.host.equals(host, true)) && (type == null || it.type == type) }
        .mapNotNull { runCatching { HostKey(it.host, HostKey.GUESS, Base64Codec.decode(it.keyBase64)) }.getOrNull() }
        .toTypedArray()
}
