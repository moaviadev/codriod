package dev.moavia.codriod.git

import com.jcraft.jsch.HostKeyRepository
import com.jcraft.jsch.JSch
import com.jcraft.jsch.KeyPair
import org.eclipse.jgit.api.Git
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class GitAuthTest {
    @get:Rule val temp = TemporaryFolder()

    private object XorVault : KeyVault {
        override val description = "test"
        override fun seal(plain: ByteArray) = ByteArray(plain.size) { (plain[it].toInt() xor 0x5a).toByte() }
        override fun open(sealed: ByteArray) = seal(sealed)
    }

    private fun stores(): Triple<SshIdentityStore, CodriodHostKeys, GitRemoteService> {
        val ids = SshIdentityStore(temp.newFolder("ids"), XorVault)
        val hosts = CodriodHostKeys(File(temp.root, "known_hosts"))
        return Triple(ids, hosts, GitRemoteService(ids, hosts, timeoutSeconds = 5))
    }

    @Test fun base64MatchesJdk() {
        for (size in 0..40) {
            val data = ByteArray(size) { (it * 37 + size).toByte() }
            assertEquals(java.util.Base64.getEncoder().encodeToString(data), Base64Codec.encode(data))
            assertEquals(java.util.Base64.getEncoder().withoutPadding().encodeToString(data), Base64Codec.encode(data, false))
            assertTrue(data.contentEquals(Base64Codec.decode(Base64Codec.encode(data))))
        }
    }

    @Test fun githubHostKeysArePinnedAndMatchPublishedFingerprints() {
        val (_, hosts, _) = stores()
        val expected = mapOf("ssh-ed25519" to "SHA256:+DiY3wvvV6TuJJhbpZisF/zLDA0zPMSvHdkr4UvCOqU",
            "ecdsa-sha2-nistp256" to "SHA256:p2QAMXNIC1TJYWeIOttrVc98/R1BUFWu3/LiyKgUfQM",
            "ssh-rsa" to "SHA256:uNiVztksCsDhcc0u9e8BujQXVUpKZIDTMczCvj3tD2s")
        for (entry in CodriodHostKeys.GITHUB) {
            val blob = Base64Codec.decode(entry.keyBase64)
            assertEquals(entry.type, CodriodHostKeys.keyType(blob))
            assertEquals(expected[entry.type], CodriodHostKeys.fingerprint(blob))
            assertEquals(HostKeyRepository.OK, hosts.check("github.com", blob))
        }
        assertEquals(3, hosts.getHostKey("github.com", null).size)
    }

    @Test fun impostorGithubKeyIsRefusedAndCannotBeTrusted() {
        val (_, hosts, _) = stores()
        val fake = KeyPair.genKeyPair(JSch(), KeyPair.ECDSA, 256).publicKeyBlob
        assertEquals(HostKeyRepository.CHANGED, hosts.check("github.com", fake))
        assertEquals("github.com", hosts.lastChanged)
        assertThrows(IllegalArgumentException::class.java) {
            hosts.trust(UnknownHostKey("github.com", "ecdsa-sha2-nistp256", Base64Codec.encode(fake), "x"))
        }
    }

    @Test fun otherHostsNeedExplicitTrust() {
        val (_, hosts, _) = stores()
        val key = KeyPair.genKeyPair(JSch(), KeyPair.ECDSA, 256).publicKeyBlob
        assertEquals(HostKeyRepository.NOT_INCLUDED, hosts.check("git.example.com", key))
        val unknown = hosts.lastUnknown!!
        assertEquals(CodriodHostKeys.fingerprint(key), unknown.fingerprint)
        hosts.trust(unknown)
        assertEquals(HostKeyRepository.OK, CodriodHostKeys(File(temp.root, "known_hosts")).check("git.example.com", key))
        val other = KeyPair.genKeyPair(JSch(), KeyPair.ECDSA, 256).publicKeyBlob
        assertEquals(HostKeyRepository.CHANGED, hosts.check("git.example.com", other))
    }

    @Test fun identityKeepsPrivateKeySealedAndOutOfPublicData() {
        val (ids, _, _) = stores()
        val identity = ids.create("Personal GitHub")
        assertTrue(identity.publicKey.startsWith("ecdsa-sha2-nistp256 "))
        assertTrue(identity.publicKey.endsWith(" codriod-personal-github"))
        assertEquals(identity.id, ids.defaultIdentity()?.id)
        val blob = Base64Codec.decode(identity.publicKey.split(' ')[1])
        assertEquals(CodriodHostKeys.fingerprint(blob), identity.fingerprint)

        val folder = File(temp.root, "ids/${identity.id}")
        val onDisk = folder.walkTopDown().filter { it.isFile }.map { it.readBytes().toString(Charsets.ISO_8859_1) }.toList()
        assertFalse(onDisk.any { it.contains("PRIVATE KEY") })
        val material = ids.material(identity.id)
        assertTrue(material.privateKey.toString(Charsets.US_ASCII).contains("PRIVATE KEY"))
        val loaded = KeyPair.load(JSch(), material.privateKey, material.publicKey)
        assertTrue(loaded.publicKeyBlob.contentEquals(blob))

        ids.markVerified(identity.id, "octocat")
        assertEquals("octocat", ids.get(identity.id)?.verifiedUser)
        val second = ids.create("Work")
        assertEquals(identity.id, ids.defaultIdentity()?.id)
        ids.setDefault(second.id)
        assertEquals(second.id, ids.defaultIdentity()?.id)
        ids.delete(second.id)
        assertEquals(identity.id, ids.defaultIdentity()?.id)
        assertFalse(File(temp.root, "ids/${second.id}").exists())
    }

    @Test fun urlRules() {
        assertEquals("git@github.com:octo/repo.git", GitRemoteService.githubSshUrl("https://github.com/octo/repo"))
        assertEquals("git@github.com:octo/repo.git", GitRemoteService.githubSshUrl("https://github.com/octo/repo.git"))
        assertNull(GitRemoteService.githubSshUrl("https://gitlab.com/octo/repo"))
        assertTrue(GitRemoteService.isSsh("git@github.com:octo/repo.git"))
        assertTrue(GitRemoteService.isSsh("ssh://git@host:2222/x.git"))
        assertFalse(GitRemoteService.isSsh("https://github.com/octo/repo"))
        listOf("http://github.com/a/b", "https://user:ghp_secret@github.com/a/b", "-uhack", "file:///tmp/x", "", "https://a b")
            .forEach { bad -> assertThrows(bad, IllegalArgumentException::class.java) { GitRemoteService.validateRemoteUrl(bad) } }
        GitRemoteService.validateRemoteUrl("https://github.com/a/b.git")
        GitRemoteService.validateRemoteUrl("git@github.com:a/b.git")
    }

    @Test fun parsesGitHubGreeting() {
        assertEquals("octo-cat", GitRemoteService.parseGitHubGreeting(
            "Hi octo-cat! You've successfully authenticated, but GitHub does not provide shell access."))
        assertEquals("octo", GitRemoteService.parseGitHubGreeting(
            "Hi octo/deploy-repo! You've successfully authenticated, but GitHub does not provide shell access."))
        assertNull(GitRemoteService.parseGitHubGreeting("Permission denied (publickey)."))
    }

    @Test fun detectsToolsOnPath() {
        val bin = temp.newFolder("bin")
        File(bin, "git").apply { writeText("#!/bin/sh\n"); setExecutable(true) }
        val scan = GitRemoteService.detectTools(bin.path)
        assertEquals(File(bin, "git").path, scan.gitCli)
        assertTrue(scan.summary.contains("JGit"))
    }

    @Test fun sshNeedsAnIdentityAndHttpsNeverTakesSecrets() {
        val (_, _, remote) = stores()
        val error = assertThrows(GitAuthException::class.java) {
            remote.testAccess("git@github.com:octo/repo.git")
        }
        assertEquals(GitAuthException.Kind.NoIdentity, error.kind)
    }

    @Test fun cloneCommitPushFetchPullAgainstLocalRemote() {
        val (ids, _, remote) = stores()
        val bare = temp.newFolder("origin.git")
        Git.init().setBare(true).setDirectory(bare).setInitialBranch("main").call().close()
        val seed = temp.newFolder("seed")
        Git.init().setDirectory(seed).setInitialBranch("main").call().use { git ->
            File(seed, "README.md").writeText("hello\n")
            git.add().addFilepattern(".").call()
            git.commit().setSign(false).setMessage("seed").setAuthor("A", "a@example.com").setCommitter("A", "a@example.com").call()
            git.remoteAdd().setName("origin").setUri(org.eclipse.jgit.transport.URIish(bare.toURI().toURL())).call()
            git.push().setRemote("origin").add("main").call()
        }
        val url = bare.toURI().toString().replace("file:/", "file:///")
        assertTrue(remote.testAccess(url, allowLocal = true).message.contains("main"))

        val one = temp.newFolder("one")
        ids.create("GitHub")
        remote.clone(url, one, allowLocal = true)
        assertEquals("hello\n", File(one, "README.md").readText())
        assertNotNull(Git.open(one).use { it.repository.config.getString("codriod", null, GitRemoteService.IDENTITY_KEY) })
        remote.setAuthor(one, "Dev", "dev@example.com")
        Git.open(one).use { git ->
            File(one, "a.txt").writeText("a\n")
            git.add().addFilepattern("a.txt").call()
            git.commit().setSign(false).setMessage("add a").call()
        }
        assertEquals("Pushed main to origin", remote.push(one).message)
        assertEquals("origin/main is already up to date", remote.push(one).message)

        val two = temp.newFolder("two")
        remote.clone(url, two, allowLocal = true)
        Git.open(two).use { git ->
            File(two, "b.txt").writeText("b\n")
            git.add().addFilepattern("b.txt").call()
            git.commit().setSign(false).setMessage("add b").setAuthor("B", "b@example.com").setCommitter("B", "b@example.com").call()
        }
        remote.push(two)

        assertTrue(remote.fetch(one).message.startsWith("Fetched"))
        remote.pull(one)
        assertEquals("b\n", File(one, "b.txt").readText())

        Git.open(two).use { git ->
            File(two, "c.txt").writeText("c\n")
            git.add().addFilepattern("c.txt").call()
            git.commit().setSign(false).setMessage("c").setAuthor("B", "b@example.com").setCommitter("B", "b@example.com").call()
        }
        Git.open(one).use { git ->
            File(one, "d.txt").writeText("d\n")
            git.add().addFilepattern("d.txt").call()
            git.commit().setSign(false).setMessage("d").call()
        }
        remote.push(two)
        val rejected = assertThrows(GitAuthException::class.java) { remote.push(one) }
        assertEquals(GitAuthException.Kind.Rejected, rejected.kind)
        assertTrue(rejected.message!!.contains("Pull first"))
    }
}

