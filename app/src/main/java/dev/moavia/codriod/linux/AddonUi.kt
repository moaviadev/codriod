package dev.moavia.codriod.linux

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val Green = Color(0xFF22C55E)
private val Red = Color(0xFFEF4444)
private val Amber = Color(0xFFF59E0B)

/** Card, progress, steps, problem and log UI shared by the Java & Kotlin and More extensions screens
 *  (same look as the Linux & AI CLIs screen). */

@Composable
fun AddonCard(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
        .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f), RoundedCornerShape(14.dp)).padding(14.dp), content = content)
}

@Composable
fun AddonSection(text: String) {
    Text(text, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp, start = 2.dp))
}

@Composable
fun AddonLogoBox(letter: String, color: Color) {
    Box(Modifier.size(38.dp).clip(RoundedCornerShape(10.dp)).background(color), contentAlignment = Alignment.Center) {
        Text(letter, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 17.sp)
    }
}

/** Whole-run progress: percent, time left, time so far and the usual total. */
@Composable
fun AddonProgress(installer: AddonInstaller) {
    Column(Modifier.padding(top = 12.dp)) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text("${(installer.overall * 100).toInt()}%", fontSize = 26.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f).padding(bottom = 3.dp)) {
                Text(installer.timeLeft.replaceFirstChar { it.uppercase() }, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
                Text("Running ${installer.elapsed} · usually ${installer.usualTime}", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        LinearProgressIndicator(progress = { installer.overall }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp).height(7.dp).clip(RoundedCornerShape(4.dp)))
        Row(Modifier.padding(top = 10.dp).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)).padding(10.dp)) {
            Text("ℹ", color = MaterialTheme.colorScheme.primary)
            Text("  It continues in the background if you switch apps, and the notification shows the time left. Keep Codriod open while it installs.",
                style = MaterialTheme.typography.labelSmall)
        }
        OutlinedButton(onClick = installer::cancel, modifier = Modifier.padding(top = 8.dp)) { Text("Stop") }
    }
}

@Composable
fun AddonStepsCard(installer: AddonInstaller, showLog: Boolean, onToggleLog: () -> Unit) {
    AddonCard {
        installer.steps.forEach { step ->
            val fixing = step.status == StepStatus.Fixing
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(9.dp)).background(if (fixing) Amber.copy(alpha = 0.12f) else Color.Transparent)
                .padding(vertical = 4.dp, horizontal = if (fixing) 4.dp else 0.dp), verticalAlignment = Alignment.Top) {
                Box(Modifier.width(24.dp), contentAlignment = Alignment.Center) {
                    when (step.status) {
                        StepStatus.Running, StepStatus.Fixing -> CircularProgressIndicator(Modifier.size(15.dp), strokeWidth = 2.dp,
                            color = if (step.status == StepStatus.Fixing) Amber else MaterialTheme.colorScheme.primary)
                        StepStatus.Done -> Text("✓", color = Green, fontWeight = FontWeight.Bold)
                        StepStatus.Skipped -> Text("✓", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        StepStatus.Failed -> Text("✗", color = Red, fontWeight = FontWeight.Bold)
                        StepStatus.Waiting -> Text("○", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Column(Modifier.weight(1f).padding(start = 6.dp)) {
                    Text(step.title, style = MaterialTheme.typography.bodyMedium,
                        color = if (step.status == StepStatus.Waiting) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
                    if (step.detail.isNotBlank()) Text((if (step.status == StepStatus.Fixing) "🩺 " else "") + step.detail,
                        style = MaterialTheme.typography.labelSmall, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        color = if (step.status == StepStatus.Failed) Red else if (step.status == StepStatus.Fixing) Amber
                        else MaterialTheme.colorScheme.onSurfaceVariant)
                    if (step.status == StepStatus.Running || step.status == StepStatus.Fixing)
                        Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            LinearProgressIndicator(progress = { step.progress }, modifier = Modifier.weight(1f).height(4.dp).clip(RoundedCornerShape(2.dp)),
                                color = if (step.status == StepStatus.Fixing) Amber else MaterialTheme.colorScheme.primary)
                            Text("  ${(step.progress * 100).toInt()}%", style = MaterialTheme.typography.labelSmall)
                        }
                }
            }
        }
        TextButton(onClick = onToggleLog, contentPadding = PaddingValues(0.dp)) { Text(if (showLog) "Hide details" else "Show details (log)") }
        if (showLog) {
            val state = rememberLazyListState()
            LaunchedEffect(installer.log.size) { if (installer.log.isNotEmpty()) state.scrollToItem(installer.log.lastIndex) }
            SelectionContainer {
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 260.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xFF111114)).padding(8.dp), state = state) {
                    items(installer.log.toList()) { line ->
                        Text(line, fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = when {
                            line.startsWith("✗") -> Red; line.startsWith("🩺") -> Amber
                            line.startsWith("✓") || line.startsWith("▶") -> Green
                            else -> Color(0xFFD4D4D8)
                        }, softWrap = true)
                    }
                }
            }
        }
    }
}

@Composable
fun AddonProblemCard(installer: AddonInstaller, what: String, onRetry: () -> Unit, onAskAgent: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Red.copy(alpha = 0.10f))
        .border(1.dp, Red.copy(alpha = 0.4f), RoundedCornerShape(14.dp)).padding(14.dp)) {
        Text("Installation failed", fontWeight = FontWeight.SemiBold, color = Red)
        Text(installer.problem.orEmpty(), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
        Row(Modifier.padding(top = 10.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onRetry, enabled = !installer.busy) { Text("Retry") }
            OutlinedButton(onClick = onAskAgent) { Text("✦ Ask Codriod AI") }
            TextButton(onClick = { clipboard.setText(AnnotatedString(installer.logText())) }) { Text("Copy log") }
        }
        Text(what, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
    }
}

@Composable
fun AddonBusyRow() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
        Text("  Working…", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
fun AddonDot(ok: Boolean) {
    Box(Modifier.size(8.dp).clip(CircleShape).background(if (ok) Green else Red))
}
