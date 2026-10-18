package de.derdl1983.weriewas.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(0xFF2E7D32),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFB9F0B7),
    onPrimaryContainer = Color(0xFF00390A),
    secondary = Color(0xFF455A64),
    background = Color(0xFFF7FBF7),
    surface = Color.White,
    surfaceVariant = Color(0xFFE4EDE4)
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF8FD68F),
    onPrimary = Color(0xFF00390A),
    primaryContainer = Color(0xFF1B4A1F),
    secondary = Color(0xFFB0BEC5),
    background = Color(0xFF101510),
    surface = Color(0xFF161C16),
    surfaceVariant = Color(0xFF263026)
)

/** App-Theme von „Wer Wie Was“ (hell/dunkel, grün als Akzentfarbe). */
@Composable
fun WerWieWasTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content
    )
}
