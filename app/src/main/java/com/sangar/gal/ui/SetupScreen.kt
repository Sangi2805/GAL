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
 * The one setup screen, kept simple: one elephant, one list of what GAL needs, one button. The button walks
 * through every missing permission in turn (and offers the battery exemption once, at the end). Backing out of
 * one stops the walk; tapping the button again carries on, and each row can also be fixed on its own.
 * Everything GAL does is switched on together; the Settings screen is where you turn parts off.
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
            Need.BATTERY -> {
                // Ask exactly once, ever. The flag is written before the dialog so a crash cannot repeat it.
                scope.launch { repo.markBatteryPromptShown() }
                Permissions.requestBatteryExemption(context).let { false }
            }
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
    // What the walk still has to ask for: every required permission, then the battery exemption once.
    walk.pending = { status ->
        buildList {
            addAll(status.missingFor(Feature.ROASTS))
            if (!settings.batteryPromptShown && !status.batteryUnrestricted) add(Need.BATTERY)
        }
    }

    val missing = live.missingFor(Feature.ROASTS)

    Column(
        Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Image(
            painterResource(R.drawable.mascot_smug),
            contentDescription = null,
            modifier = Modifier.size(120.dp).align(Alignment.CenterHorizontally),
        )
        Text(
            "A few permissions",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )
        Text(
            "GAL needs these to notice when you have been on your phone too long. It never blocks or closes " +
                "any app, and it has no internet access.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Card(
            shape = RoundedCornerShape(22.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Need.forFeature(Feature.ROASTS).forEachIndexed { i, need ->
                    if (i > 0) HorizontalDivider()
                    NeedRow(need, live.granted(need)) { walk.askOne(need) }
                }
            }
        }

        Spacer(Modifier.height(4.dp))
        if (missing.isNotEmpty()) {
            Button(
                onClick = { walk.start(permissions) },
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) {
                Text(if (missing.size == Need.forFeature(Feature.ROASTS).count { !it.optional }) "Allow" else "Allow the rest")
            }
        } else {
            Button(
                onClick = {
                    scope.launch {
                        // One app, everything on: the timer, the roast cards and the elephant.
                        repo.setRoastsEnabled(true)
                        repo.setVoiceEnabled(true)
                        repo.setOnboardingComplete()
                        // Permissions are back, so give the overlay another chance.
                        repo.setOverlayFailed(false)
                        GalServiceStarter.syncFromForeground(context, repo.current())
                        onDone()
                    }
                },
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) {
                Text(if (settings.onboardingComplete) "Done" else "Start")
            }
        }
    }
}

/**
 * Asks for the missing permissions in order, one at a time. [ask] opens the request for one permission and says
 * whether that was a runtime dialog (its answer comes back through the launcher) or a Settings page (its answer
 * comes back as the activity resuming). [pending] lists what is still to ask.
 */
private class PermissionWalk {
    var ask: (Need) -> Boolean = { false }
    var pending: (com.sangar.gal.PermissionStatus) -> List<Need> = { emptyList() }
    private var walking = false
    private var asking: Need? = null
    private var viaRuntimeDialog = false

    fun start(permissions: PermissionState) {
        permissions.refresh()
        walking = true
        next(permissions)
    }

    /** A row's button: ask for just this one. */
    fun askOne(need: Need) {
        asking = need
        viaRuntimeDialog = ask(need)
    }

    fun cancel() {
        walking = false
        asking = null
    }

    fun returned(fromRuntimeDialog: Boolean, permissions: PermissionState) {
        val need = asking ?: return
        if (fromRuntimeDialog != viaRuntimeDialog) return
        asking = null
        // The battery exemption is optional: carry on (to the end) whatever the answer was.
        if (walking && (permissions.status.granted(need) || need.optional)) next(permissions) else cancel()
    }

    private fun next(permissions: PermissionState) {
        val need = pending(permissions.status).firstOrNull()
        if (need == null) {
            cancel()
            return
        }
        askOne(need)
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
