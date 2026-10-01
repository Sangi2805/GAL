package com.sangar.gal.ui

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.sangar.gal.Permissions
import com.sangar.gal.container
import com.sangar.gal.data.AppRoastLimits
import com.sangar.gal.data.Settings
import com.sangar.gal.sidekick.scene.AppHabits
import com.sangar.gal.sidekick.scene.NormalOpenScene
import com.sangar.gal.sidekick.scene.RoastOpenScene
import com.sangar.gal.sidekick.scene.SceneDirector
import com.sangar.gal.sidekick.scene.SceneSounds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/** Settings for Voice Sidekick's scenes: Quick open, app roasts and their limits, sounds, vibration, a preview. */
@Composable
fun SceneSettingsCard(current: Settings) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repo = context.container.settings
    var showPicker by remember { mutableStateOf(false) }

    SectionCard {
        Text("Sidekick scenes", style = MaterialTheme.typography.titleMedium)
        Text(
            "When you ask Voice Sidekick to open an app, it runs out, knocks the app's crate open and the app " +
                "opens. Tap the screen to skip a scene.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        SwitchRow(
            title = "Quick open",
            body = "Skip the scenes and open apps straight away.",
            checked = !current.scenesEnabled,
            onChange = { quick -> scope.launch { repo.setScenesEnabled(!quick) } },
        )
        HorizontalDivider()

        SwitchRow(
            title = "Roast my most used social apps",
            body = "Ask for a social app you use a lot and Sidekick says something cheeky first, then smashes it " +
                "open with a hammer. At most once every 2 hours for each app, and 5 times a day.",
            checked = current.appRoastsEnabled,
            onChange = { on -> scope.launch { repo.setAppRoastsEnabled(on) } },
        )
        if (current.appRoastsEnabled) {
            if (!current.roastsActive) {
                Text(
                    "Screen Time Roasts is off, so Sidekick cannot see how much you use an app. Every app opens " +
                        "plainly until you switch it on in setup.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Text(
                "A lot means any one of these:",
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
            )
            Stepper(
                label = "Opened at least this many times today",
                value = "${current.roastMinOpens}",
                canLower = current.roastMinOpens > AppRoastLimits.OPENS.first,
                canRaise = current.roastMinOpens < AppRoastLimits.OPENS.last,
                onLower = { scope.launch { repo.setRoastMinOpens(current.roastMinOpens - AppRoastLimits.OPENS_STEP) } },
                onRaise = { scope.launch { repo.setRoastMinOpens(current.roastMinOpens + AppRoastLimits.OPENS_STEP) } },
            )
            Stepper(
                label = "Or more than this many minutes today",
                value = "${current.roastMinMinutes} min",
                canLower = current.roastMinMinutes > AppRoastLimits.MINUTES.first,
                canRaise = current.roastMinMinutes < AppRoastLimits.MINUTES.last,
                onLower = { scope.launch { repo.setRoastMinMinutes(current.roastMinMinutes - AppRoastLimits.MINUTES_STEP) } },
                onRaise = { scope.launch { repo.setRoastMinMinutes(current.roastMinMinutes + AppRoastLimits.MINUTES_STEP) } },
            )
            Stepper(
                label = "Or more than this many minutes a day, on average over a week",
                value = "${current.roastMinAverage} min",
                canLower = current.roastMinAverage > AppRoastLimits.AVERAGE.first,
                canRaise = current.roastMinAverage < AppRoastLimits.AVERAGE.last,
                onLower = { scope.launch { repo.setRoastMinAverage(current.roastMinAverage - AppRoastLimits.AVERAGE_STEP) } },
                onRaise = { scope.launch { repo.setRoastMinAverage(current.roastMinAverage + AppRoastLimits.AVERAGE_STEP) } },
            )
            Text(
                "Messaging apps are left out unless you add them below. Apps on your \"Stay quiet\" list are never roasted.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text("Also count these as social", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(
                "Social apps are found by the category they declare and a list GAL knows. Add any it misses.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val extras = current.extraSocialApps
            val labelled by produceState(initialValue = extras.map { it to it }, extras) {
                value = withContext(Dispatchers.IO) {
                    extras.map { it to AppInfo.label(context, it) }.sortedBy { it.second.lowercase() }
                }
            }
            labelled.forEach { (pkg, label) ->
                RemovableAppRow(pkg, label) { scope.launch { repo.setExtraSocialApps(extras - pkg) } }
            }
            OutlinedButton(onClick = { showPicker = true }) { Text("Add app") }
        }
        HorizontalDivider()

        SwitchRow(
            title = "Sound effects",
            body = "Short sounds for jumps, bumps and hammer hits. Silent and vibrate mode keep them quiet.",
            checked = current.sceneSounds,
            onChange = { on -> scope.launch { repo.setSceneSounds(on) } },
        )
        SwitchRow(
            title = "Vibration",
            body = "A tap of vibration on landings and hammer hits, when the phone's touch vibration is on.",
            checked = current.sceneHaptics,
            onChange = { on -> scope.launch { repo.setSceneHaptics(on) } },
        )
        HorizontalDivider()

        Text("Try it", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = { preview(context, current, roastLine = null) }) { Text("Plain open") }
            OutlinedButton(onClick = {
                scope.launch {
                    val line = context.container.phrases.appRoast("GAL", 2)
                    preview(context, current, roastLine = line)
                }
            }) { Text("Roast") }
        }
        Text(
            "Uses GAL's own icon and opens nothing.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    if (showPicker) {
        AppPickerDialog(
            title = "Count as social",
            excluded = current.extraSocialApps,
            onPick = { pkg ->
                scope.launch { repo.setExtraSocialApps(current.extraSocialApps + pkg) }
                showPicker = false
            },
            onDismiss = { showPicker = false },
        )
    }
}

@Composable
private fun SwitchRow(title: String, body: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            modifier = Modifier.semantics { contentDescription = "$title on or off" },
        )
    }
}

