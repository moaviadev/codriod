package dev.moavia.codriod.linux

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.moavia.codriod.workspace.ToolIcon
import dev.moavia.codriod.workspace.WorkspaceIconButton

private val Green = Color(0xFF22C55E)
private val Red = Color(0xFFEF4444)
private val Amber = Color(0xFFF59E0B)

/** Add-ons \u2192 Android Development: Android SDK with ARM build tools, and wireless debugging (adb). */
@Composable
fun AndroidDevScreen(
    linux: LinuxEnvironment,
    onBack: () -> Unit,
    onOpenLinux: () -> Unit,
    onOpenTerminal: () -> Unit
) {
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Row(Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            WorkspaceIconButton(ToolIcon.Back, "Back", onBack)
            Column(Modifier.weight(1f).padding(start = 4.dp)) {
                Text("ANDROID DEVELOPMENT", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("SDK, build tools and adb", style = MaterialTheme.typography.titleMedium, maxLines = 1)
            }
        }
        if (!linux.ready) {
            Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Ubuntu is required", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                Text("Install Ubuntu first in Linux & AI CLIs, then come back here.", color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp))
                Button(onClick = onOpenLinux, modifier = Modifier.padding(top = 16.dp), shape = RoundedCornerShape(12.dp)) { Text("Open Linux & AI CLIs") }
            }
            return@Column
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 14.dp, end = 14.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item { Section("ANDROID SDK") }
            item { AndroidSdkCard(linux) }
            item { Section("WIRELESS DEBUGGING") }
            item { WirelessDebuggingCard(linux) }
            item {
                Text("The Android SDK lets you build APKs on this phone with Gradle. " +
                    "Wireless debugging (adb) lets AI agents install apps silently and read crash logs.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp))
            }
        }
    }
}

@Composable
private fun Section(text: String) {
    Text(text, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp, start = 2.dp))
}

@Composable
private fun Card(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
        .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f), RoundedCornerShape(14.dp)).padding(14.dp), content = content)
}

/** JDK 17 + Android SDK + ARM build tools, with the license the user must accept. */
@Composable
private fun AndroidSdkCard(linux: LinuxEnvironment) {
    var accepted by remember { mutableStateOf(false) }
    val context = androidx.compose.ui.platform.LocalContext.current
    Card {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(38.dp).clip(RoundedCornerShape(10.dp)).background(Color(0xFF3DDC84)), contentAlignment = Alignment.Center) {
                Text("A", color = Color(0xFF073042), fontWeight = FontWeight.Bold, fontSize = 18.sp)
            }
            Column(Modifier.weight(1f).padding(start = 11.dp)) {
                Text("Android SDK", fontWeight = FontWeight.SemiBold)
                Text(linux.androidSdk?.let { "Installed \u00B7 $it \u00B7 build-tools ${AndroidDev.BUILD_TOOLS} \u00B7 JDK 17" } ?: "Not installed \u00B7 about 1.5 GB",
                    style = MaterialTheme.typography.labelSmall, color = if (linux.androidSdk != null) Green else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Text("Build Android apps here with Gradle. On ARM phones Codriod adds ARM builds of aapt2 and the other build tools " +
            "(Google ships them only for x86 computers; the ARM builds come from github.com/${AndroidDev.ARM_TOOLS_REPO}).",
            style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
        if (linux.androidSdk == null) {
            Row(Modifier.padding(top = 8.dp).clickable { accepted = !accepted }, verticalAlignment = Alignment.CenterVertically) {
                Checkbox(accepted, { accepted = it })
                Text("I accept the Android SDK License Agreement", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            }
            Text("Read it \u2197", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(start = 12.dp).clickable {
                    runCatching { context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(AndroidDev.LICENSE_URL))) }
                })
        }
        Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { linux.installAndroidSdk() }, enabled = !linux.busy && (accepted || linux.androidSdk != null), shape = RoundedCornerShape(10.dp)) {
                Text(if (linux.androidSdk == null) "Install Android SDK" else "Reinstall")
            }
        }
        if (linux.androidSdk != null) Text("In a project: android-build --install (build and install) \u00B7 codriod install app.apk \u00B7 " +
            "or just ask Codex / Copilot / Codriod AI to build the app.", style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
    }
}

/** Wireless debugging: adb inside Ubuntu talks to this phone, for silent installs and crash logs. */
@Composable
private fun WirelessDebuggingCard(linux: LinuxEnvironment) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var host by remember { mutableStateOf("127.0.0.1") }
    var pairPort by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("") }
    var open by remember { mutableStateOf(false) }
    Card {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { open = !open }) {
            Box(Modifier.size(38.dp).clip(RoundedCornerShape(10.dp)).background(Color(0xFF2563EB)), contentAlignment = Alignment.Center) {
                Text("\uD83D\uDCF6", fontSize = 18.sp)
            }
            Column(Modifier.weight(1f).padding(start = 11.dp)) {
                Text("Wireless debugging (adb)", fontWeight = FontWeight.SemiBold)
                Text(linux.adbStatus, style = MaterialTheme.typography.labelSmall, color = if (linux.adbConnected) Green else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (linux.adbBusy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            else Text(if (open) "\u2303" else "\u2304", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text("Optional. Lets AI agents install without a dialog every time, and read crash logs (android-logs) so they can fix the app themselves.",
            style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
        if (open || !linux.adbConnected) {
            if (linux.androidSdk == null) Text("Install the Android SDK first (it includes adb).", color = Amber, style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(top = 6.dp))
            Text("1. Turn on Developer options (Settings \u2192 About phone \u2192 tap Build number 7 times), then Wireless debugging. Wi-Fi must be on.",
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
            OutlinedButton(onClick = {
                runCatching { context.startActivity(android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }, modifier = Modifier.padding(top = 4.dp)) { Text("Open Developer options") }
            Text("2. Tap \u201cPair device with pairing code\u201d and type the port and the 6-digit code here (use split screen or a floating window).",
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(pairPort, { pairPort = it.filter(Char::isDigit).take(5) }, label = { Text("Port") }, singleLine = true, modifier = Modifier.weight(1f))
                OutlinedTextField(code, { code = it.filter(Char::isDigit).take(6) }, label = { Text("Code") }, singleLine = true, modifier = Modifier.weight(1f))
                Button(onClick = { linux.adbPair(host, pairPort, code) }, enabled = pairPort.isNotBlank() && code.length == 6 && !linux.adbBusy) { Text("Pair") }
            }
            Text("3. Connect. Codriod finds the port by itself, or type the one under \u201cIP address & Port\u201d.",
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(port, { port = it.filter(Char::isDigit).take(5) }, label = { Text("Port (optional)") }, singleLine = true, modifier = Modifier.weight(1f))
                Button(onClick = { linux.adbConnect(host, port) }, enabled = !linux.adbBusy) { Text("Connect") }
            }
            TextButton(onClick = { linux.adbRefresh() }) { Text("Check connection") }
        }
    }
}
