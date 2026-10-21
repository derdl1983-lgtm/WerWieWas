package de.derdl1983.weriewas.data

import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Baut die weiterführenden Links (Wikipedia, YouTube, DuckDuckGo, Amazon)
 * aus dem VOLLSTÄNDIGEN Ergebnis der Online-Analyse: Kernthema, Kategorie und
 * Schlüsselwörter aus den Erklärtexten ergeben deutlich präzisere Suchanfragen
 * als der reine Titel. Der Wikipedia-Link wird über die Wikipedia-API direkt
 * auf den passenden Artikel aufgelöst – scheitert das (offline), bleibt ein
 * Suchlink.
 */
object KnowledgeBase {

    /** Eigener User-Agent (Wikimedia-Richtlinie für API-Zugriffe). */
    private const val USER_AGENT = "WerWieWas/1.0 (Android; github.com/derdl1983-lgtm/WerWieWas)"

    /** Öffentliche Links für die Online-Analyse – aus dem vollständigen Analyseergebnis. */
    fun buildPublicLinks(explanation: Explanation): List<InfoLink> = buildLinks(explanation)

    /** Abwärtskompatibel: Links nur aus dem Titel (weniger präzise). */
    fun buildPublicLinks(title: String): List<InfoLink> = buildLinks(
        Explanation(
            title = title, category = "", summary = "", howItWorks = "", usage = "",
            practical = "", facts = emptyList(), safety = "", links = emptyList(),
            uncertain = false, source = "online"
        )
    )

    /** Räumt den Titel auf und liefert das Kernthema (für Suchen, Amazon und Bildersuche). */
    fun subjectFromTitle(title: String): String {
        var q = title.trim()
        if (q.startsWith("Möglicherweise: ")) q = q.removePrefix("Möglicherweise: ")
        if (q.startsWith("Unbekanntes Objekt")) {
            val inner = Regex("\\((.*?)\\)").find(q)?.groupValues?.get(1) ?: ""
            q = inner.removePrefix("evtl. ").trim()
        }
        q = q.replace(Regex("\\(.*?\\)"), " ")
        q = q.replace('/', ' ')
        q = q.replace(Regex("\\s+"), " ").trim()
        if (q.isBlank()) q = title.trim()
        return q
    }

    /** Füll- und Strukturwörter, die in keiner Suchanfrage auftauchen sollen. */
    private val STOP_WORDS = setOf(
        "wird", "werden", "wurde", "worden", "kann", "können", "könnte", "sollte", "sollten",
        "muss", "müssen", "dabei", "damit", "durch", "unter", "über", "auch", "noch", "nicht",
        "keine", "kein", "einer", "eine", "einen", "einem", "eines", "diese", "dieser", "dieses",
        "sowie", "beim", "für", "mit", "oder", "aber", "wenn", "weil", "wofür", "daran", "davon",
        "dazu", "mehr", "meist", "oft", "sehr", "ganz", "alles", "viele", "vieler", "beispiel",
        "beispielsweise", "einfach", "ebenfalls", "bereits", "schließlich", "hauptsächlich",
        "wichtig", "wichtige", "wichtigen", "immer", "wieder", "funktion", "funktioniert",
        "funktionieren", "verwendung", "verwendet", "verwenden", "sicherheit",
        "sicherheitshinweise", "sicherheitshinweis", "objekt", "tipps", "tipp", "fakt",
        "fakten", "informationen", "info", "erklärung", "erklärt", "erklären", "eignet",
        "handelt", "dabei", "deshalb", "deshalb", "dadurch", "hierdurch", "erforderlich"
    )

