package de.derdl1983.weriewas.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Ein erkanntes Label mit Konfidenz (0..1). */
data class LabelConfidence(val label: String, val confidence: Float)

/** Ein Quellen-/Weiterbildungslink. kind: "wiki" | "youtube-search" | "amazon-search" | "web" */
data class InfoLink(val label: String, val url: String, val kind: String)

/** Strukturierte Erklärung zu einer Entdeckung. */
data class Explanation(
    val title: String,
    val category: String,
    val summary: String,
    val howItWorks: String,
    val usage: String,
    val practical: String,
    val facts: List<String>,
    val safety: String,
    val links: List<InfoLink>,
    val uncertain: Boolean,
    val source: String // "lokal" | "online"
)

/** Eine gespeicherte Entdeckung. imagePath ist die Hauptansicht; extraPaths enthält weitere Fotos (verschiedene Seiten). */
data class Discovery(
    val id: String,
    val imagePath: String,
    val timestamp: Long,
    val labels: List<LabelConfidence>,
    val explanation: Explanation,
    var isFavorite: Boolean = false,
    val extraPaths: List<String> = emptyList()
)

/** Einfache JSON-basierte Speicherung der Entdeckungen im App-internen Speicher. */
object DiscoveryStore {

    private fun storeFile(context: Context): File = File(context.filesDir, "discoveries.json")

    fun loadAll(context: Context): MutableList<Discovery> {
        val result = mutableListOf<Discovery>()
        try {
            val file = storeFile(context)
            if (!file.exists()) return result
            val array = JSONArray(file.readText())
            for (i in 0 until array.length()) {
                result.add(discoveryFromJson(array.getJSONObject(i)))
            }
        } catch (e: Exception) {
            // Beschädigte Datei ignorieren, App weiter nutzbar halten
        }
        return result
    }

    fun saveAll(context: Context, list: List<Discovery>) {
        try {
            val array = JSONArray()
            list.forEach { array.put(discoveryToJson(it)) }
            storeFile(context).writeText(array.toString())
        } catch (e: Exception) {
        }
    }

    fun add(context: Context, discovery: Discovery): MutableList<Discovery> {
        val list = loadAll(context)
        list.add(0, discovery)
        saveAll(context, list)
        return list
    }

    fun get(context: Context, id: String): Discovery? =
        loadAll(context).firstOrNull { it.id == id }

    /** Aktualisiert eine bestehende Entdeckung (z. B. nach Online-Tiefenanalyse). */
    fun update(context: Context, discovery: Discovery) {
        val list = loadAll(context)
        val idx = list.indexOfFirst { it.id == discovery.id }
        if (idx >= 0) {
            list[idx] = discovery
            saveAll(context, list)
        }
    }

    fun toggleFavorite(context: Context, id: String) {
        val list = loadAll(context)
        val d = list.firstOrNull { it.id == id }
        if (d != null) {
            d.isFavorite = !d.isFavorite
            saveAll(context, list)
        }
    }

    fun remove(context: Context, id: String) {
        val list = loadAll(context)
        val d = list.firstOrNull { it.id == id }
        if (d != null) {
            File(d.imagePath).delete()
            d.extraPaths.forEach { File(it).delete() }
            saveAll(context, list.filter { it.id != id })
        }
    }

    fun clearAll(context: Context) {
        val list = loadAll(context)
        list.forEach {
            File(it.imagePath).delete()
            it.extraPaths.forEach { p -> File(p).delete() }
        }
        saveAll(context, emptyList())
    }

    // --- JSON-Mapping ---

    private fun discoveryToJson(d: Discovery): JSONObject {
        val links = JSONArray()
        d.explanation.links.forEach {
            links.put(JSONObject().put("label", it.label).put("url", it.url).put("kind", it.kind))
        }
        val facts = JSONArray()
        d.explanation.facts.forEach { facts.put(it) }
        val labels = JSONArray()
        d.labels.forEach { labels.put(JSONObject().put("label", it.label).put("conf", it.confidence.toDouble())) }
        val expl = JSONObject()
            .put("title", d.explanation.title)
            .put("category", d.explanation.category)
            .put("summary", d.explanation.summary)
            .put("howItWorks", d.explanation.howItWorks)
            .put("usage", d.explanation.usage)
            .put("practical", d.explanation.practical)
            .put("facts", facts)
            .put("safety", d.explanation.safety)
            .put("links", links)
            .put("uncertain", d.explanation.uncertain)
            .put("source", d.explanation.source)
        val extra = JSONArray()
        d.extraPaths.forEach { extra.put(it) }
        return JSONObject()
            .put("id", d.id)
            .put("imagePath", d.imagePath)
            .put("extraPaths", extra)
            .put("timestamp", d.timestamp)
            .put("labels", labels)
            .put("explanation", expl)
            .put("favorite", d.isFavorite)
    }

    private fun discoveryFromJson(o: JSONObject): Discovery {
        val labels = mutableListOf<LabelConfidence>()
        val labelArray = o.optJSONArray("labels") ?: JSONArray()
        for (i in 0 until labelArray.length()) {
            val jo = labelArray.getJSONObject(i)
            labels.add(LabelConfidence(jo.optString("label"), jo.optDouble("conf", 0.0).toFloat()))
        }
        val e = o.optJSONObject("explanation") ?: JSONObject()
        val facts = mutableListOf<String>()
        val factArray = e.optJSONArray("facts") ?: JSONArray()
        for (i in 0 until factArray.length()) facts.add(factArray.optString(i))
        val links = mutableListOf<InfoLink>()
        val linkArray = e.optJSONArray("links") ?: JSONArray()
        for (i in 0 until linkArray.length()) {
            val jo = linkArray.getJSONObject(i)
            links.add(InfoLink(jo.optString("label"), jo.optString("url"), jo.optString("kind")))
        }
        val extraPaths = mutableListOf<String>()
        val extraArray = o.optJSONArray("extraPaths") ?: JSONArray()
        for (i in 0 until extraArray.length()) extraPaths.add(extraArray.optString(i))
        return Discovery(
            id = o.optString("id"),
            imagePath = o.optString("imagePath"),
            timestamp = o.optLong("timestamp", 0L),
            labels = labels,
            explanation = Explanation(
                title = e.optString("title", "Unbekannt"),
                category = e.optString("category", ""),
                summary = e.optString("summary", ""),
                howItWorks = e.optString("howItWorks", ""),
                usage = e.optString("usage", ""),
                practical = e.optString("practical", ""),
                facts = facts,
                safety = e.optString("safety", ""),
                links = links,
                uncertain = e.optBoolean("uncertain", false),
                source = e.optString("source", "lokal")
            ),
            isFavorite = o.optBoolean("favorite", false),
            extraPaths = extraPaths
        )
    }
}

