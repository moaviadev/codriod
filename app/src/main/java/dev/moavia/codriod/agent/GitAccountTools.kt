package dev.moavia.codriod.agent

import org.json.JSONArray
import org.json.JSONObject

data class GitKeyInfo(val label: String, val fingerprint: String, val githubUser: String?, val isDefault: Boolean)

/** Public facts only: no private keys, tokens or passwords ever cross this boundary. */
data class GitAccountStatus(
    val toolScan: String,
    val keyStorage: String,
    val keys: List<GitKeyInfo>,
    val gitInitialized: Boolean,
    val remoteUrl: String?,
    val sshRemoteSuggestion: String?,
    val authorName: String?,
    val authorEmail: String?,
    /** Only whether a token exists and whose it is; never the token. */
    val httpsToken: String? = null
)

/** Implemented by the app. Every method acts for the person holding the phone, with their own GitHub account. */
interface GitAccountBridge {
    suspend fun status(): GitAccountStatus
    /** Creates an SSH key if needed, copies its public half and opens GitHub's official "Add SSH key" page. */
    suspend fun startGitHubLogin(keyName: String): String
    /** Returns the GitHub username that accepted the key. */
    suspend fun verifyGitHub(): String
    suspend fun configure(authorName: String?, authorEmail: String?, remoteUrl: String?, useSshRemote: Boolean): String
    suspend fun testAccess(url: String?): String
    suspend fun sync(action: String): String
}

/**
 * Lets the coding agent handle "Configure GitHub login for my account". The agent can explain and start
 * the official flow, but the person signs in to GitHub and approves the key themselves, and every change
 * needs their approval in Codriod. No tool ever returns a secret.
 */
class GitAccountTools(private val bridge: GitAccountBridge, private val approver: Approver) : AgentToolset {
    private fun schema(vararg props: Pair<String, JSONObject>, required: List<String> = emptyList()) = JSONObject()
        .put("type", "object")
        .put("properties", JSONObject().apply { props.forEach { (name, spec) -> put(name, spec) } })
        .put("required", JSONArray(required))

    private fun prop(type: String, description: String) = JSONObject().put("type", type).put("description", description)

    private val readOnly = listOf(
        ToolSpec("git_account_status",
            "Check GitHub login on this phone: whether the user connected their GitHub account (and which), this project's " +
                "remote and author. Call this first when asked to set up GitHub. Never ask the user to paste a token or password " +
                "into the chat: tokens are entered only on Codriod's GitHub screen.",
            schema()),
        ToolSpec("github_login_verify",
            "Confirm the user's saved GitHub token still works and report the GitHub username. " +
                "Call after the user says they connected GitHub.",
            schema()),
        ToolSpec("git_test_access",
            "Check read access to a repository without changing anything. Defaults to this project's origin remote.",
            schema("url" to prop("string", "Repository URL (optional)")))
    )

    private val actions = listOf(
        ToolSpec("github_login_start",
            "Start GitHub login: open Codriod's GitHub screen and GitHub's own 'New personal access token' page. The user " +
                "creates the token on their account and pastes it on the GitHub screen themselves; you never see it. " +
                "Requires the user's approval.",
            schema("key_name" to prop("string", "Short label for the key, e.g. 'Phone' (optional)"))),
        ToolSpec("git_configure",
            "Configure this project's Git: author name/email and the origin remote URL. Requires the user's approval.",
            schema("author_name" to prop("string", "Commit author name (optional)"),
                "author_email" to prop("string", "Commit author email (optional)"),
                "remote_url" to prop("string", "New origin URL (optional)"),
                )),
        ToolSpec("git_sync",
            "Fetch, pull or push the current branch with origin using the user's GitHub login. Pull and push need approval.",
            schema("action" to JSONObject().put("type", "string").put("enum", JSONArray(listOf("fetch", "pull", "push")))
                .put("description", "fetch, pull or push"), required = listOf("action")))
    )

    override fun specs(mode: AgentMode) = if (mode == AgentMode.Agent) readOnly + actions else readOnly

    override fun handles(name: String) = (readOnly + actions).any { it.name == name }

