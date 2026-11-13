package dev.moavia.codriod.workspace

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.moavia.codriod.BuildConfig

@Composable
fun GettingStartedScreen(model: WorkspaceModel, onBack: () -> Unit) {
    val p = LocalHomePalette.current
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val tabs = listOf("Overview", "Workflow", "Tutorials", "Use Cases")
    LaunchedEffect(Unit) { model.markGuideSeen() }
    Box(Modifier.fillMaxSize()) {
        HomeBackdrop()
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                Row(
                    Modifier.widthIn(max = 720.dp).fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RoundGuideButton(p, ToolIcon.Back, "Back", onBack)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Getting Started & Workflow", fontSize = 19.sp, fontWeight = FontWeight.Bold, color = p.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("Learn how to use Codriod step by step", fontSize = 12.sp, color = p.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                Row(
                    Modifier.widthIn(max = 720.dp).fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    tabs.forEachIndexed { i, label ->
                        val on = tab == i
                        val shape = RoundedCornerShape(14.dp)
                        Text(
                            label, fontSize = 13.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium,
                            color = if (on) Color.White else p.muted,
                            modifier = Modifier.clip(shape)
                                .background(if (on) Brush.horizontalGradient(listOf(p.primary, p.primary2)) else Brush.linearGradient(listOf(p.glass, p.glass)))
                                .border(1.dp, if (on) Color.Transparent else p.border, shape)
                                .clickable { tab = i }
                                .padding(horizontal = 14.dp, vertical = 8.dp)
                        )
                    }
                }
            }
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                val scroll = rememberScrollState()
                Column(
                    Modifier.widthIn(max = 720.dp).fillMaxWidth().verticalScroll(scroll)
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    when (tab) {
                        0 -> OverviewSection(p)
                        1 -> WorkflowSection(p)
                        2 -> TutorialsSection(p)
                        3 -> UseCasesSection(p)
                    }
                    AskChatGptCard(p)
                    Spacer(Modifier.height(18.dp))
                }
            }
        }
    }
}

@Composable
private fun RoundGuideButton(p: HomePalette, icon: ToolIcon, description: String, onClick: () -> Unit) {
    Box(
        Modifier.size(42.dp).glass(p, CircleShape, elevated = false).clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) { ToolGlyph(icon, p.text, Modifier.size(19.dp)) }
}

@Composable
private fun SectionCard(p: HomePalette, title: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().glass(p, RoundedCornerShape(20.dp), elevated = true).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (title != null) Text(title, color = p.text, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        content()
    }
}

@Composable
private fun OverviewSection(p: HomePalette) {
    SectionCard(p, "What is Codriod?") {
        Body(p, "Codriod is a mobile Android workspace for coding, running, and managing development projects on your phone. It combines a project home, code editor, preview/browser, terminal, Git/GitHub tools, add-ons, and Codriod AI in one app.")
        Body(p, "Who should use it: developers and learners who want to create or edit projects, run supported tasks, clone repositories, and get AI help directly on an Android device.")
        Body(p, "What you can build: HTML/CSS/JavaScript websites (with local preview), shell scripts, and — with add-ons like Ubuntu and Java & Kotlin — Java/Kotlin and other command-line based workflows inside the provided environments.")
    }
    SectionCard(p, "Available tools & features") {
        Bullet(p, "Home: create/open projects, open folders, clone from GitHub, continue recent work, access Getting Started & Workflow.")
        Bullet(p, "Editor & Files: edit code, manage files/folders, search, open images/SVG, use file actions.")
        Bullet(p, "Preview/Browser: run and preview local web projects (e.g. HTML) in the in-app browser.")
        Bullet(p, "Terminal: local shell, Ubuntu (proot) shell when installed, and remote SSH connections (Windows PowerShell/CMD or Linux) to machines you configure.")
        Bullet(p, "Git & GitHub: initialize repos, stage/commit/branch, configure remotes, use your own GitHub access, clone/open repos.")
        Bullet(p, "Codriod AI: chat/coding assistance in the agent panel (requires your provider key/setup in Add-ons).")
        Bullet(p, "Add-ons: Linux & AI CLIs, Java & Kotlin (JDK/Kotlin/Gradle in Ubuntu), More Extensions (language servers, debuggers, editor themes).")
    }
}

