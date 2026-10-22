package de.derdl1983.weriewas.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.draw.clip
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
import de.derdl1983.weriewas.ml.OnlineAnalyzer
import de.derdl1983.weriewas.util.EdgeTTS
import de.derdl1983.weriewas.util.ExportBus
import de.derdl1983.weriewas.util.ExportService
import de.derdl1983.weriewas.util.Images
import de.derdl1983.weriewas.util.VideoExporter
import de.derdl1983.weriewas.util.WebImages
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Locale

/** Eine Textkarte des Erklärvideos mit passendem Themenbild-Zeichen. */
private data class Slide(val title: String, val text: String, val emoji: String, val showPhoto: Boolean = false)

private data class SlidePalette(val top: Color, val bottom: Color)

/** Marken-Palette: dieselben Navy-Töne wie im exportierten Video (pro Szene dezent variiert). */
private val slidePalettes = listOf(
    SlidePalette(Color(0xFF0A1F4E), Color(0xFF1A50A8)),
    SlidePalette(Color(0xFF0B2A5B), Color(0xFF1565C0)),
    SlidePalette(Color(0xFF091E45), Color(0xFF283593)),
    SlidePalette(Color(0xFF0D2B52), Color(0xFF0F6BA8)),
    SlidePalette(Color(0xFF101F4C), Color(0xFF2547A8)),
    SlidePalette(Color(0xFF0A2450), Color(0xFF1E6FA8)),
    SlidePalette(Color(0xFF0C1E4E), Color(0xFF2C5FB4)),
    SlidePalette(Color(0xFF0E2A54), Color(0xFF1A5FA8))
)

/** Wandelt die Geschwindigkeit (0.75x-2x) in eine Edge-TTS-Prozentangabe um. */
private fun ratePercent(speed: Float): Int =
    ((speed - 1f) * 100f).toInt().coerceIn(-50, 100)

/**
 * Baut die Seiten des Erklärvideos aus einer Entdeckung. Die Szenenauswahl
 * passt sich der gewählten Ziellänge an: "30" kompakt (wenige Szenen),
 * "60"/"auto" der Standard-Umfang, "90" mit eigenen Fakten-Szenen für
 * ausführliche Erklärungen. Die tatsächliche Dauer folgt der Sprechzeit.
 */
