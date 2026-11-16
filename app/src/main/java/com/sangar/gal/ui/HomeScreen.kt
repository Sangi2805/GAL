package com.sangar.gal.ui

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sangar.gal.BuildConfig
import com.sangar.gal.R
import com.sangar.gal.container
import com.sangar.gal.data.Behaviour
import com.sangar.gal.data.HourUse
import com.sangar.gal.data.HourlyUsage
import com.sangar.gal.data.Insight
import com.sangar.gal.data.Settings
import com.sangar.gal.data.db.DailyUsage
import com.sangar.gal.guard.GuardLive
import com.sangar.gal.service.GalServiceStarter
import com.sangar.gal.ui.theme.Good
import com.sangar.gal.ui.theme.GoodDeep
import com.sangar.gal.ui.theme.Night
import com.sangar.gal.ui.theme.NightDeep
import com.sangar.gal.ui.theme.Pink
import com.sangar.gal.ui.theme.PinkDeep
import com.sangar.gal.ui.theme.PinkSoft
import com.sangar.gal.ui.theme.PlumCard
import com.sangar.gal.ui.theme.PlumLine
import com.sangar.gal.ui.theme.TextMain
import com.sangar.gal.ui.theme.TextMuted
import com.sangar.gal.ui.theme.TextSoft
import com.sangar.gal.ui.theme.Warm
import com.sangar.gal.ui.theme.WarmDeep
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

/** What the insights part of home has to show, loaded together so it appears in one go. */
private data class Noticed(val insights: List<Insight>, val rows: List<HourUse>, val names: Map<String, String>)

/**
 * Home is one page of numbers, all worked out on the phone: today's screen time, the week, a few things GAL
 * noticed about how you use your apps, and how much of today's limit is gone. One icon leads to the setup.
 */
@Composable
fun HomeScreen(onEdit: () -> Unit, onFixPermissions: () -> Unit) {
    val context = LocalContext.current
    val container = context.container
    val usage = container.usage
    val stored by container.settings.settings.collectAsStateWithLifecycle(initialValue = null)
    val settings = stored ?: return
    val permissions = rememberPermissionState()
    val day by rememberToday { usage.today() }

    // Keep the service in step with the setup every time home is opened: this is what brings GAL back if the
    // phone stopped it while the app was closed.
    var resumes by remember { mutableStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { resumes++ }
    LaunchedEffect(resumes, settings.guardActive, settings.roastsActive) {
        GalServiceStarter.syncFromForeground(context.applicationContext, settings)
    }

    val noticed by produceState<Noticed?>(initialValue = null, day, resumes, permissions.status.usageAccess, settings.monitoredApps) {
        value = withContext(Dispatchers.IO) {
            runCatching { container.hourly.sync() }
            val rows = usage.hourRows(Behaviour.WINDOW_DAYS)
            val names = HashMap<String, String>()
            val label: (String) -> String = { pkg -> names.getOrPut(pkg) { AppInfo.label(context, pkg) } }
            settings.monitoredApps.forEach { label(it) }
            Noticed(Behaviour.insights(rows, day, label), rows, names)
        }
    }

    Column(
        Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(22.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Today",
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = TextSoft,
                modifier = Modifier.weight(1f).pointerInput(Unit) {
                    // Debug builds only: a long press opens the diagnostics page.
                    detectTapGestures(onLongPress = {
                        if (BuildConfig.DEBUG) {
                            runCatching {
                                context.startActivity(Intent().setClassName(context.packageName, "com.sangar.gal.debug.DiagnosticsActivity"))
                            }
                        }
                    })
                },
            )
            Box(
                Modifier.size(44.dp).clip(CircleShape).background(PlumCard).clickable(role = Role.Button, onClick = onEdit)
                    .semantics { contentDescription = "Change apps, time and style" },
                contentAlignment = Alignment.Center,
            ) {
                Icon(painterResource(R.drawable.ic_tune), contentDescription = null, tint = TextSoft, modifier = Modifier.size(20.dp))
            }
        }

        val status = permissions.status
        if (!status.usageAccess || !status.overlay) {
            FixCard(if (!status.usageAccess) "Usage access is off" else "Display over other apps is off", onFixPermissions)
        }

        TodayTotal(day)
        WeekChart(day)

        Text("What we noticed", fontSize = 20.sp, fontWeight = FontWeight.ExtraBold)
        val insights = noticed?.insights
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            when {
                insights == null -> Unit
                insights.isEmpty() -> InsightRow(GalIcon.SUN, PinkSoft, PinkDeep) {
                    Text("Still learning your habits. Check back tomorrow.", fontSize = 16.sp, color = TextSoft)
                }
                else -> insights.forEach { InsightCard(it) }
            }
        }

        LimitCard(settings, day, noticed, onEdit)
    }
}

