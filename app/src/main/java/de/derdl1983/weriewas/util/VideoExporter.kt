package de.derdl1983.weriewas.util

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.core.content.FileProvider
import de.derdl1983.weriewas.R
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * Exportiert das Erklärvideo als abspielbare MP4-Datei:
 * Szenen werden mit weichen Überblendungen, sanftem Bild-Zoom (Ken Burns),
 * Intro- und Abschluss-Karte und Fortschrittsbalken als H.264-Videospur
 * encodiert (24 Bilder/s), die Sprachausgabe plus dezente Hintergrundmusik
 * als AAC-Stereotonspur, beides über MediaMuxer in eine Datei gemuxt und in
 * die Mediathek gespeichert. Blockierend mit viel I/O und Rechenarbeit – nur
 * ausserhalb des Main-Threads aufrufen.
 */
object VideoExporter {

    /** Eine Szene des Videos (Titel, Text, Themenbild-Zeichen, Foto, passendes Online-Bild). */
    data class SlideData(
        val title: String,
        val text: String,
        val emoji: String,
        val photoPath: String?,
        val webImagePath: String? = null,
        val webImageCredit: String? = null
    )

    private class SlideAudio(val pcm: ByteArray, val sampleRate: Int, val channels: Int)
    private class Sample(val data: ByteArray, val ptsUs: Long, val flags: Int)

    /** Vorbereitete Grafik-Ebenen einer Szene für den Frame-Renderer. */
    private class SlideGfx(
        val base: Bitmap,
        val photo: Bitmap?,
        val photoRect: RectF?,
        val textLayer: Bitmap?,
        val textTop: Float,
        val textH: Int,
        val captions: List<Caption>
    )

    /** Eine eingebrannte Untertitel-Zeile mit Zeitfenster und fertigem Layout. */
    private class Caption(
        val startUs: Long,
        val endUs: Long,
        val text: String,
        val layout: StaticLayout,
        val boxW: Float,
        val boxH: Float
    )

    class ExportException(message: String) : Exception(message)

    private var WIDTH = 720
    private var HEIGHT = 1280
    private var scale = 1f
    private var logoMark: Drawable? = null
    private const val FRAME_INTERVAL_US = 41666L // 24 Bilder/s für weiche Überblendungen
    private const val TARGET_RATE = 44100       // Studio-Audioqualität
    private const val CHANNELS = 2              // Stereo
    private const val SILENCE_SECONDS = 0.6
    private const val FADE_SECONDS = 0.6        // Dauer der Weichblenden (Karte/Foto/Szene)
    private const val INTRO_SECONDS = 6.0       // Längeres Intro: Marken-Karte, dann Ausgangsbild
    private const val INTRO_CARD_SECONDS = 2.4  // Dauer der Marken-Karte vor dem Ausgangsbild
    private const val TRANS_SECONDS = 0.9       // Foto-Übergangs-Clip zwischen zwei Szenen
    private const val OUTRO_SECONDS = 2.2
    private const val TEXT_FADE_SECONDS = 0.5f  // Titel/Text blenden pro Szene ein
    private const val ZOOM_AMOUNT = 0.08f      // Ken-Burns-Zoomstärke auf Fotos

    /** Durchgehende Marken-Palette: Navy-Blau-Verläufe, dezent variiert pro Szene. */
    private val palettes = arrayOf(
        intArrayOf(0xFF0A1F4E.toInt(), 0xFF1A50A8.toInt()),
        intArrayOf(0xFF0B2A5B.toInt(), 0xFF1565C0.toInt()),
        intArrayOf(0xFF091E45.toInt(), 0xFF283593.toInt()),
        intArrayOf(0xFF0D2B52.toInt(), 0xFF0F6BA8.toInt()),
        intArrayOf(0xFF101F4C.toInt(), 0xFF2547A8.toInt()),
        intArrayOf(0xFF0A2450.toInt(), 0xFF1E6FA8.toInt()),
        intArrayOf(0xFF0C1E4E.toInt(), 0xFF2C5FB4.toInt()),
        intArrayOf(0xFF0E2A54.toInt(), 0xFF1A5FA8.toInt())
    )

    /**
     * Ergebnis eines Video-Exports: Beschreibung des Speicherorts und (wo möglich)
     * eine Content-URI, über die das Video direkt geteilt bzw. hochgeladen werden kann.
     * srtUri: Untertiteldatei (SRT), wenn angefordert und erfolgreich gespeichert.
     * thumbUri: automatisch erstelltes Video-Cover (JPEG) für YouTube/TikTok.
     */
    data class ExportResult(val message: String, val shareUri: Uri?, val srtUri: Uri? = null, val thumbUri: Uri? = null)

    /**
     * Erstellt das Video und gibt eine Beschreibung des Speicherorts zurück.
     * onProgress wird laufend mit dem Gesamt-Fortschritt (0.0 bis 1.0) aufgerufen.
     * preAudio: optional je Szene eine fertige WAV-Datei (z. B. Edge-TTS);
     * fehlende Einträge werden über die Android-Sprachausgabe erzeugt.
     * videoWidth/videoHeight: Auflösung (z. B. 1280x720/1920x1080 für YouTube-Querformat);
     * wantSrt: Untertitel als SRT-Datei mitspeichern;
     * burnedSubs: Untertitel direkt ins Video einbrennen (TikTok-Stil);
     * backgroundMusic: dezente, generierte Hintergrundmusik unter die Stimme mischen;
     * musicMood: Stimmung der Hintergrundmusik (neugierig / froehlich / ruhig);
     * cacheKey: Entdeckungs-ID für den Absturz-Schutz (fertige Spuren werden im
     * App-Cache abgelegt und beim nächsten Versuch wiederverwendet);
     * speechLang: Antwort-Sprache des KI-Profils – die System-Sprachausgabe
     * wird auf diese Sprache eingestellt (folgt der Profil-Sprache);
     */
    fun export(
        context: Context,
        slides: List<SlideData>,
        voiceA: Voice?,
        voiceB: Voice?,
        rate: Float,
        preAudio: List<File?>? = null,
        videoWidth: Int = 720,
        videoHeight: Int = 1280,
        wantSrt: Boolean = false,
        burnedSubs: Boolean = false,
        backgroundMusic: Boolean = true,
        musicMood: String = "neugierig",
        cacheKey: String = "",
        speechLang: String = "de",
        onProgress: (progress: Float) -> Unit
    ): ExportResult {
        if (slides.isEmpty()) throw ExportException("Keine Inhalte zum Export vorhanden.")
        WIDTH = videoWidth
        HEIGHT = videoHeight
        scale = minOf(WIDTH, HEIGHT) / 720f
        // Video-Logo-Marke: gleiche Geometrie wie das Launcher-Icon, aber mit
        // weißer Kamera/Lupe – die dunkle Launcher-Variante war auf dem
        // dunkelblauen Video-Hintergrund praktisch unsichtbar.
        logoMark = context.getDrawable(R.drawable.ic_video_logo)
        onProgress(0.02f)
        // Absturz-Schutz: Fertig encodierte Ton-/Videospuren werden im App-Cache
        // abgelegt. Bricht der Export (oder die App) mitten ab, läuft der nächste
        // Versuch mit den bereits fertigen Spuren weiter statt komplett neu.
        val cacheHash = trackCacheHash(slides, rate, voiceA?.name, voiceB?.name, preAudio, burnedSubs, backgroundMusic, musicMood, videoWidth, videoHeight, speechLang)
        val useCache = cacheKey.isNotBlank()
        val audioCacheFile = if (useCache) File(context.cacheDir, "www_track_" + cacheKey + "_" + cacheHash + "_a.bin") else null
        val videoCacheFile = if (useCache) File(context.cacheDir, "www_track_" + cacheKey + "_" + cacheHash + "_v.bin") else null
        if (useCache) {
            context.cacheDir.listFiles()?.forEach { f ->
                if (f.isFile && f.name.startsWith("www_track_") &&
                    f.name != audioCacheFile?.name && f.name != videoCacheFile?.name
                ) {
                    runCatching { f.delete() }
                }
            }
        }
        val audioCached = if (useCache) loadTrackCache(audioCacheFile) else null
        val videoCached = if (useCache) loadTrackCache(videoCacheFile) else null
        if (audioCached != null && videoCached != null) {
            // Beide Spuren liegen bereits fertig im Cache: nur noch muxen & speichern
            onProgress(0.90f)
            val file = try {
                mux(context, audioCached.format to audioCached.samples, videoCached.format to videoCached.samples)
            } catch (e: ExportException) {
                throw e
            } catch (e: Exception) {
                throw ExportException("Video-Datei konnte nicht erstellt werden: " + (e.message ?: e.javaClass.simpleName))
            }
            onProgress(0.97f)
            val videoBase = file.name.removeSuffix(".mp4")
            val videoResult = saveVideo(context, file)
            val srtUri = if (wantSrt) saveSrt(context, videoBase, buildSrt(slides, audioCached.speechRanges)) else null
            val thumbUri = runCatching { saveThumb(context, videoBase, slides.first()) }.getOrNull()
            onProgress(1f)
            val message = if (srtUri != null) {
                videoResult.message + " – Untertitel (SRT) gespeichert"
            } else {
                videoResult.message
            }
            return ExportResult(message, videoResult.shareUri, srtUri, thumbUri)
        }
        val introUs = (INTRO_SECONDS * 1_000_000).toLong()
        val outroUs = (OUTRO_SECONDS * 1_000_000).toLong()
        val fadeUs = (FADE_SECONDS * 1_000_000).toLong()
        val silenceUs = (SILENCE_SECONDS * 1_000_000).toLong()
        val transUs = (TRANS_SECONDS * 1_000_000).toLong()

        val audios = synthesizeSlides(context, slides, voiceA, voiceB, rate, preAudio, speechLang) { p ->
            onProgress(0.05f + 0.25f * p.coerceIn(0f, 1f))
        }
        onProgress(0.30f)
        // Sicherheitsnetz: Wenn TTS-Dateien vorlagen, aber am Ende gar keine
        // Szene eine gültige Tonspur hat (z. B. Netzwerkfehler bei Edge-TTS),
        // brechen wir sauber ab, statt ein stummes Video zu erzeugen.
        if (preAudio != null && preAudio.any { it != null } && audios.all { it.pcm.isEmpty() }) {
            throw ExportException("Sprachsynthese fehlgeschlagen – bitte Internetverbindung prüfen und den Export erneut starten.")
        }

        // Ton-Zeitlinie: Intro-Pause, dann je Szene Sprachausgabe + Anzeige-Pause, dann Outro-Pause.
        // Jede Szene bekommt eine Mindest-Anzeigedauer nach Textlänge, damit der komplette
        // Text und die Themenbilder sicher sichtbar sind – auch ohne (oder bei sehr
        // kurzer) Sprachausgabe.
        val pcmOut = ByteArrayOutputStream()
        val speechRanges = mutableListOf<LongRange>()
        val sceneSpans = mutableListOf<LongRange>()
        val warnSpans = mutableListOf<LongRange>()
        val sceneStart = LongArray(slides.size)
        val sceneDur = LongArray(slides.size)
        pcmOut.write(ByteArray((INTRO_SECONDS * TARGET_RATE * 2).toInt()))
        var cursor = introUs
        for (i in audios.indices) {
            val mono = toMonoTarget(audios[i])
            val monoUs = mono.size * 1_000_000L / (TARGET_RATE * 2)
            val estimateUs = minSceneUs(slides[i].title + " " + slides[i].text)
            val padUs = maxOf(silenceUs, estimateUs - monoUs)
            pcmOut.write(mono)
            sceneStart[i] = cursor
            sceneDur[i] = monoUs + padUs
            sceneSpans.add(cursor until (cursor + sceneDur[i]))
            if (slides[i].emoji.contains("⚠")) warnSpans.add(cursor until (cursor + sceneDur[i]))
            if (monoUs > 0) speechRanges.add(cursor until (cursor + monoUs))
            cursor += monoUs + padUs
            pcmOut.write(ByteArray((padUs * TARGET_RATE * 2L / 1_000_000L).toInt()))
            // Zwischen den Szenen einen kurzen Foto-Übergangs-Clip einfügen
            // (Vollbild-Foto der nächsten Szene mit Ken-Burns-Zoom). Die Stille
            // hier verlängert die Ton-Zeitlinie im gleichen Takt.
            if (i < audios.size - 1) {
                pcmOut.write(ByteArray((transUs * TARGET_RATE * 2L / 1_000_000L).toInt()))
                cursor += transUs
            }
        }
        pcmOut.write(ByteArray((OUTRO_SECONDS * TARGET_RATE * 2).toInt()))
        val durationUs = cursor + outroUs
        val monoPcm = pcmOut.toByteArray()

        // Hintergrundmusik (gewählte Stimmung) + Sprachausgabe mischen und
        // auf YouTube-Lautstärke (−14 LUFS) normalisieren.
        val audio: Pair<MediaFormat, List<Sample>> = if (audioCached != null) {
            audioCached.format to audioCached.samples
        } else {
            val stereoPcm = normalizeLoudness(
                mixAudio(monoPcm, durationUs, speechRanges, backgroundMusic, musicMood, warnSpans)
            )
            onProgress(0.32f)
            val enc = try {
                encodeAudio(stereoPcm, TARGET_RATE, CHANNELS)
            } catch (e: ExportException) {
                throw e
            } catch (e: Exception) {
                throw ExportException("Tonspur konnte nicht encodiert werden: " + (e.message ?: e.javaClass.simpleName))
            }
            onProgress(0.35f)
            if (audioCacheFile != null) {
                saveTrackCache(audioCacheFile, MediaFormat.MIMETYPE_AUDIO_AAC, enc.first, enc.second, durationUs, sceneSpans)
            }
            enc
        }
        onProgress(0.35f)

        // Szenen-Grafiken vorbereiten (Ebenen für Zoom, Text-Fade, Untertitel).
        // Eingebrannte Untertitel laufen über das komplette Anzeige-Fenster der Szene.
        val gfx = try {
            slides.mapIndexed { i, s ->
                val g = renderSlideGfx(s, i, slides.size, burnedSubs, sceneDur[i])
                onProgress(0.35f + 0.10f * (i + 1f) / slides.size)
                g
            }
        } catch (e: ExportException) {
            throw e
        } catch (e: Exception) {
            throw ExportException("Szenen konnten nicht vorbereitet werden: " + (e.message ?: e.javaClass.simpleName))
        }
        // Fotos für das Intro (Ausgangsbild) und die Übergangs-Clips in
        // Vollbild-Qualität – unabhängig von den kleineren Szenen-Bildern.
        val fullPhotos = slides.map { decodeFullscreen(it.photoPath ?: it.webImagePath) }
        // Frame-Renderer: Intro-Karte, Überblendungen, Ken-Burns-Zoom, Outro-Karte
        val bmpA = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        val cA = Canvas(bmpA)
        val bmpB = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        val cB = Canvas(bmpB)
        val arrA = IntArray(WIDTH * HEIGHT)
        val arrB = IntArray(WIDTH * HEIGHT)
        val out = IntArray(WIDTH * HEIGHT)

        val topicText = slides.firstOrNull()?.text?.trim()?.take(90)
            ?.takeUnless { it.isNullOrBlank() } ?: "Dein Erklärvideo"
        val topicPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
        topicPaint.textSize = 44f * scale
        topicPaint.color = Color.argb(240, 255, 255, 255)
        topicPaint.setShadowLayer(3f * scale, 0f, 2f * scale, Color.argb(120, 0, 0, 0))
        val topicLayout = StaticLayout.Builder.obtain(
            topicText, 0, topicText.length, topicPaint, (WIDTH - 160f * scale).toInt()
        ).setAlignment(Layout.Alignment.ALIGN_CENTER).setLineSpacing(0f, 1.2f).build()

        // Wiederverwendete Paint-Objekte: Pro Frame neu angelegte Paints machen
        // den Video-Export auf schwächeren Geräten spürbar langsamer.
        val progressTrackPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        progressTrackPaint.color = Color.argb(40, 255, 255, 255)
        val progressFillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        progressFillPaint.color = Color.rgb(66, 133, 244)
        val captionBgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        captionBgPaint.color = Color.argb(200, 10, 14, 30)
        val textLayerPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        val photoPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        val cardBgPaint = Paint()
        cardBgPaint.shader = LinearGradient(
            0f, 0f, 0f, HEIGHT.toFloat(),
            0xFF0A1633.toInt(), 0xFF1A50A8.toInt(), Shader.TileMode.CLAMP
        )
        // Bausteine für Intro-/Übergangs-Clips: Palette-Verläufe je Szene
        // (Fallback, wenn kein Foto da ist), Vignette und Emoji-/Wortmarken.
        val transBgPaints = palettes.map { colors ->
            Paint().apply {
                shader = LinearGradient(0f, 0f, 0f, HEIGHT.toFloat(), colors[0], colors[1], Shader.TileMode.CLAMP)
            }
        }
        val transVignettePaint = Paint()
        transVignettePaint.shader = RadialGradient(
            WIDTH / 2f, HEIGHT / 2f, maxOf(WIDTH, HEIGHT) * 0.75f,
            intArrayOf(Color.TRANSPARENT, Color.argb(95, 0, 0, 0)),
            floatArrayOf(0.55f, 1f),
            Shader.TileMode.CLAMP
        )
        val transEmojiPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 230f * scale
            textAlign = Paint.Align.CENTER
            color = Color.argb(240, 255, 255, 255)
            setShadowLayer(6f * scale, 0f, 3f * scale, Color.argb(120, 0, 0, 0))
        }
        val outroTextPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        outroTextPaint.textAlign = Paint.Align.CENTER
        outroTextPaint.textSize = 36f * scale
        outroTextPaint.color = Color.argb(225, 255, 255, 255)
        val wordmarkPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
        wordmarkPaint.isFakeBoldText = true
        wordmarkPaint.setShadowLayer(5f * scale, 0f, 2f * scale, Color.argb(140, 0, 0, 0))

