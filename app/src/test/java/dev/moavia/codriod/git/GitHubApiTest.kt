package dev.moavia.codriod.git

import dev.moavia.codriod.TestHttpServer
import dev.moavia.codriod.TestHttpServer.Response
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubApiTest {
    private val token = "ghp_" + "Q".repeat(36)

    private fun repo(i: Int) = """{"full_name":"octo/r$i","name":"r$i","owner":{"login":"octo"},"private":${i % 2 == 0},
        "description":${if (i == 1) "null" else "\"repo $i\""},"clone_url":"https://github.com/octo/r$i.git","default_branch":"main","updated_at":"2026-11-0${i % 9}T00:00:00Z"}"""

    @Test fun listsAllRepositoriesAcrossPagesWithTheUsersToken() {
        val seen = mutableListOf<String>()
        TestHttpServer { req ->
            seen += req.path
            if (req.header("Authorization") != "Bearer $token") Response(401, """{"message":"Bad credentials"}""")
            else when {
                req.path == "/user" -> Response(200, """{"login":"octo"}""")
                req.path.startsWith("/user/repos") && req.path.contains("page=1") && !req.path.contains("page=1") -> Response(500)
                req.path.startsWith("/user/repos") && req.path.endsWith("page=1") -> Response(200, (1..100).joinToString(",", "[", "]") { repo(it) })
                req.path.startsWith("/user/repos") && req.path.endsWith("page=2") -> Response(200, (101..103).joinToString(",", "[", "]") { repo(it) })
                else -> Response(404)
            }
        }.use { server ->
            val api = GitHubApi(server.baseUrl)
            assertEquals("octo", api.user(token))
            val repos = api.repos(token)
            assertEquals(103, repos.size)
            assertEquals("octo/r1", repos.first().fullName)
            assertEquals("", repos.first().description)
            assertTrue(repos[1].private)
            assertTrue(seen.any { it.contains("per_page=100") && it.contains("sort=updated") })
            assertEquals(2, seen.count { it.startsWith("/user/repos") })

            val bad = assertThrows(GitAuthException::class.java) { api.user("ghp_" + "x".repeat(36)) }
            assertEquals(GitAuthException.Kind.TokenRejected, bad.kind)
        }
    }

    @Test fun githubSshAddressesBecomeHttps() {
        assertEquals("https://github.com/octo/app.git", GitRemoteService.githubHttpsUrl("git@github.com:octo/app.git"))
        assertEquals("https://github.com/octo/app.git", GitRemoteService.githubHttpsUrl("ssh://git@github.com/octo/app"))
        assertNull(GitRemoteService.githubHttpsUrl("https://github.com/octo/app.git"))
        assertNull(GitRemoteService.githubHttpsUrl("git@gitlab.com:octo/app.git"))
    }
}

class GitHubApiDetailTest {
    private val token = "ghp_" + "Q".repeat(36)

    @Test fun profileBranchesFilesAndReadme() {
        TestHttpServer { req ->
            if (req.header("Authorization") != "Bearer $token") Response(401) else when {
                req.path == "/user" -> Response(200, """{"login":"octo","name":null}""",
                    mapOf("github-authentication-token-expiration" to "2030-01-01 00:00:00 UTC"))
                req.path == "/user/repos?per_page=100&sort=updated&page=1" -> Response(200,
                    """[{"full_name":"octo/app","name":"app","owner":{"login":"octo"},"private":false,"description":"d","clone_url":"https://github.com/octo/app.git",
                       "default_branch":"main","pushed_at":"2026-11-01T10:00:00Z","language":"Kotlin","stargazers_count":31,"forks_count":2,"fork":false}]""")
                req.path.startsWith("/repos/octo/app/branches") -> Response(200, """[{"name":"main"},{"name":"dev"}]""")
                req.path == "/repos/octo/app/contents?ref=dev" -> Response(200,
                    """[{"name":"README.md","path":"README.md","type":"file"},{"name":"src","path":"src","type":"dir"}]""")
                req.path == "/repos/octo/app/readme?ref=dev" -> if (req.header("Accept") == "application/vnd.github.raw+json") Response(200, "# App\nHello") else Response(415)
                req.path == "/repos/octo/app/readme?ref=main" -> Response(404, """{"message":"Not Found"}""")
                else -> Response(404)
            }
        }.use { server ->
            val api = GitHubApi(server.baseUrl)
            val profile = api.profile(token)
            assertEquals("octo", profile.login)
            assertEquals(1893456000000L, profile.tokenExpires)
            val repo = api.repos(token).single()
            assertEquals("Kotlin", repo.language); assertEquals(31, repo.stars); assertEquals(2, repo.forks)
            assertEquals("2026-11-01T10:00:00Z", repo.updatedAt)
            assertEquals(listOf("main", "dev"), api.branches(token, "octo/app"))
            assertEquals(listOf("src", "README.md"), api.contents(token, "octo/app", "dev").map { it.name })
            assertEquals("# App\nHello", api.readme(token, "octo/app", "dev"))
            assertNull(api.readme(token, "octo/app", "main"))
            assertThrows(IllegalArgumentException::class.java) { api.branches(token, "../evil") }
        }
    }
}
