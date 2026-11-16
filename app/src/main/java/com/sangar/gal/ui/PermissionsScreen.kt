package com.sangar.gal.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sangar.gal.Need
import com.sangar.gal.Permissions
import com.sangar.gal.R
import com.sangar.gal.container
import com.sangar.gal.ui.theme.Good
import com.sangar.gal.ui.theme.GoodDeep
import com.sangar.gal.ui.theme.OnPink
import com.sangar.gal.ui.theme.Pink
import com.sangar.gal.ui.theme.PinkDeep
import com.sangar.gal.ui.theme.PinkSoft
import com.sangar.gal.ui.theme.PlumCard
import com.sangar.gal.ui.theme.PlumLine
import com.sangar.gal.ui.theme.TextMain
import com.sangar.gal.ui.theme.TextMuted
import com.sangar.gal.ui.theme.TextSoft
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

/** One card on the permissions screen. The title is the exact name of the setting on the phone. */
private data class Ask(val need: Need, val title: String, val why: String, val icon: GalIcon)

private val ASKS = listOf(
    Ask(Need.USAGE_ACCESS, "Usage access", "See which apps you use", GalIcon.CHART),
    Ask(Need.OVERLAY, "Display over other apps", "Show the pause screen", GalIcon.LAYERS),
    Ask(Need.NOTIFICATIONS, "Notifications", "Keep GAL running", GalIcon.BELL),
)

/**
 * The second screen: the three things the phone has to allow, each with the exact name of its setting and one
 * short line. Tap a card, switch it on, come back; the card turns green by itself. The next one to do is
 * outlined in pink, so there is always one obvious thing to tap.
 */
@Composable
fun PermissionsScreen(onDone: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repo = context.container.settings
    val stored by repo.settings.collectAsStateWithLifecycle(initialValue = null)
    val permissions = rememberPermissionState()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { permissions.refresh() }
    val settings = stored ?: return
    val status = permissions.status

    val doneCount = ASKS.count { status.granted(it.need) }
    // Usage access and drawing over apps are what GAL cannot work without. Notifications only keep Android
    // from stopping it, so you can carry on without them.
    val ready = status.usageAccess && status.overlay
    val next = ASKS.firstOrNull { !status.granted(it.need) }?.need

    // All three done: nothing left to do here, so move on without asking for one more tap.
    LaunchedEffect(doneCount) {
        if (doneCount == ASKS.size) onDone()
    }

    fun ask(need: Need) {
        when (need) {
            Need.USAGE_ACCESS -> Permissions.openUsageAccessSettings(context)
            Need.OVERLAY -> Permissions.openOverlaySettings(context)
            Need.NOTIFICATIONS -> {
                val activity = context.findActivity()
                val canAskInline = Permissions.needsRuntimeNotificationPermission(context) && activity != null &&
                    (!settings.notificationPermissionRequested ||
                        ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.POST_NOTIFICATIONS))
                if (canAskInline) {
                    scope.launch { repo.markNotificationPermissionRequested() }
                    launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    // Denied twice, or switched off for the whole app: only Settings can fix it now.
                    Permissions.openNotificationSettings(context)
                }
            }
            Need.BATTERY -> Permissions.requestBatteryExemption(context)
        }
    }

    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 24.dp, vertical = 28.dp)) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            Spacer(Modifier.height(16.dp))
            Text("Turn on 3 things", fontSize = 32.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.5).sp)
            Spacer(Modifier.height(8.dp))
            Text("Tap each one. Takes a minute.", fontSize = 16.sp, color = TextSoft)
            Spacer(Modifier.height(28.dp))
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ASKS.forEach { item ->
                    AskCard(item, granted = status.granted(item.need), isNext = item.need == next, onAllow = { ask(item.need) })
                }
            }
            if (next == Need.USAGE_ACCESS || next == Need.OVERLAY) {
                Spacer(Modifier.height(16.dp))
                Text(
                    "Opens Settings. Find ${stringResource(R.string.app_name)} and switch it on.",
                    fontSize = 14.sp,
                    color = TextMuted,
                )
            }
            Spacer(Modifier.height(16.dp))
        }
        StepDots(active = 1, modifier = Modifier.align(Alignment.CenterHorizontally))
        Spacer(Modifier.height(20.dp))
        PrimaryButton(if (ready) "Continue" else "$doneCount of ${ASKS.size} done", onDone, enabled = ready)
    }
}

@Composable
private fun AskCard(item: Ask, granted: Boolean, isNext: Boolean, onAllow: () -> Unit) {
    val shape = RoundedCornerShape(22.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(PlumCard)
            .then(if (isNext) Modifier.border(2.dp, Pink, shape) else Modifier)
            .padding(18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(
            Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(if (granted) GoodDeep else PinkDeep),
            contentAlignment = Alignment.Center,
        ) {
            GalIconView(if (granted) GalIcon.CHECK else item.icon, if (granted) Good else PinkSoft)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(item.title, fontSize = 17.sp, fontWeight = FontWeight.ExtraBold)
            Text(item.why, fontSize = 14.sp, color = TextMuted)
        }
        when {
            granted -> Text("Done", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Good)
            isNext -> Button(
                onClick = onAllow,
                modifier = Modifier.heightIn(min = 44.dp),
                shape = RoundedCornerShape(22.dp),
                contentPadding = PaddingValues(horizontal = 18.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Pink, contentColor = OnPink),
            ) { Text("Allow", fontSize = 15.sp, fontWeight = FontWeight.ExtraBold) }
            else -> OutlinedButton(
                onClick = onAllow,
                modifier = Modifier.heightIn(min = 44.dp),
                shape = RoundedCornerShape(22.dp),
                contentPadding = PaddingValues(horizontal = 18.dp),
                border = BorderStroke(2.dp, PlumLine),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = TextMain),
            ) { Text("Allow", fontSize = 15.sp, fontWeight = FontWeight.ExtraBold) }
        }
    }
}

internal tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
