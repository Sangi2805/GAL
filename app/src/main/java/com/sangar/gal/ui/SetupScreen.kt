package com.sangar.gal.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sangar.gal.Feature
import com.sangar.gal.Need
import com.sangar.gal.Permissions
import com.sangar.gal.R
import com.sangar.gal.container
import com.sangar.gal.service.GalServiceStarter
import kotlinx.coroutines.launch

/** Re-reads every permission each time the host resumes, which is the only signal for the Settings-based ones. */
class PermissionState(private val context: Context) {
    var status by mutableStateOf(Permissions.check(context))
        private set

    fun refresh() {
        status = Permissions.check(context)
    }
}

@Composable
fun rememberPermissionState(): PermissionState {
    val context = LocalContext.current
    val state = remember { PermissionState(context.applicationContext) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { state.refresh() }
    return state
}

/**
 * The one setup screen: a switch for Screen Time Roasts and a switch for Floating Sidekick. Nothing is asked for up
 * front. Turning a switch on walks through that feature's missing permissions one at a time; backing out of one
 * ends the walk, and the rows under the switch stay there to finish it by hand.
 */
@Composable
fun SetupScreen(onDone: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repo = context.container.settings
    val stored by repo.settings.collectAsStateWithLifecycle(initialValue = null)
    val permissions = rememberPermissionState()
    val walk = remember { PermissionWalk() }

    // Runtime dialogs report here; Settings pages report by the activity resuming.
    val runtimeLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        permissions.refresh()
        walk.returned(fromRuntimeDialog = true, permissions)
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        permissions.refresh()
        walk.returned(fromRuntimeDialog = false, permissions)
    }

    val settings = stored ?: return
    val live = permissions.status

    walk.ask = { need ->
        val activity = context.findActivity()
        when (need) {
            Need.USAGE_ACCESS -> Permissions.openUsageAccessSettings(context).let { false }
            Need.OVERLAY -> Permissions.openOverlaySettings(context).let { false }
            Need.BATTERY -> Permissions.requestBatteryExemption(context).let { false }
            Need.NOTIFICATIONS -> {
                val canAskInline = Permissions.needsRuntimeNotificationPermission(context) && activity != null &&
                    (!settings.notificationPermissionRequested ||
                        ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.POST_NOTIFICATIONS))
                if (canAskInline) {
                    scope.launch { repo.markNotificationPermissionRequested() }
                    runtimeLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    true
                } else {
                    // Denied twice, or blocked at the channel level: only Settings can fix it now.
                    Permissions.openNotificationSettings(context)
                    false
                }
            }
        }
    }

    fun toggle(feature: Feature, on: Boolean) {
        scope.launch {
            when (feature) {
                Feature.ROASTS -> repo.setRoastsEnabled(on)
                Feature.SIDEKICK -> repo.setVoiceEnabled(on)
            }
            if (on) {
                walk.start(feature, permissions)
            } else {
                walk.cancel()
                // Switching off takes effect at once: the timer stops, or Sidekick leaves the screen.
                val latest = repo.current()
                if (latest.onboardingComplete) GalServiceStarter.syncFromForeground(context, latest)
            }
        }
    }

    val anyOn = settings.roastsEnabled || settings.voiceEnabled
    val missing = buildSet {
        if (settings.roastsEnabled) addAll(live.missingFor(Feature.ROASTS))
        if (settings.voiceEnabled) addAll(live.missingFor(Feature.SIDEKICK))
    }

    Column(
        Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Image(painterResource(R.drawable.mascot_smug), contentDescription = null, modifier = Modifier.size(56.dp))
            Spacer(Modifier.size(12.dp))
            Text("What should Sidekick do?", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        }
        Text(
            "Switch on what you want. GAL asks for a permission only when the switch that needs it goes on. " +
                "It never blocks, closes or limits any app, and it has no internet access.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        FeatureCard(
            title = "Screen Time Roasts",
            body = "Sidekick times how long you stay on your phone and, after a limit you choose, pops up with a " +
                "roast card. Your stats stay on the phone.",
            checked = settings.roastsEnabled,
            onCheckedChange = { toggle(Feature.ROASTS, it) },
            needs = Need.forFeature(Feature.ROASTS),
            granted = live::granted,
            onFix = { walk.askOne(Feature.ROASTS, it) },
        )
        FeatureCard(
            title = "Floating Sidekick",
            body = "A little pink elephant who wanders along the edge of your screen, naps, hops when you tap her " +
                "and pulls a face when a roast card shows up. Drag her wherever you like.",
            checked = settings.voiceEnabled,
            onCheckedChange = { toggle(Feature.SIDEKICK, it) },
            needs = Need.forFeature(Feature.SIDEKICK),
            granted = live::granted,
            onFix = { walk.askOne(Feature.SIDEKICK, it) },
        )

        Spacer(Modifier.height(4.dp))
        Button(
            onClick = {
                scope.launch {
                    if (settings.roastsEnabled && !settings.batteryPromptShown && !live.batteryUnrestricted) {
                        // Ask exactly once, ever. The flag is written before the dialog so a crash cannot repeat it.
                        repo.markBatteryPromptShown()
                        Permissions.requestBatteryExemption(context)
                    }
                    repo.setOnboardingComplete()
                    // Permissions are back, so give the overlay another chance.
                    repo.setOverlayFailed(false)
                    GalServiceStarter.syncFromForeground(context, repo.current())
                    onDone()
                }
            },
            enabled = anyOn && missing.isEmpty(),
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) {
            Text(
                when {
                    !anyOn -> "Switch on at least one"
                    missing.isNotEmpty() -> "Grant the ${missing.size} missing to continue"
                    settings.onboardingComplete -> "Done"
                    else -> "Continue"
                },
            )
        }
    }
}

