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
import com.sangar.gal.Health
import com.sangar.gal.HealthCheck
import com.sangar.gal.service.GalServiceStarter
import com.sangar.gal.service.LiveSession
import com.sangar.gal.sidekick.SidekickStatus
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
    // The day rolls over at midnight, and the query follows it.
    val day by rememberToday(usage::today)
    val today by remember(day) { usage.observeToday(day) }
        .collectAsStateWithLifecycle(initialValue = emptyList<ScreenSession>() to LiveSession())

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
        // One word for how GAL is doing, with the one tap that fixes it.
        val health = HealthCheck.health(current, permissions, live.serviceRunning, sidekickUp)
        HealthCard(
            health = health,
            onFix = when (health) {
                Health.NEEDS_PERMISSION -> onOpenSetup
                Health.RESTRICTED -> {
                    { GalServiceStarter.syncFromForeground(context, current) }
                }
                else -> null
            },
        )

        HiddenElephantSlot(current) {
            scope.launch {
                repo.setElephantHiddenUntil(0L)
                GalServiceStarter.syncFromForeground(context, repo.current())
            }
        }

        if (current.roastsActive) {
            if (roastsReady && current.enabled && (current.overlayFailed || live.nagProblem != null)) {
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

            ToneTabs(current.tone, onSelect = { tone ->
                context.container.appScope.launch { repo.setTone(tone) }
            })

            // App scope, so leaving the screen mid-save cannot cancel the write.
            ThresholdCard(
                storedMinutes = current.thresholdMinutes,
                onCommit = { minutes -> context.container.appScope.launch { repo.setThresholdMinutes(minutes) } },
                onSwitchOff = {
                    context.container.appScope.launch { repo.setEnabled(false) }
                },
            )

            TodayTotals(today.first, live, day, usage)
        }
    }
}

/**
 * Today's minutes and unlocks. The only part of home that moves with the clock, so it holds the ticker itself
 * (every 15 s, minutes are all it shows) and the rest of the screen does not recompose with it.
 */
@Composable
private fun TodayTotals(
    sessions: List<ScreenSession>,
    live: LiveSession,
    day: java.time.LocalDate,
    usage: com.sangar.gal.data.UsageRepository,
) {
    val tick by rememberTicker(15_000L)
    val todayMinutes = remember(sessions, live, day, tick) { usage.todayScreenMillis(sessions, live, day) / 60_000L }
    val unlocks = remember(sessions, live) {
        sessions.filterNot { live.sessionOpen && it.startEpochMillis == live.sessionStartEpochMillis }
            .sumOf { it.unlockCount } + if (live.sessionOpen) live.unlockCount else 0
    }
    TodayCard(todayMinutes, live, unlocks, tick)
}

/** GAL's state in one line: Active, Paused, Needs a permission, or Stopped by the phone. */
@Composable
private fun HealthCard(health: Health, onFix: (() -> Unit)?) {
    val (label, color) = when (health) {
        Health.ACTIVE -> "Active" to MaterialTheme.colorScheme.primary
        Health.PAUSED -> "Paused" to MaterialTheme.colorScheme.onSurfaceVariant
        Health.NEEDS_PERMISSION -> "Needs a permission" to MaterialTheme.colorScheme.error
        Health.RESTRICTED -> "Stopped by your phone" to MaterialTheme.colorScheme.error
    }
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Image(
                painterResource(if (health == Health.ACTIVE) R.drawable.mascot_smug else R.drawable.mascot_disappointed),
                contentDescription = null,
                modifier = Modifier.size(48.dp),
            )
            Column(Modifier.weight(1f).padding(start = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = color)
                Text(
                    if (health == Health.RESTRICTED) "I stepped out for a moment. Tap to bring me back." else HealthCheck.headline(health),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (onFix != null) {
            Button(onClick = onFix, modifier = Modifier.align(Alignment.End)) {
                Text(if (health == Health.NEEDS_PERMISSION) "Fix it" else "Bring her back")
            }
        }
    }
}

/** Shows [HiddenElephantCard] while she is hidden, and drops it by itself when her time is up. */
@Composable
private fun HiddenElephantSlot(settings: Settings, onShowNow: () -> Unit) {
    val tick by rememberTicker(15_000L)
    val hidden = remember(settings, tick) { settings.elephantHidden() }
    if (hidden) HiddenElephantCard(settings.elephantHiddenUntil, onShowNow)
}

/** She was dropped on the X: when she comes back, and a button to bring her back now. */
@Composable
private fun HiddenElephantCard(hiddenUntil: Long, onShowNow: () -> Unit) {
    val context = LocalContext.current
    val time = android.text.format.DateFormat.getTimeFormat(context).format(java.util.Date(hiddenUntil))
    val tomorrow = java.time.Instant.ofEpochMilli(hiddenUntil).atZone(java.time.ZoneId.systemDefault()).toLocalDate() !=
        java.time.LocalDate.now()
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Image(painterResource(R.drawable.mascot_bored), contentDescription = null, modifier = Modifier.size(52.dp))
            Column(Modifier.weight(1f).padding(start = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("The elephant is hiding", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(
                    "She comes back ${if (tomorrow) "tomorrow at" else "at"} $time.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Button(onClick = onShowNow, modifier = Modifier.align(Alignment.End)) { Text("Show her now") }
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
private fun TodayCard(
    todayMinutes: Long,
    live: LiveSession,
    unlocks: Int,
    // Not read: a new value is what makes the card redraw its "current session" line.
    @Suppress("UNUSED_PARAMETER") tick: Long,
) {
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
