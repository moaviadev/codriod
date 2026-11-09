package dev.moavia.codriod.workspace

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.DateFormat
import java.util.Date

private enum class GitTab(val title: String) { Changes("Changes"), History("History"), Branches("Branches") }

@Composable
fun GitPanel(model: WorkspaceModel) {
    val project = model.project ?: return
    var tab by remember(project.id) { mutableStateOf(GitTab.Changes) }
    var commitMessage by remember(project.id) { mutableStateOf("") }
    var authorName by remember(project.id) { mutableStateOf(model.gitSnapshot.authorName) }
    var authorEmail by remember(project.id) { mutableStateOf(model.gitSnapshot.authorEmail) }
    var branchDialog by remember(project.id) { mutableStateOf(false) }
    var branchName by remember(project.id) { mutableStateOf("") }
    val code = LocalCodeColors.current

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Row(Modifier.fillMaxWidth().height(52.dp).background(code.header).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            WorkspaceIconButton(ToolIcon.Back, "Back to editor", model::closeGit)
            Column(Modifier.weight(1f).padding(start = 4.dp)) {
                Text("SOURCE CONTROL", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(project.name, style = MaterialTheme.typography.titleMedium, maxLines = 1,
                    overflow = TextOverflow.Ellipsis)
            }
            WorkspaceIconButton(ToolIcon.Refresh, "Refresh Git status", model::refreshGit)
        }

        if (!model.gitSnapshot.initialized) {
            Column(Modifier.fillMaxSize().padding(horizontal = 24.dp),
                verticalArrangement = Arrangement.Center) {
                Text("TRACK THIS PROJECT", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(10.dp))
                Text("Create a local Git repository", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(8.dp))
                Text("Your project files stay on this phone. Codriod adds its own metadata to Git's local ignore list.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(20.dp))
                Button(onClick = model::initializeGit, enabled = !model.busy,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text(if (model.busy) "Preparing repository…" else "Initialize Git")
                }
            }
        } else {
            GitBranchStrip(model.gitSnapshot.branch, model.gitSnapshot.changedCount)
            Row(Modifier.fillMaxWidth().height(44.dp).padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically) {
                GitTab.entries.forEach { item ->
                    val selected = tab == item
                    Column(Modifier.weight(1f).fillMaxHeight().clickable { tab = item },
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(item.title, color = if (selected) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.labelLarge)
                            if (item == GitTab.Changes && model.gitSnapshot.changedCount > 0) {
                                Spacer(Modifier.width(5.dp))
                                Text(model.gitSnapshot.changedCount.toString(),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    style = MaterialTheme.typography.labelSmall)
                            }
                        }
                        Spacer(Modifier.height(5.dp))
                        Box(Modifier.width(if (selected) 28.dp else 0.dp).height(2.dp)
                            .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(1.dp)))
                    }
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when (tab) {
                    GitTab.Changes -> GitChanges(model)
                    GitTab.History -> GitHistory(model.gitSnapshot.commits)
                    GitTab.Branches -> GitBranches(model, onCreate = { branchDialog = true })
                }
            }
            if (tab == GitTab.Changes) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    OutlinedTextField(
                        value = commitMessage,
                        onValueChange = { commitMessage = it.take(200) },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = model.gitSnapshot.staged.isNotEmpty() && !model.busy,
                        placeholder = { Text("Commit message") },
                        minLines = 1, maxLines = 2, textStyle = MaterialTheme.typography.bodyMedium
                    )
                    Row(verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        OutlinedTextField(authorName, { authorName = it.take(80) },
                            modifier = Modifier.weight(1f), enabled = !model.busy,
                            singleLine = true, placeholder = { Text("Author name") },
                            textStyle = MaterialTheme.typography.bodySmall)
                        OutlinedTextField(authorEmail, { authorEmail = it.take(120) },
                            modifier = Modifier.weight(1.2f), enabled = !model.busy,
                            singleLine = true, placeholder = { Text("Email") },
                            textStyle = MaterialTheme.typography.bodySmall)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Commit includes staged files only", modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Button(onClick = {
                            model.commitGit(commitMessage, authorName, authorEmail)
                            commitMessage = ""
                        }, enabled = model.gitSnapshot.staged.isNotEmpty() && commitMessage.isNotBlank() &&
                            authorName.isNotBlank() && authorEmail.contains('@') && !model.busy) {
                            Text(if (model.busy) "Working…" else "Commit")
                        }
                    }
                }
            }
        }
    }

    if (branchDialog) AlertDialog(
        onDismissRequest = { branchDialog = false },
        title = { Text("Create branch") },
        text = { OutlinedTextField(branchName, { branchName = it.take(80) }, singleLine = true,
            label = { Text("Branch name") }, placeholder = { Text("feature/my-change") }) },
        confirmButton = { TextButton(enabled = branchName.isNotBlank() && !model.busy, onClick = {
            model.createGitBranch(branchName)
            branchName = ""
            branchDialog = false
            tab = GitTab.Branches
        }) { Text("Create") } },
        dismissButton = { TextButton(onClick = { branchDialog = false }) { Text("Cancel") } }
    )
}

