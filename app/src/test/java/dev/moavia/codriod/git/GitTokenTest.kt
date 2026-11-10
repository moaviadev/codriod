package dev.moavia.codriod.git

import dev.moavia.codriod.TestHttpServer
import dev.moavia.codriod.TestHttpServer.Response
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Base64

/** The user's own access token: sealed at rest, verified with GitHub's /user, sent only to its host. */
class GitTokenTest {
    @get:Rule val temp = TemporaryFolder()
    private lateinit var server: TestHttpServer
    private val token = "ghp_" + "a1B2c3D4e5F6g7H8i9J0k1L2m3N4o5P6q7R8"
    private val seenAuth = mutableListOf<String?>()

    private object XorVault : KeyVault {
        override val description = "test"
        override fun seal(plain: ByteArray) = ByteArray(plain.size) { (plain[it].toInt() xor 0x33).toByte() }
        override fun open(sealed: ByteArray) = seal(sealed)
    }

    private fun pkt(line: String) = String.format("%04x", line.toByteArray().size + 4) + line

    @Before fun start() {
        server = TestHttpServer { req ->
            when {
                req.path == "/user" -> if (req.header("Authorization") == "Bearer $token")
                    Response(200, """{"login":"octo-cat","id":1}""") else Response(401, """{"message":"Bad credentials"}""")
                req.path.startsWith("/repo.git/info/refs") -> {
                    val auth = req.header("Authorization")
                    seenAuth += auth
                    val expected = "Basic " + Base64.getEncoder().encodeToString("x-access-token:$token".toByteArray())
                    if (auth != expected) Response(401, "", mapOf("WWW-Authenticate" to "Basic realm=\"test\""))
                    else Response(200, pkt("# service=git-upload-pack\n") + "0000" +
                        pkt("0000000000000000000000000000000000000000 capabilities^{}\u0000side-band-64k\n") + "0000",
                        mapOf("Content-Type" to "application/x-git-upload-pack-advertisement"))
                }
                else -> Response(404)
            }
        }
    }

    @After fun stop() = server.close()

    private val base get() = server.baseUrl

    @Test fun tokenIsSealedVerifiedAndOnlyUsedForItsHost() {
        val tokens = GitTokenStore(temp.newFolder("tokens"), XorVault, githubApi = base)
        val ids = SshIdentityStore(temp.newFolder("ids"), XorVault)
        val remote = GitRemoteService(ids, CodriodHostKeys(File(temp.root, "kh")), 5, tokens)

        assertThrows(IllegalArgumentException::class.java) { GitTokenStore.validateToken("short") }
        assertEquals("octo-cat", tokens.verifyGitHubToken(token))
        val bad = assertThrows(GitAuthException::class.java) { tokens.verifyGitHubToken("ghp_" + "z".repeat(36)) }
        assertEquals(GitAuthException.Kind.TokenRejected, bad.kind)

        val url = "$base/repo.git"
        val anonymous = assertThrows(GitAuthException::class.java) { remote.testAccess(url, allowLocal = true) }
        assertEquals(GitAuthException.Kind.HttpsNeedsLogin, anonymous.kind)

        tokens.save("127.0.0.1", token, "octo-cat")
        assertFalse(temp.root.walkTopDown().filter { it.isFile }.any { it.readText().contains(token) })
        assertEquals("octo-cat", tokens.get("127.0.0.1")?.user)
        assertTrue(remote.testAccess(url, allowLocal = true).message.startsWith("Access OK"))
        assertTrue(seenAuth.last()!!.startsWith("Basic "))

        tokens.delete("127.0.0.1")
        assertNull(tokens.get("127.0.0.1"))
        assertEquals(GitAuthException.Kind.HttpsNeedsLogin,
            assertThrows(GitAuthException::class.java) { remote.testAccess(url, allowLocal = true) }.kind)
    }

    @Test fun wrongSavedTokenIsReportedAsRejected() {
        val tokens = GitTokenStore(temp.newFolder("tokens"), XorVault, githubApi = base)
        val remote = GitRemoteService(SshIdentityStore(temp.newFolder("ids"), XorVault), CodriodHostKeys(File(temp.root, "kh")), 5, tokens)
        tokens.save("127.0.0.1", "ghp_" + "w".repeat(36), null)
        assertEquals(GitAuthException.Kind.TokenRejected,
            assertThrows(GitAuthException::class.java) { remote.testAccess("$base/repo.git", allowLocal = true) }.kind)
    }

    @Test fun tokensNeverGoInUrls() {
        assertThrows(IllegalArgumentException::class.java) { GitRemoteService.validateRemoteUrl("https://x:$token@github.com/a/b.git") }
        assertEquals("octo", GitTokenStore.parseLogin("""{"login": "octo", "id": 3}"""))
    }
}
