package com.sangar.gal.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sangar.gal.container
import com.sangar.gal.guard.BlockLines
import com.sangar.gal.guard.GuardRules
import com.sangar.gal.phrases.Tone
import com.sangar.gal.service.GalServiceStarter
import com.sangar.gal.sidekick.Reactions
import com.sangar.gal.ui.theme.Good
import com.sangar.gal.ui.theme.OnPink
import com.sangar.gal.ui.theme.Pink
import com.sangar.gal.ui.theme.PinkSoft
import com.sangar.gal.ui.theme.PinkTint
import com.sangar.gal.ui.theme.PlumCard
import com.sangar.gal.ui.theme.PlumLine
import com.sangar.gal.ui.theme.PlumRaised
import com.sangar.gal.ui.theme.TextMain
import com.sangar.gal.ui.theme.TextMuted
import com.sangar.gal.ui.theme.TextSoft
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * The third screen, and the only settings GAL has: which apps, how long a day, and how she talks. First-time
 * setup and "change my setup" from home are the same screen; only the button's word differs.
 */
@Composable
fun PickScreen(editing: Boolean, onDone: () -> Unit) {
    val context = LocalContext.current
    val repo = context.container.settings
    val stored by repo.settings.collectAsStateWithLifecycle(initialValue = null)
    val settings = stored ?: return

    var selected by rememberSaveable { mutableStateOf(settings.monitoredApps.toList()) }
    var limit by rememberSaveable { mutableStateOf(settings.dailyLimitMinutes) }
    var toneName by rememberSaveable { mutableStateOf(settings.tone.name) }
    var showAll by rememberSaveable { mutableStateOf(false) }
    val tone = Tone.entries.firstOrNull { it.name == toneName } ?: Tone.DEFAULT

    val apps by produceState<List<InstalledApp>?>(initialValue = null) {
        value = withContext(Dispatchers.IO) { AppInfo.launchable(context) }
    }
    val installed = apps.orEmpty()

    fun toggle(packageName: String) {
        selected = if (packageName in selected) selected - packageName else selected + packageName
    }

    if (showAll) {
        BackHandler { showAll = false }
        AllApps(installed, selected.toSet(), ::toggle, onClose = { showAll = false })
        return
    }

    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 24.dp, vertical = 28.dp)) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(28.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("Which apps?", fontSize = 22.sp, fontWeight = FontWeight.ExtraBold)
                AppGrid(tiles(installed, selected), selected.toSet(), ::toggle, onMore = { showAll = true })
            }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                    Text("Time a day", fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.weight(1f))
                    Text(limitLabel(limit), fontSize = 28.sp, fontWeight = FontWeight.ExtraBold, color = PinkSoft)
                }
                Slider(
                    value = limit.toFloat(),
                    onValueChange = { limit = GuardRules.clampLimit(it.roundToInt()) },
                    valueRange = GuardRules.MIN_LIMIT_MINUTES.toFloat()..GuardRules.MAX_LIMIT_MINUTES.toFloat(),
                    steps = (GuardRules.MAX_LIMIT_MINUTES - GuardRules.MIN_LIMIT_MINUTES) / GuardRules.LIMIT_STEP_MINUTES - 1,
                    colors = SliderDefaults.colors(
                        thumbColor = Color.White,
                        activeTrackColor = Pink,
                        inactiveTrackColor = PlumLine,
                        activeTickColor = Color.Transparent,
                        inactiveTickColor = Color.Transparent,
                    ),
                    modifier = Modifier.semantics { contentDescription = "Time a day for these apps together" },
                )
                Row(Modifier.fillMaxWidth()) {
                    Text(limitLabel(GuardRules.MIN_LIMIT_MINUTES), fontSize = 13.sp, color = TextMuted, modifier = Modifier.weight(1f))
                    Text(limitLabel(GuardRules.MAX_LIMIT_MINUTES), fontSize = 13.sp, color = TextMuted)
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("Roast style", fontSize = 22.sp, fontWeight = FontWeight.ExtraBold)
                TonePicker(tone) { toneName = it.name }
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(PlumCard).heightIn(min = 52.dp)
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    GalIconView(GalIcon.CHAT, PinkSoft, size = 18.dp)
                    Text(BlockLines.example(tone), fontSize = 15.sp, color = TextMain)
                }
            }
        }
        Spacer(Modifier.height(20.dp))
        PrimaryButton(
            text = if (editing) "Save" else "Start",
            // Emptying the list while editing is allowed: it is how GAL is switched off.
            enabled = selected.isNotEmpty() || editing,
            onClick = {
                val chosen = selected.toSet()
                // appScope so the save finishes even though this screen is about to go away.
                context.container.appScope.launch {
                    repo.setTone(tone)
                    repo.saveGuard(chosen, limit)
                    GalServiceStarter.syncFromForeground(context.applicationContext, repo.current())
                }
                onDone()
            },
        )
    }
}