class GitCloneOptionsTest {
    @get:org.junit.Rule val temp = TemporaryFolder()

    @Test fun cloneChoosesBranchAndReportsProgress() {
        val remote = GitRemoteService(SshIdentityStore(temp.newFolder("ids"), PlainVault), CodriodHostKeys(File(temp.root, "kh")), 5)
        val bare = temp.newFolder("origin.git")
        Git.init().setBare(true).setDirectory(bare).setInitialBranch("main").call().close()
        val seed = temp.newFolder("seed")
        Git.init().setDirectory(seed).setInitialBranch("main").call().use { git ->
            File(seed, "a.txt").writeText("main\n"); git.add().addFilepattern(".").call()
            git.commit().setSign(false).setMessage("m").setAuthor("A", "a@x.io").setCommitter("A", "a@x.io").call()
            git.checkout().setCreateBranch(true).setName("dev").call()
            File(seed, "a.txt").writeText("dev\n"); git.add().addFilepattern(".").call()
            git.commit().setSign(false).setMessage("d").setAuthor("A", "a@x.io").setCommitter("A", "a@x.io").call()
            git.remoteAdd().setName("origin").setUri(org.eclipse.jgit.transport.URIish(bare.toURI().toURL())).call()
            git.push().setRemote("origin").add("main").add("dev").call()
        }
        val url = bare.toURI().toString().replace("file:/", "file:///")
        val seen = mutableListOf<Int>()
        val target = temp.newFolder("clone")
        remote.clone(url, target, allowLocal = true, branch = "dev", progress = { seen += it })
        assertEquals("dev\n", File(target, "a.txt").readText())
        assertEquals("dev", Git.open(target).use { it.repository.branch })
        assertEquals(100, seen.last())
        assertEquals(seen.sorted(), seen)
    }
}
