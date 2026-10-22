package de.derdl1983.weriewas.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Farbpalette im Stil des „Wer Wie Was“-Logos: Blau als Primärfarbe,
// Grün und Violett aus den Sprechblasen als Sekundär-/Tertiärakzente.

private val LightColors = lightColorScheme(
    primary = Color(0xFF1A73E8),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD3E3FD),
    onPrimaryContainer = Color(0xFF041E49),
    secondary = Color(0xFF34A853),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD7F0DC),
    onSecondaryContainer = Color(0xFF0A3D1E),
    tertiary = Color(0xFF9B59B6),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFF0E3F7),
    onTertiaryContainer = Color(0xFF2E0B3E),
    background = Color(0xFFF7FAFD),
    onBackground = Color(0xFF191C20),
    surface = Color(0xFFFCFDFE),
    onSurface = Color(0xFF191C20),
    surfaceVariant = Color(0xFFE1E8F0),
    onSurfaceVariant = Color(0xFF43474E),
    outline = Color(0xFF74777F),
    error = Color(0xFFBA1A1A),
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002)
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF8AB4F8),
    onPrimary = Color(0xFF0B2E6B),
    primaryContainer = Color(0xFF1F4A8F),
    onPrimaryContainer = Color(0xFFD3E3FD),
    secondary = Color(0xFF86CB9C),
    onSecondary = Color(0xFF0A3D1E),
    secondaryContainer = Color(0xFF1E5230),
    onSecondaryContainer = Color(0xFFD7F0DC),
    tertiary = Color(0xFFD3AEDE),
    onTertiary = Color(0xFF2E0B3E),
    tertiaryContainer = Color(0xFF4A2B5B),
    onTertiaryContainer = Color(0xFFF0E3F7),
    background = Color(0xFF10141A),
    onBackground = Color(0xFFE0E2E8),
    surface = Color(0xFF161B22),
    onSurface = Color(0xFFE0E2E8),
    surfaceVariant = Color(0xFF3F454F),
    onSurfaceVariant = Color(0xFFBFC4CD),
    outline = Color(0xFF89909A),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6)
)

/** App-Theme von „Wer Wie Was“ (hell/dunkel) in den Farben des App-Logos. */
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
