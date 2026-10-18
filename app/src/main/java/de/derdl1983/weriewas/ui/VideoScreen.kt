package de.derdl1983.weriewas.ui

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import de.derdl1983.weriewas.data.Discovery
import de.derdl1983.weriewas.data.DiscoveryStore
import de.derdl1983.weriewas.util.Images
import java.util.Locale

/** Eine Textkarte des Erklärvideos. */
private data class Slide(val title: String, val text: String, val showPhoto: Boolean = false)

/** Baut die Seiten des Erklärvideos aus einer Entdeckung. */
private fun buildSlides(d: Discovery): List<Slide> = listOf(
    Slide("Das Objekt", d.explanation.title, showPhoto = true),
    Slide("Was ist das?", d.explanation.summary),
    Slide("Wie funktioniert es?", d.explanation.howItWorks),
    Slide("Wofür wird es verwendet?", d.explanation.usage),
    Slide("Praktische Tipps", d.explanation.practical),
    Slide("Interessante Fakten", d.explanation.facts.joinToString("\n") { "• " + it }),
    Slide(
        "Sicherheit",
        d.explanation.safety.ifBlank {
            "Für dieses Objekt sind keine besonderen Sicherheitshinweise nötig."
        }
    ),
    Slide(
        "Weiter lernen",
        "Unter dem Ergebnis findest du echte Links zu Wikipedia, YouTube und weiteren Quellen. " +
            "Viel Spaß beim Entdecken!"
    )
)

/** Erklärvideo: Foto + Textkarten, vorgelesen über die Android-Sprachausgabe. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VideoScreen(
    discoveryId: String,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val discovery = remember(discoveryId) { DiscoveryStore.get(context, discoveryId) }

    if (discovery == null) {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text("Diese Entdeckung wurde nicht gefunden.")
            Spacer(Modifier.height(16.dp))
            Button(onClick = onClose) { Text("Zurück") }
        }
        return
    }

    val slides = remember(discovery.id) { buildSlides(discovery) }
    val bitmap = remember(discovery.id) { Images.loadThumbnail(discovery.imagePath, 720) }
    val mainHandler = remember { Handler(Looper.getMainLooper()) }

    var page by remember { mutableIntStateOf(0) }
    var playing by remember { mutableStateOf(true) }
    var ttsReady by remember { mutableStateOf(false) }
    var tts by remember { mutableStateOf<TextToSpeech?>(null) }

    DisposableEffect(Unit) {
        var engine: TextToSpeech? = null
        engine = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                engine?.let { ttsEngine ->
                    ttsEngine.setLanguage(Locale.GERMANY)
                    ttsEngine.setSpeechRate(0.95f)
                    ttsEngine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onDone(utteranceId: String?) {
                            if (utteranceId?.startsWith("slide_") == true) {
                                mainHandler.post {
                                    if (playing && page < slides.size - 1) {
                                        page += 1
                                    } else if (page >= slides.size - 1) {
                                        playing = false
                                    }
                                }
                            }
                        }

                        override fun onError(utteranceId: String?) {
                            mainHandler.post { playing = false }
                        }

                        override fun onStart(utteranceId: String?) {
                        }
                    })
                    ttsReady = true
                }
            }
        }
        tts = engine
        onDispose {
            engine?.stop()
            engine?.shutdown()
            tts = null
            ttsReady = false
        }
    }

    LaunchedEffect(ttsReady, playing, page) {
        val engine = tts
        if (engine == null || !ttsReady) return@LaunchedEffect
        if (playing) {
            val slide = slides[page]
            engine.speak(
                slide.title + ". " + slide.text,
                TextToSpeech.QUEUE_FLUSH,
                Bundle().apply {
                    putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, "slide_" + page)
                },
                "slide_" + page
            )
        } else {
            engine.stop()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Erklärvideo") },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.Filled.Close, contentDescription = "Schließen")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            if (!ttsReady) {
                CircularProgressIndicator()
                Spacer(Modifier.height(16.dp))
                Text(
                    "Sprachausgabe wird vorbereitet …",
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center
                )
                return@Column
            }

            val slide = slides[page]
            if (slide.showPhoto && bitmap != null) {
                Card(shape = RoundedCornerShape(16.dp)) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = "Foto der Entdeckung",
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(220.dp),
                        contentScale = ContentScale.Crop
                    )
                }
                Spacer(Modifier.height(20.dp))
            }

            Text(
                text = slide.title,
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = slide.text,
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(24.dp))

            LinearProgressIndicator(
                progress = { (page + 1f) / slides.size },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Szene " + (page + 1) + " von " + slides.size,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
            Spacer(Modifier.height(20.dp))

            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(
                    onClick = {
                        page = 0
                        playing = true
                    }
                ) {
                    Icon(Icons.Filled.Replay, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(6.dp))
                    Text("Neu starten")
                }
                Button(
                    onClick = { playing = !playing }
                ) {
                    Icon(
                        imageVector = if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.size(6.dp))
                    Text(if (playing) "Pause" else "Weiter")
                }
            }
            Spacer(Modifier.height(24.dp))
            Text(
                text = "Tipp: Der MP4-Export als Videodatei folgt in einer späteren Version (siehe ROADMAP.md).",
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
            )
        }
    }
}
