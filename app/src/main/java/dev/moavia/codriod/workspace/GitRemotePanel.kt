package dev.moavia.codriod.workspace

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.moavia.codriod.git.GitRemoteService

@Composable
private fun SectionLabel(text: String) {
    Text(text, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 2.dp, top = 6.dp, bottom = 2.dp))
}

/** Remote tab of Source Control: the person's GitHub login, the origin remote and sync actions. */
@Composable
fun GitRemoteTab(model: WorkspaceModel) {
    val project = model.project ?: return
    var remoteDraft by remember(project.id, model.gitRemote) { mutableStateOf(model.gitRemote.orEmpty()) }
    val sshSuggestion = model.gitRemote?.let(GitRemoteService::githubSshUrl)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        GitHubAccountSection(model)

        SectionLabel("ORIGIN")
        OutlinedTextField(remoteDraft, { remoteDraft = it.trim().take(400) }, modifier = Modifier.fillMaxWidth(),
            singleLine = true, enabled = !model.busy, textStyle = MaterialTheme.typography.bodySmall,
            placeholder = { Text("git@github.com:you/repo.git") })
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = { model.updateGitRemote(remoteDraft) },
                enabled = !model.busy && remoteDraft.isNotBlank() && remoteDraft != model.gitRemote) { Text("Save remote") }
            if (model.gitRemote != null) TextButton(onClick = model::testGitAccess, enabled = !model.busy) { Text("Test access") }
        }
        if (sshSuggestion != null) {
            Surface(shape = RoundedCornerShape(6.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
                Column(Modifier.fillMaxWidth().padding(10.dp)) {
                    Text("This origin uses HTTPS. Codriod never asks for GitHub passwords or tokens, so private " +
                        "repositories and push use your SSH key instead.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick = model::useSshRemote, enabled = !model.busy,
                        contentPadding = PaddingValues(horizontal = 0.dp)) { Text("Switch to $sshSuggestion", maxLines = 1,
                            overflow = TextOverflow.Ellipsis) }
                }
            }
        }

        SectionLabel("SYNC  ·  ${model.gitSnapshot.branch.ifBlank { "no branch" }}")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val ready = !model.busy && model.gitRemote != null
            OutlinedButton(onClick = model::fetchGit, enabled = ready, modifier = Modifier.weight(1f)) { Text("Fetch") }
            OutlinedButton(onClick = model::pullGit, enabled = ready, modifier = Modifier.weight(1f)) { Text("Pull") }
            Button(onClick = model::pushGit, enabled = ready && model.gitSnapshot.hasHead, modifier = Modifier.weight(1f)) { Text("Push") }
        }
        if (model.gitRemote == null)
            Text("Set an origin remote to fetch, pull and push.", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(4.dp))
        Text("Git runs on Codriod's built-in engine (JGit) with your own SSH key. The phone's terminal is " +
            "Android's shell, which has no git command.",
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** The person's own GitHub sign-in: an SSH key made on this phone and added by them on github.com. */
@Composable
fun GitHubAccountSection(model: WorkspaceModel) {
    val account = model.gitAccount
    val key = account.defaultKey()
    var showKey by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var newKeyDialog by remember { mutableStateOf(false) }
    SectionLabel("GITHUB LOGIN")
    Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (key == null) {
                Text("Not signed in", style = MaterialTheme.typography.titleMedium)
                Text("Codriod creates an SSH key on this phone. You add its public key to your own GitHub account " +
                    "on github.com and approve it there. Codriod never sees your password or tokens and has no account of its own.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Button(onClick = model::startGitHubLogin, enabled = !model.busy, modifier = Modifier.fillMaxWidth()) {
                    Text("Set up GitHub login")
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(key.verifiedUser?.let { "Signed in as @$it" } ?: "Key ready: add it on GitHub, then Verify",
                            style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${key.label} · ${key.fingerprint}", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Box(Modifier.size(8.dp).background(
                        if (key.verifiedUser != null) LocalCodeColors.current.success else MaterialTheme.colorScheme.outline,
                        RoundedCornerShape(4.dp)))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(onClick = model::startGitHubLogin, enabled = !model.busy, modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 6.dp)) { Text("Add on GitHub", maxLines = 1) }
                    Button(onClick = model::verifyGitHub, enabled = !model.busy, modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 6.dp)) { Text("Verify", maxLines = 1) }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { showKey = !showKey }) { Text(if (showKey) "Hide public key" else "Public key") }
                    TextButton(onClick = { account.copyPublicKey(key); model.message = "Public key copied" }) { Text("Copy") }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { confirmDelete = true }, enabled = !model.busy) {
                        Text("Remove", color = MaterialTheme.colorScheme.error)
                    }
                }
                if (showKey) Text(key.publicKey, fontFamily = FontFamily.Monospace, fontSize = 10.sp,
                    modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background, RoundedCornerShape(4.dp))
                        .padding(8.dp))
                if (account.keys.size > 1 || key.verifiedUser != null) {
                    Text("KEYS ON THIS PHONE", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    account.keys.forEach { item ->
                        Row(Modifier.fillMaxWidth().clickable(enabled = !model.busy && item.id != key.id) {
                            model.setDefaultGitKey(item.id)
                        }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = item.id == key.id, onClick = { model.setDefaultGitKey(item.id) },
                                enabled = !model.busy, modifier = Modifier.size(28.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(item.label + (item.verifiedUser?.let { "  ·  @$it" } ?: ""),
                                style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f), maxLines = 1)
                        }
                    }
                    TextButton(onClick = { newKeyDialog = true }, enabled = !model.busy) { Text("Add key for another account") }
                }
            }
            Text("Private key: ${account.keyStorage}. It never leaves this phone and is excluded from backups.",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    if (confirmDelete && key != null) AlertDialog(onDismissRequest = { confirmDelete = false },
        title = { Text("Remove SSH key?") },
        text = { Text("“${key.label}” is deleted from this phone. Also delete it from github.com/settings/keys so it can't be used anywhere.") },
        confirmButton = { TextButton(onClick = { confirmDelete = false; model.deleteGitKey(key.id) }) {
            Text("Remove", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } })
    if (newKeyDialog) WorkspaceNameDialog(title = "New SSH key", confirmText = "Create", initial = "Work",
        onDismiss = { newKeyDialog = false }, onConfirm = { newKeyDialog = false; model.createGitKey(it) })
}

@Composable
fun CloneRepositoryDialog(model: WorkspaceModel, onDismiss: () -> Unit) {
    var url by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    val suggestedName = url.trim().trimEnd('/').substringAfterLast('/').substringAfterLast(':').removeSuffix(".git")
    val isSsh = GitRemoteService.isSsh(url.trim())
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Clone repository") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(url, { url = it.trim().take(400) }, singleLine = true, label = { Text("Repository URL") },
                    placeholder = { Text("git@github.com:you/repo.git") }, textStyle = MaterialTheme.typography.bodySmall)
                OutlinedTextField(name, { name = it.take(64) }, singleLine = true, label = { Text("Project name") },
                    placeholder = { Text(suggestedName.ifBlank { "my-repo" }) })
                Text(if (isSsh) "Private and public repositories over SSH use your own GitHub login below."
                    else "Public HTTPS repositories clone without signing in. For private ones use the SSH address.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                GitHubAccountSection(model)
            }
        },
        confirmButton = { TextButton(enabled = url.isNotBlank() && !model.busy, onClick = {
            onDismiss(); model.cloneRepository(url, name.ifBlank { suggestedName.ifBlank { "Cloned repository" } })
        }) { Text("Clone") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

/** Shown when a Git server's identity is unknown. GitHub's keys are pinned and never shown here. */
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
