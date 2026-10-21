package de.derdl1983.weriewas

import android.app.Application
import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Anwendungsklasse von „Wer Wie Was“.
 * Räumt beim Start alte temporäre Sprachdateien aus dem Cache auf und legt bei
 * unerwarteten Abstürzen ein Protokoll im App-Speicher ab, das in den
 * Einstellungen eingesehen und geteilt werden kann.
 */
class WerWieWasApp : Application() {

    override fun onCreate() {
        super.onCreate()
        de.derdl1983.weriewas.util.EdgeTTS.pruneCache(this)
        installCrashLogger()
        // Sperrbild-Schnellzugriff: Benachrichtigung nach Geräteneustart wiederherstellen
        val prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
        de.derdl1983.weriewas.service.LockAccess.sync(
            this,
            prefs.getBoolean(de.derdl1983.weriewas.service.LockAccess.PREF_KEY, false)
        )
    }

    /**
     * Fängt unbehandelte Abstürze ab, schreibt den Stacktrace in eine Datei
     * im App-internen Speicher (maximal 5 Dateien) und reicht den Fehler
     * anschließend an das Standard-Verhalten des Systems weiter.
     */
    private fun installCrashLogger() {
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val dir = File(filesDir, "crash_logs")
                dir.mkdirs()
                val files = dir.listFiles()?.toMutableList() ?: mutableListOf()
                while (files.size >= 5) {
                    val oldest = files.minByOrNull { it.lastModified() } ?: break
                    if (!oldest.delete()) break
                    files.remove(oldest)
                }
                val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                val outFile = File(dir, "crash_" + stamp + ".txt")
                val readable = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
                val sb = StringBuilder()
                sb.append("Zeit: ").append(readable).append('\n')
                sb.append("Thread: ").append(thread.name).append('\n')
                sb.append('\n')
                sb.append(throwable.toString()).append('\n')
                throwable.stackTrace.forEach { sb.append("    at ").append(it.toString()).append('\n') }
                var cause = throwable.cause
                var depth = 0
                while (cause != null && depth < 5) {
                    sb.append('\n').append("Verursacht durch: ").append(cause.toString()).append('\n')
                    cause.stackTrace.forEach { sb.append("    at ").append(it.toString()).append('\n') }
                    cause = cause.cause
                    depth++
                }
                sb.append('\n').append("(Fehlerprotokoll der App „Wer Wie Was“)")
                outFile.writeText(sb.toString())
            } catch (e: Exception) {
                // Die Protokollierung darf den Absturz nicht verschlimmern.
            }
            previousHandler?.uncaughtException(thread, throwable)
        }
    }

    companion object {

        /** Liefert die neueste Absturz-Protokolldatei, falls eine existiert. */
        fun latestCrashLog(context: Context): File? {
            return try {
                val dir = File(context.filesDir, "crash_logs")
                if (!dir.isDirectory) return null
                dir.listFiles()?.maxByOrNull { it.lastModified() }?.takeIf { it.length() > 0 }
            } catch (e: Exception) {
                null
            }
        }

        /** Löscht alle Absturz-Protokolle. */
        fun clearCrashLogs(context: Context) {
            try {
                val dir = File(context.filesDir, "crash_logs")
                dir.listFiles()?.forEach { it.delete() }
            } catch (e: Exception) {
            }
        }
    }
}

