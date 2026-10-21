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
    secondary = Color(0xFF4E6355),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD1E9D8),
    onSecondaryContainer = Color(0xFF0C2A1C),
    tertiary = Color(0xFF00696E),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFF9CF1F5),
    onTertiaryContainer = Color(0xFF002022),
    background = Color(0xFFF7FBF2),
    onBackground = Color(0xFF1A1C19),
    surface = Color(0xFFFCFDF7),
    onSurface = Color(0xFF1A1C19),
    surfaceVariant = Color(0xFFE0E9DC),
    onSurfaceVariant = Color(0xFF404942),
    outline = Color(0xFF707971),
    error = Color(0xFFBA1A1A),
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002)
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF8FD68F),
    onPrimary = Color(0xFF00390A),
    primaryContainer = Color(0xFF1B4A1F),
    onPrimaryContainer = Color(0xFFB9F0B7),
    secondary = Color(0xFFB3CCBB),
    onSecondary = Color(0xFF1E3528),
    secondaryContainer = Color(0xFF334B3D),
    onSecondaryContainer = Color(0xFFCFE8D7),
    tertiary = Color(0xFF4CD9DE),
    onTertiary = Color(0xFF003739),
    tertiaryContainer = Color(0xFF004F53),
    onTertiaryContainer = Color(0xFF9CF1F5),
    background = Color(0xFF101510),
    onBackground = Color(0xFFE0E4DD),
    surface = Color(0xFF161C16),
    onSurface = Color(0xFFE0E4DD),
    surfaceVariant = Color(0xFF3F4A3F),
    onSurfaceVariant = Color(0xFFBFC9BF),
    outline = Color(0xFF89938A),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6)
)

/** App-Theme von „Wer Wie Was“ (hell/dunkel, frisches Grün mit Petrol-Akzent). */
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
