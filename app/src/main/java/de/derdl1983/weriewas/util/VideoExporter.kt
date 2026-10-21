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

        val audio = encodeAudio(pcm, TARGET_RATE)
        val video = encodeVideo(durationUs) { pixelsAt(it) }
        val file = mux(context, audio, video)
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
                throw ExportException("Sprachausgabe konnte nicht initialisiert werden.")
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
                            if (image == null) {
                                throw ExportException("Der Video-Encoder wird von diesem Gerät nicht unterstützt.")
                            }
                            fillYuv420(image, pixelsAt(t))
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
