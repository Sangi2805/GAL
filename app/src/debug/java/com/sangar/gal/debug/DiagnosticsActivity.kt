package com.sangar.gal.debug

import android.app.ActivityManager
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.getSystemService
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sangar.gal.Permissions
import com.sangar.gal.container
import com.sangar.gal.overlay.TestCard
import com.sangar.gal.service.DiagnosticsState
import com.sangar.gal.service.ForegroundApp
import com.sangar.gal.service.GalService
import com.sangar.gal.ui.theme.GalTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Debug builds only. Live view of what the tracker and overlay path are really doing. */
class DiagnosticsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            GalTheme {
                Surface(Modifier.fillMaxSize()) { DiagnosticsScreen() }
            }
        }
    }
}

private data class SystemView(
    val serviceRunning: Boolean,
    val serviceForeground: Boolean,
    val servicePid: Int,
    val foregroundNow: String?,
)

@Composable
private fun DiagnosticsScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val diag by DiagnosticsState.state.collectAsStateWithLifecycle()
    val stored by context.container.settings.settings.collectAsStateWithLifecycle(initialValue = null)
    var clock by remember { mutableLongStateOf(0L) }
    var system by remember { mutableStateOf(SystemView(false, false, 0, null)) }

    LaunchedEffect(Unit) {
        while (true) {
            system = withContext(Dispatchers.Default) {
                @Suppress("DEPRECATION") // still returns this app's own services
                val record = context.getSystemService<ActivityManager>()
                    ?.getRunningServices(50)
                    ?.firstOrNull { it.service.className == GalService::class.java.name }
                SystemView(
                    serviceRunning = record != null,
                    serviceForeground = record?.foreground == true,
                    servicePid = record?.pid ?: 0,
                    foregroundNow = ForegroundApp.current(context),
                )
            }
            clock = SystemClock.elapsedRealtime()
            delay(1_000)
        }
    }

    val permissions = remember(clock) { Permissions.check(context) }
    val now = if (clock == 0L) SystemClock.elapsedRealtime() else clock
    val activeNow = if (diag.counting) diag.activeMillis + (now - diag.measuredAtElapsed).coerceAtLeast(0) else diag.activeMillis
    val exclusions = stored?.exclusions.orEmpty()

    Column(
        Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("NAG diagnostics", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text("adb logcat -s NAG", fontFamily = FontFamily.Monospace, fontSize = 13.sp)

        Section("Service") {
            Line("enabled (DataStore)", stored?.enabled?.toString() ?: "loading")
            Line("enabled (seen by service)", if (diag.settingsLoaded) diag.enabledInService.toString() else "service has not loaded settings")
            val mismatch = diag.settingsLoaded && stored != null && diag.thresholdMinutesInUse != stored?.thresholdMinutes
            Line("threshold IN USE by service", if (diag.settingsLoaded) "${diag.thresholdMinutesInUse} min" else "n/a", bad = mismatch)
            Line("threshold stored", stored?.thresholdMinutes?.let { "$it min" } ?: "loading", bad = mismatch)
            Line("service alive (its own report)", "${diag.serviceAlive} pid=${diag.pid}", bad = !diag.serviceAlive)
            Line(
                "service alive (ActivityManager)",
                "${system.serviceRunning} foreground=${system.serviceForeground} pid=${system.servicePid}",
                bad = !system.serviceRunning || !system.serviceForeground,
            )
            Line("service created", time(diag.serviceCreatedWall))
        }

        Section("Session") {
            Line("session open / counting", "${diag.sessionOpen} / ${diag.counting}")
            Line("session elapsed", "${activeNow / 1000} s")
            val nextNag = when {
                !diag.serviceAlive -> "service not running"
                !diag.sessionOpen -> "no session"
                diag.nextDueActiveMillis == null -> "not computed yet (first tick 30 s after unlock)"
                else -> {
                    val remaining = ((diag.nextDueActiveMillis ?: 0) - activeNow).coerceAtLeast(0) / 1000
                    val snooze = ((diag.snoozeUntilElapsed - now).coerceAtLeast(0)) / 1000
                    buildString {
                        append("$remaining s of session time (due at ${(diag.nextDueActiveMillis ?: 0) / 1000} s)")
                        if (!diag.counting) append(", paused while not counting")
                        if (snooze > 0) append(", snoozed $snooze s")
                        append(". Checked on 30 s ticks.")
                    }
                }
            }
            Line("next nag in", nextNag)
            Line(
                "session card cap",
                if (diag.settingsLoaded) {
                    "${diag.sessionCardsShown} of ${diag.sessionCardCap} shown, cap ${diag.sessionCardCapSetting}"
                } else {
                    "n/a"
                },
            )
            Line(
                "give_up sign-off",
                when {
                    !diag.settingsLoaded || diag.sessionCardCap == 0 -> "n/a"
                    diag.sessionCardsShown >= diag.sessionCardCap -> "already shown, session is done"
                    diag.sessionCardsShown == diag.sessionCardCap - 1 -> "next card"
                    else -> "after ${diag.sessionCardCap - 1 - diag.sessionCardsShown} more card(s)"
                },
            )
            Line("last tick", ago(diag.lastTickWall))
            Line("last SCREEN_ON", ago(diag.lastScreenOnWall))
            Line("last SCREEN_OFF", ago(diag.lastScreenOffWall))
            Line("last USER_PRESENT", ago(diag.lastUserPresentWall))
        }

        Section("Permissions") {
            Line("usage access", permissions.usageAccess.toString(), bad = !permissions.usageAccess)
            Line("draw over apps", permissions.overlay.toString(), bad = !permissions.overlay)
            Line("notifications", permissions.notifications.toString(), bad = !permissions.notifications)
            Line("battery unrestricted", permissions.batteryUnrestricted.toString())
        }

        Section("Foreground app") {
            Line("now (live query)", system.foregroundNow ?: "unknown")
            Line("now excluded", (system.foregroundNow != null && system.foregroundNow in exclusions).toString())
            Line("at last decision", diag.lastForegroundPackage ?: "none yet")
            Line("at last decision excluded", diag.lastForegroundExcluded.toString(), bad = diag.lastForegroundExcluded)
            Line("exclusion list", exclusions.sorted().joinToString().ifEmpty { "empty" })
        }

        Section("Decisions") {
            Line("last decision", "${diag.lastDecision} (${ago(diag.lastDecisionWall)})")
            Line("nag problem", diag.nagProblem ?: "none", bad = diag.nagProblem != null)
            val card = diag.lastCard
            Line(
                "last card geometry",
                card?.let { "$it (${ago(it.wallTime)})" } ?: "no card drawn yet",
                bad = card != null && (!card.onScreen || card.alpha < 0.05f || !card.shown),
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { scope.launch { TestCard.show(context, 1) } }) { Text("Test card") }
            OutlinedButton(onClick = {
                context.container.appScope.launch { context.container.settings.setThresholdMinutes(1) }
            }) { Text("Threshold 1 min") }
        }
        // The stage scenes without a voice command. Leave this screen first if a scene should run over another app.
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { StageDebug.playground(context) }) { Text("Playground") }
            OutlinedButton(onClick = { StageDebug.demo(context, "normal") }) { Text("Open") }
            OutlinedButton(onClick = { StageDebug.demo(context, "roast") }) { Text("Roast") }
            OutlinedButton(onClick = { StageDebug.demo(context, "notfound") }) { Text("?") }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        content()
    }
}

@Composable
private fun Line(label: String, value: String, bad: Boolean = false) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, modifier = Modifier.weight(0.42f), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            value,
            modifier = Modifier.weight(0.58f),
            fontSize = 13.sp,
            fontFamily = FontFamily.Monospace,
            color = if (bad) Color(0xFFD32F2F) else MaterialTheme.colorScheme.onSurface,
        )
    }
}

private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)

private fun time(wall: Long) = if (wall == 0L) "never" else timeFormat.format(Date(wall))

private fun ago(wall: Long): String {
    if (wall == 0L) return "never"
    val seconds = (System.currentTimeMillis() - wall) / 1000
    return "${time(wall)} ($seconds s ago)"
}