@Composable
private fun Stepper(
    label: String,
    value: String,
    canLower: Boolean,
    canRaise: Boolean,
    onLower: () -> Unit,
    onRaise: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        OutlinedButton(
            onClick = onLower,
            enabled = canLower,
            contentPadding = PaddingValues(0.dp),
            modifier = Modifier
                .size(40.dp)
                .semantics { contentDescription = "Lower: $label" },
        ) { Text("−") }
        Text(
            value,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(min = 56.dp),
        )
        OutlinedButton(
            onClick = onRaise,
            enabled = canRaise,
            contentPadding = PaddingValues(0.dp),
            modifier = Modifier
                .size(40.dp)
                .semantics { contentDescription = "Raise: $label" },
        ) { Text("+") }
    }
}

/** Plays a scene over the settings screen with GAL's own icon. Nothing opens. */
private fun preview(context: Context, current: Settings, roastLine: String?) {
    val app = context.applicationContext
    when {
        !Permissions.canDrawOverlays(app) -> {
            Toast.makeText(app, "Scenes need \"Display over other apps\". Switch it on in setup.", Toast.LENGTH_LONG).show()
            return
        }
        SceneDirector.animationsOff(app) -> {
            Toast.makeText(app, "Animations are off on this phone, so Sidekick skips the scenes.", Toast.LENGTH_LONG).show()
            return
        }
    }
    val icon = runCatching {
        val px = (96 * app.resources.displayMetrics.density).roundToInt()
        app.packageManager.getApplicationIcon(app.packageName).toBitmap(px, px)
    }.getOrNull()
    // With Voice Sidekick running, its own sounds are already set up. Without it, borrow some for this one scene.
    val borrowed = if (current.sceneSounds && SceneDirector.sounds == null) SceneSounds(app) else null
    borrowed?.let { SceneDirector.sounds = it }
    SceneDirector.hapticsEnabled = current.sceneHaptics
    val started = SceneDirector.play(
        app,
        icon = icon,
        makeScene = { entry ->
            if (roastLine != null) RoastOpenScene(entry, roastLine, AppHabits.moodFor(2)) else NormalOpenScene(entry)
        },
        callbacks = SceneDirector.Callbacks(
            onLaunch = { Toast.makeText(app, "This is where the app opens", Toast.LENGTH_SHORT).show() },
            onDone = {
                if (borrowed != null) {
                    if (SceneDirector.sounds === borrowed) SceneDirector.sounds = null
                    borrowed.release()
                }
            },
        ),
    )
    if (!started) {
        if (borrowed != null) {
            if (SceneDirector.sounds === borrowed) SceneDirector.sounds = null
            borrowed.release()
        }
        Toast.makeText(app, "Could not show the scene. Is \"Display over other apps\" on?", Toast.LENGTH_LONG).show()
    }
}
