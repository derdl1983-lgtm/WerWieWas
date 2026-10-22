package de.derdl1983.weriewas.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Markenfarben des „Wer Wie Was“-Logos. */
val LogoBlue = Color(0xFF1A73E8)
val LogoBlueLight = Color(0xFF4285F4)
val LogoGreen = Color(0xFF34A853)
val LogoOrange = Color(0xFFF9A825)
val LogoViolet = Color(0xFF9B59B6)
val LogoInk = Color(0xFF202124)

/**
 * Das App-Logo, aus den Logo-Elementen in Compose aufgebaut: schwarze Kamera
 * mit blauem Gehirn-Kreis, Lupe mit Fragezeichen und die drei bunten
 * Sprechblasen (Idee, Technik, Info) – darunter der Schriftzug mit blauem „Wie“.
 */
@Composable
fun WerWieWasLogo(
    markSize: Dp = 84.dp,
    wordmarkSize: Int = 30,
    markInk: Color = LogoInk,
    textColor: Color = LogoInk,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        WerWieWasMark(size = markSize, ink = markInk)
        Spacer(Modifier.height(10.dp))
        WerWieWasWordmark(fontSizeSp = wordmarkSize, textColor = textColor)
    }
}

/** Nur das Logo-Symbol (Kamera + Gehirn, Lupe, Sprechblasen) ohne Schriftzug. */
@Composable
fun WerWieWasMark(
    size: Dp = 84.dp,
    ink: Color = LogoInk,
    modifier: Modifier = Modifier
) {
    val bubble = size / 4.2f
    Box(modifier = modifier.size(size + bubble * 0.4f)) {
        // Kamera mit blauem Gehirn-Kreis in der Mitte
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .offset(y = size * 0.06f)
        ) {
            Icon(
                imageVector = Icons.Filled.PhotoCamera,
                contentDescription = null,
                tint = ink,
                modifier = Modifier.size(size)
            )
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(size * 0.44f)
                    .background(LogoBlueLight, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.Psychology,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(size * 0.28f)
                )
            }
        }
        // Lupe mit Fragezeichen, leicht über die Kamera ragend
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .size(size * 0.52f)
        ) {
            Icon(
                imageVector = Icons.Filled.Search,
                contentDescription = null,
                tint = ink,
                modifier = Modifier.fillMaxSize()
            )
            Text(
                text = "?",
                color = LogoBlue,
                fontSize = (size.value * 0.15f).sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .offset(x = size * 0.155f, y = size * 0.135f)
            )
        }
        // Drei Sprechblasen unter der Kamera: Idee, Technik, Info
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .offset(y = bubble * 0.20f),
            horizontalArrangement = Arrangement.spacedBy(size * 0.10f)
        ) {
            LogoBubble(LogoGreen, Icons.Filled.Lightbulb, bubble)
            LogoBubble(LogoOrange, Icons.Filled.Settings, bubble)
            LogoBubble(LogoViolet, Icons.Filled.Info, bubble)
        }
    }
}

/** Eine der drei Sprechblasen des Logos: farbiger Kreis mit weißem Symbol. */
@Composable
private fun LogoBubble(color: Color, icon: ImageVector, size: Dp) {
    Box(
        modifier = Modifier
            .size(size)
            .background(color, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(size * 0.58f)
        )
    }
}

/** Der Schriftzug „Wer Wie Was“ mit blauem „Wie“. */
@Composable
fun WerWieWasWordmark(
    fontSizeSp: Int = 30,
    textColor: Color = LogoInk,
    modifier: Modifier = Modifier
) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = "Wer ",
            fontSize = fontSizeSp.sp,
            fontWeight = FontWeight.Bold,
            color = textColor
        )
        Text(
            text = "Wie",
            fontSize = fontSizeSp.sp,
            fontWeight = FontWeight.Bold,
            color = LogoBlue
        )
        Text(
            text = " Was",
            fontSize = fontSizeSp.sp,
            fontWeight = FontWeight.Bold,
            color = textColor
        )
    }
}
