package com.sangar.gal.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sangar.gal.container
import com.sangar.gal.data.TrendCalculator
import com.sangar.gal.data.db.DailyUsage
import com.sangar.gal.data.db.ScreenSession
import com.sangar.gal.phrases.Trend
import com.sangar.gal.service.LiveSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.math.roundToLong

@Composable
fun StatsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val usage = context.container.usage
    val today = remember { usage.today() }
    val rows by remember { usage.observeDays(today.minusDays(30), today.minusDays(1)) }
        .collectAsStateWithLifecycle(initialValue = emptyList<DailyUsage>())
    val live by remember { usage.observeToday(today) }
        .collectAsStateWithLifecycle(initialValue = emptyList<ScreenSession>() to LiveSession())
    var tick by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            tick++
        }
    }
    var selected by remember { mutableStateOf<Int?>(null) }
    val trackingStart by produceState<LocalDate?>(initialValue = null, today) { value = usage.trackingStart() }

    @Suppress("UNUSED_EXPRESSION") tick
    val todayMinutes = (usage.todayScreenMillis(live.first, live.second, today) / 60_000L).toInt()
    val snapshot = TrendCalculator.compute(rows, today, trackingStart)
    val byDate = rows.associateBy { it.date }
    val locale = LocalLocale.current.platformLocale
    val dayLabel = remember(locale) { DateTimeFormatter.ofPattern("d MMM", locale) }
    val bars = (29 downTo 0).map { back ->
        val date = today.minusDays(back.toLong())
        val row = byDate[date]
        when {
            back == 0 -> ChartBar(dayLabel.format(date), todayMinutes, isToday = true)
            // An untracked day is a gap, not a short bar: its minutes are only what was caught while measuring.
            row != null && row.tracked -> ChartBar(dayLabel.format(date), row.totalScreenMinutes)
            else -> ChartBar(dayLabel.format(date), 0, hasData = false)
        }
    }
    val thisMonth = rows.filter { it.tracked && it.date.year == today.year && it.date.month == today.month }

    val topApps by produceState(initialValue = emptyList<Pair<String, Long>>(), today) {
        value = withContext(Dispatchers.IO) {
            usage.topApps(today.minusDays(1))
                .filter { it.foregroundMillis >= 60_000L }
                .map { AppInfo.label(context, it.packageName) to it.foregroundMillis }
        }
    }

    ScreenScaffold(title = "Stats", onBack = onBack) {
        SectionCard {
            Text("Last 30 days", style = MaterialTheme.typography.titleMedium)
            val colors = MaterialTheme.colorScheme
            BarChart(
                bars = bars,
                averageMinutes = snapshot.average30,
                selected = selected,
                onSelect = { selected = it },
                barColor = colors.onSurfaceVariant.copy(alpha = 0.55f),
                todayColor = colors.primary,
                selectedColor = colors.secondary,
                gridColor = colors.onSurfaceVariant.copy(alpha = 0.15f),
                labelColor = colors.onSurfaceVariant,
            )
            val detail = selected?.let { bars.getOrNull(it) }
            Text(
                when {
                    detail == null -> "Tap a bar for the day's total. Dashed line: 30 day average."
                    !detail.hasData -> "${detail.label}: not tracked"
                    detail.isToday -> "Today so far: ${formatMinutes(detail.minutes.toLong())}"
                    else -> "${detail.label}: ${formatMinutes(detail.minutes.toLong())}"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        SectionCard {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Stat("30 day average", snapshot.average30)
                Stat("7 day average", snapshot.average7)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Stat(
                    "${today.month.name.lowercase().replaceFirstChar { it.uppercase() }} average",
                    thisMonth.takeIf { it.isNotEmpty() }?.map { it.totalScreenMinutes }?.average(),
                )
                Column(Modifier.weight(1f)) {
                    Text("Trend", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        when (snapshot.trend) {
                            Trend.UP -> "Going up"
                            Trend.DOWN -> "Going down"
                            Trend.FLAT -> "Flat"
                        },
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    if (snapshot.historyDays < TrendCalculator.MIN_HISTORY_DAYS) {
                        Text(
                            "Needs ${TrendCalculator.MIN_HISTORY_DAYS} days of history",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Text(
                "Averages use fully tracked days only, not the install day or days the timer was not running. Days counted: ${snapshot.historyDays}.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (topApps.isNotEmpty()) {
            SectionCard {
                Text("Yesterday's top apps", style = MaterialTheme.typography.titleMedium)
                topApps.forEach { (label, millis) ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                        Text(formatMinutes(millis / 60_000L), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.Stat(label: String, minutes: Double?) {
    Column(Modifier.weight(1f)) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            minutes?.let { formatMinutes(it.roundToLong()) } ?: "No data yet",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
        )
    }
}