    /**
     * Liefert die markantesten Schlüsselwörter aus den Erklärtexten:
     * häufige, mindestens 5 Buchstaben lange Wörter ohne Füllwörter,
     * häufigste zuerst – maximal 6.
     */
    fun keywordsOf(vararg texts: String?): List<String> {
        val counts = HashMap<String, Pair<Int, Int>>() // kleinbuchstaben -> (vorkommen, länge)
        for (t in texts) {
            if (t.isNullOrBlank()) continue
            Regex("[A-Za-zÄÖÜäöüß][A-Za-zÄÖÜäöüß\\-]{4,}").findAll(t).forEach { match ->
                val w = match.value
                val lower = w.lowercase()
                if (lower !in STOP_WORDS) {
                    val old = counts[lower]
                    counts[lower] = Pair((old?.first ?: 0) + 1, w.length)
                }
            }
        }
        return counts.entries
            .sortedByDescending { it.value.first * 10 + it.value.second }
            .take(6)
            .map { it.key }
    }

    /**
     * Löst per Wikipedia-Such-API (opensearch) den passenden deutschsprachigen
     * Artikel zum Thema auf. Liefert (Artikelname, Artikel-URL) oder null.
     * Aufrufer muss auf einem Hintergrund-Thread sein.
     */
    fun resolveWikipediaArticle(query: String): Pair<String, String>? {
        if (query.isBlank()) return null
        return try {
            val url = ("https://de.wikipedia.org/w/api.php?action=opensearch&format=json"
                + "&limit=1&namespace=0&search=" + URLEncoder.encode(query, "UTF-8"))
            val conn = URL(url).openConnection() as HttpURLConnection
            try {
                conn.connectTimeout = 8000
                conn.readTimeout = 8000
                conn.setRequestProperty("User-Agent", USER_AGENT)
                conn.setRequestProperty("Accept", "application/json")
                if (conn.responseCode !in 200..299) return null
                val text = conn.inputStream.bufferedReader().use { it.readText() }
                val arr = JSONArray(text)
                val titles = arr.optJSONArray(1)
                val urls = arr.optJSONArray(3)
                if (titles != null && urls != null && titles.length() > 0) {
                    val t = titles.optString(0)
                    val u = urls.optString(0)
                    if (t.isNotBlank() && u.isNotBlank()) Pair(t, u) else null
                } else {
                    null
                }
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            null
        }
    }

    /** Baut die Weiterbildungslinks aus dem vollständigen Analyseergebnis. */
    private fun buildLinks(e: Explanation): List<InfoLink> {
        val subject = subjectFromTitle(e.title)
        val subjectWords = subject.lowercase().split(Regex("[^a-zäöüß]+")).filter { it.isNotBlank() }.toSet()
        // Schlüsselwörter aus den Erklärtexten (ohne die Wörter des Themas selbst)
        val keyword = keywordsOf(e.summary, e.howItWorks, e.usage, e.facts.joinToString(" "))
            .firstOrNull { it !in subjectWords }
        val webQuery = if (keyword != null) subject + " " + keyword else subject
        val youtubeQuery = subject + " einfach erklärt"

        val links = mutableListOf<InfoLink>()

        // Wikipedia: direkt auf den passenden Artikel auflösen (sonst Suche)
        val article = resolveWikipediaArticle(subject)
        if (article != null) {
            links.add(InfoLink("Wikipedia-Artikel: " + article.first, article.second, "wiki"))
        } else {
            links.add(
                InfoLink(
                    "Wikipedia-Suche: " + subject,
                    "https://de.wikipedia.org/wiki/Spezial:Suche?search=" + URLEncoder.encode(subject, "UTF-8"),
                    "wiki"
                )
            )
        }

        links.add(InfoLink("YouTube-Suche: " + youtubeQuery, "https://www.youtube.com/results?search_query=" + URLEncoder.encode(youtubeQuery, "UTF-8"), "youtube-search"))
        links.add(InfoLink("Websuche: " + webQuery, "https://duckduckgo.com/?q=" + URLEncoder.encode(webQuery, "UTF-8"), "web"))
        links.add(InfoLink("Amazon-Suche: " + subject, "https://www.amazon.de/s?k=" + URLEncoder.encode(subject, "UTF-8"), "amazon-search"))
        return links
    }
}
