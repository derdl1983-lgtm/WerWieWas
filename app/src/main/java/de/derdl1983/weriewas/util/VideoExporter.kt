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
import android.graphics.RectF
import android.graphics.Shader
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
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Exportiert das Erklärvideo als abspielbare MP4-Datei:
 * Szenen werden als H.264-Videospur encodiert, die Sprachausgabe als AAC-Tonspur,
 * beides über MediaMuxer in eine Datei gemuxt und in die Mediathek gespeichert.
 * Blockierend mit viel I/O – nur ausserhalb des Main-Threads aufrufen.
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

    class ExportException(message: String) : Exception(message)

    private var WIDTH = 720
    private var HEIGHT = 1280
    private var scale = 1f
    private const val FRAME_INTERVAL_US = 250000L
    private const val TARGET_RATE = 22050
    private const val SILENCE_SECONDS = 0.6

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
     * wantSrt: Untertitel als SRT-Datei mitspeichern.
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
        onProgress: (done: Int, total: Int) -> Unit
    ): ExportResult {
        if (slides.isEmpty()) throw ExportException("Keine Inhalte zum Export vorhanden.")
        WIDTH = videoWidth
        HEIGHT = videoHeight
        scale = minOf(WIDTH, HEIGHT) / 720f
        val totalSteps = 3 + slides.size
        var done = 0

        val audios = synthesizeSlides(context, slides, voiceA, voiceB, rate, preAudio)
        done += slides.size
        onProgress(done, totalSteps)

        val silence = ByteArray((SILENCE_SECONDS * TARGET_RATE * 2).toInt())
        val pcmOut = ByteArrayOutputStream()
        val ranges = mutableListOf<LongRange>()
        var start = 0L
        for (a in audios) {
            val mono = toMonoTarget(a)
            pcmOut.write(mono)
            pcmOut.write(silence)
            val end = start + (mono.size + silence.size) * 1000000L / (TARGET_RATE * 2)
            ranges.add(start until end)
            start = end
        }
        val durationUs = start
        val pcm = pcmOut.toByteArray()
        done++
        onProgress(done, totalSteps)

        val pixels = slides.mapIndexed { i, s ->
            val bmp = renderSlide(s, i)
            val px = IntArray(WIDTH * HEIGHT)
            bmp.getPixels(px, 0, WIDTH, 0, 0, WIDTH, HEIGHT)
            bmp.recycle()
            px
        }
        done++
        onProgress(done, totalSteps)

        fun pixelsAt(t: Long): IntArray {
            for (i in ranges.indices) if (ranges[i].contains(t)) return pixels[i]
            return pixels.last()
        }

        val audio = try {
            encodeAudio(pcm, TARGET_RATE)
        } catch (e: ExportException) {
            throw e
        } catch (e: Exception) {
            throw ExportException("Tonspur konnte nicht encodiert werden: " + (e.message ?: e.javaClass.simpleName))
        }
        val video = try {
            encodeVideo(durationUs) { pixelsAt(it) }
        } catch (e: ExportException) {
            throw e
        } catch (e: Exception) {
            throw ExportException("Videospur konnte nicht encodiert werden: " + (e.message ?: e.javaClass.simpleName))
        }
        val file = try {
            mux(context, audio, video)
        } catch (e: ExportException) {
            throw e
        } catch (e: Exception) {
            throw ExportException("Video-Datei konnte nicht erstellt werden: " + (e.message ?: e.javaClass.simpleName))
        }
        done++
        onProgress(done, totalSteps)
        val videoBase = file.name.removeSuffix(".mp4")
        val videoResult = saveVideo(context, file)
        val srtUri = if (wantSrt) saveSrt(context, videoBase, buildSrt(slides, ranges)) else null
        done++
        onProgress(done, totalSteps)
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

    /** Encodiert die Tonspur als AAC und liefert Format + Samples. */
    private fun encodeAudio(pcm: ByteArray, sampleRate: Int): Pair<MediaFormat, List<Sample>> {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, 1)
        format.setInteger(MediaFormat.KEY_BIT_RATE, 64000)
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
                            val finalPts = pcm.size * 1000000L / (sampleRate * 2)
                            codec.queueInputBuffer(idx, 0, 0, finalPts, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            val buf = codec.getInputBuffer(idx)
                            if (buf != null) {
                                buf.clear()
                                val n = minOf(buf.remaining(), pcm.size - offset)
                                buf.put(pcm, offset, n)
                                codec.queueInputBuffer(idx, 0, n, offset * 1000000L / (sampleRate * 2), 0)
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

    /** Encodiert die Standbilder als H.264-Videospur. */
    private fun encodeVideo(durationUs: Long, pixelsAt: (Long) -> IntArray): Pair<MediaFormat, List<Sample>> {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, WIDTH, HEIGHT)
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
        format.setInteger(MediaFormat.KEY_BIT_RATE, WIDTH * HEIGHT * 6)
        format.setInteger(MediaFormat.KEY_FRAME_RATE, 4)
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
                                // Fallback: Manche Geräte liefern kein Eingabe-Image,
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

    /** Füllt einen rohen YUV-Eingabepuffer (Fallback, wenn kein Eingabe-Image verfügbar ist). */
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

    /** Schreibt ARGB-Pixel in die YUV-Ebenen eines Encoder-Eingabildes. */
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

    /** Rendert eine Szene als Standbild (Gradient-Hintergrund, Foto, Emoji, Titel, Text). */
    private fun renderSlide(slide: SlideData, index: Int): Bitmap {
        val bmp = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val palettes = arrayOf(
            intArrayOf(0xFF1A237E.toInt(), 0xFF3949AB.toInt()),
            intArrayOf(0xFF00695C.toInt(), 0xFF00897B.toInt()),
            intArrayOf(0xFF4A148C.toInt(), 0xFF6A1B9A.toInt()),
            intArrayOf(0xFF0277BD.toInt(), 0xFF0288D1.toInt()),
            intArrayOf(0xFF283593.toInt(), 0xFF3F51B5.toInt()),
            intArrayOf(0xFF33691E.toInt(), 0xFF558B2F.toInt()),
            intArrayOf(0xFFBF360C.toInt(), 0xFFD84315.toInt()),
            intArrayOf(0xFF37474F.toInt(), 0xFF455A64.toInt())
        )
        val colors = palettes[index % palettes.size]
        val bg = Paint()
        bg.shader = LinearGradient(0f, 0f, 0f, HEIGHT.toFloat(), colors[0], colors[1], Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, WIDTH.toFloat(), HEIGHT.toFloat(), bg)

        var top = if (HEIGHT > WIDTH) 140f * scale else 80f * scale
        var hasImage = false
        if (slide.photoPath != null) {
            val photo = BitmapFactory.decodeFile(slide.photoPath)
            if (photo != null) {
                hasImage = true
                val photoH = minOf(600f * scale, HEIGHT * 0.32f)
                val target = RectF(60f * scale, top, WIDTH - 60f * scale, top + photoH)
                val matrix = Matrix()
                matrix.setRectToRect(
                    RectF(0f, 0f, photo.width.toFloat(), photo.height.toFloat()),
                    target,
                    Matrix.ScaleToFit.CENTER
                )
                canvas.drawBitmap(photo, matrix, Paint(Paint.FILTER_BITMAP_FLAG))
                photo.recycle()
                top += photoH + 60f * scale
            }
        } else if (slide.webImagePath != null) {
            // Passendes Online-Themenbild (frei lizenziert, mit Quellenzeile)
            val web = BitmapFactory.decodeFile(slide.webImagePath)
            if (web != null) {
                hasImage = true
                val webH = minOf(460f * scale, HEIGHT * 0.26f)
                val target = RectF(60f * scale, top, WIDTH - 60f * scale, top + webH)
                drawCenterCrop(canvas, web, target)
                web.recycle()
                top += webH + 20f * scale
                val credit = slide.webImageCredit
                if (!credit.isNullOrBlank()) {
                    val cp = Paint(Paint.ANTI_ALIAS_FLAG)
                    cp.textSize = 26f * scale
                    cp.textAlign = Paint.Align.CENTER
                    cp.color = Color.argb(210, 255, 255, 255)
                    canvas.drawText("Bild: " + credit, WIDTH / 2f, top, cp)
                    top += 64f * scale
                }
            }
        }
        if (slide.emoji.isNotBlank() && !hasImage) {
            val p = Paint(Paint.ANTI_ALIAS_FLAG)
            p.textSize = 190f * scale
            p.textAlign = Paint.Align.CENTER
            p.color = Color.WHITE
            val fm = p.fontMetrics
            canvas.drawText(slide.emoji, WIDTH / 2f, top + 95f * scale - (fm.top + fm.bottom) / 2f, p)
            top += 280f * scale
        }
        val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
        titlePaint.textSize = 62f * scale
        titlePaint.isFakeBoldText = true
        titlePaint.color = Color.WHITE
        val titleLayout = StaticLayout.Builder.obtain(
            slide.title, 0, slide.title.length, titlePaint, (WIDTH - 160f * scale).toInt()
        ).setAlignment(Layout.Alignment.ALIGN_CENTER).build()
        canvas.save()
        canvas.translate((WIDTH - titleLayout.width) / 2f, top)
        titleLayout.draw(canvas)
        canvas.restore()
        top += titleLayout.height + 60f * scale

        // Fließtext – der Schriftgrad passt sich an, damit der Text auch unter
        // einem Themenbild vollständig auf die Szene passt.
        val makeBody: (Float) -> StaticLayout = { size ->
            val bp = TextPaint(Paint.ANTI_ALIAS_FLAG)
            bp.textSize = size
            bp.color = Color.WHITE
            StaticLayout.Builder.obtain(
                slide.text, 0, slide.text.length, bp, (WIDTH - 160f * scale).toInt()
            ).setAlignment(Layout.Alignment.ALIGN_CENTER).setLineSpacing(0f, 1.25f).build()
        }
        var bodySize = 42f * scale
        var bodyLayout = makeBody(bodySize)
        while (top + bodyLayout.height > HEIGHT - 170f * scale && bodySize > 26f * scale) {
            bodySize -= 4f * scale
            bodyLayout = makeBody(bodySize)
        }
        canvas.save()
        canvas.translate((WIDTH - bodyLayout.width) / 2f, top)
        bodyLayout.draw(canvas)
        canvas.restore()

        // Wasserzeichen: Logo-Marke + „Wer Wie Was“-Schriftzug in jeder Szene
        drawWatermark(canvas)
        return bmp
    }

    /**
     * Wasserzeichen: runde Badge mit der Logo-Marke (Kamera mit blauem
     * Gehirn-Kreis) plus Schriftzug „Wer Wie Was“ mit blauem „Wie“,
     * in der unteren linken Ecke jeder Szene.
     */
    private fun drawWatermark(canvas: Canvas) {
        val cx = 88f * scale
        val cy = HEIGHT - 74f * scale
        val r = 30f * scale

        val badge = Paint(Paint.ANTI_ALIAS_FLAG)
        badge.color = Color.argb(210, 255, 255, 255)
        val ink = Paint(Paint.ANTI_ALIAS_FLAG)
        ink.color = Color.argb(235, 32, 33, 36)
        val blue = Paint(Paint.ANTI_ALIAS_FLAG)
        blue.color = Color.rgb(66, 133, 244)
        val white = Paint(Paint.ANTI_ALIAS_FLAG)
        white.color = Color.WHITE
        val line = Paint(Paint.ANTI_ALIAS_FLAG)
        line.color = Color.WHITE
        line.style = Paint.Style.STROKE
        line.strokeWidth = r * 0.10f
        line.strokeCap = Paint.Cap.ROUND

        val text = TextPaint(Paint.ANTI_ALIAS_FLAG)
        text.textSize = 30f * scale
        text.isFakeBoldText = true
        text.setShadowLayer(4f * scale, 0f, 2f * scale, Color.argb(130, 0, 0, 0))

        // Runde Badge mit Kamera-Silhouette
        canvas.drawCircle(cx, cy, r, badge)
        canvas.drawRoundRect(
            RectF(cx - r * 0.62f, cy - r * 0.42f, cx + r * 0.62f, cy + r * 0.42f),
            r * 0.20f, r * 0.20f, ink
        )
        canvas.drawRoundRect(
            RectF(cx - r * 0.28f, cy - r * 0.62f, cx + r * 0.28f, cy - r * 0.34f),
            r * 0.10f, r * 0.10f, ink
        )
        val lens = r * 0.40f
        canvas.drawCircle(cx, cy + r * 0.05f, lens, blue)
        canvas.drawCircle(cx, cy + r * 0.05f, lens * 0.42f, white)
        canvas.drawLine(cx - lens * 0.85f, cy - r * 0.26f, cx, cy + r * 0.05f, line)
        canvas.drawLine(cx + lens * 0.85f, cy - r * 0.26f, cx, cy + r * 0.05f, line)
        canvas.drawLine(cx, cy + r * 0.05f, cx, cy + r * 0.36f, line)

        // Schriftzug „Wer Wie Was“ mit blauem „Wie“
        val tx = cx + r + 16f * scale
        val base = cy + text.textSize * 0.34f
        text.color = Color.argb(225, 255, 255, 255)
        canvas.drawText("Wer ", tx, base, text)
        val w1 = text.measureText("Wer ")
        text.color = Color.rgb(66, 133, 244)
        canvas.drawText("Wie", tx + w1, base, text)
        val w2 = text.measureText("Wie")
        text.color = Color.argb(225, 255, 255, 255)
        canvas.drawText(" Was", tx + w1 + w2, base, text)
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




