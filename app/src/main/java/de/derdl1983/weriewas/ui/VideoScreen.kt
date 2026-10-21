package de.derdl1983.weriewas.ui

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.derdl1983.weriewas.data.Discovery
import de.derdl1983.weriewas.data.DiscoveryStore
import de.derdl1983.weriewas.util.EdgeTTS
import de.derdl1983.weriewas.util.Images
import de.derdl1983.weriewas.util.VideoExporter
import de.derdl1983.weriewas.util.WebImages
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/** Eine Textkarte des Erklärvideos mit passendem Themenbild-Zeichen. */
private data class Slide(val title: String, val text: String, val emoji: String, val showPhoto: Boolean = false)

private data class SlidePalette(val top: Color, val bottom: Color)

private val slidePalettes = listOf(
    SlidePalette(Color(0xFF1A237E), Color(0xFF3949AB)),
    SlidePalette(Color(0xFF00695C), Color(0xFF00897B)),
    SlidePalette(Color(0xFF4A148C), Color(0xFF6A1B9A)),
    SlidePalette(Color(0xFF0277BD), Color(0xFF0288D1)),
    SlidePalette(Color(0xFF283593), Color(0xFF3F51B5)),
    SlidePalette(Color(0xFF33691E), Color(0xFF558B2F)),
    SlidePalette(Color(0xFFBF360C), Color(0xFFD84315)),
    SlidePalette(Color(0xFF37474F), Color(0xFF455A64))
)

/** Wandelt die Geschwindigkeit (0.75x-2x) in eine Edge-TTS-Prozentangabe um. */
private fun ratePercent(speed: Float): Int =
    ((speed - 1f) * 100f).toInt().coerceIn(-50, 100)

/** Baut die Seiten des Erklärvideos aus einer Entdeckung. */
private fun buildSlides(d: Discovery): List<Slide> = listOf(
    Slide("Das Objekt", d.explanation.title, "📸", showPhoto = true),
    Slide("Was ist das?", d.explanation.summary, "❓"),
    Slide("Wie funktioniert es?", d.explanation.howItWorks, "⚙️"),
    Slide("Wofür wird es verwendet?", d.explanation.usage, "🎯"),
    Slide("Praktische Tipps", d.explanation.practical, "💡"),
    Slide("Interessante Fakten", d.explanation.facts.joinToString("\n") { "• " + it }, "🌟"),
    Slide(
        "Sicherheit",
        d.explanation.safety.ifBlank {
            "Für dieses Objekt sind keine besonderen Sicherheitshinweise nötig."
        },
        "⚠️"
    ),
    Slide(
        "Weiter lernen",
        "Unter dem Ergebnis findest du echte Links zu Wikipedia, YouTube und weiteren Quellen. " +
            "Viel Spaß beim Entdecken!",
        "🎓"
    )
)

