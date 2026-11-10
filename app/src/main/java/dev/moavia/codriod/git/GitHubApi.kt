package dev.moavia.codriod.git

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

data class GitHubRepo(
    val fullName: String,
    val name: String,
    val owner: String,
    val private: Boolean,
    val description: String,
    val cloneUrl: String,
    val defaultBranch: String,
    val updatedAt: String,
    val language: String = "",
    val stars: Int = 0,
    val forks: Int = 0,
    val fork: Boolean = false
)

data class GitHubUser(val login: String, val name: String, val tokenExpires: Long?)

data class GitHubEntry(val name: String, val path: String, val directory: Boolean)

/** The few GitHub REST calls Codriod needs, made with the user's own access token. */
class GitHubApi(private val base: String = "https://api.github.com") {

    private class Reply(val body: String, val headers: Map<String, List<String>>)

    /** The account that owns [token]. */
    fun user(token: String): String = profile(token).login

    fun profile(token: String): GitHubUser {
        val reply = get("/user", token)
        val json = JSONObject(reply.body)
        val login = json.optString("login").ifBlank { throw IOException("GitHub did not return an account name") }
        val expiry = reply.headers.entries.firstOrNull { it.key.equals("github-authentication-token-expiration", true) }
            ?.value?.firstOrNull()?.let(::parseExpiry)
        return GitHubUser(login, if (json.isNull("name")) "" else json.optString("name"), expiry)
    }

    /** Every repository the token can see (own, collaborator and organization), newest first. */
    fun repos(token: String, maxPages: Int = 10): List<GitHubRepo> {
        val result = mutableListOf<GitHubRepo>()
        for (page in 1..maxPages) {
            val array = JSONArray(get("/user/repos?per_page=100&sort=updated&page=$page", token).body)
            for (i in 0 until array.length()) array.optJSONObject(i)?.let { result += repo(it) }
            if (array.length() < 100) break
        }
        return result.filter { it.cloneUrl.startsWith("https://") }
    }

    fun branches(token: String, fullName: String): List<String> {
        val array = JSONArray(get("/repos/${path(fullName)}/branches?per_page=100", token).body)
        return (0 until array.length()).mapNotNull { array.optJSONObject(it)?.optString("name") }
    }

    fun contents(token: String, fullName: String, ref: String): List<GitHubEntry> {
        val array = JSONArray(get("/repos/${path(fullName)}/contents?ref=${enc(ref)}", token).body)
        return (0 until array.length()).mapNotNull { array.optJSONObject(it) }
            .map { GitHubEntry(it.optString("name"), it.optString("path"), it.optString("type") == "dir") }
            .sortedWith(compareBy<GitHubEntry> { !it.directory }.thenBy { it.name.lowercase() })
    }

    /** README text, or null when the repository has none. */
    fun readme(token: String, fullName: String, ref: String): String? = try {
        get("/repos/${path(fullName)}/readme?ref=${enc(ref)}", token, accept = "application/vnd.github.raw+json").body
    } catch (error: IOException) {
        if (error.message?.contains("404") == true) null else throw error
    }

    private fun repo(o: JSONObject) = GitHubRepo(
        fullName = o.optString("full_name"),
        name = o.optString("name"),
        owner = o.optJSONObject("owner")?.optString("login").orEmpty(),
        private = o.optBoolean("private"),
        description = if (o.isNull("description")) "" else o.optString("description"),
        cloneUrl = o.optString("clone_url"),
        defaultBranch = o.optString("default_branch", "main"),
        updatedAt = o.optString("pushed_at").ifBlank { o.optString("updated_at") },
        language = if (o.isNull("language")) "" else o.optString("language"),
        stars = o.optInt("stargazers_count"),
        forks = o.optInt("forks_count"),
        fork = o.optBoolean("fork"))

    private fun path(fullName: String): String {
        require(fullName.matches(Regex("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+")) &&
            fullName.split('/').none { it == "." || it == ".." }) { "Invalid repository name" }
        return fullName
    }

    private fun enc(value: String) = java.net.URLEncoder.encode(value, "UTF-8")

    private fun get(path: String, token: String, accept: String = "application/vnd.github+json"): Reply {
        val connection = URL(base.trimEnd('/') + path).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 20_000
            connection.readTimeout = 30_000
            connection.setRequestProperty("Authorization", "Bearer ${GitTokenStore.validateToken(token)}")
            connection.setRequestProperty("Accept", accept)
            connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            connection.setRequestProperty("User-Agent", "Codriod")
            val code = connection.responseCode
            if (code == 401) throw GitAuthException(GitAuthException.Kind.TokenRejected,
                "GitHub rejected this token. It may be wrong, expired or revoked.")
            if (code == 403) throw GitAuthException(GitAuthException.Kind.TokenRejected,
                "GitHub refused the request (HTTP 403). Check the token's permissions or try again later.")
            if (code !in 200..299) throw IOException("GitHub answered HTTP $code")
            val body = connection.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
            return Reply(body, connection.headerFields.filterKeys { it != null })
        } catch (error: java.net.UnknownHostException) {
            throw GitAuthException(GitAuthException.Kind.Network, "No internet connection to GitHub")
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        /** GitHub sends e.g. "2026-12-31 23:59:59 UTC" or "2026-12-31 23:59:59 +0500". */
        fun parseExpiry(value: String): Long? = listOf("yyyy-MM-dd HH:mm:ss z", "yyyy-MM-dd HH:mm:ss Z").firstNotNullOfOrNull { pattern ->
            runCatching { SimpleDateFormat(pattern, Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.parse(value.trim())?.time }.getOrNull()
        }

        fun parseTime(iso: String): Long? = runCatching {
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.parse(iso)?.time
        }.getOrNull()
    }
}
