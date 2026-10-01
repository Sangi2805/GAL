package com.sangar.gal.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.ceil
import kotlin.math.max

data class ChartBar(val label: String, val minutes: Int, val isToday: Boolean = false, val hasData: Boolean = true)

/**
 * A plain Compose Canvas bar chart: one bar per day, a dashed average line, and hour gridlines.
 * Tapping a bar selects it. Deliberately no chart library.
 */
@Composable
fun BarChart(
    bars: List<ChartBar>,
    averageMinutes: Double?,
    selected: Int?,
    onSelect: (Int?) -> Unit,
    barColor: Color,
    todayColor: Color,
    selectedColor: Color,
    gridColor: Color,
    labelColor: Color,
    modifier: Modifier = Modifier,
) {
    val measurer = rememberTextMeasurer()
    val labelStyle = TextStyle(color = labelColor, fontSize = 11.sp)
    val description = buildString {
        append("Bar chart of daily screen time for the last ${bars.size} days.")
        averageMinutes?.let { append(" Average ${formatMinutes(it.toLong())} a day.") }
    }

    Canvas(
        modifier
            .fillMaxWidth()
            .height(220.dp)
            .semantics { contentDescription = description }
            .pointerInput(bars.size) {
                detectTapGestures { offset ->
                    val left = AXIS_WIDTH_DP.dp.toPx()
                    val slot = (size.width - left) / max(1, bars.size)
                    val index = ((offset.x - left) / slot).toInt()
                    onSelect(if (index in bars.indices && index != selected) index else null)
                }
            },
    ) {
        val left = AXIS_WIDTH_DP.dp.toPx()
        val bottomLabels = 18.dp.toPx()
        val top = 8.dp.toPx()
        val chartHeight = size.height - bottomLabels - top
        val chartWidth = size.width - left

        val maxMinutes = max(60, max(bars.maxOfOrNull { it.minutes } ?: 0, averageMinutes?.toInt() ?: 0))
        val stepHours = when {
            maxMinutes <= 180 -> 1
            maxMinutes <= 480 -> 2
            else -> 4
        }
        val topHours = ceil(maxMinutes / 60.0 / stepHours).toInt() * stepHours
        val scaleMax = topHours * 60f
        fun y(minutes: Float) = top + chartHeight * (1f - minutes / scaleMax)

        for (h in 0..topHours step stepHours) {
            val gy = y(h * 60f)
            drawLine(gridColor, Offset(left, gy), Offset(size.width, gy), strokeWidth = 1.dp.toPx())
            drawLabel(measurer, "${h}h", labelStyle, Offset(0f, gy - 7.sp.toPx()))
        }

        val slot = chartWidth / max(1, bars.size)
        val barWidth = slot * 0.62f
        bars.forEachIndexed { i, bar ->
            val x = left + i * slot + (slot - barWidth) / 2
            val height = if (bar.minutes <= 0) 0f else max(chartHeight * bar.minutes / scaleMax, 2.dp.toPx())
            val color = when {
                i == selected -> selectedColor
                bar.isToday -> todayColor
                else -> barColor
            }
            if (bar.hasData) {
                drawRoundRect(
                    color = color,
                    topLeft = Offset(x, top + chartHeight - height),
                    size = Size(barWidth, height),
                    cornerRadius = CornerRadius(barWidth / 3, barWidth / 3),
                )
            }
            if (i % 7 == (bars.size - 1) % 7) {
                drawLabel(measurer, bar.label, labelStyle, Offset(x - 6.dp.toPx(), size.height - bottomLabels + 3.dp.toPx()))
            }
        }

        averageMinutes?.let {
            val ay = y(it.toFloat())
            drawLine(
                color = selectedColor,
                start = Offset(left, ay),
                end = Offset(size.width, ay),
                strokeWidth = 2.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f)),
            )
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawLabel(
    measurer: TextMeasurer,
    text: String,
    style: TextStyle,
    topLeft: Offset,
) {
    val layout = measurer.measure(text, style)
    val clamped = Offset(
        topLeft.x.coerceIn(0f, max(0f, size.width - layout.size.width)),
        topLeft.y.coerceIn(0f, max(0f, size.height - layout.size.height)),
    )
    drawText(layout, topLeft = clamped)
}

private const val AXIS_WIDTH_DP = 28
