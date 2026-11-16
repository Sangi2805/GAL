package com.sangar.gal.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * The redesign's colours: a deep plum ground, soft pink for the one thing to tap, and quiet greys for the rest.
 * GAL is always dark; it does not follow the phone's light theme, so every screen and the full-screen pause
 * look like one app.
 */
val Plum = Color(0xFF120E16)
val PlumCard = Color(0xFF1E1824)
val PlumRaised = Color(0xFF2A2230)
val PlumLine = Color(0xFF2E2633)
val TextMain = Color(0xFFF7EEF3)
val TextSoft = Color(0xFFC9B8C3)
val TextMuted = Color(0xFFB9A9B4)
val TextOff = Color(0xFF8E7F8A)
val Pink = Color(0xFFFF6B9A)
val PinkSoft = Color(0xFFFF8FB1)
val PinkTint = Color(0xFF2A1A24)
val PinkDeep = Color(0xFF3A2030)
val OnPink = Color(0xFF1A0B12)
val Good = Color(0xFF8FE3AE)
val GoodDeep = Color(0xFF2B4A38)
val Night = Color(0xFFA9B4FF)
val NightDeep = Color(0xFF2A2A44)
val Warm = Color(0xFFFFC978)
val WarmDeep = Color(0xFF4A3A20)

private val GalColors = darkColorScheme(
    primary = Pink,
    onPrimary = OnPink,
    primaryContainer = PinkDeep,
    onPrimaryContainer = TextMain,
    secondary = PinkSoft,
    onSecondary = OnPink,
    tertiary = Good,
    onTertiary = OnPink,
    background = Plum,
    onBackground = TextMain,
    surface = Plum,
    onSurface = TextMain,
    surfaceVariant = PlumCard,
    onSurfaceVariant = TextSoft,
    outline = PlumLine,
    error = Color(0xFFF2B8B5),
    errorContainer = Color(0xFF5C1510),
    onErrorContainer = Color(0xFFF9DEDA),
)

@Composable
fun GalTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = GalColors, content = content)
}