/**
 * The tiles on the setup screen: everything already chosen, then the feeds and video apps on this phone that
 * people most often want to cut down, up to five in all. Everything else is one tap away under "More".
 */
private fun tiles(installed: List<InstalledApp>, selected: List<String>): List<InstalledApp> {
    val byPackage = installed.associateBy { it.packageName }
    val chosen = selected.mapNotNull { byPackage[it] }
    val suggested = Reactions.SOCIAL_PACKAGES
        .flatMap { prefix -> installed.filter { it.packageName.startsWith(prefix) } }
        .filter { it.packageName !in selected }
    val others = installed.filter { it.packageName !in selected && it !in suggested }
    return (chosen + suggested + others).distinctBy { it.packageName }.take(maxOf(5, chosen.size))
}

@Composable
private fun AppGrid(tiles: List<InstalledApp>, selected: Set<String>, onToggle: (String) -> Unit, onMore: () -> Unit) {
    // The tiles and "More" together, three to a row. A short last row is padded so every tile is the same width.
    val cells: List<InstalledApp?> = tiles + listOf(null)
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        cells.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { app ->
                    if (app == null) {
                        MoreTile(onMore, Modifier.weight(1f))
                    } else {
                        AppTile(app, app.packageName in selected, { onToggle(app.packageName) }, Modifier.weight(1f))
                    }
                }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun AppTile(app: InstalledApp, selected: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(20.dp)
    Column(
        modifier
            .height(92.dp)
            .clip(shape)
            .background(if (selected) PinkTint else PlumCard)
            .border(2.dp, if (selected) Pink else PlumLine, shape)
            .toggleable(value = selected, role = Role.Checkbox, onValueChange = { onToggle() })
            .padding(horizontal = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        AppIcon(app.packageName, 36)
        Spacer(Modifier.height(8.dp))
        Text(app.label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun MoreTile(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(20.dp)
    Column(
        modifier.height(92.dp).clip(shape).border(2.dp, PlumRaised, shape).clickable(role = Role.Button, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        GalIconView(GalIcon.PLUS, TextSoft, size = 24.dp)
        Spacer(Modifier.height(8.dp))
        Text("More", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = TextSoft)
    }
}

@Composable
private fun AppIcon(packageName: String, sizeDp: Int) {
    val context = LocalContext.current
    val sizePx = with(LocalDensity.current) { sizeDp.dp.roundToPx() }
    val icon by produceState<androidx.compose.ui.graphics.ImageBitmap?>(initialValue = null, packageName, sizePx) {
        value = withContext(Dispatchers.IO) { AppInfo.icon(context, packageName, sizePx) }
    }
    val bitmap = icon
    if (bitmap != null) {
        Image(bitmap, contentDescription = null, modifier = Modifier.size(sizeDp.dp))
    } else {
        Box(Modifier.size(sizeDp.dp).clip(RoundedCornerShape(12.dp)).background(PlumRaised))
    }
}

/** Calm, Honest, Brutal: three buttons of exactly the same size, one of them filled. */
@Composable
private fun TonePicker(tone: Tone, onPick: (Tone) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(26.dp)).background(PlumCard).padding(6.dp).selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Tone.entries.forEach { option ->
            val picked = option == tone
            Box(
                Modifier
                    .weight(1f)
                    .height(48.dp)
                    .clip(RoundedCornerShape(22.dp))
                    .background(if (picked) Pink else Color.Transparent)
                    .selectable(selected = picked, role = Role.RadioButton, onClick = { onPick(option) }),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    option.title,
                    fontSize = 16.sp,
                    fontWeight = if (picked) FontWeight.ExtraBold else FontWeight.SemiBold,
                    color = if (picked) OnPink else TextSoft,
                )
            }
        }
    }
}

/** Every app on the phone, for the ones that are not among the tiles. */
@Composable
private fun AllApps(apps: List<InstalledApp>, selected: Set<String>, onToggle: (String) -> Unit, onClose: () -> Unit) {
    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 24.dp, vertical = 28.dp)) {
        Text("All apps", fontSize = 22.sp, fontWeight = FontWeight.ExtraBold)
        Spacer(Modifier.height(14.dp))
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(apps, key = { it.packageName }) { app ->
                val picked = app.packageName in selected
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(18.dp))
                        .background(if (picked) PinkTint else PlumCard)
                        .toggleable(value = picked, role = Role.Checkbox, onValueChange = { onToggle(app.packageName) })
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    AppIcon(app.packageName, 40)
                    Text(app.label, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Box(
                        Modifier.size(26.dp).clip(CircleShape).background(if (picked) Pink else Color.Transparent)
                            .border(2.dp, if (picked) Pink else PlumLine, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (picked) GalIconView(GalIcon.CHECK, OnPink, size = 16.dp)
                    }
                }
            }
        }
        Spacer(Modifier.height(20.dp))
        PrimaryButton("Done", onClose)
    }
}
