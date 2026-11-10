package dev.moavia.codriod.git

import java.io.File
import java.util.Properties

/** Public facts about a saved access token. Never contains the token itself. */
data class GitToken(val host: String, val user: String?, val saved: Long)

/**
 * Personal access tokens that the user creates on their own GitHub account and pastes into Codriod for
 * HTTPS remotes. Codriod never creates, requests from GitHub or shares tokens; each is sealed on the
 * device and is only sent to the host it belongs to.
 */
class GitTokenStore(private val directory: File, private val vault: KeyVault,
    private val githubApi: String = "https://api.github.com") {

    companion object {
        private val HOST = Regex("[a-z0-9.-]{1,253}")
        private val TOKEN = Regex("[A-Za-z0-9_.-]{20,255}")

        fun validateToken(token: String): String {
            val value = token.trim()
            require(value.matches(TOKEN)) { "That does not look like an access token" }
            return value
        }

        fun parseLogin(json: String): String? = Regex("\"login\"\\s*:\\s*\"([A-Za-z0-9-]{1,39})\"").find(json)?.groupValues?.get(1)
    }

    private fun clean(host: String): String = host.lowercase().also { require(it.matches(HOST)) { "Invalid host" } }

    @Synchronized
    fun get(host: String): GitToken? {
        val name = clean(host)
        val meta = File(directory, "$name.properties").takeIf { it.isFile } ?: return null
        if (!File(directory, "$name.sealed").isFile) return null
        val props = Properties().apply { meta.inputStream().use { load(it) } }
        return GitToken(name, props.getProperty("user"), props.getProperty("saved", "0").toLong())
    }

    fun list(): List<GitToken> = directory.listFiles().orEmpty().filter { it.name.endsWith(".sealed") }
        .mapNotNull { get(it.name.removeSuffix(".sealed")) }

    @Synchronized
    fun save(host: String, token: String, user: String?) {
        val name = clean(host)
        val value = validateToken(token)
        check(directory.isDirectory || directory.mkdirs()) { "Cannot create token storage" }
        File(directory, "$name.sealed").writeBytes(vault.seal(value.toByteArray(Charsets.UTF_8)))
        val props = Properties().apply {
            user?.let { setProperty("user", it) }
            setProperty("saved", System.currentTimeMillis().toString())
        }
        File(directory, "$name.properties").outputStream().use { props.store(it, "Codriod access token (public data)") }
    }

    @Synchronized
    fun delete(host: String) {
        val name = clean(host)
        File(directory, "$name.sealed").takeIf { it.isFile }?.let { runCatching { it.writeBytes(ByteArray(it.length().toInt())) } }
        File(directory, "$name.sealed").delete()
        File(directory, "$name.properties").delete()
    }

    /** For the HTTPS transport only. */
    internal fun secret(host: String): String? {
        val name = runCatching { clean(host) }.getOrNull() ?: return null
        val file = File(directory, "$name.sealed").takeIf { it.isFile } ?: return null
        return vault.open(file.readBytes()).toString(Charsets.UTF_8)
    }

    /** Asks GitHub which account owns [token] (GET /user). Blocking network call. */
    fun verifyGitHubToken(token: String): String = GitHubApi(githubApi).user(validateToken(token))
}