/** The big number, and how it compares with the same time yesterday. Holds the only clock on the page. */
@Composable
private fun TodayTotal(day: LocalDate) {
    val usage = LocalContext.current.container.usage
    val today by remember(day) { usage.observeToday(day) }.collectAsStateWithLifecycle(initialValue = null)
    val tick by rememberTicker(15_000L)
    val minutes = remember(today, tick) { today?.let { usage.todayScreenMillis(it.first, it.second, day) / 60_000L } }
    // Yesterday up to this time of day: comparing a morning with all of yesterday would always look good.
    val yesterday by produceState<Long?>(initialValue = null, day, tick / 4) {
        val now = System.currentTimeMillis()
        val start = usage.startOfDay(day.minusDays(1))
        value = runCatching { usage.screenMillisBetween(start, now - 24 * 60 * 60_000L) / 60_000L }.getOrNull()
    }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            compactMinutes(minutes ?: 0L),
            fontSize = 56.sp,
            fontWeight = FontWeight.ExtraBold,
            letterSpacing = (-2).sp,
            modifier = Modifier.semantics { contentDescription = "${minutes ?: 0} minutes of screen time today" },
        )
        val before = yesterday
        if (minutes != null && before != null && before > 0L) {
            val diff = minutes - before
            when {
                diff <= -1L -> Text("${compactMinutes(-diff)} less than this time yesterday", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Good)
                diff >= 1L -> Text("${compactMinutes(diff)} more than this time yesterday", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Warm)
                else -> Text("Same as this time yesterday", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = TextSoft)
            }
        }
    }
}