@Composable
private fun WorkflowSection(p: HomePalette) {
    SectionCard(p, "Recommended workflow") {
        Body(p, "Follow these steps for a smooth development flow in Codriod:")
    }
    val steps = listOf(
        WorkflowStep(1, "Initial Setup", "Prepare Codriod and optional tools.",
            "Lets you store projects accessibly and enable features you need (Ubuntu, AI, extensions).",
            "Home → allow storage if prompted; Add-ons → set up Linux & AI CLIs, Java & Kotlin, or More Extensions as needed; configure Codriod AI/provider keys in Add-ons if you want AI help.",
            "Example: Allow storage so projects live in Internal storage/Codriod, then install Ubuntu from Add-ons for Linux tools."),
        WorkflowStep(2, "Create or Open Project", "Start a new project or open existing work.",
            "Gives you a workspace with files Codriod can edit, preview, and run.",
            "Home → New project, Open folder, or Clone from GitHub.",
            "Example: Tap New project → choose Web → name it “my-site” to get a starter website."),
        WorkflowStep(3, "Use Terminal", "Run commands in the right environment.",
            "Useful for scripts, builds, package tools, and diagnostics.",
            "Open Terminal from Home or workspace; pick local/Ubuntu/remote sessions where available; use Run for supported files (e.g. .sh, web preview).",
            "Example: Open Terminal and run your project script, or use Ubuntu shell after installing the Linux add-on."),
        WorkflowStep(4, "Code with Editor", "Edit files and manage project contents.",
            "Where you write and refine code, configs, and docs.",
            "Open a project → Files/Search to navigate; tap files to edit; use editor actions (save, undo/redo, find/replace, copy/paste).",
            "Example: Edit index.html and style.css, then save changes."),
        WorkflowStep(5, "Build & Run", "Preview or execute supported project types.",
            "Confirms your work runs as expected on-device or in the configured environment.",
            "Use Preview/Browser for HTML/web projects; Run supported files (.sh) from the editor/terminal; for Java/Kotlin builds use Ubuntu + Java & Kotlin tools/Gradle as installed.",
            "Example: Open a web project and tap Browser/Preview to load the local site."),
        WorkflowStep(6, "Use AI Agent", "Get coding help inside your project.",
            "Helps explain, generate, or troubleshoot code when configured.",
            "Add-ons → Codriod AI (set provider/key/model) → open Codriod AI/agent panel in the workspace.",
            "Example: Ask Codriod AI to explain an error or suggest a fix for a file you’re editing."),
        WorkflowStep(7, "Install Extensions", "Add language servers, debuggers, and themes.",
            "Improves editing support and appearance for supported languages/tools.",
            "Add-ons → Java & Kotlin or More Extensions (requires Ubuntu where noted) → install/enable/manage items.",
            "Example: Install a language server from More Extensions to get better language tooling in supported contexts.")
    )
    steps.forEach { step ->
        WorkflowStepCard(p, step)
    }
}

@Composable
private fun WorkflowStepCard(p: HomePalette, step: WorkflowStep) {
    SectionCard(p) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(28.dp).clip(CircleShape).background(Brush.linearGradient(listOf(p.primary, p.primary2))), contentAlignment = Alignment.Center) {
                Text(step.number.toString(), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            }
            Spacer(Modifier.width(10.dp))
            Text(step.title, color = p.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        }
        Body(p, step.what)
        LabelValue(p, "Why useful", step.why)
        LabelValue(p, "How to access", step.how)
        LabelValue(p, "Example", step.example)
    }
}

