package com.sangar.gal.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sangar.gal.HealthCheck
import com.sangar.gal.Permissions
import com.sangar.gal.data.Settings
import com.sangar.gal.overlay.DemoCard
import com.sangar.gal.overlay.ShowResult
import com.sangar.gal.service.GalServiceStarter
import com.sangar.gal.service.LiveTracker
import com.sangar.gal.sidekick.SidekickDemo
import com.sangar.gal.sidekick.SidekickStatus
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * "Check GAL": a self-test anyone can read. Each thing GAL needs, ticked or not, with a fix, and buttons that
 * show a real check-in card and the elephant's "Nooo" right now.
 */
@Composable
fun CheckGalCard(settings: Settings) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val permissions = rememberPermissionState().status
    val live by LiveTracker.state.collectAsStateWithLifecycle()
    val items = HealthCheck.items(settings, permissions, live.serviceRunning, SidekickStatus.running)
    var result by remember { mutableStateOf<String?>(null) }

    SectionCard {
        Text("Check GAL", style = MaterialTheme.typography.titleMedium)
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items.forEach { item ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (item.ok) "✓" else "✗",
                        color = if (item.ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(end = 10.dp),
                    )
                    Text(item.label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    if (!item.ok) {
                        OutlinedButton(onClick = {
                            when (item.fix) {
                                "Usage access" -> Permissions.openUsageAccessSettings(context)
                                "Display over other apps" -> Permissions.openOverlaySettings(context)
                                "Notifications" -> Permissions.openNotificationSettings(context)
                                "Battery" -> Permissions.requestBatteryExemption(context)
                                else -> GalServiceStarter.syncFromForeground(context, settings)
                            }
                        }) { Text("Fix") }
                    }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                scope.launch {
                    result = when (DemoCard.show(context, label = "GAL · test check-in")) {
                        ShowResult.SHOWN -> "That is what a check-in looks like."
                        ShowResult.NO_PERMISSION -> "GAL cannot appear over other apps yet. Fix that above."
                        ShowResult.FAILED -> "The card could not be drawn. Try again, or restart the phone."
                    }
                }
            }) { Text("Show a check-in") }
            OutlinedButton(
                enabled = SidekickStatus.running,
                onClick = {
                    SidekickDemo.sayNo()
                    result = "Watch the elephant."
                },
            ) { Text("Show her \"Nooo\"") }
        }
        result?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Right after setup: the elephant shows her "Nooo", then a sample check-in, so the idea lands in seconds. */
suspend fun playFirstRunDemo(context: android.content.Context) {
    // Give the service a moment to put her on screen.
    delay(2_500)
    if (SidekickStatus.running) {
        SidekickDemo.sayNo()
        delay(6_000)
    }
    DemoCard.show(context, label = "GAL · this is a check-in")
}
