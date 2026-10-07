package com.sangar.gal.ui

import android.content.Intent
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sangar.gal.BuildConfig
import com.sangar.gal.Feature
import com.sangar.gal.R
import com.sangar.gal.container
import com.sangar.gal.data.Settings
import com.sangar.gal.data.db.ScreenSession
import com.sangar.gal.service.GalServiceStarter
import com.sangar.gal.service.LiveSession
import com.sangar.gal.sidekick.SidekickStatus
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun HomeScreen(
    onOpenSetup: () -> Unit,
    onOpenStats: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repo = context.container.settings
    val usage = context.container.usage
    val permissions = rememberPermissionState().status
    val settings by repo.settings.collectAsStateWithLifecycle(initialValue = null)
    val today by remember { usage.observeToday() }.collectAsStateWithLifecycle(initialValue = emptyList<ScreenSession>() to LiveSession())

    // Recompose once a second so the live session and today's total keep moving between service ticks.
    var clock by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1_000)
            clock++
        }
    }

    val current = settings ?: return
    val live = today.second
    val roastsReady = permissions.ready(Feature.ROASTS)
    val voiceReady = permissions.ready(Feature.SIDEKICK)
    val sidekickUp = SidekickStatus.running

    // Measuring never stops once roasts are set up, whether cards are on or off, and Sidekick should be on screen
    // whenever its switch is on. If either is missing (killed, updated, refused at boot), bring it back.
    LaunchedEffect(current.roastsActive, current.voiceActive, live.serviceRunning, sidekickUp, voiceReady) {
        val roastsDown = current.roastsActive && !live.serviceRunning
        val voiceDown = current.voiceActive && voiceReady && !sidekickUp
        if (roastsDown || voiceDown) GalServiceStarter.syncFromForeground(context, current)
    }

    ScreenScaffold(
        title = "GAL",
        leadingIcon = {
            Image(
                painter = painterResource(R.drawable.mascot_smug),
                contentDescription = null,
                modifier = Modifier.size(34.dp),
            )
        },
        actions = {
            if (BuildConfig.DEBUG) {
                // The diagnostics screen only exists in the debug source set, so it is started by name.
                TextButton(onClick = {
                    runCatching {
                        context.startActivity(Intent().setClassName(context.packageName, "com.sangar.gal.debug.DiagnosticsActivity"))
                    }
                }) { Text("NAG") }
            }
            ActionIcon(R.drawable.ic_stats, "Stats", onOpenStats)
            ActionIcon(R.drawable.ic_tune, "Settings", onOpenSettings)
        },
    ) {
        if (current.roastsActive) {
            if (!roastsReady) {
                val nagBlocking = !permissions.usageAccess || !permissions.overlay
                WarningCard(
                    message = "GAL is missing: ${permissions.missing.joinToString()}. " +
                        if (nagBlocking) "Cards are paused until it is back." else "The timer still runs, but Android may stop it sooner.",
                    actionLabel = "Fix in setup",
                    onAction = onOpenSetup,
                )
            } else if (current.enabled && (current.overlayFailed || live.nagProblem != null)) {
                WarningCard(
                    message = live.nagProblem ?: "The card could not be drawn, so cards are paused.",
                    actionLabel = "Try again",
                    onAction = { scope.launch { repo.setOverlayFailed(false) } },
                )
            }

            EnableCard(current, roastsReady) { on ->
                // The switch only turns cards on and off; the service keeps measuring either way.
                scope.launch { repo.setEnabled(on) }
            }

            CardStyleTabs(current.phrasePack) { pack ->
                context.container.appScope.launch { repo.setPhrasePack(pack) }
            }

            // App scope, so leaving the screen mid-save cannot cancel the write.
            ThresholdCard(
                storedMinutes = current.thresholdMinutes,
                onCommit = { minutes -> context.container.appScope.launch { repo.setThresholdMinutes(minutes) } },
                onSwitchOff = {
                    context.container.appScope.launch { repo.setEnabled(false) }
                },
            )

            @Suppress("UNUSED_EXPRESSION") clock
            val todayMinutes = usage.todayScreenMillis(today.first, live) / 60_000L
            val unlocks = today.first
                .filterNot { live.sessionOpen && it.startEpochMillis == live.sessionStartEpochMillis }
                .sumOf { it.unlockCount } + if (live.sessionOpen) live.unlockCount else 0
            TodayCard(todayMinutes, live, unlocks)
        }
    }
}

@Composable
private fun EnableCard(settings: Settings, allGranted: Boolean, onToggle: (Boolean) -> Unit) {
    SectionCard {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    if (settings.enabled) "Watching you" else "Off duty",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    when {
                        settings.enabled -> "Roast cards will appear after ${formatMinutes(settings.thresholdMinutes.toLong())} of continuous use."
                        allGranted -> "Roast cards are off. Screen time is still being measured."
                        else -> "Grant the missing permissions before switching on."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = settings.enabled,
                // Turning off is always allowed; turning on needs every permission.
                enabled = settings.enabled || allGranted,
                onCheckedChange = onToggle,
                modifier = Modifier
                    .scale(1.35f)
                    .semantics { contentDescription = "Roast cards on or off" },
            )
        }
    }
}

@Composable
private fun TodayCard(todayMinutes: Long, live: LiveSession, unlocks: Int) {
    SectionCard {
        Text("Today", style = MaterialTheme.typography.titleMedium)
        Text(formatMinutes(todayMinutes), style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
        val sessionLine = when {
            !live.serviceRunning -> "Not tracking right now."
            live.counting -> "Current session: ${formatMinutes(live.activeMillisNow() / 60_000L)}"
            live.sessionOpen -> "Session paused: ${formatMinutes(live.activeMillisNow() / 60_000L)} so far"
            else -> "No session running."
        }
        Text(sessionLine, style = MaterialTheme.typography.bodyLarge)
        Text(
            "$unlocks unlock${if (unlocks == 1) "" else "s"}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