@Composable
private fun TutorialsSection(p: HomePalette) {
    val tutorials = listOf(
        Tutorial("1. Initial App Setup", listOf(
            "Open Codriod on your device (launch opens the Home screen).",
            "If prompted, allow storage/All files access so projects can be saved in Internal storage/Codriod and remain visible to other apps/file managers.",
            "Optional: go to Add-ons to set up Linux & AI CLIs (Ubuntu), Java & Kotlin, or More Extensions if your workflow needs them.",
            "Optional: configure Codriod AI in Add-ons with your provider API key/model to enable AI assistance."
        ), "These are the settings and add-ons Codriod exposes on Home/Add-ons; install only what you need."),
        Tutorial("2. Creating Your First Project", listOf(
            "From Home, tap New project (or the quick Website/Shell/Empty actions on first-run).",
            "Choose a starter: Web (HTML/CSS/JS), Shell, or Empty (as available in the dialog/screen).",
            "Enter a project name and confirm Create.",
            "Codriod opens the project workspace where you can edit files and use preview/terminal/tools."
        ), "Use Web for sites you want to preview in the in-app browser."),
        Tutorial("3. Opening an Existing Folder", listOf(
            "From Home, tap Open folder.",
            "Select a folder from your device using the system picker.",
            "Grant access when asked so Codriod can read/write in place.",
            "The folder opens as a project; edits apply to that folder (Codriod does not copy it away)."
        ), "Requires folder access permission granted through Android."),
        Tutorial("4. Cloning a GitHub Repository", listOf(
            "From Home, tap GitHub (Clone from GitHub).",
            "Connect/sign in with your own GitHub access as provided by Codriod’s GitHub screen (token-based flow).",
            "Browse/select a repository and choose Clone (and branch if shown).",
            "Wait for clone to complete, then open the project from Home/workspace."
        ), "Use your own credentials/token; cloning needs network access and may take time for large repos."),
        Tutorial("5. Using the Terminal", listOf(
            "Open Terminal from Home or the workspace terminal action.",
            "Use the local Android shell for basic commands, or open Ubuntu/remote sessions if configured.",
            "Type commands or use Run for supported files (e.g. shell scripts, web preview entry points).",
            "Use terminal controls/menu options available in the UI (tabs/sessions, clear, copy/paste, etc.)."
        ), "Python/Node/PHP/bash as full Linux tools are not part of Android itself; use Ubuntu add-on or a remote machine that has them."),
        Tutorial("6. Java & Kotlin Development", listOf(
            "Ensure the Linux/Ubuntu environment is available if required by the add-on (Add-ons → Linux & AI CLIs).",
            "Open Add-ons → Java & Kotlin to check/install/manage JDK, Kotlin compiler, and Gradle tools.",
            "Open or create a project containing your Java/Kotlin/Gradle files.",
            "Build/run using the provided Ubuntu/terminal tools and project build commands (e.g. Gradle) as supported by your setup."
        ), "Java & Kotlin tooling in Codriod runs through the configured Ubuntu environment; availability depends on installation status."),
        Tutorial("7. Using the Code Editor", listOf(
            "Open a project and select a file from Files or search.",
            "Edit text; changes can be saved (auto/manual depending on context).",
            "Use editor features exposed in the UI: undo/redo, find/replace, copy/cut/paste, select all, and tab management.",
            "Open images/SVGs with the image viewer or open as text where supported."
        ), "Only features present in the editor UI are available; behavior follows current project implementation."),
        Tutorial("8. Building and Running Projects", listOf(
            "Web: open an HTML project and use Preview/Browser to view it locally.",
            "Shell: run supported .sh files via Run/terminal in the project context.",
            "Java/Kotlin/Gradle: use terminal/Ubuntu with installed Java & Kotlin tools for builds and runs.",
            "Check output/errors in the terminal or UI messages and iterate."
        ), "Run/preview support depends on file type and installed runtimes/add-ons."),
        Tutorial("9. Using the AI Agent", listOf(
            "Open Add-ons → Codriod AI and configure a provider, API key, and model (your key stays on-device and is sent only to that provider).",
            "Open Codriod AI/agent panel from the workspace or Add-ons entry.",
            "Describe your task or question with relevant project context.",
            "Review suggestions carefully before applying changes to your code."
        ), "AI features require a configured provider/key; Codriod does not provide built-in third-party AI access without your setup."),
        Tutorial("10. Installing and Managing Extensions", listOf(
            "Open Add-ons → More Extensions (and/or Java & Kotlin for its tools).",
            "Browse categories (e.g. language servers, debuggers, themes) as shown.",
            "Install, enable/disable, or manage items using the controls provided; check status/feedback in the UI.",
            "Use installed extensions/themes within supported editor/workspace flows."
        ), "Some extensions require Ubuntu to be installed; manage them from the same Add-ons screens."),
        Tutorial("11. Connecting a Remote Terminal", listOf(
            "Open Terminal and access remote connection/profile management (as available in the terminal UI).",
            "Create/edit a profile with host, port, username, and shell preset (Windows PowerShell, Windows CMD, or Linux).",
            "Provide credentials when connecting (password or key as supported); secrets are used for the session and not stored as plaintext in saved profile fields.",
            "Connect and interact with the remote machine over SSH; disconnect when done."
        ), "Requires network access and a reachable SSH server on the remote machine."),
        Tutorial("12. Managing Files and Projects", listOf(
            "From Home, open, rename, or delete projects as offered; use Continue to reopen recent work.",
            "Inside a project, use Files to create/rename/delete/move/open files and folders.",
            "Use Search to find content across the project.",
            "Use Git/GitHub tools for version control and repository sync where configured."
        ), "Deleting or moving projects/folders affects device storage—confirm actions carefully.")
    )
    tutorials.forEach { t -> ExpandableTutorial(p, t) }
}

