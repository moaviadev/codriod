package dev.moavia.codriod.git

import com.sun.net.httpserver.HttpServer
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
import java.net.InetSocketAddress
import java.util.Base64

/** The user's own access token: sealed at rest, verified with GitHub's /user, sent only to its host. */
class GitTokenTest {
    @get:Rule val temp = TemporaryFolder()
    private lateinit var server: HttpServer
    private val token = "ghp_" + "a1B2c3D4e5F6g7H8i9J0k1L2m3N4o5P6q7R8"
    private val seenAuth = mutableListOf<String?>()

    private object XorVault : KeyVault {
        override val description = "test"
        override fun seal(plain: ByteArray) = ByteArray(plain.size) { (plain[it].toInt() xor 0x33).toByte() }
        override fun open(sealed: ByteArray) = seal(sealed)
    }

    private fun pkt(line: String) = String.format("%04x", line.toByteArray().size + 4) + line

    @Before fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/user") { ex ->
            val ok = ex.requestHeaders.getFirst("Authorization") == "Bearer $token"
            val body = if (ok) """{"login":"octo-cat","id":1}""" else """{"message":"Bad credentials"}"""
            ex.sendResponseHeaders(if (ok) 200 else 401, body.length.toLong()); ex.responseBody.use { it.write(body.toByteArray()) }
        }
        server.createContext("/repo.git/info/refs") { ex ->
            val auth = ex.requestHeaders.getFirst("Authorization")
            seenAuth += auth
            val expected = "Basic " + Base64.getEncoder().encodeToString("x-access-token:$token".toByteArray())
            if (auth != expected) {
                ex.responseHeaders.add("WWW-Authenticate", "Basic realm=\"test\"")
                ex.sendResponseHeaders(401, -1); ex.close(); return@createContext
            }
            val body = pkt("# service=git-upload-pack\n") + "0000" +
                pkt("0000000000000000000000000000000000000000 capabilities^{}\u0000side-band-64k\n") + "0000"
            ex.responseHeaders.add("Content-Type", "application/x-git-upload-pack-advertisement")
            ex.sendResponseHeaders(200, body.length.toLong()); ex.responseBody.use { it.write(body.toByteArray()) }
        }
        server.start()
    }

    @After fun stop() = server.stop(0)

    private val base get() = "http://127.0.0.1:${server.address.port}"

    @Test fun tokenIsSealedVerifiedAndOnlyUsedForItsHost() {
        val tokens = GitTokenStore(temp.newFolder("tokens"), XorVault, githubApi = base)
        val ids = SshIdentityStore(temp.newFolder("ids"), XorVault)
        val remote = GitRemoteService(ids, CodriodHostKeys(File(temp.root, "kh")), 5, tokens)

        assertThrows(IllegalArgumentException::class.java) { GitTokenStore.validateToken("short") }
        assertEquals("octo-cat", tokens.verifyGitHubToken(token))
        val bad = assertThrows(GitAuthException::class.java) { tokens.verifyGitHubToken("ghp_" + "z".repeat(36)) }
        assertEquals(GitAuthException.Kind.AuthFailed, bad.kind)

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