/**
 * Asks for a feature's missing permissions in order, one at a time. [ask] opens the request for one permission
 * and says whether that was a runtime dialog (its answer comes back through the launcher) or a Settings page
 * (its answer comes back as the activity resuming).
 */
private class PermissionWalk {
    var ask: (Need) -> Boolean = { false }
    private var feature: Feature? = null
    private var asking: Need? = null
    private var viaRuntimeDialog = false

    fun start(feature: Feature, permissions: PermissionState) {
        permissions.refresh()
        next(feature, permissions)
    }

    /** A row's button: ask for just this one, then carry on with the rest. */
    fun askOne(feature: Feature, need: Need) {
        this.feature = feature
        asking = need
        viaRuntimeDialog = ask(need)
    }

    fun cancel() {
        feature = null
        asking = null
    }

    fun returned(fromRuntimeDialog: Boolean, permissions: PermissionState) {
        val need = asking ?: return
        val feature = feature ?: return
        if (fromRuntimeDialog != viaRuntimeDialog) return
        if (permissions.status.granted(need)) next(feature, permissions) else cancel()
    }

    private fun next(feature: Feature, permissions: PermissionState) {
        val need = permissions.status.missingFor(feature).firstOrNull()
        if (need == null) {
            cancel()
            return
        }
        askOne(feature, need)
    }
}

@Composable
private fun FeatureCard(
    title: String,
    body: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    needs: List<Need>,
    granted: (Need) -> Boolean,
    onFix: (Need) -> Unit,
) {
    Card(
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Switch(
                    checked = checked,
                    onCheckedChange = onCheckedChange,
                    modifier = Modifier.semantics { contentDescription = "$title on or off" },
                )
            }
            Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (checked) {
                needs.forEach { need ->
                    HorizontalDivider()
                    NeedRow(need, granted(need)) { onFix(need) }
                }
            }
        }
    }
}

@Composable
private fun NeedRow(need: Need, granted: Boolean, onFix: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(need.label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            StatusChip(granted = granted, optional = need.optional)
        }
        Text(need.explanation, style = MaterialTheme.typography.bodySmall)
        if (!granted) {
            OutlinedButton(onClick = onFix) { Text(if (need.grantedInSettings) "Open settings" else "Allow") }
        }
    }
}

@Composable
private fun StatusChip(granted: Boolean, optional: Boolean) {
    val (label, color) = when {
        granted -> "Granted" to MaterialTheme.colorScheme.primary
        optional -> "Optional" to MaterialTheme.colorScheme.onSurfaceVariant
        else -> "Needed" to MaterialTheme.colorScheme.error
    }
    Text(label, color = color, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
}

tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
