package com.spendcalci

import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.format.DateTimeFormatter

@Composable
fun UpdatesScreen() {
    val ctx = LocalContext.current
    val progress by UpdateCenter.progress.collectAsState()
    LaunchedEffect(Unit) { UpdateCenter.check() }

    LazyColumn(
        Modifier.fillMaxSize().statusBarsPadding(),
        contentPadding = PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Text("Updates", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        }
        item {
            Card2 {
                Text("Installed version", color = TextDim, style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.height(4.dp))
                Text("v${UpdateCenter.installed}", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            }
        }
        item {
            Card2 {
                UpdateStatus(ctx, progress)
            }
        }
        item {
            Card2 {
                Text("Latest push to main", color = TextDim, style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.height(6.dp))
                val p = UpdateCenter.push
                if (p == null) {
                    Text("Not available yet", color = TextDim)
                } else {
                    Text(p.message, fontWeight = FontWeight.SemiBold, maxLines = 3)
                    Text(formatPushDate(p.date), color = TextDim, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        item {
            OutlinedButton(
                onClick = { UpdateCenter.check() },
                enabled = !UpdateCenter.checking && progress == null,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (UpdateCenter.checking) "Checking…" else "Check for updates") }
        }
        item {
            Text(
                "Source: ${Updater.REPO_URL}",
                color = TextDim,
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

@Composable
private fun UpdateStatus(ctx: android.content.Context, progress: Float?) {
    val m = UpdateCenter.manifest
    val error = UpdateCenter.error
    when {
        error != null -> Text("⚠️  $error", color = Danger)
        m == null && UpdateCenter.checking -> Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(12.dp))
            Text("Checking GitHub…")
        }
        m == null -> Text(UpdateCenter.notice ?: "Tap “Check for updates” to look for a new version.", color = TextDim)
        !UpdateCenter.updateAvailable -> Text("✓  You're on the latest version", color = Positive, fontWeight = FontWeight.SemiBold)
        else -> {
            Text("Version ${m.versionName} is available", color = Amber, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            if (m.notes.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(m.notes, color = TextDim)
            }
            Spacer(Modifier.height(12.dp))
            if (progress != null) {
                Text("Downloading… ${(progress * 100).toInt()}%", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
            } else {
                Button(
                    onClick = { UpdateCenter.downloadAndInstall(ctx) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (Updater.canInstallPackages(ctx)) "Download & install" else "Allow installs, then update")
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    "Android will ask you to confirm. Your data is kept.",
                    color = TextDim,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}

private fun formatPushDate(iso: String): String = runCatching {
    DateTimeFormatter.ofPattern("d MMM yyyy, h:mm a").format(Instant.parse(iso).atZone(zone))
}.getOrDefault(iso)
