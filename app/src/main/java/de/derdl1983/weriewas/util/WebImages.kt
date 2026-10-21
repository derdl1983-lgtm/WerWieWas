package de.derdl1983.weriewas.util

import android.content.Context
import de.derdl1983.weriewas.data.KnowledgeBase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Sucht online passende, frei lizenzierte Bilder zum Text der Erklärung
 * (primär Wikimedia Commons, alternativ Openverse) und lädt sie für die
 * Szenen des Erklärvideos in den App-Cache. Die Dateien beginnen mit
 * „www_“ und werden wie die TTS-Dateien nach 24 Stunden automatisch
 * gelöscht (EdgeTTS.pruneCache).
 *
 * Alle Fehler werden stillschweigend verschluckt: Findet die Suche nichts
 * (offline, kein Treffer, API gesperrt), läuft das Video einfach ohne
 * Themenbilder weiter.
 */
object WebImages {

    /** Ein zu einer Szene gefundenes Themenbild mit Quellenangabe (Autor, Lizenz). */
    data class WebImage(val filePath: String, val credit: String)

    /** Für Wikimedia-Pflicht: eigener User-Agent mit Kontaktadresse. */
    private const val USER_AGENT = "WerWieWas/1.0 (Android; github.com/derdl1983-lgtm/WerWieWas)"

    /**
     * Sucht für jede angegebene Suchanfrage ein Bild und liefert die Ergebnisse
     * in gleicher Reihenfolge zurück. queries darf null-Einträge enthalten –
     * für diese Szenen wird kein Bild gesucht (Ergebnis: null).
     * Nie mehr als zwei gleiche Bilder: Treffer werden nacheinander verbraucht.
     */
    suspend fun findByQueries(context: Context, queries: List<String?>): List<WebImage?> =
        withContext(Dispatchers.IO) {
            val cacheDir = context.cacheDir
            val results = arrayOfNulls<WebImage?>(queries.size)
            // Suchanfragen sammeln (eindeutig, ohne leere Einträge), Ecken der Liste behalten
            val distinct: List<Pair<Int, String>> = queries.mapIndexed { i, q -> i to q }
                .filter { !it.second.isNullOrBlank() }
                .map { it.first to it.second!! }
                .distinctBy { it.second.lowercase() }
            if (distinct.isEmpty()) return@withContext results.toList()
            val usedUrls = mutableSetOf<String>()
            for ((index, query) in distinct) {
                val img = runCatching {
                    searchCommons(query, usedUrls)?.let { download(cacheDir, it) }
                        ?: searchOpenverse(query, usedUrls)?.let { download(cacheDir, it) }
                }.getOrNull()
                if (img != null) {
                    results[index] = img
                    // Alle weiteren Positionen mit derselben Anfrage bedienen
                    queries.forEachIndexed { i, q ->
                        if (i != index && q != null && q.lowercase() == query.lowercase()) results[i] = img
                    }
                }
            }
            results.toList()
        }

    /**
     * Baut aus dem Analyseergebnis je Videoszene eine passende Suchanfrage:
     * Kernthema plus markantes Schlüsselwort aus dem Szenentext. Erste Szene
     * (eigenes Foto) und letzte Szene (Abspann) bekommen kein Bild.
     */
    fun queriesForSlides(explanationTitle: String, sceneTitles: List<String>, sceneTexts: List<String>): List<String?> {
        val subject = KnowledgeBase.subjectFromTitle(explanationTitle)
        if (subject.isBlank()) return sceneTitles.map { null }
        return sceneTitles.mapIndexed { i, title ->
            when (title) {
                "Das Objekt" -> null               // eigenes Foto vorhanden
                "Weiter lernen" -> null           // Abspann ohne Themenbezug
                else -> {
                    val subjectWords = subject.lowercase().split(Regex("[^a-zäöüß]+"))
                        .filter { it.isNotBlank() }.toSet()
                    val keyword = KnowledgeBase.keywordsOf(sceneTexts.getOrElse(i) { "" })
                        .firstOrNull { it !in subjectWords }
                    if (keyword != null) "$subject $keyword" else subject
                }
            }
        }
    }

