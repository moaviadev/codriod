package dev.moavia.codriod.workspace

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.moavia.codriod.git.GitHubApi
import dev.moavia.codriod.git.GitHubRepo

private val GhBlue = Color(0xFF007ACC)
private val GhLink = Color(0xFF3794FF)
private val GhGreen = Color(0xFF89D185)
private const val OCTOCAT = "M12 2a10 10 0 0 0-3.2 19.5c.5.1.7-.2.7-.5v-1.7c-2.8.6-3.4-1.3-3.4-1.3-.4-1.2-1.1-1.5-1.1-1.5-.9-.6.1-.6.1-.6 1 .1 1.5 1 1.5 1 .9 1.5 2.3 1.1 2.9.8.1-.6.3-1.1.6-1.3-2.2-.3-4.6-1.1-4.6-5 0-1.1.4-2 1-2.7-.1-.2-.4-1.3.1-2.6 0 0 .8-.3 2.7 1a9.4 9.4 0 0 1 5 0c1.9-1.3 2.7-1 2.7-1 .5 1.3.2 2.4.1 2.6.6.7 1 1.6 1 2.7 0 3.9-2.4 4.7-4.6 5 .4.3.7.9.7 1.9V21c0 .3.2.6.7.5A10 10 0 0 0 12 2z"

@Composable
fun GitHubMark(size: androidx.compose.ui.unit.Dp, tint: Color) {
    val path = remember { PathParser().parsePathString(OCTOCAT).toPath() }
    Canvas(Modifier.size(size)) { scale(this.size.width / 24f, this.size.height / 24f, pivot = androidx.compose.ui.geometry.Offset.Zero) { drawPath(path, tint) } }
}

private val languageColors = mapOf("Kotlin" to 0xFFA97BFF, "Java" to 0xFFB07219, "JavaScript" to 0xFFF1E05A, "TypeScript" to 0xFF3178C6,
    "HTML" to 0xFFE34C26, "CSS" to 0xFF563D7C, "Python" to 0xFF3572A5, "Shell" to 0xFF89E051, "Go" to 0xFF00ADD8, "Rust" to 0xFFDEA584,
    "C" to 0xFF555555, "C++" to 0xFFF34B7D, "C#" to 0xFF178600, "Dart" to 0xFF00B4AB, "PHP" to 0xFF4F5D95, "Ruby" to 0xFF701516,
    "Swift" to 0xFFF05138, "Vue" to 0xFF41B883, "SCSS" to 0xFFC6538C, "Jupyter Notebook" to 0xFFDA5B0B)

private fun langColor(language: String) = Color(languageColors[language] ?: 0xFF8B949E)

private fun updatedAgo(iso: String): String {
    val time = GitHubApi.parseTime(iso) ?: return ""
    val minutes = ((System.currentTimeMillis() - time).coerceAtLeast(0)) / 60_000
    return when {
        minutes < 60 -> "Updated ${minutes.coerceAtLeast(1)} min ago"
        minutes < 24 * 60 -> "Updated ${minutes / 60} h ago"
        minutes < 48 * 60 -> "Yesterday"
        minutes < 7 * 24 * 60 -> "${minutes / (24 * 60)} days ago"
        minutes < 30 * 24 * 60 -> "${minutes / (7 * 24 * 60)} weeks ago"
        else -> "${minutes / (30 * 24 * 60)} months ago"
    }
}

@Composable
private fun GhTopBar(title: String, onBack: () -> Unit, actions: @Composable RowScope.() -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        WorkspaceIconButton(ToolIcon.Back, "Back", onBack)
        Text(title, fontSize = 17.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f).padding(start = 4.dp))
        actions()
    }
}

/**
 * GitHub: the user connects their own access token, sees all their repositories, opens one to pick a
 * branch and clone it. Pull and push then work from Source Control.
 */
@Composable
fun GitHubScreen(model: WorkspaceModel, onBack: () -> Unit) {
    val repo = model.selectedRepo
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        when {
            model.gitAccount.githubToken == null -> ConnectGitHub(model, onBack)
            repo != null -> RepoDetail(model, repo) { model.selectedRepo = null }
            else -> RepoList(model, onBack)
        }
    }
}