@Composable
private fun GitBranchStrip(branch: String, changed: Int) {
    Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer)
        .padding(horizontal = 14.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
        ToolGlyph(ToolIcon.Git, MaterialTheme.colorScheme.primary, Modifier.size(17.dp))
        Spacer(Modifier.width(8.dp))
        Text(branch.ifBlank { "No branch" }, modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(if (changed == 0) "clean" else "$changed changed",
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun GitChanges(model: WorkspaceModel) {
    val snapshot = model.gitSnapshot
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        if (snapshot.staged.isNotEmpty()) {
            GitSectionHeader("STAGED", snapshot.staged.size, trailing = "Unstage all", enabled = !model.busy,
                onClick = model::unstageAllGitFiles)
            snapshot.staged.forEach { path -> GitFileRow(path, "S", true, model) }
        }
        val unstaged = (snapshot.modified + snapshot.untracked).distinct().sorted()
        if (unstaged.isNotEmpty()) {
            GitSectionHeader("CHANGES", unstaged.size, trailing = "Stage all", enabled = !model.busy,
                onClick = model::stageAllGitFiles)
            unstaged.forEach { path ->
                val untracked = path in snapshot.untracked
                GitFileRow(path, if (untracked) "?" else "M", false, model)
            }
        }
        if (snapshot.changedCount == 0) {
            Column(Modifier.fillMaxWidth().weight(1f).padding(22.dp), verticalArrangement = Arrangement.Center) {
                Text("Working tree clean", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                Text("Changes to project files will appear here.", color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall)
            }
        }
        model.gitDiffPath?.let { path ->
            Column(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 5.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("DIFF  /  $path", modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    TextButton(onClick = { model.showGitDiff(path, !model.gitDiffCached) }) {
                        Text(if (model.gitDiffCached) "Staged" else "Unstaged")
                    }
                }
                GitDiffView(model.gitDiff)
            }
        }
    }
}

@Composable
private fun GitSectionHeader(title: String, count: Int, trailing: String, enabled: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 8.dp, top = 10.dp, bottom = 3.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(7.dp))
        Text(count.toString(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.weight(1f))
        TextButton(onClick = onClick, enabled = enabled, contentPadding = PaddingValues(horizontal = 8.dp)) {
            Text(trailing, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun GitFileRow(path: String, marker: String, staged: Boolean, model: WorkspaceModel) {
    val selected = model.gitDiffPath == path && model.gitDiffCached == staged
    Row(Modifier.fillMaxWidth().background(if (selected) MaterialTheme.colorScheme.surfaceContainer else MaterialTheme.colorScheme.background)
        .clickable(enabled = !model.busy) { model.showGitDiff(path, staged) }
        .padding(start = 14.dp, end = 8.dp, top = 3.dp, bottom = 3.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(marker, modifier = Modifier.width(20.dp), style = MaterialTheme.typography.labelSmall,
            color = when (marker) {
                "S", "?" -> LocalCodeColors.current.success
                else -> MaterialTheme.colorScheme.primary
            })
        Text(path, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        TextButton(onClick = { if (staged) model.unstageGitFile(path) else model.stageGitFile(path) },
            enabled = !model.busy, contentPadding = PaddingValues(horizontal = 8.dp)) {
            Text(if (staged) "−" else "+", fontSize = 19.sp, color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun GitDiffView(diff: String) {
    Column(Modifier.fillMaxWidth().heightIn(max = 260.dp).clip(RoundedCornerShape(5.dp))
        .background(MaterialTheme.colorScheme.surfaceContainer).verticalScroll(rememberScrollState())
        .horizontalScroll(rememberScrollState()).padding(8.dp)) {
        diff.lineSequence().take(500).forEach { line ->
            val color = when {
                line.startsWith("+") && !line.startsWith("+++") -> LocalCodeColors.current.success
                line.startsWith("-") && !line.startsWith("---") -> MaterialTheme.colorScheme.error
                line.startsWith("@@") || line.startsWith("diff ") || line.startsWith("index ") -> MaterialTheme.colorScheme.primary
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            }
            Text(line.ifEmpty { " " }, color = color, fontFamily = FontFamily.Monospace,
                fontSize = 10.sp, maxLines = 1, softWrap = false)
        }
    }
}

@Composable
private fun GitHistory(commits: List<GitCommit>) {
    if (commits.isEmpty()) {
        Column(Modifier.fillMaxSize().padding(22.dp), verticalArrangement = Arrangement.Center) {
            Text("No commits yet", style = MaterialTheme.typography.titleMedium)
            Text("Stage files and create your first local commit.",
                color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        }
        return
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        commits.forEach { commit ->
            Column(Modifier.fillMaxWidth().padding(horizontal = 15.dp, vertical = 11.dp)) {
                Text(commit.message, style = MaterialTheme.typography.bodyMedium, maxLines = 2,
                    overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(4.dp))
                Text("${commit.id.take(7)}  ·  ${commit.author}  ·  ${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(commit.timestamp))}",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}

@Composable
private fun GitBranches(model: WorkspaceModel, onCreate: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text("LOCAL BRANCHES", modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = onCreate, enabled = !model.busy && model.gitSnapshot.hasHead) { Text("New branch") }
        }
        if (model.gitSnapshot.branches.isEmpty()) {
            Text("Create the first commit to establish a branch.", Modifier.padding(16.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            model.gitSnapshot.branches.forEach { branch ->
                val current = branch == model.gitSnapshot.branch
                Row(Modifier.fillMaxWidth().clickable(enabled = !current && !model.busy) {
                    model.checkoutGitBranch(branch)
                }.padding(horizontal = 15.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
                    ToolGlyph(ToolIcon.Git, if (current) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant, Modifier.size(17.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(branch, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    if (current) Text("CURRENT", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary)
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
        if (!model.gitSnapshot.hasHead) {
            Text("Make an initial commit before creating or switching branches.",
                Modifier.fillMaxWidth().padding(12.dp), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