@Composable
private fun ExpandableTutorial(p: HomePalette, t: Tutorial) {
    var open by rememberSaveable(t.title) { mutableStateOf(false) }
    Column(
        Modifier.fillMaxWidth().glass(p, RoundedCornerShape(18.dp), elevated = false)
            .animateContentSize(spring(stiffness = Spring.StiffnessMediumLow))
            .clickable { open = !open }
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(t.title, color = p.text, fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Text(if (open) "−" else "+", color = p.primary, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }
        AnimatedVisibility(open) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                t.steps.forEachIndexed { i, s -> Numbered(p, i + 1, s) }
                if (t.note.isNotBlank()) Text(t.note, color = p.muted, fontSize = 12.sp, lineHeight = 17.sp)
            }
        }
    }
}

@Composable
private fun UseCasesSection(p: HomePalette) {
    val cases = listOf(
        UseCase("Build an HTML/CSS/JavaScript website", listOf(
            "Home → New project → choose Web and create it.",
            "Open index.html, CSS, and JS files in the editor and make your changes.",
            "Save files, then open Preview/Browser to view the local site.",
            "Iterate: edit, save, and refresh/preview as needed."
        )),
        UseCase("Create and run a Java project", listOf(
            "Install/verify Ubuntu and Java & Kotlin tools in Add-ons (JDK/Gradle as needed).",
            "Create or open a folder/project containing your Java source/build files.",
            "Open Terminal (Ubuntu) in the project context.",
            "Run the appropriate build/run commands for your project (e.g. javac/java or Gradle tasks) and review output."
        )),
        UseCase("Create and build a Kotlin project", listOf(
            "Install/verify Ubuntu and Java & Kotlin tools in Add-ons (Kotlin/Gradle as needed).",
            "Create or open a Kotlin/Gradle project in Codriod.",
            "Use the editor to update source and build files.",
            "Open Terminal (Ubuntu) and run the project’s Gradle/build commands to compile and run."
        )),
        UseCase("Clone and work on a GitHub repository", listOf(
            "Home → GitHub/Clone from GitHub and connect with your GitHub access.",
            "Select the repository (and branch) and clone it to your device.",
            "Open the cloned project, edit files, and use Terminal/Preview as applicable.",
            "Use Git tools in Codriod to stage/commit and sync (fetch/pull/push) with your configured remote."
        )),
        UseCase("Use terminal commands to manage a project", listOf(
            "Open your project and launch Terminal.",
            "Choose the appropriate shell (local, Ubuntu, or remote) for the task.",
            "Navigate the project directory and run file/build/tool commands you need.",
            "Inspect command output and fix issues in the editor."
        )),
        UseCase("Use AI assistance for coding", listOf(
            "Configure Codriod AI in Add-ons with a provider key and model.",
            "Open the Codriod AI/agent panel in your project workspace.",
            "Ask for help (explain code, find issues, suggest changes) with clear context.",
            "Review responses and apply only changes you understand and approve."
        ))
    )
    cases.forEach { c -> UseCaseCard(p, c) }
}

@Composable
private fun UseCaseCard(p: HomePalette, c: UseCase) {
    SectionCard(p, c.title) {
        c.steps.forEachIndexed { i, s -> Numbered(p, i + 1, s) }
    }
}

