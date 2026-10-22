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
import java.io.ByteArrayOutputStream
import java.io.File
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
    private const val FADE_SECONDS = 0.6        // Dauer der Szenen-Überblendung
    private const val INTRO_SECONDS = 2.8
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
     */
    data class ExportResult(val message: String, val shareUri: Uri?, val srtUri: Uri? = null)

    /**
     * Erstellt das Video und gibt eine Beschreibung des Speicherorts zurück.
     * onProgress wird mit (erledigte Schritte, Gesamtschritte) aufgerufen.
     * preAudio: optional je Szene eine fertige WAV-Datei (z. B. Edge-TTS);
     * fehlende Einträge werden über die Android-Sprachausgabe erzeugt.
     * videoWidth/videoHeight: Auflösung (z. B. 1280x720/1920x1080 für YouTube-Querformat);
     * wantSrt: Untertitel als SRT-Datei mitspeichern;
     * burnedSubs: Untertitel direkt ins Video einbrennen (TikTok-Stil);
     * backgroundMusic: dezente, generierte Hintergrundmusik unter die Stimme mischen.
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
        onProgress: (done: Int, total: Int) -> Unit
    ): ExportResult {
        if (slides.isEmpty()) throw ExportException("Keine Inhalte zum Export vorhanden.")
        WIDTH = videoWidth
        HEIGHT = videoHeight
        scale = minOf(WIDTH, HEIGHT) / 720f
        // Echte App-Logo-Marke (gleicher Vektor wie das Launcher-Icon) für
        // Intro-/Outro-Karte und Wasserzeichen laden.
        logoMark = context.getDrawable(R.drawable.ic_launcher_foreground)
        val totalSteps = slides.size + 5
        val introUs = (INTRO_SECONDS * 1_000_000).toLong()
        val outroUs = (OUTRO_SECONDS * 1_000_000).toLong()
        val fadeUs = (FADE_SECONDS * 1_000_000).toLong()
        val silenceUs = (SILENCE_SECONDS * 1_000_000).toLong()

        val audios = synthesizeSlides(context, slides, voiceA, voiceB, rate, preAudio)
        onProgress(slides.size, totalSteps)

        // Ton-Zeitlinie: Intro-Pause, dann je Szene Sprachausgabe + Anzeige-Pause, dann Outro-Pause.
        // Jede Szene bekommt eine Mindest-Anzeigedauer nach Textlänge, damit der komplette
        // Text und die Themenbilder sicher sichtbar sind – auch ohne (oder bei sehr
        // kurzer) Sprachausgabe.
        val pcmOut = ByteArrayOutputStream()
        val speechRanges = mutableListOf<LongRange>()
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
            sceneDur[i] = monoUs + padUs + fadeUs / 2
            if (monoUs > 0) speechRanges.add(cursor until (cursor + monoUs))
            cursor += monoUs + padUs
            pcmOut.write(ByteArray((padUs * TARGET_RATE * 2L / 1_000_000L).toInt()))
        }
        pcmOut.write(ByteArray((OUTRO_SECONDS * TARGET_RATE * 2).toInt()))
        val durationUs = cursor + outroUs
        val monoPcm = pcmOut.toByteArray()

        // Hintergrundmusik + Sprachausgabe zu einer Stereospur mischen
        val stereoPcm = mixAudio(monoPcm, durationUs, speechRanges, backgroundMusic)
        onProgress(slides.size + 1, totalSteps)

        val audio = try {
            encodeAudio(stereoPcm, TARGET_RATE, CHANNELS)
        } catch (e: ExportException) {
            throw e
        } catch (e: Exception) {
            throw ExportException("Tonspur konnte nicht encodiert werden: " + (e.message ?: e.javaClass.simpleName))
        }
        onProgress(slides.size + 2, totalSteps)

        // Szenen-Grafiken vorbereiten (Ebenen für Zoom, Text-Fade, Untertitel).
        // Eingebrannte Untertitel laufen über das komplette Anzeige-Fenster der Szene.
        val gfx = try {
            slides.mapIndexed { i, s ->
                renderSlideGfx(s, i, slides.size, burnedSubs, sceneDur[i] - fadeUs / 2)
            }
        } catch (e: ExportException) {
            throw e
        } catch (e: Exception) {
            throw ExportException("Szenen konnten nicht vorbereitet werden: " + (e.message ?: e.javaClass.simpleName))
        }
        onProgress(slides.size + 3, totalSteps)

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

        fun drawProgress(c: Canvas, t: Long) {
            val track = Paint(Paint.ANTI_ALIAS_FLAG)
            track.color = Color.argb(40, 255, 255, 255)
            c.drawRect(0f, HEIGHT - 5f * scale, WIDTH.toFloat(), HEIGHT.toFloat(), track)
            val fill = Paint(Paint.ANTI_ALIAS_FLAG)
            fill.color = Color.rgb(66, 133, 244)
            val w = WIDTH * (t.toFloat() / durationUs.toFloat()).coerceIn(0f, 1f)
            c.drawRect(0f, HEIGHT - 5f * scale, w, HEIGHT.toFloat(), fill)
        }

        fun drawCaption(c: Canvas, i: Int, t: Long) {
            val tIn = t - sceneStart[i]
            val cap = gfx[i].captions.firstOrNull { tIn >= it.startUs && tIn < it.endUs } ?: return
            val left = (WIDTH - cap.boxW) / 2f
            val top = HEIGHT - 120f * scale - cap.boxH
            val box = RectF(left, top, left + cap.boxW, top + cap.boxH)
            val bgp = Paint(Paint.ANTI_ALIAS_FLAG)
            bgp.color = Color.argb(200, 10, 14, 30)
            c.drawRoundRect(box, 14f * scale, 14f * scale, bgp)
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
                // Ken-Burns-Zoom: Foto wird im Lauf der Szene leicht herangeführt
                val prog = ((t - sceneStart[i]).toFloat() / sceneDur[i].toFloat()).coerceIn(0f, 1f)
                val z = 1f + ZOOM_AMOUNT * prog
                val clip = Path()
                clip.addRoundRect(r, 18f * scale, 18f * scale, Path.Direction.CW)
                c.save()
                c.clipPath(clip)
                val m = Matrix()
                m.postTranslate(-p.width / 2f, -p.height / 2f)
                m.postScale(z, z)
                m.postTranslate(r.centerX(), r.centerY())
                c.drawBitmap(p, m, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
                c.restore()
            }
            val tl = g.textLayer
            if (tl != null && g.textH > 0) {
                // Titel/Text fahren sanft ein und blenden ein
                val a = ((t - sceneStart[i]).toFloat() / (TEXT_FADE_SECONDS * 1_000_000f)).coerceIn(0f, 1f)
                val ease = a * a * (3f - 2f * a)
                val pa = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
                pa.alpha = (ease * 255).toInt()
                val dy = (1f - ease) * 26f * scale
                val src = Rect(0, 0, WIDTH, g.textH)
                c.drawBitmap(tl, src, RectF(0f, g.textTop + dy, WIDTH.toFloat(), g.textTop + dy + g.textH), pa)
            }
            if (burnedSubs) drawCaption(c, i, t)
            drawProgress(c, t)
        }

        fun drawWordmark(c: Canvas, y: Float, size: Float, alpha: Int) {
            val a = alpha.coerceIn(0, 255)
            val tp = TextPaint(Paint.ANTI_ALIAS_FLAG)
            tp.textSize = size
            tp.isFakeBoldText = true
            tp.setShadowLayer(5f * scale, 0f, 2f * scale, Color.argb(140, 0, 0, 0))
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
            val bg = Paint()
            bg.shader = LinearGradient(
                0f, 0f, 0f, HEIGHT.toFloat(),
                0xFF0A1633.toInt(), 0xFF1A50A8.toInt(), Shader.TileMode.CLAMP
            )
            c.drawRect(0f, 0f, WIDTH.toFloat(), HEIGHT.toFloat(), bg)
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
                val p2 = Paint(Paint.ANTI_ALIAS_FLAG)
                p2.textAlign = Paint.Align.CENTER
                p2.textSize = 36f * scale
                p2.color = Color.argb(225, 255, 255, 255)
                c.drawText("Einfach ein Foto machen –", WIDTH / 2f, cy + 310f * scale, p2)
                c.drawText("die App erklärt den Rest!", WIDTH / 2f, cy + 360f * scale, p2)
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
            if (t < introUs - half) {
                drawCard(cA, t, 0L, true)
                bmpA.getPixels(out, 0, WIDTH, 0, 0, WIDTH, HEIGHT)
                return out
            }
            if (t < introUs + half) {
                drawCard(cA, t, 0L, true)
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
            val nextB = if (seg + 1 < slides.size) sceneStart[seg + 1] else Long.MAX_VALUE
            if (t >= nextB - half) {
                drawScene(cA, seg, t)
                bmpA.getPixels(arrA, 0, WIDTH, 0, 0, WIDTH, HEIGHT)
                drawScene(cB, seg + 1, t)
                bmpB.getPixels(arrB, 0, WIDTH, 0, 0, WIDTH, HEIGHT)
                blend(arrA, arrB, (t - (nextB - half)).toFloat() / fadeUs.toFloat(), out)
                return out
            }
            drawScene(cA, seg, t)
            bmpA.getPixels(out, 0, WIDTH, 0, 0, WIDTH, HEIGHT)
            return out
        }

        val video = try {
            encodeVideo(durationUs) { t -> renderFrame(t) }
        } catch (e: ExportException) {
            throw e
        } catch (e: Exception) {
            throw ExportException("Videospur konnte nicht encodiert werden: " + (e.message ?: e.javaClass.simpleName))
        }
        onProgress(slides.size + 4, totalSteps)

        val file = try {
            mux(context, audio, video)
        } catch (e: ExportException) {
            throw e
        } catch (e: Exception) {
            throw ExportException("Video-Datei konnte nicht erstellt werden: " + (e.message ?: e.javaClass.simpleName))
        }
        val videoBase = file.name.removeSuffix(".mp4")
        val videoResult = saveVideo(context, file)
        val srtUri = if (wantSrt) saveSrt(context, videoBase, buildSrt(slides, speechRanges)) else null
        onProgress(totalSteps, totalSteps)
        val message = if (srtUri != null) {
            videoResult.message + " – Untertitel (SRT) gespeichert"
        } else {
            videoResult.message
        }
        return ExportResult(message, videoResult.shareUri, srtUri)
    }

    /** Erzeugt je Szene eine gesprochene Tonspur – vorgefertigte Dateien haben Vorrang. */
    private fun synthesizeSlides(
        context: Context,
        slides: List<SlideData>,
        voiceA: Voice?,
        voiceB: Voice?,
        rate: Float,
        preAudio: List<File?>?
    ): List<SlideAudio> {
        val provided: List<File?> = slides.mapIndexed { i, _ -> preAudio?.getOrNull(i) }
        if (provided.isNotEmpty() && provided.all { it != null }) {
            return provided.map { f ->
                if (f == null) {
                    SlideAudio(ByteArray(0), TARGET_RATE, 1)
                } else {
                    runCatching { parseWav(f) }.getOrElse { SlideAudio(ByteArray(0), TARGET_RATE, 1) }
                }
            }
        }
        val initLatch = CountDownLatch(1)
        val tts = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) initLatch.countDown()
        }
        try {
            if (!initLatch.await(15, TimeUnit.SECONDS)) {
                // Keine Sprachausgabe verfügbar: Das Video wird ohne Ton exportiert
                // (jede Szene erhält nur die kurze Sprechpause als Dauer).
                return slides.map { SlideAudio(ByteArray(0), TARGET_RATE, 1) }
            }
            tts.language = Locale.GERMANY
            tts.setSpeechRate(rate)
            val result = mutableListOf<SlideAudio>()
            for ((index, slide) in slides.withIndex()) {
                val pre = provided.getOrNull(index)
                if (pre != null) {
                    result.add(
                        runCatching { parseWav(pre) }
                            .getOrElse { SlideAudio(ByteArray(0), TARGET_RATE, 1) }
                    )
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
                val latch = CountDownLatch(1)
                tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onDone(utteranceId: String?) { latch.countDown() }
                    override fun onError(utteranceId: String?) { latch.countDown() }
                    override fun onStart(utteranceId: String?) { }
                })
                val ok = tts.synthesizeToFile(
                    slide.title + ". " + slide.text,
                    Bundle().apply {
                        putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, "exp" + index)
                    },
                    wav,
                    "exp" + index
                )
                if (ok == TextToSpeech.SUCCESS) latch.await(60, TimeUnit.SECONDS) else Thread.sleep(1000)
                val audio = runCatching { parseWav(wav) }
                    .getOrElse { SlideAudio(ByteArray(0), TARGET_RATE, 1) }
                result.add(audio)
                wav.delete()
            }
            return result
        } finally {
            tts.stop()
            tts.shutdown()
        }
    }

    /** Mischt die Sprachausgabe (Mono) mit der Hintergrundmusik zu einer Stereospur. */
    private fun mixAudio(
        monoPcm: ByteArray,
        durationUs: Long,
        speechRanges: List<LongRange>,
        wantMusic: Boolean
    ): ByteArray {
        val frames = monoPcm.size / 2
        if (frames == 0) return ByteArray(0)
        val totalSec = durationUs / 1_000_000.0
        val firstSpeech = speechRanges.firstOrNull()
        val lastSpeech = speechRanges.lastOrNull()
        val speechStartSec = if (firstSpeech != null) firstSpeech.first / 1_000_000.0 else totalSec
        val speechEndSec = if (lastSpeech != null) lastSpeech.last / 1_000_000.0 else 0.0
        val loop = if (wantMusic) buildPadLoop() else null
        val loopFrames = if (loop != null) loop.size / 2 else 0
        val out = ByteArray(frames * 4)
        for (f in 0 until frames) {
            val s = (((monoPcm[f * 2].toInt() and 0xff) or ((monoPcm[f * 2 + 1].toInt() and 0xff) shl 8))).toShort()
            var l = s.toInt()
            var r = s.toInt()
            if (loop != null && loopFrames > 0) {
                val t = f / TARGET_RATE.toDouble()
                var g = 0.5f
                if (t < 1.5) g *= (t / 1.5).toFloat()
                val remain = totalSec - t
                if (remain < 2.0) g *= (remain / 2.0).coerceAtLeast(0.0).toFloat()
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
     * Baut einen 16-Sekunden-Ambient-Pad-Loop (A – E – F#m – D), der als dezente
     * Hintergrundmusik unter der Sprachausgabe läuft. Vollständig im Code erzeugt,
     * daher lizenzfrei und ohne Zusatzdateien.
     */
    private fun buildPadLoop(): FloatArray {
        val totalSec = 16.0
        val chordLen = 4.0
        val overlap = 1.0
        val n = (totalSec * TARGET_RATE).toInt()
        val out = FloatArray(n * 2)
        val chords = arrayOf(
            doubleArrayOf(110.0, 164.81, 220.0, 277.18, 329.63),
            doubleArrayOf(123.47, 164.81, 207.65, 246.94, 329.63),
            doubleArrayOf(92.5, 138.59, 185.0, 220.0, 277.18),
            doubleArrayOf(146.83, 220.0, 293.66, 369.99, 440.0)
        )
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
                val amp = env * 0.30 / tones.size
                out[i * 2] += (lv * amp).toFloat()
                out[i * 2 + 1] += (rv * amp).toFloat()
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
        format.setInteger(MediaFormat.KEY_BIT_RATE, 128000)
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
    private fun encodeVideo(durationUs: Long, pixelsAt: (Long) -> IntArray): Pair<MediaFormat, List<Sample>> {
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

    /** Baut aus Szenen und Zeitbereichen den Inhalt einer SRT-Untertiteldatei. */
    private fun buildSrt(slides: List<SlideData>, ranges: List<LongRange>): String {
        val sb = StringBuilder()
        for (i in slides.indices) {
            val range = ranges.getOrNull(i) ?: continue
            sb.append(i + 1).append('\n')
            sb.append(srtTime(range.first)).append(" --> ").append(srtTime(range.last)).append('\n')
            sb.append(slides[i].title).append('\n')
            sb.append(slides[i].text).append('\n')
            sb.append('\n')
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
        val fileName = videoBase + ".srt"
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, "application/x-subrip")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/WerWieWas")
            }
            val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: return null
            context.contentResolver.openOutputStream(uri)?.use { it.write(content.toByteArray()) }
            return uri
        }
        return runCatching {
            val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: context.filesDir, "videos")
            dir.mkdirs()
            val target = File(dir, fileName)
            target.writeText(content)
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

        // Textebene: Titel (und ohne eingebrannte Untertitel auch der Fließtext)
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
            while (top + titleLayout.height + 60f * scale + bl.height > HEIGHT - 160f * scale && bodySize > 26f * scale) {
                bodySize -= 4f * scale
                bl = makeBody(bodySize)
            }
            bodyLayout = bl
        }
        val textTop = top
        val body = bodyLayout
        val layerH = (titleLayout.height +
            (if (body != null) 60f * scale + body.height else 0f) + 8f)
            .toInt().coerceAtLeast(1)
            .coerceAtMost((HEIGHT - textTop).toInt().coerceAtLeast(1))
        val textLayer = Bitmap.createBitmap(WIDTH, layerH, Bitmap.Config.ARGB_8888)
        val tc = Canvas(textLayer)
        tc.save()
        tc.translate((WIDTH - titleLayout.width) / 2f, 0f)
        titleLayout.draw(tc)
        tc.restore()
        if (body != null) {
            tc.save()
            tc.translate((WIDTH - body.width) / 2f, titleLayout.height + 60f * scale)
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
            val d = speechDurUs * ch.length / totalLen
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
}
