package dev.moavia.codriod.git

import org.eclipse.jgit.api.Git
import org.eclipse.jgit.junit.ssh.SshTestGitServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.KeyPairGenerator

/** Real SSH round trip through Codriod's JSch session factory against JGit's test SSH Git server. */
class GitSshTransportTest {
    @get:Rule val temp = TemporaryFolder()
    private lateinit var server: SshTestGitServer
    private lateinit var ids: SshIdentityStore
    private lateinit var hosts: CodriodHostKeys
    private lateinit var remote: GitRemoteService
    private lateinit var identity: SshIdentity
    private var port = 0

    @Before fun start() {
        ids = SshIdentityStore(temp.newFolder("ids"), PlainVault)
        hosts = CodriodHostKeys(File(temp.root, "known_hosts"))
        remote = GitRemoteService(ids, hosts, timeoutSeconds = 10)
        identity = ids.create("Test")
        val userKey = File(temp.root, "user.pub").apply { writeText(identity.publicKey + "\n") }
        val bare = temp.newFolder("served.git")
        val repo = Git.init().setBare(true).setDirectory(bare).setInitialBranch("main").call().repository
        val hostKey = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
        server = SshTestGitServer("git", userKey.toPath(), repo, hostKey)
        port = server.start()
    }

    @After fun stop() = server.stop()

    private val url get() = "ssh://git@localhost:$port/served.git"

    @Test fun unknownHostIsRefusedUntilTrustedThenPushAndCloneWork() {
        val unknown = assertThrows(GitAuthException::class.java) { remote.testAccess(url) }
        assertEquals(GitAuthException.Kind.UnknownHost, unknown.kind)
        val pending = unknown.unknownHost!!
        assertTrue(pending.fingerprint.startsWith("SHA256:"))
        assertTrue(unknown.message!!.contains(pending.fingerprint))
        hosts.trust(pending)

        assertTrue(remote.testAccess(url).message.startsWith("Access OK"))

        val work = temp.newFolder("work")
        remote.clone(url, work)
        Git.open(work).use { git ->
            File(work, "hello.txt").writeText("hi\n")
            git.add().addFilepattern(".").call()
            git.commit().setSign(false).setMessage("first").setAuthor("Me", "me@example.com")
                .setCommitter("Me", "me@example.com").call()
        }
        assertEquals("Pushed main to origin", remote.push(work).message)

        val copy = temp.newFolder("copy")
        remote.clone(url, copy)
        assertEquals("hi\n", File(copy, "hello.txt").readText())
    }

    @Test fun keyNotAddedToTheAccountIsRejected() {
        hosts.trust(assertThrows(GitAuthException::class.java) { remote.testAccess(url) }.unknownHost!!)
        assertTrue(remote.testAccess(url).message.startsWith("Access OK"))
        val stranger = ids.create("Not on the account")
        val error = assertThrows(GitAuthException::class.java) { remote.testAccess(url, stranger.id) }
        assertEquals(GitAuthException.Kind.AuthFailed, error.kind)
    }
}