/** Seven bars: the last six days and today. A day that was not measured is a short stub, never a low bar. */
@Composable
private fun WeekChart(day: LocalDate) {
    val usage = LocalContext.current.container.usage
    val rows by remember(day) { usage.observeDays(day.minusDays(6), day.minusDays(1)) }.collectAsStateWithLifecycle(initialValue = emptyList())
    val today by remember(day) { usage.observeToday(day) }.collectAsStateWithLifecycle(initialValue = null)
    val tick by rememberTicker(60_000L)
    val todayMinutes = remember(today, tick) { today?.let { usage.todayScreenMillis(it.first, it.second, day) / 60_000L } ?: 0L }

    val byDate: Map<LocalDate, DailyUsage> = rows.associateBy { it.date }
    val days = (6 downTo 0).map { day.minusDays(it.toLong()) }
    val values: List<Long?> = days.map { date ->
        if (date == day) todayMinutes else byDate[date]?.takeIf { it.tracked }?.totalScreenMinutes?.toLong()
    }
    val max = (values.filterNotNull().maxOrNull() ?: 0L).coerceAtLeast(1L)
    val spoken = days.zip(values).joinToString(". ") { (date, value) ->
        val name = date.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.ENGLISH)
        if (value == null) "$name not measured" else "$name ${value / 60} hours ${value % 60} minutes"
    }

    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(PlumCard).padding(start = 18.dp, end = 18.dp, top = 18.dp, bottom = 14.dp)
            .clearAndSetSemantics { contentDescription = "This week. $spoken" },
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(Modifier.fillMaxWidth().height(110.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Bottom) {
            days.zip(values).forEach { (date, value) ->
                val fraction = if (value == null) 0f else (value.toFloat() / max).coerceIn(0.04f, 1f)
                Box(
                    Modifier
                        .weight(1f)
                        .then(if (value == null) Modifier.height(6.dp) else Modifier.fillMaxHeight(fraction))
                        .clip(RoundedCornerShape(8.dp))
                        .background(
                            when {
                                date == day -> Pink
                                value == null -> PlumLine
                                else -> Color(0xFF4A3040)
                            },
                        ),
                )
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            days.forEach { date ->
                Text(
                    date.dayOfWeek.getDisplayName(TextStyle.NARROW, Locale.ENGLISH),
                    fontSize = 12.sp,
                    fontWeight = if (date == day) FontWeight.ExtraBold else FontWeight.Normal,
                    color = if (date == day) TextMain else TextMuted,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun InsightCard(insight: Insight) {
    val (icon, tint, ground) = when (insight.kind) {
        Insight.Kind.PEAK_SLOT -> Triple(GalIcon.SUN, PinkSoft, PinkDeep)
        Insight.Kind.BUSIEST_HOUR -> Triple(GalIcon.CHART, Night, NightDeep)
        Insight.Kind.LATE_NIGHT -> Triple(GalIcon.MOON, Night, NightDeep)
        Insight.Kind.HEAVIEST_DAY -> Triple(GalIcon.CALENDAR, PinkSoft, PinkDeep)
        Insight.Kind.TOP_SHARE -> Triple(GalIcon.PIE, PinkSoft, PinkDeep)
        Insight.Kind.APP_TREND ->
            if (insight.strong.startsWith("down")) Triple(GalIcon.TREND, Good, GoodDeep) else Triple(GalIcon.TREND, Warm, WarmDeep)
    }
    InsightRow(icon, tint, ground) {
        Text(
            buildAnnotatedString {
                append(insight.before)
                withStyle(SpanStyle(fontWeight = FontWeight.ExtraBold)) { append(insight.strong) }
                append(insight.after)
            },
            fontSize = 16.sp,
            lineHeight = 22.sp,
        )
    }
}

@Composable
private fun InsightRow(icon: GalIcon, tint: Color, ground: Color, content: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(PlumCard).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(ground), contentAlignment = Alignment.Center) {
            GalIconView(icon, tint, size = 20.dp)
        }
        Box(Modifier.weight(1f)) { content() }
    }
}

/** Today's limit and the apps it covers. This is also the proof that GAL is set up and watching. */
@Composable
private fun LimitCard(settings: Settings, day: LocalDate, noticed: Noticed?, onEdit: () -> Unit) {
    val live by GuardLive.state.collectAsStateWithLifecycle()
    val chosen = settings.monitoredApps
    val limit = settings.dailyLimitMinutes
    // The running service knows to the second; without it, the last sync of the phone's own records will do.
    val usedMillis = live?.takeIf { it.day == day }?.usedMillis ?: noticed?.let { HourlyUsage.total(it.rows, chosen, day) } ?: 0L
    val used = (usedMillis / 60_000L).toInt()

    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(PlumCard).clickable(role = Role.Button, onClick = onEdit).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (chosen.isEmpty()) {
            Text("No apps chosen", fontSize = 15.sp, fontWeight = FontWeight.ExtraBold)
            Text("Tap to pick the apps to watch.", fontSize = 14.sp, color = TextMuted)
            return@Column
        }
        Row(Modifier.fillMaxWidth()) {
            Text("Limit today", fontSize = 15.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.weight(1f))
            Text("$used of $limit min", fontSize = 15.sp, color = if (used >= limit) Warm else TextSoft)
        }
        Box(Modifier.fillMaxWidth().height(8.dp).clip(CircleShape).background(PlumLine)) {
            Box(Modifier.fillMaxWidth((used.toFloat() / limit).coerceIn(0f, 1f)).fillMaxHeight().clip(CircleShape).background(Pink))
        }
        val names = chosen.map { noticed?.names?.get(it) ?: "" }.filter { it.isNotBlank() }.sorted()
        val watching = when {
            names.isEmpty() -> "Watching ${chosen.size} ${if (chosen.size == 1) "app" else "apps"}"
            names.size <= 2 -> "Watching ${names.joinToString(" and ")}"
            else -> "Watching ${names.take(2).joinToString(", ")} +${names.size - 2}"
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(Good))
            Text(watching, fontSize = 14.sp, color = TextMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Shown only when a setting GAL needs was switched off. One line, one tap to fix. */
@Composable
private fun FixCard(message: String, onFix: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(WarmDeep).clickable(role = Role.Button, onClick = onFix).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        GalIconView(GalIcon.WARNING, Warm)
        Text(message, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        Text("Fix", fontSize = 15.sp, fontWeight = FontWeight.ExtraBold, color = Warm)
    }
}
