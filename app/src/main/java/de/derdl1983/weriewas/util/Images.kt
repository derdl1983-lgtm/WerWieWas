package de.derdl1983.weriewas.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.util.Log
import java.io.ByteArrayInputStream
import java.io.File

/** Hilfsfunktionen für Bitmaps: skalierendes Dekodieren (inkl. EXIF-Drehung) und lokales Speichern. */
object Images {

    private const val TAG = "WerWieWasImages"

    /**
     * Liefert eine Klartext-Beschreibung des letzten Dekodier-Fehlers –
     * wird im Toast angezeigt, damit das Problem konkret benannt werden kann.
     * Null, wenn das letzte Dekodieren erfolgreich war.
     */
    @Volatile
    var lastError: String? = null
        private set

    /**
     * Dekodiert ein Bild (Content-URI oder Datei-URI) grob skalierend und
     * dreht es anhand der EXIF-Ausrichtung richtig herum.
     *
     * Strategie (jeder Schritt mit eigenem Fallback):
     *  1. Bild einmalig komplett in den Speicher lesen und aus dem Byte-Puffer
     *     per BitmapFactory dekodieren, EXIF-Drehung anwenden.
     *  2. Falls BitmapFactory nichts liefern kann (typisch HEIC/HEIF): ab
     *     Android 9 (API 28) ImageDecoder – versteht HEIC/HEIF, GIF, WebP
     *     und wendet die EXIF-Rotation selbst an.
     */
    fun decodeScaled(context: Context, uri: Uri, maxDim: Int = 1024): Bitmap? {
        lastError = null

        // Schritt 0: Daten einmalig lesen (robust gegen nur einmal öffnbare Streams).
        val bytes: ByteArray = try {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: run {
                    lastError = "Stream konnte nicht geöffnet werden ($uri)"
                    null
                }
        } catch (e: Exception) {
            lastError = "Lesen fehlgeschlagen: ${e.javaClass.simpleName}: ${e.message}"
            null
        }
        if (bytes == null || bytes.isEmpty()) {
            if (lastError == null) lastError = "Datei ist leer (0 Bytes)"
            Log.e(TAG, "decodeScaled abgebrochen: $lastError")
            return null
        }
        Log.i(TAG, "${bytes.size} Bytes gelesen von $uri")

        // Schritt 1: BitmapFactory aus dem Byte-Puffer + EXIF-Drehung.
        val classic = try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth > 0 && bounds.outHeight > 0) {
                val opts = BitmapFactory.Options().apply {
                    inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, maxDim)
                }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)?.let { bmp ->
                    applyOrientation(bmp, readOrientation(bytes))
                }
            } else {
                Log.w(TAG, "BitmapFactory: keine Bildmaße erkannt (Format nicht unterstützt, z. B. HEIC)")
                null
            }
        } catch (e: OutOfMemoryError) {
            lastError = "Zu wenig Speicher (OutOfMemory)"
            Log.e(TAG, "OutOfMemory beim BitmapFactory-Dekodieren")
            null
        } catch (e: Exception) {
            Log.w(TAG, "BitmapFactory fehlgeschlagen: ${e.message}")
            null
        }
        if (classic != null) return classic

        // Schritt 2: ImageDecoder (ab API 28) – versteht HEIC/HEIF, GIF, WebP inkl. EXIF-Rotation.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                val source = ImageDecoder.createSource(ByteArrayInputStream(bytes))
                val decoded = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    val longest = maxOf(info.size.width, info.size.height)
                    if (longest > maxDim) {
                        val sample = sampleSize(info.size.width, info.size.height, maxDim)
                        if (sample > 1) decoder.setTargetSampleSize(sample)
                    }
                }
                if (decoded != null && !decoded.isRecycled) return decoded
            } catch (e: Exception) {
                lastError = "ImageDecoder: ${e.javaClass.simpleName}: ${e.message}"
                Log.w(TAG, "ImageDecoder fehlgeschlagen: $lastError")
            }
        }

        if (lastError == null) lastError = "Format wird vom Gerät nicht unterstützt"
        Log.e(TAG, "Alle Dekodier-Wege fehlgeschlagen: $lastError")
        return null
    }

    /** Liest die EXIF-Ausrichtung aus den bereits gelesenen Bytes. */
    private fun readOrientation(bytes: ByteArray): Int = try {
        ExifInterface(ByteArrayInputStream(bytes)).getAttributeInt(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_NORMAL
        )
    } catch (e: Exception) {
        ExifInterface.ORIENTATION_NORMAL
    }

    /** Dreht ein Bitmap anhand der EXIF-Ausrichtung; bei Fehlern wird das Original zurückgegeben. */
    private fun applyOrientation(src: Bitmap, orientation: Int): Bitmap {
        val m = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { m.postRotate(90f); m.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_TRANSVERSE -> { m.postRotate(270f); m.postScale(-1f, 1f) }
            else -> return src
        }
        return try {
            val rotated = Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
            if (rotated != src) src.recycle()
            rotated
        } catch (e: OutOfMemoryError) {
            src
        }
    }

    /**
     * Kopiert ein Bild skalierend in den App-internen Speicher; gibt den Pfad zurück.
     * Blockierend (Datei-I/O) – nicht auf dem Main-Thread aufrufen.
     */
    fun copyToInternal(context: Context, uri: Uri): String? {
        val bmp = decodeScaled(context, uri, 1280) ?: return null
        val dir = File(context.filesDir, "images").apply { mkdirs() }
        val file = File(dir, "img_${System.currentTimeMillis()}.jpg")
        return try {
            file.outputStream().use { out -> bmp.compress(Bitmap.CompressFormat.JPEG, 88, out) }
            file.absolutePath
        } catch (e: Exception) {
            lastError = "Speichern fehlgeschlagen: ${e.message}"
            Log.e(TAG, "Speichern fehlgeschlagen", e)
            file.delete()
            null
        } finally {
            bmp.recycle()
        }
    }

    /** Lädt ein bereits intern gespeichertes Bild als skalierte Bitmap (bereits richtig gedreht). */
    fun loadThumbnail(path: String, maxDim: Int = 512): Bitmap? {
        return try {
            val file = File(path)
            if (!file.exists()) return null
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            BitmapFactory.decodeFile(
                path,
                BitmapFactory.Options().apply { inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, maxDim) }
            )
        } catch (e: Exception) {
            null
        } catch (e: OutOfMemoryError) {
            null
        }
    }

    private fun sampleSize(w: Int, h: Int, maxDim: Int): Int {
        var sample = 1
        val longest = maxOf(w, h)
        while (longest / (sample * 2) >= maxDim) sample *= 2
        return sample
    }
}