@Composable
private fun ConnectGitHub(model: WorkspaceModel, onBack: () -> Unit) {
    var token by remember { mutableStateOf("") }
    var opened by rememberSaveableBool()
    val clipboard = LocalClipboardManager.current
    Column(Modifier.fillMaxSize()) {
        GhTopBar("GitHub", onBack)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp))
                .background(Brush.linearGradient(listOf(Color(0xFF1B2A3A), Color(0xFF22203A))))
                .border(1.dp, Color(0xFF2F3B55), RoundedCornerShape(20.dp)).padding(vertical = 22.dp, horizontal = 18.dp),
                horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(64.dp).background(Color.White, CircleShape), contentAlignment = Alignment.Center) { GitHubMark(38.dp, Color(0xFF111111)) }
                Spacer(Modifier.height(12.dp))
                Text("Connect your GitHub", color = Color.White, fontSize = 21.sp, fontWeight = FontWeight.Bold)
                Text("See all your repositories, clone them to this phone, and pull or push your changes.", color = Color(0xFFAAB6C8),
                    fontSize = 12.5.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp))
            }
            Spacer(Modifier.height(16.dp))
            StepRow(if (opened) "✓" else "1", opened, "Create a token on GitHub", "The page opens with “repo” already ticked. Press Generate token and copy it.") {
                OutlinedButton(onClick = { opened = true; model.gitAccount.openUrl(GitAccountController.GITHUB_NEW_TOKEN_URL) },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp).height(46.dp), shape = RoundedCornerShape(12.dp)) {
                    Text("↗  Open GitHub token page", fontWeight = FontWeight.SemiBold)
                }
            }
            Spacer(Modifier.height(14.dp))
            StepRow("2", false, "Paste it here", "Starts with ghp_ or github_pat_") {
                Row(Modifier.fillMaxWidth().padding(top = 8.dp).height(46.dp).clip(RoundedCornerShape(12.dp))
                    .border(1.5.dp, GhBlue, RoundedCornerShape(12.dp)).padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) {
                        if (token.isEmpty()) Text("Paste your token", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
                        BasicTextField(token, { token = it.trim().take(255) }, singleLine = true, enabled = !model.busy,
                            visualTransformation = PasswordVisualTransformation(), cursorBrush = SolidColor(GhLink),
                            textStyle = LocalTextStyle.current.copy(color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp), modifier = Modifier.fillMaxWidth())
                    }
                    TextButton(onClick = { clipboard.getText()?.text?.let { token = it.trim().take(255) } }) { Text("Paste", color = GhLink) }
                }
                Button(onClick = { model.connectGitHub(token); token = "" }, enabled = !model.busy && token.length >= 20,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp).height(46.dp), shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = GhBlue)) {
                    Text(if (model.busy) "Connecting…" else "Connect", fontWeight = FontWeight.SemiBold)
                }
            }
            Row(Modifier.padding(vertical = 16.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color(0xFF1D2A20))
                .border(1.dp, Color(0xFF2C4A33), RoundedCornerShape(12.dp)).padding(12.dp)) {
                ToolGlyph(ToolIcon.Lock, Color(0xFFB5E0A0), Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text("Your token stays encrypted on this phone, goes only to github.com, and is never shared with AI agents.",
                    color = Color(0xFFB5E0A0), fontSize = 11.5.sp)
            }
        }
    }
}

@Composable
private fun rememberSaveableBool(): MutableState<Boolean> = androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }

@Composable
private fun StepRow(number: String, done: Boolean, title: String, detail: String, content: @Composable ColumnScope.() -> Unit) {
    Row {
        Box(Modifier.size(26.dp).background(if (done) Color(0xFF2F8F5B) else GhBlue, CircleShape), contentAlignment = Alignment.Center) {
            Text(number, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            Text(detail, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            content()
        }
    }
}

@Composable
private fun RepoList(model: WorkspaceModel, onBack: () -> Unit) {
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf("All") }
    var sort by remember { mutableStateOf("Recent") }
    var sortMenu by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var urlDialog by remember { mutableStateOf(false) }
    var confirmDisconnect by remember { mutableStateOf(false) }
    val profile = model.githubProfile
    val login = profile?.login ?: model.gitAccount.githubToken?.user.orEmpty()
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val repos = model.githubRepos
    val shown = repos.filter { r ->
        (query.isBlank() || r.fullName.contains(query.trim(), true) || r.description.contains(query.trim(), true)) && when (filter) {
            "Mine" -> r.owner.equals(login, true)
            "Private" -> r.private
            "Orgs" -> !r.owner.equals(login, true)
            else -> true
        }
    }.let { list -> when (sort) { "Name" -> list.sortedBy { it.name.lowercase() }; "Stars" -> list.sortedByDescending { it.stars }; else -> list } }
    Column(Modifier.fillMaxSize()) {
        GhTopBar("GitHub", onBack) {
            WorkspaceIconButton(ToolIcon.Refresh, "Reload repositories", model::loadGitHubRepos)
            Box {
                WorkspaceIconButton(ToolIcon.More, "GitHub menu") { menu = true }
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem({ Text("Clone from URL") }, { menu = false; urlDialog = true })
                    DropdownMenuItem({ Text("Open github.com") }, { menu = false; model.gitAccount.openUrl("https://github.com/$login") })
                    DropdownMenuItem({ Text("Disconnect", color = MaterialTheme.colorScheme.error) }, { menu = false; confirmDisconnect = true })
                }
            }
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(bottom = 80.dp)) {
            item {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(52.dp).clip(CircleShape).background(Brush.linearGradient(listOf(Color(0xFFF6A54C), Color(0xFFE3577C)))),
                        contentAlignment = Alignment.Center) { Text(login.take(1).uppercase(), color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 20.sp) }
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text("@$login", fontSize = 17.sp, fontWeight = FontWeight.Bold)
                        val expires = profile?.tokenExpires
                        Text(when {
                            expires == null -> "Connected"
                            expires < System.currentTimeMillis() -> "Connected · token expired"
                            else -> "Connected · token expires in ${((expires - System.currentTimeMillis()) / 86_400_000L).coerceAtLeast(0)} days"
                        }, fontSize = 12.sp, color = muted)
                    }
                }
            }
            item {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(repos.size to "Repositories", repos.count { it.private } to "Private",
                        repos.count { model.localProjectFor(it) != null } to "On this phone").forEach { (n, label) ->
                        Column(Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surface).padding(horizontal = 10.dp, vertical = 8.dp)) {
                            Text(n.toString(), fontSize = 16.sp, fontWeight = FontWeight.Bold)
                            Text(label, fontSize = 11.sp, color = muted)
                        }
                    }
                }
            }
            item {
                Row(Modifier.padding(horizontal = 16.dp).fillMaxWidth().height(42.dp).clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surface).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    ToolGlyph(ToolIcon.Search, muted, Modifier.size(16.dp))
                    Spacer(Modifier.width(9.dp))
                    Box(Modifier.weight(1f)) {
                        if (query.isEmpty()) Text("Find a repository…", color = muted, fontSize = 13.sp)
                        BasicTextField(query, { query = it }, singleLine = true, cursorBrush = SolidColor(GhLink),
                            textStyle = LocalTextStyle.current.copy(color = MaterialTheme.colorScheme.onSurface, fontSize = 13.sp), modifier = Modifier.fillMaxWidth())
                    }
                }
                Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("All", "Mine", "Private", "Orgs").forEach { label ->
                            val on = filter == label
                            Text(label, fontSize = 11.5.sp, color = if (on) Color(0xFF9FD0FF) else muted,
                                modifier = Modifier.clip(RoundedCornerShape(14.dp)).background(if (on) Color(0xFF0B3A5E) else MaterialTheme.colorScheme.surface)
                                    .clickable { filter = label }.padding(horizontal = 11.dp, vertical = 5.dp))
                        }
                    }
                    Box {
                        Text("$sort ▾", fontSize = 11.5.sp, modifier = Modifier.clickable { sortMenu = true }.padding(6.dp))
                        DropdownMenu(sortMenu, { sortMenu = false }) {
                            listOf("Recent", "Name", "Stars").forEach { DropdownMenuItem({ Text(it) }, { sort = it; sortMenu = false }) }
                        }
                    }
                }
            }
            when {
                model.githubLoading && repos.isEmpty() -> item { Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
                model.githubError != null && repos.isEmpty() -> item {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(model.githubError.orEmpty(), color = MaterialTheme.colorScheme.error)
                        Button(onClick = model::loadGitHubRepos) { Text("Try again") }
                    }
                }
                shown.isEmpty() -> item { Text(if (repos.isEmpty()) "No repositories on this account yet." else "No repository matches.", Modifier.padding(20.dp), color = muted) }
            }
            items(shown, key = { it.fullName }) { repo -> RepoRow(model, repo, login) }
        }
        OutlinedButton(onClick = { urlDialog = true }, modifier = Modifier.padding(16.dp).fillMaxWidth().height(46.dp), shape = RoundedCornerShape(12.dp)) {
            Text("🔗  Clone from URL", fontWeight = FontWeight.SemiBold)
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
private fun RepoRow(model: WorkspaceModel, repo: GitHubRepo, login: String) {
    val local = model.localProjectFor(repo)
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Row(Modifier.fillMaxWidth().clickable { model.openRepo(repo) }.padding(horizontal = 16.dp, vertical = 11.dp), verticalAlignment = Alignment.Top) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (repo.owner.equals(login, true)) repo.name else repo.fullName, color = GhLink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                Text(if (repo.private) "Private" else "Public", fontSize = 9.5.sp, color = muted, modifier = Modifier.padding(start = 6.dp)
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(9.dp)).padding(horizontal = 6.dp, vertical = 1.dp))
            }
            if (repo.description.isNotBlank()) Text(repo.description, fontSize = 11.5.sp, color = muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 3.dp))
            Row(Modifier.padding(top = 5.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (repo.language.isNotBlank()) Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(9.dp).background(langColor(repo.language), CircleShape)); Spacer(Modifier.width(4.dp))
                    Text(repo.language, fontSize = 11.sp, color = muted)
                }
                Text("★ ${repo.stars}", fontSize = 11.sp, color = muted)
                Text(updatedAgo(repo.updatedAt), fontSize = 11.sp, color = muted, maxLines = 1)
            }
        }
        Spacer(Modifier.width(8.dp))
        if (local != null) Text("✓ Open", color = GhGreen, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.clip(RoundedCornerShape(9.dp)).background(Color(0xFF1D2A20)).border(1.dp, Color(0xFF2C4A33), RoundedCornerShape(9.dp))
                .clickable { model.githubOpen = false; model.openProject(local) }.padding(horizontal = 12.dp, vertical = 7.dp))
        else Text("Clone", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.clip(RoundedCornerShape(9.dp)).background(if (model.busy) Color(0xFF3C3C3C) else GhBlue)
                .clickable(enabled = !model.busy) { model.cloneGitHubRepo(repo) }.padding(horizontal = 12.dp, vertical = 7.dp))
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .5f))
}

