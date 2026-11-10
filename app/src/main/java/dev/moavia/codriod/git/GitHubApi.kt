package dev.moavia.codriod.git

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

data class GitHubRepo(
    val fullName: String,
    val name: String,
    val owner: String,
    val private: Boolean,
    val description: String,
    val cloneUrl: String,
    val defaultBranch: String,
    val updatedAt: String
)

/** The few GitHub REST calls Codriod needs, made with the user's own access token. */
class GitHubApi(private val base: String = "https://api.github.com") {

    /** The account that owns [token]. */
    fun user(token: String): String {
        val json = JSONObject(get("/user", token))
        return json.optString("login").ifBlank { throw IOException("GitHub did not return an account name") }
    }

    /** Every repository the token can see (own, collaborator and organization), newest first. */
    fun repos(token: String, maxPages: Int = 10): List<GitHubRepo> {
        val result = mutableListOf<GitHubRepo>()
        for (page in 1..maxPages) {
            val array = JSONArray(get("/user/repos?per_page=100&sort=updated&page=$page", token))
            for (i in 0 until array.length()) {
                val o = array.optJSONObject(i) ?: continue
                result += GitHubRepo(
                    fullName = o.optString("full_name"),
                    name = o.optString("name"),
                    owner = o.optJSONObject("owner")?.optString("login").orEmpty(),
                    private = o.optBoolean("private"),
                    description = if (o.isNull("description")) "" else o.optString("description"),
                    cloneUrl = o.optString("clone_url"),
                    defaultBranch = o.optString("default_branch", "main"),
                    updatedAt = o.optString("updated_at"))
            }
            if (array.length() < 100) break
        }
        return result.filter { it.cloneUrl.startsWith("https://") }
    }

    private fun get(path: String, token: String): String {
        val connection = URL(base.trimEnd('/') + path).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 20_000
            connection.readTimeout = 30_000
            connection.setRequestProperty("Authorization", "Bearer ${GitTokenStore.validateToken(token)}")
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            connection.setRequestProperty("User-Agent", "Codriod")
            val code = connection.responseCode
            if (code == 401) throw GitAuthException(GitAuthException.Kind.TokenRejected,
                "GitHub rejected this token. It may be wrong, expired or revoked.")
            if (code == 403) throw GitAuthException(GitAuthException.Kind.TokenRejected,
                "GitHub refused the request (HTTP 403). Check the token's permissions or try again later.")
            if (code !in 200..299) throw IOException("GitHub answered HTTP $code")
            return connection.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
        } catch (error: java.net.UnknownHostException) {
            throw GitAuthException(GitAuthException.Kind.Network, "No internet connection to GitHub")
        } finally {
            connection.disconnect()
        }
    }
}
