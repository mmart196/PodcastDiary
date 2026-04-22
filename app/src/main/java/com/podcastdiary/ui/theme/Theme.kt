package com.podcastdiary.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(0xFF2E5AAC),
    onPrimary = Color.White,
    secondary = Color(0xFF4F6380),
    background = Color(0xFFFBFBFE),
    surface = Color(0xFFFFFFFF),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF9FB4DE),
    onPrimary = Color(0xFF11224A),
    secondary = Color(0xFFB8C6DB),
    background = Color(0xFF121316),
    surface = Color(0xFF1A1C20),
)

@Composable
fun PodcastDiaryTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colors = if (darkTheme) DarkColors else LightColors
    MaterialTheme(colorScheme = colors, content = content)
}
