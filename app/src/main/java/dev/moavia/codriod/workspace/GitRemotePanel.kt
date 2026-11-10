package dev.moavia.codriod.workspace

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.moavia.codriod.git.GitHubRepo

/**
 * GitHub: the user pastes an access token they created on their own GitHub account, sees all their
 * repositories, and clones any of them with one tap. Pull and push then work from Source Control.
 */
@Composable
fun GitHubScreen(model: WorkspaceModel, onBack: () -> Unit) {
    val account = model.gitAccount
    val connected = account.githubToken
    val code = LocalCodeColors.current
    var query by remember { mutableStateOf("") }
    var urlDialog by remember { mutableStateOf(false) }
    var confirmDisconnect by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Row(Modifier.fillMaxWidth().height(52.dp).background(code.header).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            WorkspaceIconButton(ToolIcon.Back, "Back", onBack)
            Column(Modifier.weight(1f).padding(start = 4.dp)) {
                Text("GITHUB", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(connected?.user?.let { "@$it" } ?: "Connect your account", style = MaterialTheme.typography.titleMedium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (connected != null) {
                WorkspaceIconButton(ToolIcon.Refresh, "Reload repositories", model::loadGitHubRepos)
                TextButton(onClick = { confirmDisconnect = true }, enabled = !model.busy) {
                    Text("Disconnect", color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                }
            }
        }
        if (connected == null) ConnectGitHub(model)
        else {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(query, { query = it }, modifier = Modifier.weight(1f), singleLine = true,
                    placeholder = { Text("Search ${model.githubRepos.size} repositories") },
                    leadingIcon = { ToolGlyph(ToolIcon.Search, MaterialTheme.colorScheme.onSurfaceVariant) },
                    textStyle = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = { urlDialog = true }) { Text("URL") }
            }
            when {
                model.githubLoading && model.githubRepos.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                model.githubError != null && model.githubRepos.isEmpty() -> Column(Modifier.fillMaxWidth().padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(model.githubError.orEmpty(), color = MaterialTheme.colorScheme.error)
                    Button(onClick = model::loadGitHubRepos) { Text("Try again") }
                }
                else -> {
                    val shown = model.githubRepos.filter { query.isBlank() || it.fullName.contains(query.trim(), true) }
                    LazyColumn(Modifier.fillMaxSize()) {
                        if (shown.isEmpty()) item {
                            Text(if (model.githubRepos.isEmpty()) "No repositories on this account yet." else "No repository matches “$query”.",
                                Modifier.padding(20.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        items(shown, key = { it.fullName }) { repo -> RepoRow(repo, enabled = !model.busy) { model.cloneGitHubRepo(repo) } }
                    }
                }
            }
        }
    }
    if (urlDialog) CloneUrlDialog(model) { urlDialog = false }
    if (confirmDisconnect) AlertDialog(onDismissRequest = { confirmDisconnect = false },
        title = { Text("Disconnect GitHub?") },
        text = { Text("The token is removed from this phone. Cloned projects stay. You can also delete the token at github.com/settings/tokens.") },
        confirmButton = { TextButton(onClick = { confirmDisconnect = false; model.disconnectGitHub() }) {
            Text("Disconnect", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = { confirmDisconnect = false }) { Text("Cancel") } })
}

@Composable
private fun ConnectGitHub(model: WorkspaceModel) {
    var token by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Connect GitHub", style = MaterialTheme.typography.titleLarge)
        Text("Use your own GitHub access token. You will see all your repositories, clone them here, and pull or push later.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Step("1", "Create a token on GitHub", "Tick the “repo” box, choose an expiry and press Generate token. Copy the token.")
        OutlinedButton(onClick = { model.gitAccount.openUrl(GitAccountController.GITHUB_NEW_TOKEN_URL) }, modifier = Modifier.fillMaxWidth()) {
            Text("Open GitHub token page")
        }
        Step("2", "Paste it here", "It starts with ghp_ or github_pat_.")
        OutlinedTextField(token, { token = it.trim().take(255) }, modifier = Modifier.fillMaxWidth(), singleLine = true,
            enabled = !model.busy, placeholder = { Text("Paste your token") },
            visualTransformation = PasswordVisualTransformation(), textStyle = MaterialTheme.typography.bodyMedium)
        Button(onClick = { model.connectGitHub(token); token = "" }, enabled = !model.busy && token.length >= 20,
            modifier = Modifier.fillMaxWidth().heightIn(min = 46.dp)) {
            Text(if (model.busy) "Connecting…" else "Connect")
        }
        Text("The token is checked with GitHub and kept encrypted on this phone only. It is sent only to github.com and never to AI agents.",
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Step(number: String, title: String, detail: String) {
    Row(verticalAlignment = Alignment.Top) {
        Box(Modifier.size(24.dp).background(MaterialTheme.colorScheme.primary, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
            Text(number, color = MaterialTheme.colorScheme.onPrimary, fontSize = 12.sp)
        }
        Spacer(Modifier.width(10.dp))
        Column {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun RepoRow(repo: GitHubRepo, enabled: Boolean, onClone: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        ToolGlyph(ToolIcon.Git, MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(repo.fullName, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false))
                Spacer(Modifier.width(6.dp))
                Text(if (repo.private) "Private" else "Public", fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(4.dp))
                        .padding(horizontal = 4.dp, vertical = 1.dp))
            }
            if (repo.description.isNotBlank()) Text(repo.description, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(8.dp))
        OutlinedButton(onClick = onClone, enabled = enabled, contentPadding = PaddingValues(horizontal = 12.dp)) { Text("Clone") }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

@Composable
private fun CloneUrlDialog(model: WorkspaceModel, onDismiss: () -> Unit) {
    var url by remember { mutableStateOf("") }
    val name = url.trim().trimEnd('/').substringAfterLast('/').substringAfterLast(':').removeSuffix(".git")
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Clone from URL") },
        text = {
            OutlinedTextField(url, { url = it.trim().take(400) }, singleLine = true, label = { Text("Repository URL") },
                placeholder = { Text("https://github.com/owner/repo") }, textStyle = MaterialTheme.typography.bodySmall)
        },
        confirmButton = { TextButton(enabled = url.isNotBlank() && !model.busy, onClick = {
            onDismiss(); model.cloneRepository(url, name.ifBlank { "Cloned repository" })
        }) { Text("Clone") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

/** Remote tab of Source Control: who is connected, the origin, and Pull / Push. */
@Composable
fun GitRemoteTab(model: WorkspaceModel) {
    val project = model.project ?: return
    val connected = model.gitAccount.githubToken
    var remoteDraft by remember(project.id, model.gitRemote) { mutableStateOf(model.gitRemote.orEmpty()) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
            Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("GitHub", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(connected?.let { "Connected as @${it.user ?: "you"}" } ?: "Not connected", style = MaterialTheme.typography.titleMedium)
                }
                if (connected == null) Button(onClick = model::showGitHub) { Text("Connect") }
                else TextButton(onClick = model::showGitHub) { Text("My repos") }
            }
        }

        Text("ORIGIN", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(remoteDraft, { remoteDraft = it.trim().take(400) }, modifier = Modifier.fillMaxWidth(),
            singleLine = true, enabled = !model.busy, textStyle = MaterialTheme.typography.bodySmall,
            placeholder = { Text("https://github.com/you/repo.git") })
        if (remoteDraft.isNotBlank() && remoteDraft != model.gitRemote)
            OutlinedButton(onClick = { model.updateGitRemote(remoteDraft) }, enabled = !model.busy) { Text("Save origin") }

        Text("SYNC  ·  ${model.gitSnapshot.branch.ifBlank { "no branch" }}", style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val ready = !model.busy && model.gitRemote != null
            OutlinedButton(onClick = model::pullGit, enabled = ready, modifier = Modifier.weight(1f).heightIn(min = 46.dp)) { Text("Pull") }
            Button(onClick = model::pushGit, enabled = ready && model.gitSnapshot.hasHead,
                modifier = Modifier.weight(1f).heightIn(min = 46.dp)) { Text("Push") }
        }
        Text(when {
            model.gitRemote == null -> "Set the origin above, or clone a repository from your GitHub list."
            connected == null -> "Connect GitHub to push and to pull private repositories."
            else -> "Commit your changes in the Changes tab, then Push."
        }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Only shown for non-GitHub SSH servers. GitHub's keys are pinned and never shown here. */
@Composable
fun HostTrustDialog(model: WorkspaceModel) {
    val host = model.gitAccount.pendingHost ?: return
    AlertDialog(onDismissRequest = { model.gitAccount.pendingHost = null },
        title = { Text("Trust this server?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Codriod has not connected to ${host.host} before. Check that this fingerprint matches the one your " +
                    "Git host publishes. If it does not, cancel.", style = MaterialTheme.typography.bodySmall)
                Text("${host.type}\n${host.fingerprint}", fontFamily = FontFamily.Monospace, fontSize = 11.sp)
            }
        },
        confirmButton = { TextButton(onClick = model::trustPendingHost) { Text("Trust") } },
        dismissButton = { TextButton(onClick = { model.gitAccount.pendingHost = null }) { Text("Cancel") } })
}
