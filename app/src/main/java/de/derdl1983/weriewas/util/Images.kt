package de.derdl1983.weriewas.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import java.io.File

/** Hilfsfunktionen für Bitmaps: skalierendes Dekodieren und lokales Speichern. */
object Images {

    private const val TAG = "WerWieWasImages"

    /**
     * Dekodiert ein Bild (Content-URI oder Datei-URI) grob skalierend.
     * Das Bild wird einmalig komplett gelesen und daraus dekodiert –
     * robuster gegen HEIC/HEIF-Fotos, Cloud-Picker-URIs und Streams,
     * die nicht zweimal geöffnet werden können.
     */
    fun decodeScaled(context: Context, uri: Uri, maxDim: Int = 1024): Bitmap? {
        return try {
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            if (bytes == null || bytes.isEmpty()) {
                Log.w(TAG, "Stream leer für $uri")
                return null
            }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            val sample = if (bounds.outWidth > 0 && bounds.outHeight > 0) {
                sampleSize(bounds.outWidth, bounds.outHeight, maxDim)
            } else {
                // Bounds nicht lesbar: unskaliert versuchen.
                1
            }
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        } catch (e: Exception) {
            Log.e(TAG, "Dekodieren fehlgeschlagen für $uri", e)
            null
        }
    }

    /** Kopiert ein Bild skalierend in den App-internen Speicher; gibt den Pfad zurück. */
    fun copyToInternal(context: Context, uri: Uri): String? {
        val bmp = decodeScaled(context, uri, 1280) ?: return null
        val dir = File(context.filesDir, "images").apply { mkdirs() }
        val file = File(dir, "img_${System.currentTimeMillis()}.jpg")
        return try {
            file.outputStream().use { out -> bmp.compress(Bitmap.CompressFormat.JPEG, 88, out) }
            bmp.recycle()
            file.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "Speichern fehlgeschlagen", e)
            bmp.recycle()
            null
        }
    }

    /** Lädt ein bereits intern gespeichertes Bild als skalierte Bitmap. */
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
        }
    }

    private fun sampleSize(w: Int, h: Int, maxDim: Int): Int {
        var sample = 1
        val longest = maxOf(w, h)
        while (longest / (sample * 2) >= maxDim) sample *= 2
        return sample
    }
}
