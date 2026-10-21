package de.derdl1983.weriewas.data

import java.net.URLEncoder

/**
 * Baut die weiterführenden Links (Wikipedia, YouTube, DuckDuckGo, Amazon)
 * zum Titel einer Online-Analyse. Die fruehere lokale Wissensbasis mit den
 * festen Erkennungs-Erklärungen wurde entfernt – die Analyse läuft ausschließlich online.
 */
object KnowledgeBase {

    /** Öffentliche Links für die Online-Analyse: nur echte Suchlinks. */
    fun buildPublicLinks(title: String): List<InfoLink> = buildLinks(title, null)

    /** Baut eine präzisere Amazon-Suchanfrage: räumt den Titel auf, damit die Treffer zum Thema passen. */
    private fun buildAmazonQuery(title: String): String {
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

    /** Baut echte Weiterbildungslinks: Wikipedia (Artikel oder Suche), YouTube, DuckDuckGo, Amazon. */
    private fun buildLinks(title: String, wiki: String?): List<InfoLink> {
        val q = URLEncoder.encode(title, "UTF-8")
        val amazonQuery = buildAmazonQuery(title)
        val links = mutableListOf<InfoLink>()
        if (wiki != null) {
            links.add(
                InfoLink(
                    "Wikipedia: " + wiki.replace('_', ' '),
                    "https://de.wikipedia.org/wiki/" + wiki,
                    "wiki"
                )
            )
        } else {
            links.add(
                InfoLink(
                    "Wikipedia-Suche: " + title,
                    "https://de.wikipedia.org/wiki/Spezial:Suche?search=" + q,
                    "wiki"
                )
            )
        }
        links.add(InfoLink("YouTube-Suche: " + title, "https://www.youtube.com/results?search_query=" + q, "youtube-search"))
        links.add(InfoLink("DuckDuckGo-Suche: " + title, "https://duckduckgo.com/?q=" + q, "web"))
        links.add(InfoLink("Amazon-Suche: " + amazonQuery, "https://www.amazon.de/s?k=" + URLEncoder.encode(amazonQuery, "UTF-8"), "amazon-search"))
        return links
    }
}
