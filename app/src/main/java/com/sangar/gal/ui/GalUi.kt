package com.sangar.gal.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sangar.gal.R
import com.sangar.gal.ui.theme.OnPink
import com.sangar.gal.ui.theme.Pink
import com.sangar.gal.ui.theme.PlumLine
import com.sangar.gal.ui.theme.PlumRaised
import com.sangar.gal.ui.theme.TextOff

/** The one big button at the bottom of every screen. */
@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.fillMaxWidth().height(58.dp),
        shape = RoundedCornerShape(29.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = Pink,
            contentColor = OnPink,
            disabledContainerColor = PlumRaised,
            disabledContentColor = TextOff,
        ),
    ) {
        Text(text, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold)
    }
}

/** Where you are in the three setup steps. Decoration only. */
@Composable
fun StepDots(active: Int, modifier: Modifier = Modifier, count: Int = 3) {
    Row(modifier.clearAndSetSemantics { }, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        repeat(count) { index ->
            Box(
                Modifier
                    .height(8.dp)
                    .width(if (index == active) 22.dp else 8.dp)
                    .clip(CircleShape)
                    .background(if (index == active) Pink else PlumLine),
            )
        }
    }
}

/** GAL in a soft circle. The art is the splash icon, which keeps its drawing inside the middle of its canvas. */
@Composable
fun ElephantBadge(size: Dp, modifier: Modifier = Modifier) {
    Box(modifier.size(size).clip(CircleShape).background(Color(0xFF241A26)), contentAlignment = Alignment.Center) {
        Image(
            painter = painterResource(R.drawable.splash_icon),
            contentDescription = null,
            modifier = Modifier.requiredSize(size * 1.18f),
        )
    }
}

/** The handful of line icons the app needs, drawn here so no icon library is pulled in. */
enum class GalIcon { CHECK, CHART, LAYERS, BELL, PLUS, SUN, MOON, TREND, CALENDAR, PIE, CHAT, WARNING }

@Composable
fun GalIconView(icon: GalIcon, tint: Color, modifier: Modifier = Modifier, size: Dp = 22.dp) {
    Canvas(modifier.size(size)) { drawIcon(icon, tint) }
}

private fun DrawScope.drawIcon(icon: GalIcon, tint: Color) {
    val u = size.minDimension / 24f
    val stroke = Stroke(width = 2.2f * u, cap = StrokeCap.Round, join = StrokeJoin.Round)
    fun line(x1: Float, y1: Float, x2: Float, y2: Float) =
        drawLine(tint, Offset(x1 * u, y1 * u), Offset(x2 * u, y2 * u), strokeWidth = 2.2f * u, cap = StrokeCap.Round)
    fun path(build: Path.() -> Unit) = drawPath(Path().apply(build), tint, style = stroke)
    fun box(x: Float, y: Float, w: Float, h: Float, r: Float) =
        drawRoundRect(tint, Offset(x * u, y * u), Size(w * u, h * u), CornerRadius(r * u), style = stroke)
    when (icon) {
        GalIcon.CHECK -> path { moveTo(5 * u, 12.5f * u); lineTo(10 * u, 17.5f * u); lineTo(19 * u, 7 * u) }
        GalIcon.CHART -> {
            line(6f, 19f, 6f, 13f)
            line(12f, 19f, 12f, 5f)
            line(18f, 19f, 18f, 10f)
        }
        GalIcon.LAYERS -> {
            box(4f, 4f, 12f, 12f, 3f)
            box(9f, 9f, 11f, 11f, 3f)
        }
        GalIcon.BELL -> {
            path {
                moveTo(6 * u, 16 * u)
                lineTo(6 * u, 11 * u)
                cubicTo(6 * u, 3 * u, 18 * u, 3 * u, 18 * u, 11 * u)
                lineTo(18 * u, 16 * u)
                lineTo(20 * u, 18 * u)
                lineTo(4 * u, 18 * u)
                close()
            }
            line(10.5f, 21f, 13.5f, 21f)
        }
        GalIcon.PLUS -> {
            line(12f, 5f, 12f, 19f)
            line(5f, 12f, 19f, 12f)
        }
        GalIcon.SUN -> {
            drawCircle(tint, 4 * u, center, style = stroke)
            line(12f, 2f, 12f, 4f)
            line(12f, 20f, 12f, 22f)
            line(2f, 12f, 4f, 12f)
            line(20f, 12f, 22f, 12f)
            line(5f, 5f, 6.5f, 6.5f)
            line(17.5f, 17.5f, 19f, 19f)
            line(5f, 19f, 6.5f, 17.5f)
            line(17.5f, 6.5f, 19f, 5f)
        }
        GalIcon.MOON -> path {
            moveTo(20 * u, 14.5f * u)
            cubicTo(15 * u, 21 * u, 4 * u, 17 * u, 4 * u, 11 * u)
            cubicTo(4 * u, 7.5f * u, 6.5f * u, 5 * u, 9.5f * u, 4 * u)
            cubicTo(8 * u, 10 * u, 13 * u, 16 * u, 20 * u, 14.5f * u)
            close()
        }
        GalIcon.TREND -> {
            path { moveTo(4 * u, 16 * u); lineTo(10 * u, 10 * u); lineTo(14 * u, 14 * u); lineTo(20 * u, 8 * u) }
            path { moveTo(15 * u, 8 * u); lineTo(20 * u, 8 * u); lineTo(20 * u, 13 * u) }
        }
        GalIcon.CALENDAR -> {
            box(4f, 5f, 16f, 15f, 3f)
            line(4f, 10f, 20f, 10f)
            line(8.5f, 3f, 8.5f, 6f)
            line(15.5f, 3f, 15.5f, 6f)
        }
        GalIcon.PIE -> {
            drawCircle(tint, 8 * u, center, style = stroke)
            line(12f, 12f, 12f, 4f)
            line(12f, 12f, 18.5f, 15.5f)
        }
        GalIcon.CHAT -> path {
            moveTo(4 * u, 5 * u)
            lineTo(20 * u, 5 * u)
            lineTo(20 * u, 16 * u)
            lineTo(9 * u, 16 * u)
            lineTo(4 * u, 20 * u)
            close()
        }
        GalIcon.WARNING -> {
            path { moveTo(12 * u, 4 * u); lineTo(21 * u, 19 * u); lineTo(3 * u, 19 * u); close() }
            line(12f, 10f, 12f, 14f)
            line(12f, 16.6f, 12f, 16.7f)
        }
    }
}

/** 134 -> "2h 14m", 14 -> "14m". */
fun compactMinutes(minutes: Long): String = if (minutes >= 60) "${minutes / 60}h ${minutes % 60}m" else "${minutes}m"

/** The slider's label: 30 -> "30 min", 90 -> "1 h 30 min", 120 -> "2 h". */
fun limitLabel(minutes: Int): String = when {
    minutes < 60 -> "$minutes min"
    minutes % 60 == 0 -> "${minutes / 60} h"
    else -> "${minutes / 60} h ${minutes % 60} min"
}