@Composable
private fun AskChatGptCard(p: HomePalette) {
    val context = LocalContext.current
    var showDialog by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    val supportMessage = remember { buildSupportMessage(context) }

    SectionCard(p) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(Color(0xFF10A37F)), contentAlignment = Alignment.Center) {
                Text("✦", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Need Help? Ask ChatGPT", color = p.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text("If you encounter an issue, open your Codriod support conversation in ChatGPT and get help.", color = p.muted, fontSize = 12.sp, lineHeight = 17.sp)
            }
        }
        Body(p, "Opens the official ChatGPT Android app so you can continue in your existing Codriod support conversation. Codriod does not create a new conversation, use the OpenAI API, or open a browser. Private project files, source code, credentials, API keys, or personal information are never sent automatically.")
        if (status != null) Text(status!!, color = p.muted, fontSize = 12.sp)
        Button(
            onClick = { showDialog = true },
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(containerColor = p.primary, contentColor = Color.White)
        ) { Text("Ask ChatGPT", fontWeight = FontWeight.SemiBold) }
    }

    if (showDialog) {
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text("Open ChatGPT for help") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Codriod will open the official ChatGPT app if installed. You must navigate to your existing Codriod support conversation yourself. Do not create a new conversation. Nothing is sent without your review.", fontSize = 13.sp)
                    Text("Prepared support message (review/copy only — not sent automatically):", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(p.glass).border(1.dp, p.border, RoundedCornerShape(10.dp)).padding(10.dp)) {
                        Text(supportMessage, fontSize = 12.sp, color = p.text)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val result = openChatGptApp(context)
                    status = result
                    showDialog = false
                }) { Text("Open ChatGPT app") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { copyToClipboard(context, supportMessage); status = "Support message copied. Paste it in your existing Codriod support conversation in ChatGPT if you choose."; showDialog = false }) { Text("Copy message") }
                    TextButton(onClick = { showDialog = false }) { Text("Cancel") }
                }
            }
        )
    }
}

private fun buildSupportMessage(context: Context): String {
    val version = runCatching { BuildConfig.VERSION_NAME }.getOrDefault("?")
    val versionCode = runCatching { BuildConfig.VERSION_CODE }.getOrDefault(0)
    return buildString {
        appendLine("Codriod support request")
        appendLine("App version: $version ($versionCode)")
        appendLine("Screen/feature: [add screen or feature name]")
        appendLine("Issue: [describe the issue briefly]")
        appendLine("Error message: [paste error if available]")
        append("Please help with this Codriod issue in this existing support conversation.")
    }
}

private fun copyToClipboard(context: Context, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("Codriod support message", text))
}

private fun openChatGptApp(context: Context): String {
    val pkg = "com.openai.chatgpt"
    val pm = context.packageManager
    val installed = try {
        pm.getPackageInfo(pkg, 0); true
    } catch (_: PackageManager.NameNotFoundException) { false } catch (_: Exception) { false }
    if (!installed) return "Official ChatGPT app is not installed. Install ChatGPT from Play Store, then open your existing Codriod support conversation manually."
    val launch = pm.getLaunchIntentForPackage(pkg)?.apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        ?: Intent(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_LAUNCHER); setPackage(pkg); addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
    return try {
        context.startActivity(launch)
        "ChatGPT app opened. Please navigate to your existing Codriod support conversation (do not create a new one). Codriod cannot verify the conversation automatically."
    } catch (_: ActivityNotFoundException) {
        "Could not open the ChatGPT app automatically. Please open it manually and navigate to your existing Codriod support conversation."
    } catch (_: Exception) {
        "Could not open the ChatGPT app automatically. Please open it manually and navigate to your existing Codriod support conversation."
    }
}

@Composable private fun Body(p: HomePalette, text: String) { Text(text, color = p.muted, fontSize = 13.sp, lineHeight = 19.sp) }
@Composable private fun Bullet(p: HomePalette, text: String) {
    Row(verticalAlignment = Alignment.Top) {
        Text("•", color = p.primary, fontSize = 13.sp, modifier = Modifier.padding(end = 8.dp, top = 1.dp))
        Text(text, color = p.muted, fontSize = 13.sp, lineHeight = 19.sp, modifier = Modifier.weight(1f))
    }
}
@Composable private fun LabelValue(p: HomePalette, label: String, value: String) {
    Column {
        Text(label, color = p.text, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
        Text(value, color = p.muted, fontSize = 12.5.sp, lineHeight = 18.sp)
    }
}
@Composable private fun Numbered(p: HomePalette, n: Int, text: String) {
    Row(verticalAlignment = Alignment.Top) {
        Text("$n.", color = p.primary, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(22.dp))
        Text(text, color = p.muted, fontSize = 12.5.sp, lineHeight = 18.sp, modifier = Modifier.weight(1f))
    }
}

private data class WorkflowStep(val number: Int, val title: String, val what: String, val why: String, val how: String, val example: String)
private data class Tutorial(val title: String, val steps: List<String>, val note: String)
private data class UseCase(val title: String, val steps: List<String>)
