package de.derdl1983.weriewas.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * Ein Foto (oder mehrere Ansichten), dessen Analyse noch aussteht –
 * z. B. weil beim Aufnehmen kein Netz vorhanden war.
 */
data class PendingItem(
    val id: String,
    val paths: List<String>,
    val timestamp: Long
)

/**
 * Analyse-Warteschlange: Fotos, deren Online-Analyse fehlgeschlagen ist
 * (meist wegen fehlender Internetverbindung), werden hier gesammelt.
 * Die Startseite versucht sie automatisch nachzusenden, sobald wieder
 * Netz da ist – oder der Nutzer löst den erneuten Versuch manuell aus.
 */
object PendingStore {

    private fun storeFile(context: Context): File = File(context.filesDir, "pending.json")

    fun loadAll(context: Context): MutableList<PendingItem> {
        val result = mutableListOf<PendingItem>()
        try {
            val file = storeFile(context)
            if (!file.exists()) return result
            val array = JSONArray(file.readText())
            for (i in 0 until array.length()) {
                result.add(fromJson(array.getJSONObject(i)))
            }
        } catch (e: Exception) {
            // Beschädigte Datei ignorieren, App weiter nutzbar halten
        }
        return result
    }

    /** Legt ein neues Fotoset in die Warteschlange und gibt es zurück. */
    fun add(context: Context, paths: List<String>): PendingItem {
        val item = PendingItem(
            id = UUID.randomUUID().toString(),
            paths = paths,
            timestamp = System.currentTimeMillis()
        )
        val list = loadAll(context)
        list.add(0, item)
        saveAll(context, list)
        return item
    }

    /** Entfernt ein erledigtes Fotoset (Bilddateien bleiben erhalten – sie werden zur Entdeckung). */
    fun remove(context: Context, id: String) {
        val list = loadAll(context)
        saveAll(context, list.filter { it.id != id })
    }

    /** Verwirft die komplette Warteschlange inklusive der gespeicherten Bilder. */
    fun clearAll(context: Context) {
        val list = loadAll(context)
        list.forEach { item ->
            item.paths.forEach { p -> File(p).delete() }
        }
        saveAll(context, emptyList())
    }

    private fun saveAll(context: Context, list: List<PendingItem>) {
        try {
            val array = JSONArray()
            list.forEach { array.put(toJson(it)) }
            storeFile(context).writeText(array.toString())
        } catch (e: Exception) {
        }
    }

    private fun toJson(item: PendingItem): JSONObject {
        val paths = JSONArray()
        item.paths.forEach { paths.put(it) }
        return JSONObject()
            .put("id", item.id)
            .put("paths", paths)
            .put("timestamp", item.timestamp)
    }

    private fun fromJson(o: JSONObject): PendingItem {
        val paths = mutableListOf<String>()
        val arr = o.optJSONArray("paths") ?: JSONArray()
        for (i in 0 until arr.length()) paths.add(arr.optString(i))
        return PendingItem(
            id = o.optString("id"),
            paths = paths,
            timestamp = o.optLong("timestamp", 0L)
        )
    }
}