/** Erklärvideo: Foto, Themenbilder und Textkarten mit mehreren Stimmen (System oder Edge-TTS), Geschwindigkeit und MP4-Download. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VideoScreen(
    discoveryId: String,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
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

    // Passende, frei lizenzierte Online-Themenbilder je Szene (falls online verfügbar)
    val webImages = remember(discovery.id) { mutableStateOf<List<WebImages.WebImage?>?>(null) }
    LaunchedEffect(discovery.id) {
        webImages.value = null
        val queries = WebImages.queriesForSlides(
            discovery.explanation.title,
            slides.map { it.title },
            slides.map { it.text }
        )
        webImages.value = WebImages.findByQueries(context, queries)
    }

    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    val prefs = remember { context.getSharedPreferences("settings", Context.MODE_PRIVATE) }
    val speedLabels = remember {
        mapOf(0.75f to "0,75x", 1f to "1x", 1.25f to "1,25x", 1.5f to "1,5x", 2f to "2x")
    }

    // Gratis Online-Stimmen (Edge-TTS) aus den Einstellungen
    val edgeA = remember { prefs.getString("edge_voice_a", "") ?: "" }
    val edgeB = remember { prefs.getString("edge_voice_b", "") ?: "" }
    val useEdge = edgeA.isNotBlank() || edgeB.isNotBlank()

    var page by remember { mutableIntStateOf(0) }
    var playing by remember { mutableStateOf(true) }
    var ttsReady by remember { mutableStateOf(false) }
    var ttsFailed by remember { mutableStateOf(false) }
    var tts by remember { mutableStateOf<TextToSpeech?>(null) }
    var voiceA by remember { mutableStateOf<Voice?>(null) }
    var voiceB by remember { mutableStateOf<Voice?>(null) }
    var dualVoices by remember { mutableStateOf(false) }
    var speed by remember { mutableStateOf(1f) }
    var exporting by remember { mutableStateOf(false) }
    var exportProgress by remember { mutableStateOf("") }
    var exportMessage by remember { mutableStateOf<String?>(null) }
    var exportedUri by remember { mutableStateOf<Uri?>(null) }
    var exportedSrt by remember { mutableStateOf<Uri?>(null) }
    var videoFormat by rememberSaveable {
        mutableStateOf(prefs.getString("video_format", "portrait") ?: "portrait")
    }
    var wantSrt by rememberSaveable { mutableStateOf(prefs.getBoolean("video_srt", true)) }
    val formatLabels = listOf(
        "portrait" to "Hochformat",
        "landscape720" to "Quer 720p",
        "landscape1080" to "Quer 1080p"
    )
    var player by remember { mutableStateOf<MediaPlayer?>(null) }
    var edgeLoading by remember { mutableStateOf(false) }
    var edgeError by remember { mutableStateOf<String?>(null) }

    fun speakCurrent(engine: TextToSpeech) {
        val slide = slides[page]
        engine.setSpeechRate(speed * 0.95f)
        if (dualVoices) {
            val second = page % 2 == 1
            val va = voiceA
            val vb = voiceB
            if (second) {
                if (vb != null) {
                    engine.setVoice(vb)
                    engine.setPitch(1f)
                } else {
                    if (va != null) engine.setVoice(va)
                    engine.setPitch(0.8f)
                }
            } else {
                if (va != null) engine.setVoice(va)
                engine.setPitch(1f)
            }
        } else {
            engine.setPitch(1f)
        }
        engine.speak(
            slide.title + ". " + slide.text,
            TextToSpeech.QUEUE_FLUSH,
            Bundle().apply {
                putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, "slide_" + page)
            },
            "slide_" + page
        )
    }

    DisposableEffect(Unit) {
        var engine: TextToSpeech? = null
        engine = try {
            TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                engine?.let { ttsEngine ->
                    ttsEngine.setLanguage(Locale.GERMANY)
                    val germanVoices = try {
                        ttsEngine.voices?.filter { it.locale.language == "de" } ?: emptyList()
                    } catch (e: Exception) {
                        emptyList<Voice>()
                    }
                    val sorted = germanVoices.sortedBy { it.name }
                    if (sorted.isNotEmpty()) {
                        val nameA = prefs.getString("tts_voice_a", null)
                        val nameB = prefs.getString("tts_voice_b", null)
                        voiceA = sorted.firstOrNull { it.name == nameA } ?: sorted[0]
                        voiceB = sorted.firstOrNull { it.name == nameB } ?: sorted.getOrNull(1)
                        dualVoices = sorted.size > 1
                    }
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
            } else {
                ttsFailed = true
            }
            }
        } catch (e: Exception) {
            ttsFailed = true
            null
        }
        tts = engine
        onDispose {
            engine?.stop()
            engine?.shutdown()
            tts = null
            ttsReady = false
        }
    }

    // Zeitüberschreitung, falls die System-Sprachausgabe nie bereit wird
    LaunchedEffect(ttsReady) {
        if (!useEdge && !ttsReady) {
            delay(20000)
            if (!ttsReady) ttsFailed = true
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            player?.release()
            player = null
        }
    }

    LaunchedEffect(ttsReady, playing, page, speed, dualVoices) {
        val engine = tts
        if (engine == null || !ttsReady) return@LaunchedEffect
        if (useEdge) return@LaunchedEffect
        if (playing) {
            speakCurrent(engine)
        } else {
            engine.stop()
        }
    }

    // Wiedergabe über gratis Edge-TTS-Online-Stimmen
    LaunchedEffect(useEdge, playing, page, speed) {
        if (!useEdge) return@LaunchedEffect
        val old = player
        old?.stop()
        old?.release()
        player = null
        if (!playing) return@LaunchedEffect
        val slide = slides[page]
        val voice = if (page % 2 == 1) {
            if (edgeB.isNotBlank()) edgeB else edgeA
        } else {
            if (edgeA.isNotBlank()) edgeA else edgeB
        }
        val rp = ratePercent(speed)
        edgeLoading = true
        edgeError = null
        try {
            val mp3 = withContext(Dispatchers.IO) {
                val f = File(context.cacheDir, "www_edge_" + discovery.id + "_" + page + "_" + rp + ".mp3")
                if (!f.exists() || f.length() < 200L) {
                    EdgeTTS.synthesizeToMp3(slide.title + ". " + slide.text, voice, rp, f)
                }
                f
            }
            if (playing) {
                val p = MediaPlayer()
                p.setDataSource(mp3.absolutePath)
                p.prepare()
                p.setOnCompletionListener {
                    if (playing && page < slides.size - 1) {
                        page += 1
                    } else {
                        playing = false
                    }
                }
                player = p
                p.start()
            }
        } catch (e: Exception) {
            edgeError = "Online-Stimme nicht erreichbar: " + (e.message ?: "unbekannter Fehler")
            playing = false
        } finally {
            edgeLoading = false
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
            if (!ttsReady && !useEdge) {
                if (ttsFailed) {
                    Text(
                        "Die Sprachausgabe dieses Geräts ist nicht verfügbar. " +
                            "Installiere eine Sprachausgabe (z. B. Google-Sprachausgabe) " +
                            "oder wähle in den Einstellungen eine kostenlose Online-Stimme.",
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.error
                    )
                    Spacer(Modifier.height(16.dp))
                    OutlinedButton(onClick = onClose) { Text("Zurück") }
