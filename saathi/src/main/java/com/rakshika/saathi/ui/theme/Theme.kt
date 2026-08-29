package com.rakshika.saathi.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val SaathiGreen = Color(0xFF1F6F4A)
val SaathiGreenSoft = Color(0xFFE3F1E9)
val SaathiAmber = Color(0xFFC98A2E)
val SaathiRed = Color(0xFFD8365E)
val SaathiBlue = Color(0xFF378ADD)
val InkPrimary = Color(0xFF1B1B1B)
val InkSecondary = Color(0xFF6B6B6B)
val Hairline = Color(0xFFE4E1DA)
val PageBg = Color(0xFFF7F5F0)
val CardBg = Color(0xFFFFFFFF)

private val LightColors = lightColorScheme(
    primary = SaathiGreen,
    onPrimary = Color.White,
    background = PageBg,
    surface = CardBg,
    onSurface = InkPrimary,
    error = SaathiRed
)

private val DarkColors = darkColorScheme(
    primary = SaathiGreen,
    background = Color(0xFF14110E),
    surface = Color(0xFF1E1A16),
    error = SaathiRed
)

@Composable
fun RakshikaSaathiTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        content = content
    )
}