    override suspend fun execute(name: String, args: JSONObject, mode: AgentMode): ToolOutcome {
        if (mode == AgentMode.Ask && actions.any { it.name == name })
            return ToolOutcome("Error: $name needs Agent mode. Tell the user to switch to Agent mode.", "$name blocked (Ask mode)")
        return when (name) {
            "git_account_status" -> status()
            "github_login_verify" -> {
                val user = bridge.verifyGitHub()
                ToolOutcome("GitHub accepted the SSH key. Signed in as @$user.", "GitHub verified: @$user")
            }
            "git_test_access" -> {
                val result = bridge.testAccess(args.optString("url").trim().ifEmpty { null })
                ToolOutcome(result, result.take(80))
            }
            "github_login_start" -> {
                val label = args.optString("key_name").trim().ifEmpty { "Phone" }.take(60)
                val approved = approver.approve(ApprovalRequest.Action("Connect GitHub",
                    "Open Codriod's GitHub screen and GitHub's token page. You create a token on your GitHub account and paste it on " +
                        "the GitHub screen yourself; the agent never sees it."))
                if (approved == Decision.Deny) ToolOutcome("The user declined starting GitHub login.", "GitHub login declined")
                else {
                    bridge.startGitHubLogin(label)
                    ToolOutcome("Opened GitHub's token page and Codriod's GitHub screen. Tell the user: on GitHub tick 'repo', " +
                        "press 'Generate token', copy it, then paste it on Codriod's GitHub screen and press Connect. " +
                        "They will then see all their repositories and can clone any of them. Afterwards call github_login_verify.",
                        "Opened GitHub to create a token")
                }
            }
            "git_configure" -> {
                val authorName = args.optString("author_name").trim().ifEmpty { null }
                val authorEmail = args.optString("author_email").trim().ifEmpty { null }
                val remote = args.optString("remote_url").trim().ifEmpty { null }
                val useSsh = false
                val changes = buildList {
                    authorName?.let { add("author name: $it") }
                    authorEmail?.let { add("author email: $it") }
                    remote?.let { add("origin: $it") }
                }
                if (changes.isEmpty()) return ToolOutcome("Nothing to configure.", "git_configure: no changes")
                if (approver.approve(ApprovalRequest.Action("Configure Git", changes.joinToString("\n"))) == Decision.Deny)
                    ToolOutcome("The user declined the Git configuration change.", "Git configuration declined")
                else {
                    val result = bridge.configure(authorName, authorEmail, remote, useSsh)
                    ToolOutcome(result, "Git configured")
                }
            }
            "git_sync" -> {
                val action = args.optString("action")
                require(action in listOf("fetch", "pull", "push")) { "action must be fetch, pull or push" }
                if (action != "fetch" && approver.approve(ApprovalRequest.Action(
                        if (action == "push") "Push to origin" else "Pull from origin",
                        if (action == "push") "Upload this branch's commits to the origin remote."
                        else "Download and merge origin's changes into this branch.", risky = action == "push")) == Decision.Deny)
                    ToolOutcome("The user declined git $action.", "git $action declined")
                else bridge.sync(action).let { ToolOutcome(it, "git $action: ${it.take(60)}", changedFiles = action == "pull") }
            }
            else -> ToolOutcome("Error: unknown tool $name", "Unknown tool $name")
        }
    }

    private suspend fun status(): ToolOutcome {
        val s = bridge.status()
        val text = buildString {
            appendLine(s.toolScan)
            appendLine("Login method: the user's own GitHub access token, created on github.com and pasted on Codriod's GitHub " +
                "screen (no Codriod OAuth app or server). After connecting, all their repositories are listed for one-tap clone.")
            appendLine("Credential storage: ${s.keyStorage}.")
            appendLine("GitHub: ${s.httpsToken ?: "not connected"}")
            s.keys.forEach { key ->
                appendLine("SSH key \"${key.label}\" ${key.fingerprint}" + (if (key.isDefault) " (default)" else "") +
                    (key.githubUser?.let { " · verified on GitHub as @$it" } ?: " · not verified yet"))
            }
            appendLine("Project Git: " + if (s.gitInitialized) "initialized" else "not initialized")
            appendLine("Origin: ${s.remoteUrl ?: "not set"}")
            appendLine("Author: ${s.authorName ?: "not set"} <${s.authorEmail ?: "not set"}>")
        }.trimEnd()
        return ToolOutcome(text, "Checked Git account status")
    }
}