        fun drawProgress(c: Canvas, t: Long) {
            c.drawRect(0f, HEIGHT - 5f * scale, WIDTH.toFloat(), HEIGHT.toFloat(), progressTrackPaint)
            val w = WIDTH * (t.toFloat() / durationUs.toFloat()).coerceIn(0f, 1f)
            c.drawRect(0f, HEIGHT - 5f * scale, w, HEIGHT.toFloat(), progressFillPaint)
        }

        fun drawCaption(c: Canvas, i: Int, t: Long) {
            val tIn = t - sceneStart[i]
            val cap = gfx[i].captions.firstOrNull { tIn >= it.startUs && tIn < it.endUs } ?: return
            val left = (WIDTH - cap.boxW) / 2f
            val top = HEIGHT - 120f * scale - cap.boxH
            val box = RectF(left, top, left + cap.boxW, top + cap.boxH)
            c.drawRoundRect(box, 14f * scale, 14f * scale, captionBgPaint)
            c.save()
            c.translate((WIDTH - cap.layout.width) / 2f, box.top + 14f * scale)
            cap.layout.draw(c)
            c.restore()
        }

        fun drawScene(c: Canvas, i: Int, t: Long) {
            val g = gfx[i]
            c.drawBitmap(g.base, 0f, 0f, null)
            val p = g.photo
            val r = g.photoRect
            if (p != null && r != null) {
                // Ken-Burns-Zoom: Richtung wechselt pro Szene – gerade Szenen
                // zoomen leicht heran, ungerale Szenen leicht heraus. Das gibt
                // dem Video visuelle Abwechslung statt einer starren
                // Einheitsbewegung auf jedem Bild.
                val prog = ((t - sceneStart[i]).toFloat() / sceneDur[i].toFloat()).coerceIn(0f, 1f)
                val z = if (i % 2 == 0) 1f + ZOOM_AMOUNT * prog else 1f + ZOOM_AMOUNT * (1f - prog)
                val clip = Path()
                clip.addRoundRect(r, 18f * scale, 18f * scale, Path.Direction.CW)
                c.save()
                c.clipPath(clip)
                val m = Matrix()
                m.postTranslate(-p.width / 2f, -p.height / 2f)
                m.postScale(z, z)
                m.postTranslate(r.centerX(), r.centerY())
                c.drawBitmap(p, m, photoPaint)
                c.restore()
            }
            val tl = g.textLayer
            if (tl != null && g.textH > 0) {
                // Titel/Text fahren sanft ein und blenden ein
                val a = ((t - sceneStart[i]).toFloat() / (TEXT_FADE_SECONDS * 1_000_000f)).coerceIn(0f, 1f)
                val ease = a * a * (3f - 2f * a)
                textLayerPaint.alpha = (ease * 255).toInt()
                val dy = (1f - ease) * 26f * scale
                val src = Rect(0, 0, WIDTH, g.textH)
                c.drawBitmap(tl, src, RectF(0f, g.textTop + dy, WIDTH.toFloat(), g.textTop + dy + g.textH), textLayerPaint)
            }
            if (burnedSubs) drawCaption(c, i, t)
            drawProgress(c, t)
        }

        fun drawWordmark(c: Canvas, y: Float, size: Float, alpha: Int) {
            val a = alpha.coerceIn(0, 255)
            val tp = wordmarkPaint
            tp.textSize = size
            val s1 = "Wer "
            val s2 = "Wie"
            val s3 = " Was"
            val total = tp.measureText(s1) + tp.measureText(s2) + tp.measureText(s3)
            var x = WIDTH / 2f - total / 2f
            tp.color = Color.argb(a, 255, 255, 255)
            c.drawText(s1, x, y, tp)
            x += tp.measureText(s1)
            tp.color = Color.argb(a, 66, 133, 244)
            c.drawText(s2, x, y, tp)
            x += tp.measureText(s2)
            tp.color = Color.argb(a, 255, 255, 255)
            c.drawText(s3, x, y, tp)
        }

        fun drawCard(c: Canvas, t: Long, cardStart: Long, intro: Boolean) {
            c.drawRect(0f, 0f, WIDTH.toFloat(), HEIGHT.toFloat(), cardBgPaint)
            val a = ((t - cardStart).toFloat() / 900_000f).coerceIn(0f, 1f)
            val ease = a * a * (3f - 2f * a)
            val cy = HEIGHT * (if (intro) 0.34f else 0.38f)
            val grow = 0.92f + 0.08f * ease
            val mark = logoMark
            if (mark != null) {
                c.save()
                c.scale(grow, grow, WIDTH / 2f, cy)
                drawLogoMark(c, mark, WIDTH / 2f, cy, 180f * scale, (ease * 255).toInt())
                c.restore()
            }
            drawWordmark(c, cy + 175f * scale, 78f * scale, (ease * 255).toInt())
            if (intro) {
                c.save()
                c.translate((WIDTH - topicLayout.width) / 2f, cy + 290f * scale)
                topicLayout.draw(c)
                c.restore()
            } else {
                c.drawText("Einfach ein Foto machen –", WIDTH / 2f, cy + 310f * scale, outroTextPaint)
                c.drawText("die App erklärt den Rest!", WIDTH / 2f, cy + 360f * scale, outroTextPaint)
            }
            drawProgress(c, t)
        }

        fun blend(a: IntArray, b: IntArray, f: Float, dst: IntArray) {
            val ia = (f.coerceIn(0f, 1f) * 256f).toInt()
            val ib = 256 - ia
            for (i in dst.indices) {
                val ca = a[i]
                val cb = b[i]
                val rr = ((ca ushr 16 and 0xff) * ib + (cb ushr 16 and 0xff) * ia) shr 8
                val gg = ((ca ushr 8 and 0xff) * ib + (cb ushr 8 and 0xff) * ia) shr 8
                val bb = ((ca and 0xff) * ib + (cb and 0xff) * ia) shr 8
                dst[i] = (0xff shl 24) or (rr shl 16) or (gg shl 8) or bb
            }
        }

        /** Zeichnet eine Bitmap formatfüllend, mittig beschnitten und mit Zoom-Faktor. */
        fun drawFullscreen(c: Canvas, bmp: Bitmap, zoom: Float) {
            val s = maxOf(WIDTH.toFloat() / bmp.width, HEIGHT.toFloat() / bmp.height) * zoom
            val m = Matrix()
            m.postScale(s, s)
            m.postTranslate((WIDTH - bmp.width * s) / 2f, (HEIGHT - bmp.height * s) / 2f)
            c.drawBitmap(bmp, m, photoPaint)
        }

        /** Intro-Phase 2: Das Ausgangsbild der Entdeckung als Vollbild mit
         *  langsamem Ken-Burns-Zoom und dezenter Wortmarke. */
        fun drawIntroPhoto(c: Canvas, t: Long) {
            val p = fullPhotos.firstOrNull()
            if (p == null) {
                // Kein Foto vorhanden: Marken-Karte bleibt stehen
                drawCard(c, t, 0L, true)

                return
            }
            val f = (t.toFloat() / introUs.toFloat()).coerceIn(0f, 1f)
            drawFullscreen(c, p, 1.05f + 0.07f * f)
            c.drawRect(0f, 0f, WIDTH.toFloat(), HEIGHT.toFloat(), transVignettePaint)
            drawWordmark(c, HEIGHT * 0.90f, 52f * scale, 215)
            drawProgress(c, t)
        }

