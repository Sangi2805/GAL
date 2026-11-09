package com.sangar.gal.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.sangar.gal.data.Garden
import com.sangar.gal.data.Plant
import com.sangar.gal.data.PlantStage
import com.sangar.gal.data.UsageRepository
import com.sangar.gal.data.db.ScreenSession
import com.sangar.gal.service.LiveSession
import java.time.LocalDate

/**
 * GAL's plant and your garden score. The plant grows a step for every day at or under the daily goal, wilts on
 * a day over it and dies after three of those in a row. Seven good days make a flower, and flowers are the score.
 */
@Composable
fun PlantCard(
    plant: Plant,
    goalMinutes: Int,
    sessions: List<ScreenSession>,
    live: LiveSession,
    day: LocalDate,
    usage: UsageRepository,
    onGoal: (Int) -> Unit,
) {
    val tick by rememberTicker(30_000L)
    val todayMinutes = remember(sessions, live, day, tick) { usage.todayScreenMillis(sessions, live, day) / 60_000L }
    val overToday = todayMinutes > goalMinutes

    SectionCard {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("GAL's plant", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Text(
                "Garden: ${plant.flowers} flower${if (plant.flowers == 1) "" else "s"}",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            val stage = Garden.stage(plant)
            Canvas(
                Modifier.size(96.dp).semantics {
                    contentDescription = "Plant: ${stage.name.lowercase()}${if (plant.wilt > 0 || overToday) ", wilting" else ""}"
                },
            ) { drawPlant(stage, plant.growth, wilting = plant.wilt > 0 || overToday) }
            Column(Modifier.weight(1f).padding(start = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    "Day ${plant.growth} of ${Garden.BLOOM_AT}",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                Text(Garden.status(plant, todayMinutes, goalMinutes), style = MaterialTheme.typography.bodyMedium)
                Text(
                    "Today: ${formatMinutes(todayMinutes)} of ${formatMinutes(goalMinutes.toLong())}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (overToday) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Daily goal", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            OutlinedButton(
                onClick = { onGoal(goalMinutes - Garden.GOAL_STEP_MINUTES) },
                enabled = goalMinutes > Garden.MIN_GOAL_MINUTES,
                modifier = Modifier.semantics { contentDescription = "Lower the daily goal" },
            ) { Text("−") }
            Text(formatMinutes(goalMinutes.toLong()), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            OutlinedButton(
                onClick = { onGoal(goalMinutes + Garden.GOAL_STEP_MINUTES) },
                enabled = goalMinutes < Garden.MAX_GOAL_MINUTES,
                modifier = Modifier.semantics { contentDescription = "Raise the daily goal" },
            ) { Text("+") }
        }
        if (plant.lost > 0) {
            Text(
                "Plants lost: ${plant.lost}. The flowers you grew stay yours.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private val Pot = Color(0xFFC9703F)
private val PotRim = Color(0xFF9B4F27)
private val Soil = Color(0xFF5A3A26)
private val Leaf = Color(0xFF3FAE6A)
private val LeafDry = Color(0xFFB8A25A)
private val Petal = Color(0xFFFF5C8A)

/** A pot with a plant as tall as its [growth], drooping and yellowed when [wilting]. */
private fun DrawScope.drawPlant(stage: PlantStage, growth: Int, wilting: Boolean) {
    val w = size.width
    val h = size.height
    val potTop = h * 0.68f
    val pot = Path().apply {
        moveTo(w * 0.28f, potTop)
        lineTo(w * 0.72f, potTop)
        lineTo(w * 0.65f, h * 0.96f)
        lineTo(w * 0.35f, h * 0.96f)
        close()
    }
    drawPath(pot, Pot)
    drawRect(PotRim, Offset(w * 0.24f, potTop - h * 0.05f), Size(w * 0.52f, h * 0.07f))
    drawOval(Soil, Offset(w * 0.30f, potTop - h * 0.035f), Size(w * 0.40f, h * 0.04f))
    if (stage == PlantStage.SEED) {
        drawOval(Color(0xFFE9D8B4), Offset(w * 0.47f, potTop - h * 0.045f), Size(w * 0.06f, h * 0.035f))
        return
    }
    val green = if (wilting) LeafDry else Leaf
    val stemHeight = h * (0.14f + 0.07f * growth.coerceAtMost(Garden.BLOOM_AT - 1))
    val base = Offset(w * 0.5f, potTop - h * 0.02f)
    // A wilting plant leans over.
    rotate(if (wilting) 28f else 0f, pivot = base) {
        val top = Offset(base.x, base.y - stemHeight)
        drawLine(green, base, top, strokeWidth = w * 0.035f, cap = StrokeCap.Round)
        val pairs = when (stage) {
            PlantStage.SPROUT -> 1
            PlantStage.LEAVES -> 2
            else -> 3
        }
        for (i in 0 until pairs) {
            val y = base.y - stemHeight * (0.35f + 0.28f * i).coerceAtMost(0.95f)
            val leaf = Size(w * 0.17f, h * 0.075f)
            rotate(-25f, pivot = Offset(base.x, y)) { drawOval(green, Offset(base.x - leaf.width, y - leaf.height / 2), leaf) }
            rotate(25f, pivot = Offset(base.x, y)) { drawOval(green, Offset(base.x, y - leaf.height / 2), leaf) }
        }
        if (stage == PlantStage.BUD) {
            drawCircle(Petal, radius = w * 0.06f, center = top)
            drawCircle(Color.White, radius = w * 0.06f, center = top, style = Stroke(width = w * 0.012f))
        }
    }
}