    /** Sucht ein passendes Bild auf Wikimedia Commons (Datei-Namensraum, freie Lizenzen). */
    private fun searchCommons(query: String, usedUrls: MutableSet<String>): Candidate? {
        val url = ("https://commons.wikimedia.org/w/api.php?action=query&format=json"
            + "&generator=search&gsrsearch=" + URLEncoder.encode(query + " filetype:bitmap", "UTF-8")
            + "&gsrnamespace=6&gsrlimit=10&prop=imageinfo"
            + "&iiprop=url%7Cextmetadata&iiurlwidth=900")
        val text = httpGet(url) ?: return null
        val pages = JSONObject(text).optJSONObject("query")?.optJSONObject("pages") ?: return null
        val keys = pages.keys()
        while (keys.hasNext()) {
            val page = pages.optJSONObject(keys.next()) ?: continue
            val info = page.optJSONArray("imageinfo")?.optJSONObject(0) ?: continue
            val thumb = info.optString("thumburl")
            if (thumb.isBlank() || thumb in usedUrls) continue
            val meta = info.optJSONObject("extmetadata")
            val artist = stripHtml(meta?.optJSONObject("Artist")?.optString("value"))
            val license = meta?.optJSONObject("LicenseShortName")?.optString("value") ?: ""
            if (artist.isBlank() && license.isBlank()) continue
            usedUrls.add(thumb)
            val credit = listOf("Wikimedia Commons", artist.take(60), license.take(40))
                .filter { it.isNotBlank() }.joinToString(" · ")
            return Candidate(thumb, credit)
        }
        return null
    }

    /** Fallback: freie Bilder über die Openverse-API (CC-lizenziert). */
    private fun searchOpenverse(query: String, usedUrls: MutableSet<String>): Candidate? {
        val url = "https://api.openverse.org/v1/images/?page_size=10&q=" + URLEncoder.encode(query, "UTF-8")
        val text = httpGet(url) ?: return null
        val results = JSONObject(text).optJSONArray("results") ?: return null
        for (i in 0 until results.length()) {
            val item = results.optJSONObject(i) ?: continue
            val imageUrl = item.optString("url")
            if (imageUrl.isBlank() || imageUrl in usedUrls) continue
            usedUrls.add(imageUrl)
            val creator = item.optString("creator")
            val license = item.optString("license").uppercase()
            val credit = listOf("Openverse", creator.take(60), license.take(40))
                .filter { it.isNotBlank() }.joinToString(" · ")
            return Candidate(imageUrl, credit)
        }
        return null
    }

    /** Lädt ein Bild in den Cache und liefert Pfad + Quellenangabe zurück. */
    private fun download(cacheDir: File, candidate: Candidate): WebImage? {
        val conn = URL(candidate.url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 10000
            conn.readTimeout = 20000
            conn.setRequestProperty("User-Agent", USER_AGENT)
            if (conn.responseCode !in 200..299) return null
            val bytes = conn.inputStream.use { it.readBytes() }
            if (bytes.size < 500) return null
            val file = File(cacheDir, "www_img_" + Integer.toHexString(candidate.url.hashCode()) + ".jpg")
            FileOutputStream(file).use { it.write(bytes) }
            return WebImage(file.absolutePath, candidate.credit)
        } catch (e: Exception) {
            return null
        } finally {
            conn.disconnect()
        }
    }

    private data class Candidate(val url: String, val credit: String)

    private fun httpGet(url: String): String? {
        val conn = URL(url).openConnection() as HttpURLConnection
        return try {
            conn.connectTimeout = 10000
            conn.readTimeout = 15000
            conn.setRequestProperty("User-Agent", USER_AGENT)
            conn.setRequestProperty("Accept", "application/json")
            if (conn.responseCode !in 200..299) null
            else conn.inputStream.bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            null
        } finally {
            conn.disconnect()
        }
    }

    /** Entfernt HTML-Tags und Whitespace aus den Metadaten von Commons. */
    private fun stripHtml(s: String?): String =
        (s ?: "").replace(Regex("<[^>]*>"), " ").replace("&amp;", "&")
            .replace(Regex("\\s+"), " ").trim()
}