        /** Übergangs-Clip zwischen Szene seg und seg+1: Vollbild-Foto der
         *  nächsten Szene mit Ken-Burns-Zoom – ohne Foto Palette-Verlauf mit
         *  großem Emoji. */
        fun drawTransitionBase(c: Canvas, seg: Int, t: Long) {
            val next = seg + 1
            val f = ((t - sceneStart[seg] - sceneDur[seg]).toFloat() / transUs.toFloat()).coerceIn(0f, 1f)
            val p = fullPhotos.getOrNull(next)
            if (p != null) {
                drawFullscreen(c, p, 1.10f - 0.08f * f)
                c.drawRect(0f, 0f, WIDTH.toFloat(), HEIGHT.toFloat(), transVignettePaint)
            } else {
                c.drawRect(0f, 0f, WIDTH.toFloat(), HEIGHT.toFloat(), transBgPaints[next % transBgPaints.size])
                val emoji = slides[next].emoji.ifBlank { "✨" }
                val fm = transEmojiPaint.fontMetrics
                c.drawText(emoji, WIDTH / 2f, HEIGHT / 2f - (fm.top + fm.bottom) / 2f, transEmojiPaint)
            }
            drawProgress(c, t)
        }

        fun fadeToBlack(arr: IntArray, f: Float) {
            val ib = 256 - (f.coerceIn(0f, 1f) * 256f).toInt()
            for (i in arr.indices) {
                val cc = arr[i]
                val rr = ((cc ushr 16 and 0xff) * ib) shr 8
                val gg = ((cc ushr 8 and 0xff) * ib) shr 8
                val bb = ((cc and 0xff) * ib) shr 8
                arr[i] = (0xff shl 24) or (rr shl 16) or (gg shl 8) or bb
            }
        }

        fun renderFrame(t: Long): IntArray {
            val half = fadeUs / 2
            val outroStart = durationUs - outroUs
            val introCardUs = (INTRO_CARD_SECONDS * 1_000_000).toLong()
            // Intro: erst die Marken-Karte, dann weich zum Vollbild-Ausgangsbild
            if (t < introCardUs - half) {
                drawCard(cA, t, 0L, true)
                bmpA.getPixels(out, 0, WIDTH, 0, 0, WIDTH, HEIGHT)
                return out
            }
            if (t < introCardUs + half) {
                drawCard(cA, t, 0L, true)
                bmpA.getPixels(arrA, 0, WIDTH, 0, 0, WIDTH, HEIGHT)
                drawIntroPhoto(cB, t)
                bmpB.getPixels(arrB, 0, WIDTH, 0, 0, WIDTH, HEIGHT)
                blend(arrA, arrB, (t - (introCardUs - half)).toFloat() / fadeUs.toFloat(), out)
                return out
            }
            if (t < introUs - half) {
                drawIntroPhoto(cA, t)
                bmpA.getPixels(out, 0, WIDTH, 0, 0, WIDTH, HEIGHT)
                return out
            }
            if (t < introUs + half) {
                drawIntroPhoto(cA, t)
                bmpA.getPixels(arrA, 0, WIDTH, 0, 0, WIDTH, HEIGHT)
                drawScene(cB, 0, t)
                bmpB.getPixels(arrB, 0, WIDTH, 0, 0, WIDTH, HEIGHT)
                blend(arrA, arrB, (t - (introUs - half)).toFloat() / fadeUs.toFloat(), out)
                return out
            }
            if (t >= outroStart - half) {
                if (t < outroStart + half) {
                    drawScene(cA, slides.size - 1, t)
                    bmpA.getPixels(arrA, 0, WIDTH, 0, 0, WIDTH, HEIGHT)
                    drawCard(cB, t, outroStart, false)
                    bmpB.getPixels(arrB, 0, WIDTH, 0, 0, WIDTH, HEIGHT)
                    blend(arrA, arrB, (t - (outroStart - half)).toFloat() / fadeUs.toFloat(), out)
                } else {
                    drawCard(cA, t, outroStart, false)
                    bmpA.getPixels(out, 0, WIDTH, 0, 0, WIDTH, HEIGHT)
                    val remain = durationUs - t
                    if (remain in 1..500_000L) fadeToBlack(out, 1f - remain / 500_000f)
                }
                return out
            }
            var seg = 0
            for (i in sceneStart.indices) if (t >= sceneStart[i]) seg = i
            val sceneEnd = sceneStart[seg] + sceneDur[seg]
            if (seg < slides.size - 1 && t >= sceneEnd) {
                // Foto-Übergangs-Clip: weich aus Szene seg hinein- und in
                // Szene seg+1 wieder herausgeblendet (flüssiger Szenenwechsel)
                val f = ((t - sceneEnd).toFloat() / transUs.toFloat()).coerceIn(0f, 1f)
                if (f < 0.3f) {
                    drawScene(cA, seg, t)
                    bmpA.getPixels(arrA, 0, WIDTH, 0, 0, WIDTH, HEIGHT)
                    drawTransitionBase(cB, seg, t)
                    bmpB.getPixels(arrB, 0, WIDTH, 0, 0, WIDTH, HEIGHT)
                    blend(arrA, arrB, f / 0.3f, out)
                } else if (f > 0.7f) {
                    drawTransitionBase(cA, seg, t)
                    bmpA.getPixels(arrA, 0, WIDTH, 0, 0, WIDTH, HEIGHT)
                    drawScene(cB, seg + 1, t)
                    bmpB.getPixels(arrB, 0, WIDTH, 0, 0, WIDTH, HEIGHT)
                    blend(arrA, arrB, (f - 0.7f) / 0.3f, out)
                } else {
                    drawTransitionBase(cA, seg, t)
                    bmpA.getPixels(out, 0, WIDTH, 0, 0, WIDTH, HEIGHT)
                }
                return out
            }
            drawScene(cA, seg, t)
            bmpA.getPixels(out, 0, WIDTH, 0, 0, WIDTH, HEIGHT)
            return out
        }

