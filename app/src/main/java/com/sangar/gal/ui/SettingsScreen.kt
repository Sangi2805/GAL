package com.sangar.gal.ui

import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sangar.gal.R
import com.sangar.gal.container
import com.sangar.gal.data.SessionCardCap
import com.sangar.gal.overlay.DefaultExclusions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun SettingsScreen(onBack: () -> Unit, onOpenPrivacy: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val container = context.container
    val settings by container.settings.settings.collectAsStateWithLifecycle(initialValue = null)
    var showPicker by remember { mutableStateOf(false) }
    var confirmWipe by remember { mutableStateOf(false) }
    val current = settings ?: return

    ScreenScaffold(title = "Settings", onBack = onBack) {
        SectionCard {
            Text("Stay quiet in these apps", style = MaterialTheme.typography.titleMedium)
            Text(
                "No cards while one of these is on screen. Calls are always quiet, whatever the app.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (current.exclusions.isEmpty()) {
                Text("Nothing excluded.", style = MaterialTheme.typography.bodyLarge)
            }
            val labelled by produceState(initialValue = current.exclusions.map { it to it }, current.exclusions) {
                value = withContext(Dispatchers.IO) {
                    current.exclusions.map { it to AppInfo.label(context, it) }.sortedBy { it.second.lowercase() }
                }
            }
            labelled.forEach { (pkg, label) ->
                RemovableAppRow(pkg, label) {
                    scope.launch { container.settings.setExclusions(current.exclusions - pkg) }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = { showPicker = true }) { Text("Add app") }
                OutlinedButton(onClick = {
                    scope.launch {
                        val defaults = withContext(Dispatchers.Default) { DefaultExclusions.resolve(context) }
                        container.settings.setExclusions(current.exclusions + defaults)
                        Toast.makeText(context, "Default apps added back", Toast.LENGTH_SHORT).show()
                    }
                }) { Text("Restore defaults") }
            }
        }

        // Debug builds only: a manual overlay trigger. The release source set has an empty one.
        TestPopupCard()

        SectionCard {
            Text("How the cards work", style = MaterialTheme.typography.titleMedium)
            Text(
                SessionCardCap.describe(current.thresholdMinutes) + " of continuous use.",
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Bold,
            )
            Text(
                "A card arrives each time another \"nag me after\" stretch of continuous use goes by. " +
                    "Card ${SessionCardCap.PER_SESSION} is the last one: GAL gives up on you and stays quiet " +
                    "until your next session. A session ends once the screen has been off for more than a minute.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        SectionCard {
            Text("The elephant", style = MaterialTheme.typography.titleMedium)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Show her on screen", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text(
                        "She walks around over your apps, says no to social media, cheers for useful apps and " +
                            "gets cross when you stay on too long.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = current.voiceEnabled,
                    onCheckedChange = { on ->
                        scope.launch {
                            container.settings.setVoiceEnabled(on)
                            com.sangar.gal.service.GalServiceStarter.syncFromForeground(context, container.settings.current())
                        }
                    },
                    modifier = Modifier.semantics { contentDescription = "Show the elephant on or off" },
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Let her wander", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text(
                        "Every few seconds she strolls, hops, looks around or naps. Off keeps her still.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = current.sidekickWanders,
                    onCheckedChange = { on -> scope.launch { container.settings.setSidekickWanders(on) } },
                    modifier = Modifier.semantics { contentDescription = "Let her wander on or off" },
                )
            }
        }

        SectionCard {
            Text("Privacy", style = MaterialTheme.typography.titleMedium)
            Text(
                "What this app does with your data. Shown once when you first open the app, and kept here " +
                    "so it stays easy to find.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(onClick = onOpenPrivacy) { Text("Read it again") }
        }

        SectionCard {
            Text("Data", style = MaterialTheme.typography.titleMedium)
            Text(
                "Everything stays on this phone. Wiping removes all sessions, daily history, stats and the " +
                    "list of recently shown lines. Your settings and exclusions stay.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(
                onClick = { confirmWipe = true },
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                ),
            ) { Text("Wipe all data") }
        }
        Spacer(Modifier.height(12.dp))
    }

    if (showPicker) {
        AppPickerDialog(
            title = "Stay quiet in…",
            excluded = current.exclusions,
            onPick = { pkg ->
                scope.launch { container.settings.setExclusions(current.exclusions + pkg) }
                showPicker = false
            },
            onDismiss = { showPicker = false },
        )
    }

    if (confirmWipe) {
        AlertDialog(
            onDismissRequest = { confirmWipe = false },
            title = { Text("Wipe all data?") },
            text = { Text("This deletes your whole screen time history and cannot be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmWipe = false
                    container.appScope.launch {
                        container.usage.wipeAll()
                        container.phrases.resetRecent()
                        withContext(Dispatchers.Main) {
                            Toast.makeText(context, "All data wiped", Toast.LENGTH_SHORT).show()
                        }
                    }
                }) { Text("Wipe", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold) }
            },
            dismissButton = { TextButton(onClick = { confirmWipe = false }) { Text("Cancel") } },
        )
    }
}

/** An app with its icon, label and a remove button. */
@Composable
internal fun RemovableAppRow(packageName: String, label: String, onRemove: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        AppIcon(packageName)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (label != packageName) {
                Text(
                    packageName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        ActionIcon(R.drawable.ic_close, "Remove $label", onRemove)
    }
}

@Composable
private fun AppIcon(packageName: String) {
    val context = LocalContext.current
    val sizePx = with(LocalDensity.current) { 36.dp.roundToPx() }
    val icon by produceState(initialValue = null as androidx.compose.ui.graphics.ImageBitmap?, packageName) {
        value = withContext(Dispatchers.IO) { AppInfo.icon(context, packageName, sizePx) }
    }
    Box(Modifier.size(36.dp)) {
        icon?.let { Image(it, contentDescription = null, modifier = Modifier.size(36.dp)) }
    }
}

/** Every launchable app except [excluded], with a search box. */
@Composable
internal fun AppPickerDialog(title: String, excluded: Set<String>, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var query by remember { mutableStateOf("") }
    val apps by produceState(initialValue = emptyList<InstalledApp>()) {
        value = withContext(Dispatchers.IO) { AppInfo.launchable(context) }
    }
    val visible = apps.filter {
        it.packageName !in excluded &&
            (query.isBlank() || it.label.contains(query, ignoreCase = true) || it.packageName.contains(query, ignoreCase = true))
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    label = { Text("Search apps") },
                    modifier = Modifier.fillMaxWidth(),
                )
                if (apps.isEmpty()) Text("Loading apps…")
                LazyColumn(Modifier.height(360.dp)) {
                    items(visible, key = { it.packageName }) { app ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { onPick(app.packageName) }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            AppIcon(app.packageName)
                            Spacer(Modifier.width(12.dp))
                            Text(app.label, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}
