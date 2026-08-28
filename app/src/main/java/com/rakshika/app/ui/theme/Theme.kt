package com.rakshika.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val RakshikaColorScheme = lightColorScheme(
    primary = RakshikaRed,
    onPrimary = Color.White,
    secondary = RakshikaGreen,
    background = SurfacePage,
    surface = SurfaceCard,
    onBackground = TextPrimary,
    onSurface = TextPrimary,
    error = RakshikaRedDark
)

private val RakshikaTypography = Typography(
    titleLarge = TextStyle(fontWeight = FontWeight.Medium, fontSize = 22.sp),
    titleMedium = TextStyle(fontWeight = FontWeight.Medium, fontSize = 18.sp),
    bodyLarge = TextStyle(fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp),
    labelSmall = TextStyle(fontWeight = FontWeight.Medium, fontSize = 12.sp)
)

@Composable
fun RakshikaTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = RakshikaColorScheme,
        typography = RakshikaTypography,
        content = content
    )
}
