package dev.moavia.codriod.agent

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GitAccountToolsTest {
    private class FakeBridge : GitAccountBridge {
        val calls = mutableListOf<String>()
        override suspend fun status() = GitAccountStatus("Built-in Git engine: JGit", "Android Keystore",
            listOf(GitKeyInfo("Phone", "SHA256:abc", null, true)), true,
            "https://github.com/octo/app.git", "git@github.com:octo/app.git", null, null).also { calls += "status" }
        override suspend fun startGitHubLogin(keyName: String) = "ecdsa-sha2-nistp256 AAAA codriod-phone".also { calls += "start:$keyName" }
        override suspend fun verifyGitHub() = "octo".also { calls += "verify" }
        override suspend fun configure(authorName: String?, authorEmail: String?, remoteUrl: String?, useSshRemote: Boolean) =
            "configured".also { calls += "configure:$authorName:$useSshRemote" }
        override suspend fun testAccess(url: String?) = "Access OK".also { calls += "test:$url" }
        override suspend fun sync(action: String) = "done $action".also { calls += "sync:$action" }
    }

    private class Recorder(var decision: Decision) : Approver {
        val requests = mutableListOf<ApprovalRequest>()
        override suspend fun approve(request: ApprovalRequest) = decision.also { requests += request }
    }

    private fun call(name: String, args: String = "{}") = ToolCall("1", name, args)

    private fun tools(bridge: GitAccountBridge, approver: Approver): AgentTools {
        val workspace = object : AgentWorkspace {
            override fun files() = emptyList<String>()
            override fun read(path: String) = error("unused")
            override fun exists(path: String) = false
            override fun write(path: String, text: String) = Unit
            override fun delete(path: String) = Unit
            override fun search(query: String) = emptyList<SearchMatch>()
            override fun run(command: String, timeoutSeconds: Int) = CommandResult(0, "", false)
        }
        return AgentTools(workspace, approver, listOf(GitAccountTools(bridge, approver)))
    }

    @Test fun fullLoginFlowNeedsApprovalAndNeverReturnsSecrets() = runBlocking {
        val bridge = FakeBridge(); val approver = Recorder(Decision.Allow)
        val agent = tools(bridge, approver)
        val names = agent.specs(AgentMode.Agent).map { it.name }
        assertTrue(names.containsAll(listOf("git_account_status", "github_login_start", "github_login_verify", "git_configure", "git_test_access", "git_sync")))

        val status = agent.execute(call("git_account_status"), AgentMode.Agent).content
        assertTrue(status.contains("no Codriod OAuth app"))
        assertTrue(status.contains("GitHub: not connected"))
        assertTrue(status.contains("https://github.com/octo/app.git"))
        assertFalse(status.contains("PRIVATE", ignoreCase = false))

        val start = agent.execute(call("github_login_start", """{"key_name":"Pixel"}"""), AgentMode.Agent)
        assertTrue(start.content.contains("Generate token"))
        assertFalse(start.content.contains("ghp_"))
        assertTrue(approver.requests.single() is ApprovalRequest.Action)
        assertEquals("@octo", Regex("@\\w+").find(agent.execute(call("github_login_verify"), AgentMode.Agent).content)?.value)
        agent.execute(call("git_configure", """{"use_ssh_remote":true,"author_name":"Octo"}"""), AgentMode.Agent)
        agent.execute(call("git_sync", """{"action":"fetch"}"""), AgentMode.Agent)
        agent.execute(call("git_sync", """{"action":"push"}"""), AgentMode.Agent)
        assertEquals(listOf("status", "start:Pixel", "verify", "configure:Octo:false", "sync:fetch", "sync:push"), bridge.calls)
        assertEquals(3, approver.requests.size) // start, configure, push (fetch needs none)
    }

    @Test fun declinedOrAskModeDoesNothing() = runBlocking {
        val bridge = FakeBridge(); val approver = Recorder(Decision.Deny)
        val agent = tools(bridge, approver)
        assertTrue(agent.execute(call("github_login_start"), AgentMode.Agent).content.contains("declined"))
        assertTrue(agent.execute(call("git_sync", """{"action":"pull"}"""), AgentMode.Agent).content.contains("declined"))
        assertTrue(agent.execute(call("git_configure", """{"remote_url":"git@github.com:a/b.git"}"""), AgentMode.Agent).content.contains("declined"))
        assertFalse(agent.specs(AgentMode.Ask).any { it.name == "github_login_start" })
        assertTrue(agent.execute(call("github_login_start"), AgentMode.Ask).content.contains("Agent mode"))
        assertTrue(bridge.calls.isEmpty())
        agent.execute(call("git_test_access"), AgentMode.Ask)
        assertEquals(listOf("test:null"), bridge.calls)
    }
}