private fun buildSlides(d: Discovery, length: String): List<Slide> {
    val e = d.explanation
    val continueSlide = Slide(
        "Weiter lernen",
        "Unter dem Ergebnis findest du echte Links zu Wikipedia, YouTube und weiteren Quellen. " +
            "Viel Spaß beim Entdecken!",
        "🎓"
    )
    if (length == "30") {
        // Kompakt: das Wichtigste in wenigen Szenen (~30 Sekunden Sprechzeit)
        val slides = mutableListOf(
            Slide("Das Objekt", e.title, "📸", showPhoto = true),
            Slide("Was ist das?", e.summary, "❓"),
            Slide(
                "Gut zu wissen",
                listOfNotNull(
                    e.practical.ifBlank { null },
                    e.facts.firstOrNull()?.let { "• " + it }
                ).joinToString("\n\n"),
                "💡"
            )
        )
        if (e.safety.isNotBlank()) slides.add(Slide("Sicherheit", e.safety, "⚠️"))
        slides.add(continueSlide)
        return slides
    }
    val slides = mutableListOf(
        Slide("Das Objekt", e.title, "📸", showPhoto = true),
        Slide("Was ist das?", e.summary, "❓"),
        Slide("Wie funktioniert es?", e.howItWorks, "⚙️"),
        Slide("Wofür wird es verwendet?", e.usage, "🎯"),
        Slide("Praktische Tipps", e.practical, "💡")
    )
    if (length == "90" && e.facts.isNotEmpty()) {
        // Ausführlich: jeder Faktenpunkt bekommt eine eigene Szene (~90 Sekunden)
        e.facts.forEachIndexed { i, f -> slides.add(Slide("Fakt " + (i + 1), f, "🌟")) }
    } else {
        slides.add(Slide("Interessante Fakten", e.facts.joinToString("\n") { "• " + it }, "🌟"))
    }
    slides.add(
        Slide(
            "Sicherheit",
            e.safety.ifBlank {
                "Für dieses Objekt sind keine besonderen Sicherheitshinweise nötig."
            },
            "⚠️"
        )
    )
    slides.add(continueSlide)
    return slides
}

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

    val prefs = remember { context.getSharedPreferences("settings", Context.MODE_PRIVATE) }

    // Ziel-Länge des Videos: steuert die Szenenauswahl (und die Textlänge
    // neuer Foto-Analysen über das KI-Profil).
    var videoLength by rememberSaveable {
        mutableStateOf(prefs.getString("video_length", "auto") ?: "auto")
    }
    val slides = remember(discovery.id, videoLength) { buildSlides(discovery, videoLength) }
    val bitmap = remember(discovery.id) { Images.loadThumbnail(discovery.imagePath, 720) }

    // Passende, frei lizenzierte Online-Themenbilder je Szene (falls online verfügbar)
    val webImages = remember(discovery.id, videoLength) { mutableStateOf<List<WebImages.WebImage?>?>(null) }
    LaunchedEffect(discovery.id, slides) {
        webImages.value = null
        val queries = WebImages.queriesForSlides(
            discovery.explanation.title,
            slides.map { it.title },
            slides.map { it.text }
        )
        webImages.value = WebImages.findByQueries(context, queries)
    }

    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    val speedLabels = remember {
        mapOf(0.75f to "0,75x", 1f to "1x", 1.25f to "1,25x", 1.5f to "1,5x", 2f to "2x")
    }

    // Gratis Online-Stimmen (Edge-TTS) aus den Einstellungen. Sie folgen
    // automatisch der Antwort-Sprache des KI-Profils (z. B. Englisch im
    // Sprachenlernen-Profil), ohne neue Stimmen wählen zu müssen.
    val aiLang = remember { prefs.getString("ai_language", "de") ?: "de" }
    val edgeA = remember(aiLang) {
        EdgeTTS.resolveVoice(prefs.getString("edge_voice_a", "") ?: "", aiLang, 0)
    }
    val edgeB = remember(aiLang) {
        EdgeTTS.resolveVoice(prefs.getString("edge_voice_b", "") ?: "", aiLang, 1)
    }
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
    // Export läuft in einem Vordergrund-Dienst weiter – auch bei geschlossener App.
    // Fortschritt und Ergebnis liegen global im ExportBus und überleben das
    // Verlassen und erneute Öffnen des Screens.
    val exporting = ExportBus.running
    val exportFrac = ExportBus.frac
    val exportProgress = ExportBus.pctText
    val busResult = if (ExportBus.resultId == discovery.id) ExportBus.result else null
    val exportMessage = busResult?.let { r ->
        if (r.shareUri != null) r.message + " Das Video liegt dauerhaft im Video-Archiv auf der Startseite." else r.message
    }
    val exportedUri = busResult?.shareUri
    val exportedSrt = busResult?.srtUri
    val exportedThumb = busResult?.thumbUri
    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    var videoFormat by rememberSaveable {
        mutableStateOf(prefs.getString("video_format", "portrait") ?: "portrait")
    }
    var wantSrt by rememberSaveable { mutableStateOf(prefs.getBoolean("video_srt", true)) }
    var burnedSubs by rememberSaveable { mutableStateOf(prefs.getBoolean("video_burn_subs", false)) }
    var wantMusic by rememberSaveable { mutableStateOf(prefs.getBoolean("video_music", true)) }
    var musicMood by rememberSaveable {
        mutableStateOf(prefs.getString("music_mood", "neugierig") ?: "neugierig")
    }
    val formatLabels = listOf(
        "portrait" to "Hochformat",
        "landscape720" to "Quer 720p",
        "landscape1080" to "Quer 1080p"
    )
    var player by remember { mutableStateOf<MediaPlayer?>(null) }
    var edgeLoading by remember { mutableStateOf(false) }
    var edgeError by remember { mutableStateOf<String?>(null) }

    // Audio-Export: nur die gesprochene Erklärung, ohne Video
    var audioBusy by remember { mutableStateOf(false) }
    var audioError by remember { mutableStateOf<String?>(null) }
    var audioResult by remember { mutableStateOf<VideoExporter.AudioExportResult?>(null) }
    var audioPlaying by remember { mutableStateOf(false) }
    var audioPlayer by remember { mutableStateOf<MediaPlayer?>(null) }
    // Geschätzte Länge der aktuellen Szenenauswahl: Wortzahl → Sprechzeit
    // (ca. 2,6 Wörter pro Sekunde) plus Szenen-Pausen, Intro und Outro.
    val estSeconds = remember(slides, speed) {
        val words = slides.sumOf { s ->
            (s.title + " " + s.text).split(Regex("\\s+")).count { it.isNotBlank() }
        }
        (words / (2.6f * speed)).toInt() + slides.size * 2 + 5
    }

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
            EdgeTTS.speakableText(slide.title) + ". " + EdgeTTS.speakableText(slide.text),
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
                    // Sprachausgabe folgt der Antwort-Sprache des KI-Profils;
                    // sind dafür keine Stimmen installiert, greifen die
                    // deutschen Stimmen als Fallback.
                    ttsEngine.setLanguage(Locale.forLanguageTag(aiLang))
                    val allVoices = try {
                        ttsEngine.voices ?: emptyList<Voice>()
                    } catch (e: Exception) {
                        emptyList<Voice>()
                    }
                    val langVoices = allVoices.filter { it.locale.language == aiLang }
                    val picked = if (langVoices.isNotEmpty()) langVoices else allVoices.filter { it.locale.language == "de" }
                    val sorted = picked.sortedBy { it.name }
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
            audioPlayer?.release()
            audioPlayer = null
            audioPlaying = false
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
                // Cache-Name enthält die Stimme, damit nach einem Sprachwechsel
                // im KI-Profil keine alte Sprachversion abgespielt wird.
                val f = File(context.cacheDir, "www_edge_s_" + discovery.id + "_" + page + "_" + rp + "_" + voice.hashCode() + ".mp3")
                if (!f.exists() || f.length() < 200L) {
                    EdgeTTS.synthesizeToMp3(slide.title + ". " + slide.text, voice, rp, f, ssml = true)
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
                } else {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(16.dp))
                    Text(
                        "Sprachausgabe wird vorbereitet …",
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center
                    )
                }
                return@Column
            }

            val slide = slides[page]
            val palette = slidePalettes[page % slidePalettes.size]

            // Vorschau im Design des fertigen Videos: dieselben Navy-Töne,
            // Szenen-Chip, Wortmarke, Glas-Panel mit Titel & Text und
            // Fortschrittsbalken wie im Export – so sieht man vor dem
            // Speichern genau, wie das Video aussehen wird.
            val previewBmp = if (slide.showPhoto) {
                bitmap
            } else {
                val wi = webImages.value?.getOrNull(page)
                if (wi != null) Images.loadThumbnail(wi.filePath, 900) else null
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(if (videoFormat.startsWith("landscape")) 16f / 9f else 9f / 16f)
                    .clip(RoundedCornerShape(16.dp))
                    .background(
                        brush = Brush.verticalGradient(listOf(palette.top, palette.bottom))
                    )
            ) {
                if (previewBmp != null) {
                    Image(
                        bitmap = previewBmp.asImageBitmap(),
                        contentDescription = if (slide.showPhoto) "Foto der Entdeckung" else "Themenbild (frei lizenziert)",
                        modifier = Modifier
                            .fillMaxWidth()
                            .fillMaxHeight(0.55f)
                            .align(Alignment.TopCenter),
                        contentScale = ContentScale.Crop
                    )
                    // Weiche Abdunklung am Bildrand, wie die Vignette im Video
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .fillMaxHeight(0.55f)
                            .align(Alignment.TopCenter)
                            .background(
                                Brush.verticalGradient(listOf(Color.Transparent, Color(0x66000000)))
                            )
                    )
                } else if (slide.emoji.isNotBlank()) {
                    Text(
                        slide.emoji,
                        fontSize = 56.sp,
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(top = 24.dp)
                    )
                }
                // Szenen-Chip (wie im Video)
                Text(
                    "Szene " + (page + 1) + "/" + slides.size,
                    color = Color.White.copy(alpha = 0.9f),
                    fontSize = 12.sp,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 10.dp)
                        .background(Color(0x33000000), RoundedCornerShape(12.dp))
                        .padding(horizontal = 10.dp, vertical = 3.dp)
                )
                // Wortmarke (wie im Video)
                Text(
                    "Wer Wie Was",
                    color = Color.White.copy(alpha = 0.75f),
                    fontSize = 11.sp,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 10.dp, end = 12.dp)
                )
                // Glas-Panel mit Titel & Text (wie die Textebene im Video)
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(14.dp)
                        .background(Color(0xCC060A1A), RoundedCornerShape(20.dp))
                        .padding(horizontal = 16.dp, vertical = 14.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .width(56.dp)
                            .height(4.dp)
                            .background(Color(0xFF4285F4), RoundedCornerShape(2.dp))
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = slide.title,
                        color = Color.White,
                        style = MaterialTheme.typography.titleMedium,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (slide.text.isNotBlank()) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = slide.text,
                            color = Color.White,
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
                // Fortschrittsbalken (wie im Video)
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .height(5.dp)
                        .background(Color(0x28FFFFFF))
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .fillMaxWidth((page + 1f) / slides.size)
                            .background(Color(0xFF4285F4))
                    )
                }
            }
            // Quellenzeile für frei lizenzierte Themenbilder (wie im Video)
            if (!slide.showPhoto) {
                val wi = webImages.value?.getOrNull(page)
                if (wi != null) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "Bild: " + wi.credit,
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
            Spacer(Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = { if (page > 0) page -= 1 },
                    enabled = page > 0
                ) { Text("⏮") }
                Slider(
                    value = page.toFloat(),
                    onValueChange = { page = it.toInt().coerceIn(0, slides.size - 1) },
                    valueRange = 0f..(slides.size - 1).toFloat(),
                    steps = (slides.size - 2).coerceAtLeast(0),
                    modifier = Modifier.weight(1f)
                )
                OutlinedButton(
                    onClick = { if (page < slides.size - 1) page += 1 },
                    enabled = page < slides.size - 1
                ) { Text("⏭") }
            }
            Spacer(Modifier.height(12.dp))

            if (edgeLoading) {
                Text(
                    "Online-Stimme wird geladen …",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
                Spacer(Modifier.height(12.dp))
            }
            val edgeErrMsg = edgeError
            if (edgeErrMsg != null) {
                Text(
                    edgeErrMsg,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(12.dp))
            }

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
            Spacer(Modifier.height(16.dp))

            if (useEdge) {
                Text(
                    text = "Online-Stimmen aktiv (Edge-TTS, gratis, ohne API-Schlüssel): Moderator " +
                        EdgeTTS.labelFor(if (edgeA.isNotBlank()) edgeA else edgeB) +
                        ", Erklärbär " + EdgeTTS.labelFor(if (edgeB.isNotBlank()) edgeB else edgeA) +
                        "." +
                        (if (aiLang != "de") " Die Stimmen folgen der Profil-Sprache " + OnlineAnalyzer.languageName(aiLang) + "." else "") +
                        " Benötigt Internet.",
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            } else {
                OutlinedButton(
                    onClick = {
                        dualVoices = !dualVoices
                    },
                    colors = if (dualVoices) {
                        ButtonDefaults.outlinedButtonColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer
                        )
                    } else {
                        ButtonDefaults.outlinedButtonColors()
                    }
                ) {
                    Text(if (dualVoices) "Zwei Stimmen: abwechselnd" else "Zwei Stimmen: aus")
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Moderator und Erklärbär wechseln sich pro Szene ab. " +
                        "Die Stimmen kannst du in den Einstellungen wählen." +
                        (if (voiceB == null) " Auf diesem Gerät ist nur eine Stimme installiert – die zweite wird dann über die Tonhöhe simuliert." else "") +
                        (if (aiLang != "de") " Fehlt eine Stimme für die Profil-Sprache " + OnlineAnalyzer.languageName(aiLang) + ", greift die deutsche Sprachausgabe." else ""),
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                )
            }
            Spacer(Modifier.height(16.dp))

            Text(
                "Geschwindigkeit",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(8.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                speedLabels.entries.forEach { entry ->
                    OutlinedButton(
                        onClick = { speed = entry.key },
                        colors = if (speed == entry.key) {
                            ButtonDefaults.outlinedButtonColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer
                            )
                        } else {
                            ButtonDefaults.outlinedButtonColors()
                        }
                    ) {
                        Text(entry.value)
                    }
                }
            }
            Spacer(Modifier.height(20.dp))

            Text(
                "Ziel-Länge des Videos",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(8.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                listOf(
                    "auto" to "Automatik",
                    "30" to "~30 s",
                    "60" to "~60 s",
                    "90" to "~90 s"
                ).forEach { (key, label) ->
                    OutlinedButton(
                        onClick = {
                            videoLength = key
                            prefs.edit().putString("video_length", key).apply()
                            page = page.coerceIn(0, slides.size - 1)
                        },
                        colors = if (videoLength == key) {
                            ButtonDefaults.outlinedButtonColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer
                            )
                        } else {
                            ButtonDefaults.outlinedButtonColors()
                        }
                    ) {
                        Text(label)
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Weniger Szenen für kurze Videos, eigene Fakten-Szenen für lange. " +
                    "Aktuell geschätzte Länge der Auswahl: ca. " + estSeconds + " Sekunden" +
                    (if (videoLength == "30" || videoLength == "90")
                        " – neue Foto-Analysen schreibt die KI dann von sich aus passend dazu."
                    else "") + ".",
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
            Spacer(Modifier.height(20.dp))

            Text(
                "Videoformat",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(8.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                formatLabels.forEach { (key, label) ->
                    OutlinedButton(
                        onClick = {
                            videoFormat = key
                            prefs.edit().putString("video_format", key).apply()
                        },
                        colors = if (videoFormat == key) {
                            ButtonDefaults.outlinedButtonColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer
                            )
                        } else {
                            ButtonDefaults.outlinedButtonColors()
                        }
                    ) {
                        Text(label)
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Switch(
                    checked = wantMusic,
                    onCheckedChange = {
                        wantMusic = it
                        prefs.edit().putBoolean("video_music", it).apply()
                    }
                )
                Text("Hintergrundmusik (dezent, automatisch leiser unter der Stimme)", style = MaterialTheme.typography.bodyMedium)
            }
            if (wantMusic) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "Musik-Stimmung",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )
                Spacer(Modifier.height(4.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    listOf(
                        "neugierig" to "Neugierig",
                        "froehlich" to "Fröhlich",
                        "ruhig" to "Ruhig"
                    ).forEach { (key, label) ->
                        OutlinedButton(
                            onClick = {
                                musicMood = key
                                prefs.edit().putString("music_mood", key).apply()
                            },
                            colors = if (musicMood == key) {
                                ButtonDefaults.outlinedButtonColors(
                                    containerColor = MaterialTheme.colorScheme.primaryContainer
                                )
                            } else {
                                ButtonDefaults.outlinedButtonColors()
                            }
                        ) {
                            Text(label, style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Switch(
                    checked = burnedSubs,
                    onCheckedChange = {
                        burnedSubs = it
                        prefs.edit().putBoolean("video_burn_subs", it).apply()
                    }
                )
                Text("Untertitel ins Video einbrennen (TikTok-Stil)", style = MaterialTheme.typography.bodyMedium)
            }
            Spacer(Modifier.height(6.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Switch(
                    checked = wantSrt,
                    onCheckedChange = {
                        wantSrt = it
                        prefs.edit().putBoolean("video_srt", it).apply()
                    }
                )
                Text("Untertitel zusätzlich als SRT-Datei speichern", style = MaterialTheme.typography.bodyMedium)
            }
            Spacer(Modifier.height(20.dp))

            if (ttsFailed && !useEdge) {
                Text(
                    "Keine Sprachausgabe auf diesem Gerät gefunden – das Video wird ohne Ton exportiert. " +
                        "Für natürliche Stimmen einmal kostenlos Edge-TTS in den Einstellungen einrichten.",
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.error
                )
                Spacer(Modifier.height(12.dp))
            }
            Button(
                onClick = {
                    // Export startet als Vordergrund-Dienst und läuft weiter,
                    // auch wenn die App geschlossen wird.
                    playing = false
                    if (Build.VERSION.SDK_INT >= 33 &&
                        context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
                        PackageManager.PERMISSION_GRANTED
                    ) {
                        notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                    val arr = JSONArray()
                    val webList = webImages.value
                    slides.forEachIndexed { i, s ->
                        val wi = if (s.showPhoto) null else webList?.getOrNull(i)
                        arr.put(
                            JSONObject()
                                .put("title", s.title)
                                .put("text", s.text)
                                .put("emoji", s.emoji)
                                .put("photo", if (s.showPhoto) (discovery.imagePath ?: "") else "")
                                .put("web", wi?.filePath ?: "")
                                .put("credit", wi?.credit ?: "")
                        )
                    }
                    ExportService.start(
                        context,
                        discovery.id,
                        arr.toString(),
                        videoFormat,
                        wantSrt,
                        burnedSubs,
                        wantMusic,
                        musicMood,
                        speed,
                        useEdge,
                        edgeA,
                        edgeB,
                        voiceA?.name ?: "",
                        voiceB?.name ?: "",
                        aiLang
                    )
                },
                enabled = !exporting
            ) {
                if (exporting) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.size(8.dp))
                    Text("Video wird erstellt …")
                } else {
                    Text("Als Video speichern (MP4)")
                }
            }
            if (exporting) {
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { exportFrac },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "Fortschritt: " + (if (exportProgress.isBlank()) "0 %" else exportProgress) +
                        " – Text, Szenen, Intro & Outro werden eingebaut. Die App kann geschlossen werden, der Export läuft im Hintergrund weiter.",
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )
            }

            // Audio-Export: nur die gesprochene Erklärung als Audiodatei –
            // zum Anhören und Teilen ganz ohne Video-Export.
            Spacer(Modifier.height(12.dp))
            OutlinedButton(
                onClick = {
                    playing = false
                    audioBusy = true
                    audioError = null
                    scope.launch {
                        try {
                            val result = withContext(Dispatchers.IO) {
                                exportAudioOnly(
                                    context, discovery, slides, speed,
                                    useEdge, edgeA, edgeB, voiceA, voiceB, aiLang
                                )
                            }
                            audioResult = result
                        } catch (e: Exception) {
                            audioError = "Audio-Export fehlgeschlagen: " + (e.message ?: "unbekannter Fehler")
                        } finally {
                            audioBusy = false
                        }
                    }
                },
                enabled = !audioBusy && !exporting,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (audioBusy) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.size(8.dp))
                    Text("Audio wird erstellt …")
                } else {
                    Text("Nur Audio speichern (Erklärung ohne Video)")
                }
            }
            val audioErrMsg = audioError
            if (audioErrMsg != null) {
                Spacer(Modifier.height(6.dp))
                Text(
                    audioErrMsg,
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.error
                )
            }
            val audio = audioResult
            if (audio != null && audio.shareUri != null) {
                val aUri = audio.shareUri
                Spacer(Modifier.height(8.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(
                        onClick = {
                            val p = audioPlayer
                            if (p != null) {
                                p.stop()
                                p.release()
                                audioPlayer = null
                                audioPlaying = false
                            } else {
                                runCatching {
                                    val np = MediaPlayer()
                                    np.setDataSource(context, aUri)
                                    np.prepare()
                                    np.setOnCompletionListener {
                                        audioPlaying = false
                                        runCatching { np.release() }
                                        if (audioPlayer === np) audioPlayer = null
                                    }
                                    np.start()
                                    audioPlayer = np
                                    audioPlaying = true
                                }.onFailure {
                                    audioError = "Audio konnte nicht abgespielt werden: " +
                                        (it.message ?: "unbekannter Fehler")
                                }
                            }
                        },
                        modifier = Modifier.weight(1f)
                    ) { Text(if (audioPlaying) "Audio stoppen" else "Audio anhören") }
                    OutlinedButton(
                        onClick = { shareAudio(context, aUri, audio.mime) },
                        modifier = Modifier.weight(1f)
                    ) { Text("Audio teilen …") }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    audio.message,
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }
            val message = exportMessage
            if (message != null) {
                Spacer(Modifier.height(12.dp))
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            val vUri = exportedUri
            if (vUri != null) {
                Spacer(Modifier.height(16.dp))
                Text(
                    "Video hochladen & teilen:",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.height(4.dp))
                Button(
                    onClick = { shareVideo(context, vUri, "com.google.android.youtube") },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Auf YouTube hochladen") }
                Spacer(Modifier.height(6.dp))
                Button(
                    onClick = { shareVideo(context, vUri, "com.zhiliaoapp.musically") },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Auf TikTok hochladen") }
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { shareVideo(context, vUri, "com.instagram.android") },
                        modifier = Modifier.weight(1f)
                    ) { Text("Instagram") }
                    OutlinedButton(
                        onClick = { shareVideo(context, vUri, "com.whatsapp") },
                        modifier = Modifier.weight(1f)
                    ) { Text("WhatsApp") }
                }
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { shareVideo(context, vUri, "org.telegram.messenger") },
                        modifier = Modifier.weight(1f)
                    ) { Text("Telegram") }
                    OutlinedButton(
                        onClick = { shareVideo(context, vUri, "com.facebook.katana") },
                        modifier = Modifier.weight(1f)
                    ) { Text("Facebook") }
                }
                Spacer(Modifier.height(6.dp))
                OutlinedButton(
                    onClick = { shareVideo(context, vUri, null) },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Video teilen … (alle Apps)") }
                val tUri = exportedThumb
                if (tUri != null) {
                    Spacer(Modifier.height(6.dp))
                    OutlinedButton(
                        onClick = { shareThumb(context, tUri) },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Thumbnail (Cover) teilen …") }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Das automatisch erstellte Cover (Bilder/WerWieWas) eignet sich als Thumbnail für YouTube, TikTok & Instagram.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    "YouTube und TikTok öffnen ihren Upload mit dem Video als Entwurf – " +
                        "Titel, Beschreibung und Veröffentlichen legst du dort fest.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
                val sUri = exportedSrt
                if (sUri != null) {
                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(
                        onClick = { shareSrt(context, sUri) },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Untertitel (SRT) teilen") }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Die Untertitel-Datei liegt im Download-Ordner und eignet sich " +
                            "für YouTube-Uploads und barrierefreie Untertitel.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/**
 * Exportiert nur die gesprochene Erklärung als Audiodatei: Bei Edge-Stimmen
 * werden die fertigen Szenen-MP3s direkt aneinandergereiht (echtes MP3, ohne
 * Qualitätsverlust – die Dateien teilt der Audio-Export mit dem Video-Export,
 * sodass beides gegenseitig vom Cache profitiert). Bei System-Stimmen entsteht
 * über die Android-Sprachausgabe eine normalisierte WAV-Datei.
 */
private fun exportAudioOnly(
    context: Context,
    discovery: Discovery,
    slides: List<Slide>,
    speed: Float,
    useEdge: Boolean,
    edgeA: String,
    edgeB: String,
    voiceA: Voice?,
    voiceB: Voice?,
    speechLang: String
): VideoExporter.AudioExportResult {
    // Dateiname: Objektname (gesäubert) + Entdeckungs-ID
    val cleanTitle = discovery.explanation.title
        .replace(Regex("[^A-Za-z0-9ÄÖÜäöüß .-]"), "")
        .trim().take(40)
    val base = ("WerWieWas-" + cleanTitle + "-" + discovery.id.take(8))
        .replace(Regex("\\s+"), "-")
    if (useEdge) {
        val vA = if (edgeA.isNotBlank()) edgeA else edgeB
        val vB = if (edgeB.isNotBlank()) edgeB else edgeA
        val rp = ratePercent(speed)
        val out = ByteArrayOutputStream()
        slides.forEachIndexed { i, s ->
            val voice = if (i % 2 == 1) vB else vA
            val mp3 = File(context.cacheDir, "www_exps_" + discovery.id + "_" + i + "_" + rp + "_" + voice.hashCode() + ".mp3")
            if (!mp3.exists() || mp3.length() < 200L) {
                EdgeTTS.synthesizeToMp3(s.title + ". " + s.text, voice, rp, mp3, ssml = true)
            }
            out.write(mp3.readBytes())
        }
        return VideoExporter.saveAudioMp3(context, base, out.toByteArray())
    }
    return VideoExporter.exportAudioWav(
        context,
        slides.map { VideoExporter.SlideData(it.title, it.text, it.emoji, null) },
        voiceA,
        voiceB,
        speed,
        speechLang,
        base
    )
}

/** Übergibt die exportierte Audiodatei an eine Ziel-App (z. B. WhatsApp, Bluetooth). */
private fun shareAudio(context: Context, uri: Uri, mime: String) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = mime
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_SUBJECT, "Wer Wie Was – Erklärung als Audio")
        clipData = ClipData.newRawUri("audio", uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    runCatching {
        context.startActivity(
            Intent.createChooser(send, "Audio teilen")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}

/** Übergibt die exportierte Untertiteldatei (SRT) an eine Ziel-App (z. B. YouTube-Upload). */
private fun shareSrt(context: Context, uri: Uri) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_SUBJECT, "Wer Wie Was – Untertitel")
        clipData = ClipData.newRawUri("srt", uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    runCatching {
        context.startActivity(
            Intent.createChooser(send, "Untertitel teilen")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}

/** Übergibt das automatisch erstellte Cover-Bild (Thumbnail) an eine Ziel-App (z. B. YouTube-Upload). */
private fun shareThumb(context: Context, uri: Uri) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "image/jpeg"
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_SUBJECT, "Wer Wie Was – Video-Cover")
        clipData = ClipData.newRawUri("cover", uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    runCatching {
        context.startActivity(
            Intent.createChooser(send, "Cover teilen")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}

/** Übergibt das exportierte Video an eine Ziel-App (z. B. YouTube, TikTok) oder die System-Auswahl. */
private fun shareVideo(context: Context, uri: Uri, pkg: String?) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "video/mp4"
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_SUBJECT, "Wer Wie Was – Erklärvideo")
        clipData = ClipData.newRawUri("video", uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        if (pkg != null) setPackage(pkg)
    }
    // Installations-Check: Nicht installierte Ziel-Apps sofort abfangen,
    // statt den Start später crashen zu lassen (Fallback: System-Auswahl).
    if (pkg != null) {
        val installed = try {
            context.packageManager.getPackageInfo(pkg, 0)
            true
        } catch (e: Exception) {
            false
        }
        if (!installed) {
            send.setPackage(null)
            Toast.makeText(context, "Ziel-App nicht installiert – System-Auswahl wird geöffnet.", Toast.LENGTH_SHORT).show()
        }
    }
    try {
        context.startActivity(send.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: ActivityNotFoundException) {
        // Ziel-App nicht installiert: System-Auswahl anbieten
        send.setPackage(null)
        Toast.makeText(context, "Ziel-App nicht gefunden – bitte aus der Auswahl wählen.", Toast.LENGTH_SHORT).show()
        runCatching {
            context.startActivity(
                Intent.createChooser(send, "Video teilen")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    } catch (e: Exception) {
        // Still ignorieren
    }
}



