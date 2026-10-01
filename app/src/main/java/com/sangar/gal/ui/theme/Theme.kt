package com.sangar.gal.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** Concept C colours: Sidekick's mint, its rim and ink, with GetALife's tangerine kept for roasts. */
val Mint = Color(0xFF5CE79B)
val MintDark = Color(0xFF1FA463)
val Rim = Color(0xFF14663C)
val Ink = Color(0xFF0E2B1B)
val MintGround = Color(0xFFDFF7EA)
val Tangerine = Color(0xFFF28C28)
val Cream = Color(0xFFFBF3E4)
val Warning = Color(0xFFB3261E)

private val LightColors = lightColorScheme(
    primary = Rim,
    onPrimary = Color.White,
    primaryContainer = Mint,
    onPrimaryContainer = Ink,
    secondary = Ink,
    onSecondary = MintGround,
    tertiary = Tangerine,
    onTertiary = Ink,
    background = MintGround,
    onBackground = Ink,
    surface = MintGround,
    onSurface = Ink,
    surfaceVariant = Color(0xFFC4EDD6),
    onSurfaceVariant = Color(0xFF355846),
    error = Warning,
    errorContainer = Color(0xFFF9DEDA),
    onErrorContainer = Color(0xFF5C1510),
)

private val DarkColors = darkColorScheme(
    primary = Mint,
    onPrimary = Ink,
    primaryContainer = Rim,
    onPrimaryContainer = MintGround,
    secondary = MintGround,
    onSecondary = Ink,
    tertiary = Tangerine,
    onTertiary = Ink,
    background = Color(0xFF0B1F14),
    onBackground = MintGround,
    surface = Color(0xFF0B1F14),
    onSurface = MintGround,
    surfaceVariant = Color(0xFF1C3A2A),
    onSurfaceVariant = Color(0xFFB5D9C4),
    error = Color(0xFFF2B8B5),
    errorContainer = Color(0xFF5C1510),
    onErrorContainer = Color(0xFFF9DEDA),
)

@Composable
fun GalTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        content = content,
    )
}