@Composable
private fun RepoDetail(model: WorkspaceModel, repo: GitHubRepo, onBack: () -> Unit) {
    var branch by remember(repo.fullName) { mutableStateOf(repo.defaultBranch) }
    var name by remember(repo.fullName) { mutableStateOf(repo.name) }
    var branchMenu by remember { mutableStateOf(false) }
    val local = model.localProjectFor(repo)
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column(Modifier.fillMaxSize()) {
        GhTopBar("Repository", onBack) {
            Box(Modifier.size(40.dp).clickable { model.gitAccount.openUrl("https://github.com/${repo.fullName}") }, contentAlignment = Alignment.Center) {
                Text("↗", fontSize = 18.sp)
            }
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
            Text("${repo.owner} /", fontSize = 12.5.sp, color = muted)
            Text(repo.name, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            if (repo.description.isNotBlank()) Text(repo.description, fontSize = 12.5.sp, color = muted, modifier = Modifier.padding(top = 6.dp))
            Row(Modifier.padding(top = 10.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill(if (repo.private) "🔒 Private" else "🌐 Public")
                Pill("★ ${repo.stars}")
                Pill("⑂ ${repo.forks} ${if (repo.forks == 1) "fork" else "forks"}")
                if (repo.language.isNotBlank()) Row(Modifier.clip(RoundedCornerShape(9.dp)).background(MaterialTheme.colorScheme.surface)
                    .padding(horizontal = 9.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(9.dp).background(langColor(repo.language), CircleShape)); Spacer(Modifier.width(5.dp)); Text(repo.language, fontSize = 11.5.sp)
                }
            }
            Card("CLONE TO THIS PHONE") {
                Box {
                    Row(Modifier.fillMaxWidth().height(40.dp).clip(RoundedCornerShape(10.dp)).border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(10.dp))
                        .clickable(enabled = local == null) { branchMenu = true }.padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        ToolGlyph(ToolIcon.Git, MaterialTheme.colorScheme.onSurface, Modifier.size(15.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Branch: $branch", fontSize = 13.sp, modifier = Modifier.weight(1f))
                        Text(if (model.repoBranches.isEmpty()) "…" else "${model.repoBranches.size} branches ▾", fontSize = 12.sp, color = muted)
                    }
                    DropdownMenu(branchMenu, { branchMenu = false }) {
                        model.repoBranches.forEach { b -> DropdownMenuItem({ Text(if (b == branch) "✓ $b" else b) }, { branch = b; branchMenu = false; model.openRepo(repo, b) }) }
                    }
                }
                if (local == null) Row(Modifier.padding(top = 8.dp).fillMaxWidth().height(40.dp).clip(RoundedCornerShape(10.dp))
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(10.dp)).padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    ToolGlyph(ToolIcon.Folder, MaterialTheme.colorScheme.onSurface, Modifier.size(15.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Project name: ", fontSize = 13.sp, color = muted)
                    BasicTextField(name, { name = it.take(64) }, singleLine = true, cursorBrush = SolidColor(GhLink),
                        textStyle = LocalTextStyle.current.copy(color = MaterialTheme.colorScheme.onSurface, fontSize = 13.sp), modifier = Modifier.weight(1f))
                }
                val progress = model.cloneProgress
                when {
                    local != null -> Button(onClick = { model.githubOpen = false; model.selectedRepo = null; model.openProject(local) },
                        modifier = Modifier.padding(top = 10.dp).fillMaxWidth().height(46.dp), shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2F8F5B))) { Text("✓ Open “${local.name}”", fontWeight = FontWeight.SemiBold) }
                    progress != null -> {
                        Box(Modifier.padding(top = 10.dp).fillMaxWidth().height(46.dp).clip(RoundedCornerShape(12.dp)).background(GhBlue), contentAlignment = Alignment.Center) {
                            Text("Cloning… $progress%", color = Color.White, fontWeight = FontWeight.SemiBold)
                        }
                        LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.padding(top = 8.dp).fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                            color = GhLink, trackColor = Color(0xFF333333))
                    }
                    else -> Button(onClick = { model.cloneGitHubRepo(repo, branch, name.trim().ifEmpty { repo.name }) }, enabled = !model.busy,
                        modifier = Modifier.padding(top = 10.dp).fillMaxWidth().height(46.dp), shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = GhBlue)) { Text("Clone", fontWeight = FontWeight.SemiBold) }
                }
            }
            model.repoError?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp, modifier = Modifier.padding(top = 10.dp)) }
            Card("FILES") {
                val files = model.repoFiles
                when {
                    files == null -> Text("Loading…", fontSize = 12.sp, color = muted)
                    files.isEmpty() -> Text("This branch has no files yet.", fontSize = 12.sp, color = muted)
                    else -> files.take(60).forEachIndexed { i, f ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(if (f.directory) "📁" else "📄", fontSize = 13.sp); Spacer(Modifier.width(9.dp))
                            Text(f.name, fontSize = 12.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        if (i < files.take(60).lastIndex) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .5f))
                    }
                }
            }
            model.repoReadme?.takeIf { it.isNotBlank() }?.let { readme ->
                Card("README") {
                    Text(readme.take(4000).lines().joinToString("\n") { it.trimStart('#', ' ').let { l -> if (l.startsWith("![")) "" else l } }.trim(),
                        fontSize = 12.sp, lineHeight = 18.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .85f))
                }
            }
        }
    }
}

@Composable
private fun Pill(text: String) {
    Text(text, fontSize = 11.5.sp, modifier = Modifier.clip(RoundedCornerShape(9.dp)).background(MaterialTheme.colorScheme.surface).padding(horizontal = 9.dp, vertical = 5.dp))
}

@Composable
private fun Card(label: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.padding(top = 12.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surface).padding(horizontal = 14.dp, vertical = 12.dp)) {
        Text(label, fontSize = 11.sp, letterSpacing = .6.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 7.dp))
        content()
    }
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