        val video: Pair<MediaFormat, List<Sample>> = if (videoCached != null) {
            videoCached.format to videoCached.samples
        } else {
            val enc = try {
                encodeVideo(durationUs, { t -> renderFrame(t) }) { p ->
                    onProgress(0.45f + 0.50f * p.coerceIn(0f, 1f))
                }
            } catch (e: ExportException) {
                throw e
            } catch (e: Exception) {
                throw ExportException("Videospur konnte nicht encodiert werden: " + (e.message ?: e.javaClass.simpleName))
            }
            if (videoCacheFile != null) {
                saveTrackCache(videoCacheFile, MediaFormat.MIMETYPE_VIDEO_AVC, enc.first, enc.second, 0L, emptyList())
            }
            enc
        }
        val file = try {
            mux(context, audio, video)
        } catch (e: ExportException) {
            throw e
        } catch (e: Exception) {
            throw ExportException("Video-Datei konnte nicht erstellt werden: " + (e.message ?: e.javaClass.simpleName))
        }
        onProgress(0.97f)
        val videoBase = file.name.removeSuffix(".mp4")
        val videoResult = saveVideo(context, file)
        val srtUri = if (wantSrt) saveSrt(context, videoBase, buildSrt(slides, sceneSpans)) else null
        // Automatisches Video-Cover (Thumbnail) für YouTube/TikTok mitspeichern
        val thumbUri = runCatching { saveThumb(context, videoBase, slides.first()) }.getOrNull()
        onProgress(1f)
        val message = if (srtUri != null) {
            videoResult.message + " – Untertitel (SRT) gespeichert"
        } else {
            videoResult.message
        }
        return ExportResult(message, videoResult.shareUri, srtUri, thumbUri)
    }

    /** Erzeugt je Szene eine gesprochene Tonspur – vorgefertigte Dateien haben Vorrang. */
    private fun synthesizeSlides(
        context: Context,
        slides: List<SlideData>,
        voiceA: Voice?,
        voiceB: Voice?,
        rate: Float,
        preAudio: List<File?>?,
        speechLang: String = "de",
        progress: (Float) -> Unit = { }
    ): List<SlideAudio> {
        val provided: List<File?> = slides.mapIndexed { i, _ -> preAudio?.getOrNull(i) }
        // Vorhandene TTS-Dateien vorab lesen. null = Datei fehlt, ist defekt
        // oder enthält keine Daten – diese Szenen werden später über die
        // Android-Sprachausgabe synthetisiert, statt stumm zu bleiben.
        val preParsed: List<SlideAudio?>? = if (provided.any { it != null }) {
            val parsed = provided.map { f ->
                if (f == null) null
                else runCatching { parseWav(f) }.getOrNull()?.takeIf { it.pcm.isNotEmpty() }
            }
            if (parsed.all { it != null }) {
                // Alle Szenen haben fertige, gültige Tonspuren
                progress(1f)
                return parsed.map { it as SlideAudio }
            }
            parsed
        } else null
        val initLatch = CountDownLatch(1)
        val tts = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) initLatch.countDown()
        }
        try {
            if (!initLatch.await(15, TimeUnit.SECONDS)) {
                // Keine Sprachausgabe verfügbar: bereits gelesene Vorlagen-Dateien
                // nutzen, fehlende Szenen bleiben ohne Ton (nur Sprechpause).
                progress(1f)
                return slides.mapIndexed { i, _ ->
                    preParsed?.getOrNull(i) ?: SlideAudio(ByteArray(0), TARGET_RATE, 1)
                }
            }
            // Sprache der System-Sprachausgabe folgt der Antwort-Sprache des
            // KI-Profils (Standard Deutsch).
            tts.language = Locale.forLanguageTag(if (speechLang.isBlank()) "de" else speechLang)
            tts.setSpeechRate(rate)
            val result = mutableListOf<SlideAudio>()
            for ((index, slide) in slides.withIndex()) {
                val pre = preParsed?.getOrNull(index)
                if (pre != null) {
                    result.add(pre)
                    continue
                }
                val voice = if (index % 2 == 1) (voiceB ?: voiceA) else voiceA
                if (voice != null) {
                    tts.setVoice(voice)
                    tts.setPitch(1f)
                } else {
                    tts.setPitch(if (index % 2 == 1) 0.8f else 1f)
                }
                val wav = File(context.cacheDir, "www_slide_" + index + ".wav")
                var audio = SlideAudio(ByteArray(0), TARGET_RATE, 1)
                // Neuversuch: Die Synthese einer Szene kann einmal scheitern
                // oder eine leere Datei liefern – dann ein zweites Mal
                // probieren, statt die Szene stumm zu lassen.
                for (attempt in 0 until 2) {
                    val latch = CountDownLatch(1)
                    tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onDone(utteranceId: String?) { latch.countDown() }
                        override fun onError(utteranceId: String?) { latch.countDown() }
                        override fun onStart(utteranceId: String?) { }
                    })
                    val ok = tts.synthesizeToFile(
                        EdgeTTS.speakableText(slide.title) + ". " + EdgeTTS.speakableText(slide.text),
                        Bundle().apply {
                            putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, "exp" + index)
                        },
                        wav,
                        "exp" + index
                    )
                    if (ok == TextToSpeech.SUCCESS) latch.await(60, TimeUnit.SECONDS) else Thread.sleep(1000)
                    audio = runCatching { parseWav(wav) }
                        .getOrElse { SlideAudio(ByteArray(0), TARGET_RATE, 1) }
                    wav.delete()
                    if (audio.pcm.isNotEmpty()) break
                }
                result.add(audio)
                progress((index + 1f) / slides.size)
            }
            return result
        } finally {
            tts.stop()
            tts.shutdown()
        }
    }

    /** Mischt die Sprachausgabe (Mono) mit der Hintergrundmusik zu einer Stereospur.
     *  In Sicherheits-Szenen (warnRanges) wird die Musik fast ganz abgesenkt,
     *  damit die Warnung ernst und klar verständlich bleibt. */
    private fun mixAudio(
        monoPcm: ByteArray,
        durationUs: Long,
        speechRanges: List<LongRange>,
        wantMusic: Boolean,
        mood: String,
        warnRanges: List<LongRange> = emptyList()
    ): ByteArray {
        val frames = monoPcm.size / 2
        if (frames == 0) return ByteArray(0)
        val totalSec = durationUs / 1_000_000.0
        val firstSpeech = speechRanges.firstOrNull()
        val lastSpeech = speechRanges.lastOrNull()
        val speechStartSec = if (firstSpeech != null) firstSpeech.first / 1_000_000.0 else totalSec
        val speechEndSec = if (lastSpeech != null) lastSpeech.last / 1_000_000.0 else 0.0
        val loop = if (wantMusic) buildPadLoop(mood) else null
        val loopFrames = if (loop != null) loop.size / 2 else 0
        val out = ByteArray(frames * 4)
        // Sicherheits-Fenster in Sekunden für die Musik-Absenkung
        val warnSec = warnRanges.map { Pair(it.first / 1_000_000.0, it.last / 1_000_000.0) }
        for (f in 0 until frames) {
            val s = (((monoPcm[f * 2].toInt() and 0xff) or ((monoPcm[f * 2 + 1].toInt() and 0xff) shl 8))).toShort()
            var l = s.toInt()
            var r = s.toInt()
            if (loop != null && loopFrames > 0) {
                val t = f / TARGET_RATE.toDouble()
                var g = 0.5f
                if (t < 1.5) g *= (t / 1.5).toFloat()
                val remain = totalSec - t
                // Ausklingen im Outro etwas länger, damit das Video weich endet
                if (remain < 2.5) g *= (remain / 2.5).coerceAtLeast(0.0).toFloat()
                // Ducking: Musik wird unter der Sprachausgabe automatisch leiser
                val ds = speechStartSec - 0.4
                val de = speechEndSec + 0.4
                if (t > ds && t < de) {
                    g *= when {
                        t < ds + 0.6 -> 1f - 0.68f * ((t - ds) / 0.6).toFloat()
                        t > de - 0.6 -> 1f - 0.68f * ((de - t) / 0.6).toFloat()
                        else -> 0.32f
                    }
                }
                // Sicherheits-Szene: Musik fast ganz absenken – die Warnung
                // soll ernst rüberkommen, nicht von der Melodie geschluckt.
                if (warnSec.any { t >= it.first && t < it.second }) g *= 0.2f
                val idx = (f % loopFrames) * 2
                l += (loop[idx] * g * 32767f).toInt()
                r += (loop[idx + 1] * g * 32767f).toInt()
            }
            l = l.coerceIn(-32767, 32767)
            r = r.coerceIn(-32767, 32767)
            out[f * 4] = (l and 0xff).toByte()
            out[f * 4 + 1] = ((l shr 8) and 0xff).toByte()
            out[f * 4 + 2] = (r and 0xff).toByte()
            out[f * 4 + 3] = ((r shr 8) and 0xff).toByte()
        }
        return out
    }

    /**
     * Baut den Musik-Loop der gewählten Stimmung – vollständig im Code erzeugt,
     * daher lizenzfrei und ohne Zusatzdateien:
     * • „neugierig“ (Standard): 16 s Ambient-Pad (A – E – F#m – D)
     * • „froehlich“: 16 s fröhliche Dur-Akkordfolge (C – G – Am – F – C – G – F – G)
     * • „ruhig“: 24 s tiefe, langsame Akkorde (a – F – C – G)
     */
    private fun buildPadLoop(mood: String): FloatArray {
        val chords: Array<DoubleArray>
        val totalSec: Double
        val chordLen: Double
        val overlap: Double
        val amp: Double
        when (mood) {
            "froehlich" -> {
                totalSec = 16.0
                chordLen = 2.0
                overlap = 0.5
                amp = 0.24
                chords = arrayOf(
                    doubleArrayOf(130.81, 196.0, 261.63, 329.63),   // C
                    doubleArrayOf(196.0, 246.94, 293.66, 392.0),    // G
                    doubleArrayOf(220.0, 261.63, 329.63, 440.0),    // Am
                    doubleArrayOf(174.61, 220.0, 261.63, 349.23),   // F
                    doubleArrayOf(130.81, 196.0, 261.63, 329.63),   // C
                    doubleArrayOf(196.0, 246.94, 293.66, 392.0),    // G
                    doubleArrayOf(174.61, 220.0, 261.63, 349.23),   // F
                    doubleArrayOf(196.0, 246.94, 293.66, 392.0)     // G
                )
            }
            "ruhig" -> {
                totalSec = 24.0
                chordLen = 6.0
                overlap = 2.0
                amp = 0.22
                chords = arrayOf(
                    doubleArrayOf(110.0, 164.81, 220.0, 277.18),    // a
                    doubleArrayOf(87.31, 130.81, 174.61, 220.0),    // F
                    doubleArrayOf(130.81, 196.0, 261.63, 329.63),   // C
                    doubleArrayOf(98.0, 146.83, 196.0, 246.94)      // G
                )
            }
            else -> {
                totalSec = 16.0
                chordLen = 4.0
                overlap = 1.0
                amp = 0.30
                chords = arrayOf(
                    doubleArrayOf(110.0, 164.81, 220.0, 277.18, 329.63),
                    doubleArrayOf(123.47, 164.81, 207.65, 246.94, 329.63),
                    doubleArrayOf(92.5, 138.59, 185.0, 220.0, 277.18),
                    doubleArrayOf(146.83, 220.0, 293.66, 369.99, 440.0)
                )
            }
        }
        val n = (totalSec * TARGET_RATE).toInt()
        val out = FloatArray(n * 2)
        for (c in chords.indices) {
            val startS = c * chordLen
            val dur = if (c == chords.size - 1) chordLen else chordLen + overlap
            val s0 = (startS * TARGET_RATE).toInt()
            val s1 = minOf(((startS + dur) * TARGET_RATE).toInt(), n)
            val tones = chords[c]
            for (i in s0 until s1) {
                val t = (i - s0).toDouble() / TARGET_RATE
                var env = minOf(t / 1.0, (dur - t) / 1.0, 1.0).coerceAtLeast(0.0)
                env = env * env
                var lv = 0.0
                var rv = 0.0
                for (k in tones.indices) {
                    val w = 2.0 * Math.PI * tones[k]
                    lv += Math.sin(w * t) * 0.55 + Math.sin(w * 2.0 * t) * 0.18 + Math.sin(w * 3.0 * t) * 0.06
                    rv += Math.sin(w * 1.003 * t) * 0.55 + Math.sin(w * 2.006 * t) * 0.18 + Math.sin(w * 3.009 * t) * 0.06
                }
                val a = env * amp / tones.size
                out[i * 2] += (lv * a).toFloat()
                out[i * 2 + 1] += (rv * a).toFloat()
            }
        }
        var peak = 0f
        for (v in out) if (abs(v) > peak) peak = abs(v)
        if (peak > 0.5f) {
            val norm = 0.5f / peak
            for (i in out.indices) out[i] *= norm
        }
        return out
    }

    /** Liest eine WAV-Datei (16-Bit-PCM) und extrahiert die Rohdaten. */
    private fun parseWav(file: File): SlideAudio {
        val bytes = file.readBytes()
        if (bytes.size < 44) throw ExportException("Audio-Datei zu klein.")
        var sampleRate = TARGET_RATE
        var channels = 1
        var dataOffset = -1
        var dataLen = 0
        var i = 12
        while (i + 8 <= bytes.size) {
            val id = String(bytes, i, 4, Charsets.US_ASCII)
            val len = readIntLE(bytes, i + 4)
            if (id == "fmt ") {
                channels = readShortLE(bytes, i + 10).toInt().coerceAtLeast(1)
                sampleRate = readIntLE(bytes, i + 12)
            } else if (id == "data") {
                dataOffset = i + 8
                dataLen = minOf(len, bytes.size - dataOffset)
            }
            if (len <= 0) break
            i += 8 + len + (len % 2)
        }
        if (dataOffset < 0 || dataLen <= 0) throw ExportException("Audio-Daten nicht gefunden.")
        return SlideAudio(bytes.copyOfRange(dataOffset, dataOffset + dataLen), sampleRate, channels)
    }

    /** Wandelt eine Spur in Mono mit fester Abtastrate um (nötig für den AAC-Encoder). */
    private fun toMonoTarget(a: SlideAudio): ByteArray {
        val mono: ShortArray = if (a.channels >= 2) {
            val frames = a.pcm.size / 4
            ShortArray(frames) { i ->
                val l = readShortLE(a.pcm, i * 4)
                val r = readShortLE(a.pcm, i * 4 + 2)
                ((l.toInt() + r.toInt()) / 2).toShort()
            }
        } else {
            val frames = a.pcm.size / 2
            ShortArray(frames) { i -> readShortLE(a.pcm, i * 2) }
        }
        if (a.sampleRate == TARGET_RATE || mono.isEmpty()) return shortsToBytes(mono)
        val ratio = a.sampleRate.toDouble() / TARGET_RATE
        val outLen = (mono.size / ratio).toInt().coerceAtLeast(1)
        val out = ShortArray(outLen)
        for (i in 0 until outLen) {
            val pos = i * ratio
            val i0 = pos.toInt().coerceAtMost(mono.size - 1)
            val i1 = (i0 + 1).coerceAtMost(mono.size - 1)
            val frac = (pos - i0).toFloat()
            out[i] = (mono[i0] * (1f - frac) + mono[i1] * frac).toInt().toShort()
        }
        return shortsToBytes(out)
    }

    private fun shortsToBytes(a: ShortArray): ByteArray {
        val out = ByteArray(a.size * 2)
        for (i in a.indices) {
            val v = a[i].toInt()
            out[i * 2] = (v and 0xff).toByte()
            out[i * 2 + 1] = ((v shr 8) and 0xff).toByte()
        }
        return out
    }

    private fun readIntLE(b: ByteArray, o: Int): Int =
        (b[o].toInt() and 0xff) or
            ((b[o + 1].toInt() and 0xff) shl 8) or
            ((b[o + 2].toInt() and 0xff) shl 16) or
            ((b[o + 3].toInt() and 0xff) shl 24)

    private fun readShortLE(b: ByteArray, o: Int): Short =
        (((b[o].toInt() and 0xff) or ((b[o + 1].toInt() and 0xff) shl 8))).toShort()

    /** Encodiert die Stereotonspur als AAC und liefert Format + Samples. */
    private fun encodeAudio(pcm: ByteArray, sampleRate: Int, channels: Int): Pair<MediaFormat, List<Sample>> {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channels)
        format.setInteger(MediaFormat.KEY_BIT_RATE, 192000)
        format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 32768)
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
        val samples = mutableListOf<Sample>()
        var outFormat: MediaFormat? = null
        var offset = 0
        var inputDone = false
        try {
            while (true) {
                if (!inputDone) {
                    val idx = codec.dequeueInputBuffer(10000)
                    if (idx >= 0) {
                        if (offset >= pcm.size) {
                            val finalPts = pcm.size * 1000000L / (sampleRate * 2 * channels)
                            codec.queueInputBuffer(idx, 0, 0, finalPts, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            val buf = codec.getInputBuffer(idx)
                            if (buf != null) {
                                buf.clear()
                                val n = minOf(buf.remaining(), pcm.size - offset)
                                buf.put(pcm, offset, n)
                                codec.queueInputBuffer(idx, 0, n, offset * 1000000L / (sampleRate * 2 * channels), 0)
                                offset += n
                            } else {
                                inputDone = true

                            }
                        }
                    }
                }
                val info = MediaCodec.BufferInfo()
                val oidx = codec.dequeueOutputBuffer(info, if (inputDone) 10000 else 0)
                if (oidx >= 0) {
                    val buf = codec.getOutputBuffer(oidx)
                    if (buf != null && info.size > 0) {
                        val arr = ByteArray(info.size)
                        buf.get(arr)
                        samples.add(Sample(arr, info.presentationTimeUs, info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME))
                    }
                    codec.releaseOutputBuffer(oidx, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                } else if (oidx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    outFormat = MediaFormat(codec.outputFormat)
                }
            }
        } finally {
            codec.stop()
            codec.release()
        }
        return Pair(outFormat ?: format, samples)
    }

    /** Encodiert die animierten Videoframes als H.264-Videospur (24 Bilder/s). */
    private fun encodeVideo(durationUs: Long, pixelsAt: (Long) -> IntArray, progress: (Float) -> Unit = { }): Pair<MediaFormat, List<Sample>> {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, WIDTH, HEIGHT)
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
        format.setInteger(MediaFormat.KEY_BIT_RATE, WIDTH * HEIGHT * 3)
        format.setInteger(MediaFormat.KEY_FRAME_RATE, 24)
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
        // Tatsächlich verhandeltes Eingabe-Farbformat – entscheidet, wie rohe
        // YUV-Puffer im Fallback-Pfad (ohne Eingabe-Image) angeordnet sind.
        val semiPlanar = try {
            val fmt = codec.inputFormat.getInteger(MediaFormat.KEY_COLOR_FORMAT)
            fmt == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar ||
                fmt == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420PackedSemiPlanar ||
                (fmt and 0x7F000000.toInt()) != 0
        } catch (e: Exception) {
            true
        }
        val samples = mutableListOf<Sample>()
        var outFormat: MediaFormat? = null
        var t = 0L
        var lastPf = 0f
        var inputDone = false
        try {
            while (true) {
                if (!inputDone) {
                    val idx = codec.dequeueInputBuffer(10000)
                    if (idx >= 0) {
                        if (t >= durationUs) {
                            codec.queueInputBuffer(idx, 0, 0, durationUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            val image = codec.getInputImage(idx)
                            if (image != null) {
                                fillYuv420(image, pixelsAt(t))
                            } else {
                                // Fallback: Manche Geräte liefern kein Eingabebild,
                                // akzeptieren aber rohe YUV-Puffer (semi-planar oder planar).
                                val buf = codec.getInputBuffer(idx)
                                if (buf == null) {
                                    throw ExportException("Der Video-Encoder wird von diesem Gerät nicht unterstützt.")
                                }
                                fillYuvBuffer(buf, pixelsAt(t), semiPlanar)
                            }
                            codec.queueInputBuffer(idx, 0, WIDTH * HEIGHT * 3 / 2, t, 0)
                            val pf = (t.toFloat() / durationUs.toFloat()).coerceIn(0f, 1f)
                            if (pf - lastPf >= 0.005f) {
                                lastPf = pf
                                progress(pf)
                            }
                            t += FRAME_INTERVAL_US
                        }
                    }
                }
                val info = MediaCodec.BufferInfo()
                val oidx = codec.dequeueOutputBuffer(info, if (inputDone) 10000 else 0)
                if (oidx >= 0) {
                    val buf = codec.getOutputBuffer(oidx)
                    if (buf != null && info.size > 0) {
                        val arr = ByteArray(info.size)
                        buf.get(arr)
                        samples.add(Sample(arr, info.presentationTimeUs, info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME))
                    }
                    codec.releaseOutputBuffer(oidx, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                } else if (oidx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    outFormat = MediaFormat(codec.outputFormat)
                }
            }
        } finally {
            codec.stop()
            codec.release()
        }
        return Pair(outFormat ?: format, samples)
    }

    /** Füllt einen rohen YUV-Eingabepuffer (Fallback, wenn kein Eingabebild verfügbar ist). */
    private fun fillYuvBuffer(buf: ByteBuffer, argb: IntArray, semiPlanar: Boolean) {
        val y = ByteArray(WIDTH * HEIGHT)
        val u = ByteArray(WIDTH * HEIGHT / 4)
        val v = ByteArray(WIDTH * HEIGHT / 4)
        for (h in 0 until HEIGHT) {
            for (w in 0 until WIDTH) {
                val c = argb[h * WIDTH + w]
                val r = (c shr 16) and 0xff
                val g = (c shr 8) and 0xff
                val b = c and 0xff
                y[h * WIDTH + w] = (((66 * r + 129 * g + 25 * b + 128) shr 8) + 16).toByte()
            }
        }
        for (h in 0 until HEIGHT / 2) {
            for (w in 0 until WIDTH / 2) {
                val c = argb[(2 * h + 1) * WIDTH + (2 * w + 1)]
                val r = (c shr 16) and 0xff
                val g = (c shr 8) and 0xff
                val b = c and 0xff
                u[h * (WIDTH / 2) + w] = (((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128).toByte()
                v[h * (WIDTH / 2) + w] = (((112 * r - 94 * g - 18 * b + 128) shr 8) + 128).toByte()
            }
        }
        buf.clear()
        buf.put(y)
        if (semiPlanar) {
            for (i in u.indices) {
                buf.put(u[i])
                buf.put(v[i])
            }
        } else {
            buf.put(u)
            buf.put(v)
        }
    }

    /** Schreibt ARGB-Pixel in die YUV-Ebenen eines Encoder-Eingabebildes. */
    private fun fillYuv420(image: Image, argb: IntArray) {
        val planes = image.planes
        val yPlane = planes[0]
        val uPlane = planes[1]
        val vPlane = planes[2]
        val yBuf = yPlane.buffer
        val uBuf = uPlane.buffer
        val vBuf = vPlane.buffer
        val yRow = yPlane.rowStride
        val yPix = yPlane.pixelStride
        // Schneller Pfad: Bei dichter Y-Ebene (pixelStride==1) die Zeile im
        // Speicher aufbauen und blockweise in den Puffer schreiben – das
        // spart hunderttausende einzelne Puffer-Zugriffe pro Frame.
        var fastYDone = false
        if (yPix == 1 && yRow >= WIDTH) {
            runCatching {
                val line = ByteArray(WIDTH)
                for (h in 0 until HEIGHT) {
                    val rowStart = h * WIDTH
                    for (w in 0 until WIDTH) {
                        val c = argb[rowStart + w]
                        val r = (c shr 16) and 0xff
                        val g = (c shr 8) and 0xff
                        val b = c and 0xff
                        line[w] = (((66 * r + 129 * g + 25 * b + 128) shr 8) + 16).toByte()
                    }
                    yBuf.position(h * yRow)
                    yBuf.put(line, 0, WIDTH)
                }
                fastYDone = true
            }
        }
        if (!fastYDone) {
            for (h in 0 until HEIGHT) {
                for (w in 0 until WIDTH) {
                    val c = argb[h * WIDTH + w]
                    val r = (c shr 16) and 0xff
                    val g = (c shr 8) and 0xff
                    val b = c and 0xff
                    val y = ((66 * r + 129 * g + 25 * b + 128) shr 8) + 16
                    yBuf.put(h * yRow + w * yPix, y.toByte())
                }
            }
        }
        val uRow = uPlane.rowStride
        val uPix = uPlane.pixelStride
        val vRow = vPlane.rowStride
        val vPix = vPlane.pixelStride
        for (h in 0 until HEIGHT / 2) {
            for (w in 0 until WIDTH / 2) {
                val c = argb[(2 * h + 1) * WIDTH + (2 * w + 1)]
                val r = (c shr 16) and 0xff
                val g = (c shr 8) and 0xff
                val b = c and 0xff
                val u = ((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128
                val v = ((112 * r - 94 * g - 18 * b + 128) shr 8) + 128
                uBuf.put(h * uRow + w * uPix, u.toByte())
                vBuf.put(h * vRow + w * vPix, v.toByte())
            }
        }
    }

    /** Muxt Video- und Tonspur in eine MP4-Datei im App-Cache. */
    private fun mux(context: Context, audio: Pair<MediaFormat, List<Sample>>, video: Pair<MediaFormat, List<Sample>>): File {
        val file = File(context.cacheDir, "erklarvideo_" + System.currentTimeMillis() + ".mp4")
        val muxer = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        try {
            val vTrack = muxer.addTrack(video.first)
            val aTrack = muxer.addTrack(audio.first)
            muxer.start()
            for (s in video.second) {
                val info = MediaCodec.BufferInfo()
                info.set(0, s.data.size, s.ptsUs, s.flags)
                muxer.writeSampleData(vTrack, ByteBuffer.wrap(s.data), info)
            }
            for (s in audio.second) {
                val info = MediaCodec.BufferInfo()
                info.set(0, s.data.size, s.ptsUs, s.flags)
                muxer.writeSampleData(aTrack, ByteBuffer.wrap(s.data), info)
            }
            muxer.stop()
        } finally {
            muxer.release()
        }
        return file
    }

    /** Speichert das fertige Video in der Mediathek (ab Android 10) bzw. im App-Ordner. */
    private fun saveVideo(context: Context, file: File): ExportResult {
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, file.name)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/WerWieWas")
            }
            val uri = context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                ?: throw ExportException("Video konnte nicht gespeichert werden.")
            context.contentResolver.openOutputStream(uri)?.use { it.write(file.readBytes()) }
                ?: throw ExportException("Video konnte nicht gespeichert werden.")
            file.delete()
            return ExportResult("Video gespeichert unter Filme/WerWieWas/" + file.name, uri)
        }
        val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: context.filesDir, "videos")
        dir.mkdirs()
        val target = File(dir, file.name)
        file.copyTo(target, overwrite = true)
        file.delete()
        // Content-URI über den FileProvider, damit das Video direkt geteilt werden kann
        val shareUri = runCatching {
            FileProvider.getUriForFile(context, context.packageName + ".fileprovider", target)
        }.getOrNull()
        return ExportResult("Video gespeichert unter: " + target.absolutePath, shareUri)
    }

    /**
     * Ergebnis eines Audio-Exports (nur die gesprochene Erklärung, ohne Video):
     * Beschreibung des Speicherorts, eine Content-URI fürs Teilen und der
     * zugehörige MIME-Typ ("audio/mpeg" bzw. "audio/wav").
     */
    data class AudioExportResult(val message: String, val shareUri: Uri?, val mime: String)

    /**
     * Speichert die gesprochene Erklärung als Audiodatei: fertige Edge-TTS-MP3s
     * werden direkt aneinandergereiht (echtes MP3, ohne Qualitätsverlust und
     * ohne erneute Encodierung). Die Datei liegt unter Musik/WerWieWas.
     */
    fun saveAudioMp3(context: Context, base: String, mp3: ByteArray): AudioExportResult =
        saveAudio(context, base, mp3, "audio/mpeg", ".mp3")

    /**
     * Exportiert die gesprochene Erklärung mit den System-Stimmen als WAV-Datei
     * (44,1 kHz Stereo, mit 0,7 Sekunden Pause zwischen den Szenen und auf
     * YouTube-Lautstärke von −14 LUFS normalisiert). Fallback, wenn keine
     * Edge-TTS-Stimmen eingerichtet sind.
     */
    fun exportAudioWav(
        context: Context,
        slides: List<SlideData>,
        voiceA: Voice?,
        voiceB: Voice?,
        rate: Float,
        speechLang: String = "de",
        base: String = "WerWieWas-Erklaerung",
        onProgress: (Float) -> Unit = { }
    ): AudioExportResult {
        val audios = synthesizeSlides(context, slides, voiceA, voiceB, rate, null, speechLang) { p ->
            onProgress(0.1f + 0.6f * p.coerceIn(0f, 1f))
        }
        onProgress(0.75f)
        val lead = (0.4 * TARGET_RATE * 2).toInt()
        val gap = (0.7 * TARGET_RATE * 2).toInt()
        val out = ByteArrayOutputStream()
        out.write(ByteArray(lead))
        for (a in audios) {
            out.write(toMonoTarget(a))
            out.write(ByteArray(gap))
        }
        val monoPcm = out.toByteArray()
        // Mono auf Stereo verdoppeln, damit die Lautheits-Normalisierung
        // (die auf Stereo arbeitet) greift.
        val stereo = ByteArray(monoPcm.size * 2)
        var i = 0
        var o = 0
        while (i < monoPcm.size) {
            val b = monoPcm[i]
            val b2 = monoPcm[i + 1]
            stereo[o] = b
            stereo[o + 1] = b2
            stereo[o + 2] = b
            stereo[o + 3] = b2
            i += 2
            o += 4
        }
        val normalized = normalizeLoudness(stereo)
        onProgress(0.9f)
        val result = saveAudio(context, base, writeWavBytes(normalized, TARGET_RATE, 2), "audio/wav", ".wav")
        onProgress(1f)
        return result
    }

    /** Speichert eine Audiodatei im Musik-Ordner (ab Android 10) bzw. im App-Ordner (ältere Geräte). */
    private fun saveAudio(context: Context, base: String, bytes: ByteArray, mime: String, ext: String): AudioExportResult {
        val fileName = base + ext
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.Audio.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Audio.Media.MIME_TYPE, mime)
                put(MediaStore.Audio.Media.RELATIVE_PATH, Environment.DIRECTORY_MUSIC + "/WerWieWas")
            }
            val uri = context.contentResolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
                ?: throw ExportException("Audio konnte nicht gespeichert werden.")
            context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                ?: throw ExportException("Audio konnte nicht gespeichert werden.")
            return AudioExportResult("Audio gespeichert unter Musik/WerWieWas/" + fileName, uri, mime)
        }
        val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: context.filesDir, "audio")
        dir.mkdirs()
        val target = File(dir, fileName)
        FileOutputStream(target).use { it.write(bytes) }
        // Content-URI über den FileProvider, damit das Audio direkt geteilt werden kann
        val shareUri = runCatching {
            FileProvider.getUriForFile(context, context.packageName + ".fileprovider", target)
        }.getOrNull()
        return AudioExportResult("Audio gespeichert unter: " + target.absolutePath, shareUri, mime)
    }

    /** Schreibt 16-Bit-PCM als WAV-Bytes (RIFF-Header + Rohdaten). */
    private fun writeWavBytes(pcm: ByteArray, sampleRate: Int, channels: Int): ByteArray {
        val blockAlign = channels * 2
        val byteRate = sampleRate * blockAlign
        val out = ByteArray(44 + pcm.size)
        fun le32(v: Int, off: Int) {
            out[off] = (v and 0xff).toByte()
            out[off + 1] = ((v shr 8) and 0xff).toByte()
            out[off + 2] = ((v shr 16) and 0xff).toByte()
            out[off + 3] = ((v shr 24) and 0xff).toByte()
        }
        fun le16(v: Int, off: Int) {
            out[off] = (v and 0xff).toByte()
            out[off + 1] = ((v shr 8) and 0xff).toByte()
        }
        "RIFF".toByteArray().copyInto(out, 0)
        le32(36 + pcm.size, 4)
        "WAVE".toByteArray().copyInto(out, 8)
        "fmt ".toByteArray().copyInto(out, 12)
        le32(16, 16)
        le16(1, 20)
        le16(channels, 22)
        le32(sampleRate, 24)
        le32(byteRate, 28)
        le16(blockAlign, 32)
        le16(16, 34)
        "data".toByteArray().copyInto(out, 36)
        le32(pcm.size, 40)
        pcm.copyInto(out, 44)
        return out
    }

    /** Baut aus Szenen und Zeitbereichen den Inhalt einer SRT-Untertiteldatei. */
    /**
     * Baut die SRT-Untertitel: satzweise Cues, zeitlich proportional über die
     * Szene verteilt (gleiche Häppchen-Logik wie die eingebrannten Untertitel)
     * – statt einer einzigen Riesentafel pro Szene, die YouTube & Co. nur als
     * wandelnden Textblock anzeigen könnten.
     */
    private fun buildSrt(slides: List<SlideData>, ranges: List<LongRange>): String {
        val sb = StringBuilder()
        var cue = 1
        for (i in slides.indices) {
            val range = ranges.getOrNull(i) ?: continue
            val spanUs = range.last - range.first
            if (spanUs <= 0) continue
            val chunks = mutableListOf<String>()
            chunks.add(slides[i].title)
            for (line in slides[i].text.split('\n')) {
                val trimmed = line.trim()
                if (trimmed.isEmpty()) continue
                var cur = ""
                for (raw in trimmed.split(Regex("(?<=[.!?…])\\s+"))) {
                    val p = raw.trim()
                    if (p.isEmpty()) continue
                    if (cur.isNotEmpty() && cur.length + p.length + 1 > 80) {
                        chunks.add(cur)
                        cur = p
                    } else {
                        cur = if (cur.isEmpty()) p else cur + " " + p
                    }
                }
                if (cur.isNotEmpty()) chunks.add(cur)
            }
            if (chunks.isEmpty()) continue
            val totalLen = chunks.sumOf { it.length }.coerceAtLeast(1)
            // Wie bei den eingebrannten Untertiteln: echte Sprechpausen nach
            // Satzende in das Cue-Timing einrechnen (siehe buildCaptions).
            val pauseCount = chunks.count { endsSentence(it) }
            val cueSpanUs = (spanUs - CUE_PAUSE_US * pauseCount).coerceAtLeast(1_000L)
            var t = range.first
            for (ch in chunks) {
                val d = cueSpanUs * ch.length / totalLen + (if (endsSentence(ch)) CUE_PAUSE_US else 0L)
                val start = t
                val end = (t + d).coerceAtMost(range.last)
                if (end > start) {
                    sb.append(cue).append('\n')
                    sb.append(srtTime(start)).append(" --> ").append(srtTime(end)).append('\n')
                    sb.append(ch).append('\n')
                    sb.append('\n')
                    cue++
                }
                t += d
            }
        }
        return sb.toString()
    }

    /** Formatiert einen Mikrosekunden-Zeitpunkt als SRT-Zeitstempel (HH:MM:SS,mmm). */
    private fun srtTime(us: Long): String {
        val ms = us / 1000
        return String.format(
            Locale.US,
            "%02d:%02d:%02d,%03d",
            ms / 3600000,
            (ms % 3600000) / 60000,
            (ms % 60000) / 1000,
            ms % 1000
        )
    }

    /** Speichert die SRT-Untertiteldatei in Downloads (ab Android 10) bzw. im App-Ordner. */
    private fun saveSrt(context: Context, videoBase: String, content: String): Uri? {
        // UTF-8-BOM voranstellen: Ältere Player (z. B. unter Windows) zeigen
        // ohne BOM deutsche Umlaute in Untertiteln als kaputte Zeichen an.
        val bytes = ("\uFEFF" + content).toByteArray(Charsets.UTF_8)
        val fileName = videoBase + ".srt"
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, "application/x-subrip")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/WerWieWas")
            }
            val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: return null
            context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
            return uri
        }
        return runCatching {
            val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: context.filesDir, "videos")
            dir.mkdirs()
            val target = File(dir, fileName)
            target.writeBytes(bytes)
            FileProvider.getUriForFile(context, context.packageName + ".fileprovider", target)
        }.getOrNull()
    }

    /**
     * Bereitet eine Szene als Ebenen vor: Grundbild (Marke, Emoji, Quellenzeile,
     * Szenen-Chip, Wasserzeichen), separat skaliertes Foto für den Ken-Burns-Zoom
     * und eine transparente Textebene für den weichen Text-Einzug.
     * Bei eingebrannten Untertiteln trägt die Szene nur den Titel, der Fließtext
     * erscheint als zeitgesteuerte Untertitel-Box.
     */
    private fun renderSlideGfx(
        slide: SlideData,
        index: Int,
        totalSlides: Int,
        burnedSubs: Boolean,
        speechDurUs: Long
    ): SlideGfx {
        val base = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(base)
        val colors = palettes[index % palettes.size]
        val bg = Paint()
        bg.shader = LinearGradient(0f, 0f, 0f, HEIGHT.toFloat(), colors[0], colors[1], Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, WIDTH.toFloat(), HEIGHT.toFloat(), bg)
        // Dezente Vignette: dunkelt die Ränder ab und gibt dem Bild Tiefe
        val vignette = Paint()
        vignette.shader = RadialGradient(
            WIDTH / 2f, HEIGHT / 2f, maxOf(WIDTH, HEIGHT) * 0.75f,
            intArrayOf(Color.TRANSPARENT, Color.argb(70, 0, 0, 0)),

            floatArrayOf(0.55f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, WIDTH.toFloat(), HEIGHT.toFloat(), vignette)

        var top = if (HEIGHT > WIDTH) 150f * scale else 90f * scale
        var photo: Bitmap? = null
        var photoRect: RectF? = null
        if (slide.photoPath != null) {
            val src = BitmapFactory.decodeFile(slide.photoPath)
            if (src != null) {
                val photoH = minOf(600f * scale, HEIGHT * 0.32f)
                val target = RectF(60f * scale, top, WIDTH - 60f * scale, top + photoH)
                photo = scalePhotoTo(src, target)
                photoRect = target
                src.recycle()
                top += photoH + 60f * scale
            }
        } else if (slide.webImagePath != null) {
            // Passendes Online-Themenbild (frei lizenziert, mit Quellenzeile)
            val src = BitmapFactory.decodeFile(slide.webImagePath)
            if (src != null) {
                val webH = minOf(460f * scale, HEIGHT * 0.26f)
                val target = RectF(60f * scale, top, WIDTH - 60f * scale, top + webH)
                photo = scalePhotoTo(src, target)
                photoRect = target
                src.recycle()
                top += webH + 20f * scale
                val credit = slide.webImageCredit
                if (!credit.isNullOrBlank()) {
                    val cp = Paint(Paint.ANTI_ALIAS_FLAG)
                    cp.textSize = 26f * scale
                    cp.textAlign = Paint.Align.CENTER
                    cp.color = Color.argb(210, 255, 255, 255)
                    canvas.drawText("Bild: " + credit, WIDTH / 2f, top + 18f * scale, cp)
                    top += 64f * scale
                }
            }
        }
        if (slide.emoji.isNotBlank() && photo == null) {
            val p = Paint(Paint.ANTI_ALIAS_FLAG)
            p.textSize = 190f * scale
            p.textAlign = Paint.Align.CENTER
            p.color = Color.WHITE
            val fm = p.fontMetrics
            canvas.drawText(slide.emoji, WIDTH / 2f, top + 95f * scale - (fm.top + fm.bottom) / 2f, p)
            top += 280f * scale
        }

        drawSceneChip(canvas, index, totalSlides)
        drawWatermark(canvas)

        // Textebene: Titel (und ohne eingebrannte Untertitel auch der Fließtext),
        // liegt auf einem abgedunkelten Glas-Panel mit blauem Marken-Akzent (Profi-Look).
        val panelPadTop = 28f * scale
        val panelPadBottom = 30f * scale
        val accentH = 6f * scale
        val accentGap = 18f * scale
        val bodyGap = 46f * scale
        val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
        titlePaint.textSize = 62f * scale
        titlePaint.isFakeBoldText = true
        titlePaint.color = Color.WHITE
        titlePaint.setShadowLayer(3f * scale, 0f, 2f * scale, Color.argb(110, 0, 0, 0))
        val titleLayout = StaticLayout.Builder.obtain(
            slide.title, 0, slide.title.length, titlePaint, (WIDTH - 160f * scale).toInt()
        ).setAlignment(Layout.Alignment.ALIGN_CENTER).build()

        val makeBody: (Float) -> StaticLayout = { size ->
            val bp = TextPaint(Paint.ANTI_ALIAS_FLAG)
            bp.textSize = size
            bp.color = Color.WHITE
            bp.setShadowLayer(2f * scale, 0f, 1f * scale, Color.argb(90, 0, 0, 0))
            StaticLayout.Builder.obtain(
                slide.text, 0, slide.text.length, bp, (WIDTH - 160f * scale).toInt()
            ).setAlignment(Layout.Alignment.ALIGN_CENTER).setLineSpacing(0f, 1.25f).build()
        }
        var bodyLayout: StaticLayout? = null
        if (!burnedSubs && slide.text.isNotBlank()) {
            var bodySize = 42f * scale
            var bl = makeBody(bodySize)
            while (top + panelPadTop + accentH + accentGap + titleLayout.height + bodyGap + bl.height + panelPadBottom > HEIGHT - 140f * scale && bodySize > 26f * scale) {
                bodySize -= 4f * scale
                bl = makeBody(bodySize)
            }
            bodyLayout = bl
        }
        val textTop = top
        val body = bodyLayout
        val layerH = (titleLayout.height +
            (if (body != null) bodyGap + body.height else 0f) +
            panelPadTop + accentH + accentGap + panelPadBottom)
            .toInt().coerceAtLeast(1)
            .coerceAtMost((HEIGHT - textTop).toInt().coerceAtLeast(1))
        val textLayer = Bitmap.createBitmap(WIDTH, layerH, Bitmap.Config.ARGB_8888)
        val tc = Canvas(textLayer)
        // Glas-Panel hinter dem Text: deutlich bessere Lesbarkeit auf jedem Hintergrund
        val panelPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        panelPaint.color = Color.argb(80, 6, 10, 26)
        tc.drawRoundRect(
            RectF(30f * scale, 0f, WIDTH - 30f * scale, layerH.toFloat()),
            26f * scale, 26f * scale, panelPaint
        )
        // Blauer Marken-Akzentbalken über dem Titel
        val accentPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        accentPaint.color = Color.rgb(66, 133, 244)
        tc.drawRoundRect(
            RectF(WIDTH / 2f - 30f * scale, panelPadTop, WIDTH / 2f + 30f * scale, panelPadTop + accentH),
            accentH / 2f, accentH / 2f, accentPaint
        )
        tc.save()
        tc.translate((WIDTH - titleLayout.width) / 2f, panelPadTop + accentH + accentGap)
        titleLayout.draw(tc)
        tc.restore()
        if (body != null) {
            tc.save()
            tc.translate(
                (WIDTH - body.width) / 2f,
                panelPadTop + accentH + accentGap + titleLayout.height + bodyGap
            )
            body.draw(tc)
            tc.restore()
        }

        val captions = if (burnedSubs) buildCaptions(slide, speechDurUs) else emptyList()
        return SlideGfx(base, photo, photoRect, textLayer, textTop, layerH, captions)
    }

    /**
     * Mindest-Anzeigedauer einer Szene nach Textlänge (etwa 14 Zeichen pro
     * Sekunde plus 2,5 s Anlauf): Der komplette Text bleibt solange sichtbar,
     * selbst wenn die Sprachausgabe fehlt oder kürzer ist.
     */
    /** Sprechpause nach Satzende (SSML-Break bzw. Satzpausen der
     *  System-Synthese), die beim Untertitel-Timing berücksichtigt wird. */
    private const val CUE_PAUSE_US = 300_000L

    /** True, wenn ein Untertitel-/SRT-Cue auf ein Satzzeichen endet –
     *  danach pausiert die Stimme, bevor der nächste Cue gesprochen wird. */
    private fun endsSentence(text: String): Boolean =
        text.isNotEmpty() && text.last() in ".!?…"

    private fun minSceneUs(text: String): Long {
        val len = text.length.coerceAtMost(400)
        return (2_500_000L + len * 70_000L).coerceIn(3_000_000L, 25_000_000L)
    }

    /** Teilt den Szenentext in kurze Untertitel-Häppchen mit proportionaler Zeitlast. */
    private fun buildCaptions(slide: SlideData, speechDurUs: Long): List<Caption> {
        if (speechDurUs <= 0 || slide.text.isBlank()) return emptyList()
        val chunks = mutableListOf<String>()
        for (line in slide.text.split('\n')) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue
            var cur = ""
            for (raw in trimmed.split(Regex("(?<=[.!?…])\\s+"))) {
                val p = raw.trim()
                if (p.isEmpty()) continue
                if (cur.isNotEmpty() && cur.length + p.length + 1 > 90) {
                    chunks.add(cur)
                    cur = p
                } else {
                    cur = if (cur.isEmpty()) p else cur + " " + p
                }
            }
            if (cur.isNotEmpty()) chunks.add(cur)
        }
        if (chunks.isEmpty()) return emptyList()
        val totalLen = chunks.sumOf { it.length }.coerceAtLeast(1)
        // Echte Sprechpausen einrechnen: Nach Satzenden pausiert die Stimme
        // (SSML-Breaks bzw. Satzpausen der System-Synthese). Der Satz-Cue
        // bleibt dafür entsprechend länger stehen und der nächste Cue
        // startet erst, wenn wirklich weitergesprochen wird – so treffen
        // die Untertitel den Redemoment, statt zu früh umzuschalten.
        val pauseCount = chunks.count { endsSentence(it) }
        val cueSpeechUs = (speechDurUs - CUE_PAUSE_US * pauseCount).coerceAtLeast(1_000L)
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG)
        paint.textSize = 34f * scale
        paint.isFakeBoldText = true
        paint.color = Color.WHITE
        paint.setShadowLayer(2f * scale, 0f, 1f * scale, Color.argb(160, 0, 0, 0))
        val maxW = (WIDTH - 90f * scale).toInt()
        val padH = 20f * scale
        val padV = 14f * scale
        var t = 0L
        val out = mutableListOf<Caption>()
        for (ch in chunks) {
            val d = cueSpeechUs * ch.length / totalLen + (if (endsSentence(ch)) CUE_PAUSE_US else 0L)
            val layout = StaticLayout.Builder.obtain(ch, 0, ch.length, paint, maxW)
                .setAlignment(Layout.Alignment.ALIGN_CENTER)
                .setLineSpacing(0f, 1.15f)
                .build()
            val boxW = minOf(layout.width + padH * 2, maxW + padH * 2)
            val boxH = layout.height + padV * 2
            out.add(Caption(t, t + d, ch, layout, boxW, boxH))
            t += d
        }
        return out
    }

    /** Skaliert ein Foto formatfüllend in das Zielrechteck plus Zoom-Reserve (Ken Burns). */
    private fun scalePhotoTo(src: Bitmap, target: RectF): Bitmap {
        val pad = 1f + ZOOM_AMOUNT + 0.04f
        val w = (target.width() * pad).toInt().coerceAtLeast(8)
        val h = (target.height() * pad).toInt().coerceAtLeast(8)
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        drawCenterCrop(c, src, RectF(0f, 0f, w.toFloat(), h.toFloat()))
        return out
    }

    /** Kleiner Chip oben rechts mit der Szenen-Nummer. */
    private fun drawSceneChip(c: Canvas, index: Int, total: Int) {
        val label = "Szene " + (index + 1) + " / " + total
        val p = TextPaint(Paint.ANTI_ALIAS_FLAG)
        p.textSize = 26f * scale
        p.isFakeBoldText = true
        p.color = Color.argb(200, 255, 255, 255)
        val chip = Paint(Paint.ANTI_ALIAS_FLAG)
        chip.color = Color.argb(60, 255, 255, 255)
        val w = p.measureText(label)
        val padH = 16f * scale
        val rect = RectF(
            WIDTH - w - padH * 2 - 24f * scale,
            20f * scale,
            WIDTH - 24f * scale,
            64f * scale
        )
        c.drawRoundRect(rect, 22f * scale, 22f * scale, chip)
        c.drawText(label, rect.left + padH, rect.centerY() - (p.fontMetrics.top + p.fontMetrics.bottom) / 2f, p)
    }

    /**
     * Wasserzeichen mittig am unteren Rand jeder Szene: die echte App-Logo-Marke
     * (gleiche Vektorgrafik wie das Launcher-Icon – Kamera mit blauem Gehirn-Kreis,
     * Lupe mit Fragezeichen, drei farbige Sprechblasen) plus Schriftzug
     * „Wer Wie Was“ mit blauem „Wie“.
     */
    private fun drawWatermark(canvas: Canvas) {
        val mark = logoMark ?: return
        val markH = 40f * scale
        val markW = markH * 1.35f
        val text = TextPaint(Paint.ANTI_ALIAS_FLAG)
        text.textSize = 28f * scale
        text.isFakeBoldText = true
        text.setShadowLayer(4f * scale, 0f, 2f * scale, Color.argb(130, 0, 0, 0))
        val w1 = text.measureText("Wer ")
        val w2 = text.measureText("Wie")
        val w3 = text.measureText(" Was")
        val gap = 14f * scale
        val total = markW + gap + w1 + w2 + w3
        val cy = HEIGHT - 70f * scale
        val startX = (WIDTH - total) / 2f
        drawLogoMark(canvas, mark, startX + markW / 2f, cy, markH, 225)
        // Schriftzug mit blauem „Wie“
        val base = cy + text.textSize * 0.34f
        var x = startX + markW + gap
        text.color = Color.argb(225, 255, 255, 255)
        canvas.drawText("Wer ", x, base, text)
        x += w1
        text.color = Color.rgb(66, 133, 244)
        canvas.drawText("Wie", x, base, text)
        x += w2
        text.color = Color.argb(225, 255, 255, 255)
        canvas.drawText(" Was", x, base, text)
    }

    /**
     * Zeichnet die App-Logo-Marke aus dem Launcher-Icon-Vektor (108er-Viewport)
     * so, dass der Logo-Inhalt (etwa x 29–83.6, y 42.7–83.2 im Viewport) mittig
     * um (cx, cy) mit der Inhalts-Höhe h sitzt.
     */
    private fun drawLogoMark(c: Canvas, mark: Drawable, cx: Float, cy: Float, h: Float, alpha: Int) {
        val d = h * 108f / 40.5f
        val left = cx - (56.3f / 108f) * d
        val top = cy - (62.9f / 108f) * d
        mark.alpha = alpha.coerceIn(0, 255)
        mark.setBounds(left.toInt(), top.toInt(), (left + d).toInt(), (top + d).toInt())
        mark.draw(c)
    }

    // ---------- Absturz-Schutz: Cache encodierter Spuren ----------

    /** Fertig encodierte Ton- oder Videospur mit den nötigen Zusatzinformationen. */
    /**
     * Fertig encodierte Spur aus dem Cache. `speechRanges` enthält eine
     * Spanne pro Szene (Anzeige-Fenster inklusive Sprechpausen) – die
     * Grundlage für die satzweisen SRT-Untertitel.
     */
    private class TrackCache(
        val mime: String,
        val format: MediaFormat,
        val samples: List<Sample>,
        val durationUs: Long,
        val speechRanges: List<LongRange>
    )

    /** Fasst alle Einflussfaktoren des Videos zu einem Cache-Schlüssel zusammen. */
    private fun trackCacheHash(
        slides: List<SlideData>,
        rate: Float,
        voiceA: String?,
        voiceB: String?,
        preAudio: List<File?>?,
        burnedSubs: Boolean,
        music: Boolean,
        mood: String,
        width: Int,
        height: Int,
        speechLang: String = "de"
    ): String {
        var h = 17
        // Cache-Format-Version 3: Intro-Ausgangsbild und Foto-Übergangs-Clips
        // ändern das Timing komplett, und Pre-Audio-Dateien fließen jetzt mit
        // Größe + Änderungsdatum in den Schlüssel ein – alte (ggf. stumme)
        // Tonspuren werden dadurch sicher verworfen.
        h = h * 31 + 3
        for (s in slides) {
            h = h * 31 + s.title.hashCode()
            h = h * 31 + s.text.hashCode()
            h = h * 31 + s.emoji.hashCode()
            h = h * 31 + (s.photoPath?.hashCode() ?: 0)
            h = h * 31 + (s.webImagePath?.hashCode() ?: 0)
        }
        h = h * 31 + java.lang.Float.floatToIntBits(rate)
        h = h * 31 + (voiceA ?: "").hashCode()
        h = h * 31 + (voiceB ?: "").hashCode()
        // Pre-Audio-Dateien namentlich in den Schlüssel aufnehmen: Ihre Namen
        // enthalten Stimme, Tempo und Pausen-Faktor – so wird der Tonspur-Cache
        // nach einem Zielgruppen- oder Stimmwechsel sicher verworfen.
        for (p in (preAudio ?: emptyList())) {
            h = h * 31 + (p?.name?.hashCode() ?: 0)
            // Größe + Änderungsdatum: Eine leere oder defekte TTS-Datei (z. B.
            // nach einem Netzwerkfehler) darf nie denselben Schlüssel liefern
            // wie eine später korrekt erzeugte – sonst bliebe der Ton für immer weg.
            h = h * 31 + (p?.length()?.hashCode() ?: 0)
            h = h * 31 + (p?.lastModified()?.hashCode() ?: 0)
        }
        h = h * 31 + if (burnedSubs) 1 else 0
        h = h * 31 + if (music) 1 else 0
        h = h * 31 + mood.hashCode()
        h = h * 31 + width
        h = h * 31 + height
        h = h * 31 + speechLang.hashCode()
        return Integer.toHexString(h)
    }

    /** Liest einen optionalen Integer aus einem MediaFormat (0, wenn nicht gesetzt). */
    private fun fmtInt(format: MediaFormat, key: String): Int =
        try { format.getInteger(key) } catch (e: Exception) { 0 }

    /** Speichert eine fertig encodierte Spur im App-Cache, damit ein abgebrochener Export fortgesetzt werden kann. */
    private fun saveTrackCache(file: File, mime: String, format: MediaFormat, samples: List<Sample>, durationUs: Long, speechRanges: List<LongRange>) {
        runCatching {
            DataOutputStream(BufferedOutputStream(FileOutputStream(file))).use { w ->
                w.writeUTF(mime)
                w.writeInt(fmtInt(format, MediaFormat.KEY_SAMPLE_RATE))
                w.writeInt(fmtInt(format, MediaFormat.KEY_CHANNEL_COUNT))
                w.writeInt(fmtInt(format, MediaFormat.KEY_WIDTH))
                w.writeInt(fmtInt(format, MediaFormat.KEY_HEIGHT))
                val csd = format.getByteBuffer("csd-0")
                if (csd != null) {
                    val dup = csd.duplicate()
                    val arr = ByteArray(dup.remaining())
                    dup.get(arr)
                    w.writeInt(arr.size)
                    w.write(arr)
                } else {
                    w.writeInt(0)
                }
                w.writeLong(durationUs)
                w.writeInt(speechRanges.size)
                for (r in speechRanges) {
                    w.writeLong(r.first)
                    w.writeLong(r.last)
                }
                w.writeInt(samples.size)
                for (s in samples) {
                    w.writeLong(s.ptsUs)
                    w.writeInt(s.flags)
                    w.writeInt(s.data.size)
                    w.write(s.data)
                }
            }
        }
    }

    /** Lädt eine gecachte Spur – null, wenn keine gültige Cache-Datei existiert. */
    private fun loadTrackCache(file: File?): TrackCache? {
        if (file == null || !file.exists() || file.length() == 0L) return null
        return runCatching {
            DataInputStream(BufferedInputStream(FileInputStream(file))).use { r ->
                val mime = r.readUTF()
                val sampleRate = r.readInt()
                val channels = r.readInt()
                val width = r.readInt()
                val height = r.readInt()
                val csdLen = r.readInt()
                val csd = if (csdLen > 0) ByteArray(csdLen).also { r.readFully(it) } else null
                val durationUs = r.readLong()
                val rangeCount = r.readInt()
                val ranges = mutableListOf<LongRange>()
                repeat(rangeCount) { ranges.add(r.readLong() until r.readLong()) }
                val sampleCount = r.readInt()
                val samples = mutableListOf<Sample>()
                repeat(sampleCount) {
                    val pts = r.readLong()
                    val flags = r.readInt()
                    val arr = ByteArray(r.readInt())
                    r.readFully(arr)
                    samples.add(Sample(arr, pts, flags))
                }
                val format = if (mime == MediaFormat.MIMETYPE_VIDEO_AVC) {
                    MediaFormat.createVideoFormat(mime, width, height).apply {
                        setInteger(MediaFormat.KEY_BIT_RATE, width * height * 3)
                        setInteger(MediaFormat.KEY_FRAME_RATE, 24)
                        setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                    }
                } else {
                    MediaFormat.createAudioFormat(mime, sampleRate, channels).apply {
                        setInteger(MediaFormat.KEY_BIT_RATE, 192000)
                        setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 32768)
                    }
                }
                if (csd != null) format.setByteBuffer("csd-0", ByteBuffer.wrap(csd))
                TrackCache(mime, format, samples, durationUs, ranges)
            }
        }.getOrNull()
    }

    // ---------- Lautheits-Normalisierung (ITU-R BS.1770, Ziel −14 LUFS) ----------

    /** Ein Biquad-Filter (normalisierte Koeffizienten). */
    private class Biquad(b0: Double, b1: Double, b2: Double, a0: Double, a1: Double, a2: Double) {
        private val b0n = b0 / a0
        private val b1n = b1 / a0
        private val b2n = b2 / a0
        private val a1n = a1 / a0
        private val a2n = a2 / a0
        private var z1 = 0.0
        private var z2 = 0.0
        fun process(x: Double): Double {
            val y = b0n * x + z1
            z1 = b1n * x - a1n * y + z2
            z2 = b2n * x - a2n * y
            return y
        }
    }

    /** K-Gewichtung Stufe 1: Hochregal wie im BS.1770-Messfilter. */
    private fun highShelf(f0: Double, gainDb: Double, q: Double, fs: Int): Biquad {
        val w0 = 2.0 * Math.PI * f0 / fs
        val cw = Math.cos(w0)
        val sw = Math.sin(w0)
        val alpha = sw / (2.0 * q)
        val a = Math.pow(10.0, gainDb / 40.0)
        val sq = Math.sqrt(a) * alpha
        return Biquad(
            a * ((a + 1.0) + (a - 1.0) * cw + 2.0 * sq),
            -2.0 * a * ((a - 1.0) + (a + 1.0) * cw),
            a * ((a + 1.0) + (a - 1.0) * cw - 2.0 * sq),
            (a + 1.0) + (a - 1.0) * cw + 2.0 * sq,
            -2.0 * ((a - 1.0) + (a + 1.0) * cw),
            (a + 1.0) + (a - 1.0) * cw - 2.0 * sq
        )
    }

    /** K-Gewichtung Stufe 2: Hochpass wie im BS.1770-Messfilter. */
    private fun highPass(f0: Double, q: Double, fs: Int): Biquad {
        val w0 = 2.0 * Math.PI * f0 / fs
        val cw = Math.cos(w0)
        val sw = Math.sin(w0)
        val alpha = sw / (2.0 * q)
        return Biquad(
            (1.0 + cw) / 2.0,
            -(1.0 + cw),
            (1.0 + cw) / 2.0,
            1.0 + alpha,
            -2.0 * cw,
            1.0 - alpha
        )
    }

    /**
     * Normalisiert das gemischte Stereo-PCM auf −14 LUFS (ITU-R BS.1770-4 mit
     * 400-ms-Blöcken, 100-ms-Versatz und absoluter/relativer Gate-Schwelle) –
     * der Lautstärke-Standard von YouTube: Stimme und Musik klingen so über
     * alle Videos und Geräte hinweg konsistent laut.
     */
    private fun normalizeLoudness(pcm: ByteArray): ByteArray {
        val frames = pcm.size / 4
        if (frames < TARGET_RATE) return pcm // unter 1 s: nichts sinnvoll messbar
        return runCatching {
            val shelfL = highShelf(1681.9741891, 3.99984385397, 0.7071752369554196, TARGET_RATE)
            val hpfL = highPass(38.1354708760244, 0.5003270373238773, TARGET_RATE)
            val shelfR = highShelf(1681.9741891, 3.99984385397, 0.7071752369554196, TARGET_RATE)
            val hpfR = highPass(38.1354708760244, 0.5003270373238773, TARGET_RATE)
            val blockLen = TARGET_RATE * 2 / 5 // 400 ms
            val hopLen = TARGET_RATE / 10       // 100 ms
            val ringL = DoubleArray(blockLen)

            val ringR = DoubleArray(blockLen)
            var sumL = 0.0
            var sumR = 0.0
            var ringPos = 0
            var filled = 0
            val blocks = mutableListOf<DoubleArray>()
            for (f in 0 until frames) {
                val l = (((pcm[f * 4].toInt() and 0xff) or ((pcm[f * 4 + 1].toInt() and 0xff) shl 8)).toShort()).toDouble()
                val r = (((pcm[f * 4 + 2].toInt() and 0xff) or ((pcm[f * 4 + 3].toInt() and 0xff) shl 8)).toShort()).toDouble()
                val fl = hpfL.process(shelfL.process(l))
                val fr = hpfR.process(shelfR.process(r))
                sumL += fl - ringL[ringPos]
                sumR += fr - ringR[ringPos]
                ringL[ringPos] = fl
                ringR[ringPos] = fr
                ringPos = (ringPos + 1) % blockLen
                filled++
                if (filled >= blockLen && (filled - blockLen) % hopLen == 0) {
                    blocks.add(doubleArrayOf(sumL / blockLen, sumR / blockLen))
                }
            }
            if (blocks.isEmpty()) return pcm
            fun blockLufs(z: DoubleArray): Double =
                -0.691 + 10.0 * Math.log10((z[0] + z[1]).coerceAtLeast(1e-12))
            val absGated = blocks.filter { blockLufs(it) > -70.0 }
            if (absGated.isEmpty()) return pcm
            val ungated = -0.691 + 10.0 * Math.log10(
                (absGated.sumOf { it[0] + it[1] } / absGated.size).coerceAtLeast(1e-12)
            )
            val relGated = absGated.filter { blockLufs(it) > ungated - 10.0 }
            val pool = if (relGated.isNotEmpty()) relGated else absGated
            val lufs = -0.691 + 10.0 * Math.log10(
                (pool.sumOf { it[0] + it[1] } / pool.size).coerceAtLeast(1e-12)
            )
            var gain = Math.pow(10.0, (-14.0 - lufs) / 20.0).coerceIn(0.1, 10.0)
            // Clip-Schutz: nie über die 16-Bit-Vollaussteuerung hinaus verstärken
            var peak = 0.0
            for (f in 0 until frames) {
                val l = abs((((pcm[f * 4].toInt() and 0xff) or ((pcm[f * 4 + 1].toInt() and 0xff) shl 8)).toShort()).toDouble())
                val r = abs((((pcm[f * 4 + 2].toInt() and 0xff) or ((pcm[f * 4 + 3].toInt() and 0xff) shl 8)).toShort()).toDouble())
                if (l > peak) peak = l
                if (r > peak) peak = r
            }
            if (peak > 0.0 && peak * gain > 32000.0) gain = 32000.0 / peak
            if (Math.abs(gain - 1.0) < 0.015) return pcm
            val out = ByteArray(pcm.size)
            for (f in 0 until frames) {
                val l = ((((pcm[f * 4].toInt() and 0xff) or ((pcm[f * 4 + 1].toInt() and 0xff) shl 8)).toShort()).toDouble() * gain).toInt().coerceIn(-32767, 32767)
                val r = ((((pcm[f * 4 + 2].toInt() and 0xff) or ((pcm[f * 4 + 3].toInt() and 0xff) shl 8)).toShort()).toDouble() * gain).toInt().coerceIn(-32767, 32767)
                out[f * 4] = (l and 0xff).toByte()
                out[f * 4 + 1] = ((l shr 8) and 0xff).toByte()
                out[f * 4 + 2] = (r and 0xff).toByte()
                out[f * 4 + 3] = ((r shr 8) and 0xff).toByte()
            }
            out
        }.getOrDefault(pcm)
    }

    // ---------- Automatisches Video-Cover (Thumbnail) ----------

    /** Rendert das Video-Cover (JPEG-Thumbnail für YouTube/TikTok) für die erste Szene. */
    private fun renderCover(slide: SlideData): Bitmap {
        val bmp = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val colors = palettes[0]
        val bg = Paint()
        bg.shader = LinearGradient(0f, 0f, 0f, HEIGHT.toFloat(), colors[0], colors[1], Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, WIDTH.toFloat(), HEIGHT.toFloat(), bg)
        val vignette = Paint()
        vignette.shader = RadialGradient(
            WIDTH / 2f, HEIGHT / 2f, maxOf(WIDTH, HEIGHT) * 0.75f,
            intArrayOf(Color.TRANSPARENT, Color.argb(70, 0, 0, 0)),
            floatArrayOf(0.55f, 1f),
            Shader.TileMode.CLAMP
        )
        c.drawRect(0f, 0f, WIDTH.toFloat(), HEIGHT.toFloat(), vignette)
        val logo = logoMark
        if (logo != null) drawLogoMark(c, logo, WIDTH / 2f, HEIGHT * 0.09f, 130f * scale, 255)
        // Themen-Foto (Kamerafoto oder Online-Bild) in gerundeter Karte
        val photoPath = slide.photoPath ?: slide.webImagePath
        val photo = if (photoPath != null) runCatching { BitmapFactory.decodeFile(photoPath) }.getOrNull() else null
        if (photo != null) {
            val card = RectF(60f * scale, HEIGHT * 0.14f, WIDTH - 60f * scale, HEIGHT * 0.56f)
            val frame = RectF(
                card.left - 8f * scale, card.top - 8f * scale,
                card.right + 8f * scale, card.bottom + 8f * scale
            )
            val shadow = Paint(Paint.ANTI_ALIAS_FLAG)
            shadow.color = Color.argb(70, 0, 0, 0)
            c.drawRoundRect(frame, 26f * scale, 26f * scale, shadow)
            c.save()
            val clip = Path()
            clip.addRoundRect(card, 24f * scale, 24f * scale, Path.Direction.CW)
            c.clipPath(clip)
            drawCenterCrop(c, photo, card)
            c.restore()
            photo.recycle()
        }
        // Thema als großer Titel mit automatischer Verkleinerung
        val title = slide.text.trim().take(110).ifBlank { "Dein Erklärvideo" }
        var size = 60f * scale
        var layout = buildCoverLayout(title, size)
        while (layout.height > HEIGHT * 0.20f && size > 32f * scale) {
            size -= 6f * scale
            layout = buildCoverLayout(title, size)
        }
        c.save()
        c.translate((WIDTH - layout.width) / 2f, HEIGHT * 0.60f)
        layout.draw(c)
        c.restore()
        // Zweifarbige Wortmarke unten
        val tp = TextPaint(Paint.ANTI_ALIAS_FLAG)
        tp.textSize = 62f * scale
        tp.isFakeBoldText = true
        tp.setShadowLayer(5f * scale, 0f, 2f * scale, Color.argb(140, 0, 0, 0))
        val s1 = "Wer "
        val s2 = "Wie"
        val s3 = " Was"
        val total = tp.measureText(s1) + tp.measureText(s2) + tp.measureText(s3)
        var x = WIDTH / 2f - total / 2f
        val y = HEIGHT * 0.88f
        tp.color = Color.WHITE
        c.drawText(s1, x, y, tp)
        x += tp.measureText(s1)
        tp.color = Color.rgb(66, 133, 244)
        c.drawText(s2, x, y, tp)
        x += tp.measureText(s2)
        tp.color = Color.WHITE
        c.drawText(s3, x, y, tp)
        return bmp
    }

    /** Titel-Layout für das Video-Cover (zentriert, mit Schatten für Lesbarkeit). */
    private fun buildCoverLayout(text: String, size: Float): StaticLayout {
        val tp = TextPaint(Paint.ANTI_ALIAS_FLAG)
        tp.textSize = size
        tp.isFakeBoldText = true
        tp.color = Color.WHITE
        tp.setShadowLayer(4f * scale, 0f, 2f * scale, Color.argb(140, 0, 0, 0))
        return StaticLayout.Builder.obtain(
            text, 0, text.length, tp, (WIDTH - 140f * scale).toInt()
        ).setAlignment(Layout.Alignment.ALIGN_CENTER).setLineSpacing(0f, 1.15f).build()
    }

    /** Speichert das Cover als JPEG in der Galerie (ab Android 10) bzw. im App-Ordner. */
    private fun saveThumb(context: Context, videoBase: String, slide: SlideData): Uri? {
        val bmp = renderCover(slide)
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, videoBase + "_cover.jpg")
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/WerWieWas")
            }
            val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            if (uri != null) {
                context.contentResolver.openOutputStream(uri)?.use { bmp.compress(Bitmap.CompressFormat.JPEG, 92, it) }
            }
            bmp.recycle()
            return uri
        }
        val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: context.filesDir, "videos")
        dir.mkdirs()
        val target = File(dir, videoBase + "_cover.jpg")
        FileOutputStream(target).use { bmp.compress(Bitmap.CompressFormat.JPEG, 92, it) }
        bmp.recycle()
        return FileProvider.getUriForFile(context, context.packageName + ".fileprovider", target)
    }

    /** Zeichnet eine Bitmap formatfüllend und mittig beschnitten in ein Zielrechteck. */
    private fun drawCenterCrop(canvas: Canvas, bmp: Bitmap, target: RectF) {
        val scale = maxOf(target.width() / bmp.width, target.height() / bmp.height)
        val matrix = Matrix()
        matrix.postScale(scale, scale)
        val w = bmp.width * scale
        val h = bmp.height * scale
        matrix.postTranslate(target.left + (target.width() - w) / 2f, target.top + (target.height() - h) / 2f)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        canvas.save()
        canvas.clipRect(target)
        canvas.drawBitmap(bmp, matrix, paint)
        canvas.restore()
    }

    /** Dekodiert ein Foto in Vollbild-Qualität (maximal Zielgröße) für das
     *  Intro-Ausgangsbild und die Übergangs-Clips; null, wenn kein Foto da ist. */
    private fun decodeFullscreen(path: String?): Bitmap? {
        if (path.isNullOrBlank()) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        if (runCatching { BitmapFactory.decodeFile(path, bounds) }.getOrNull() == null) return null
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val target = maxOf(WIDTH, HEIGHT)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= target && bounds.outHeight / (sample * 2) >= target) sample *= 2
        return runCatching {
            BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
        }.getOrNull()
    }
}



