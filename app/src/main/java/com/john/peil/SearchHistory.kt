package com.john.peil

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/** Merkt sich, was gesucht wurde – wie oft und wann zuletzt. Bleibt nur auf dem Handy. */
class SearchHistory(context: Context) {

    class Entry(val text: String, var count: Int, var lastUsed: Long)

    private val prefs = context.getSharedPreferences("peil", Context.MODE_PRIVATE)

    private fun key(s: String) = s.trim().lowercase(Locale.GERMAN)

    private fun load(): MutableList<Entry> {
        val arr = runCatching { JSONArray(prefs.getString("history", "[]")) }.getOrElse { JSONArray() }
        return (0 until arr.length()).mapNotNull { i ->
            runCatching {
                val o = arr.getJSONObject(i)
                Entry(o.getString("t"), o.optInt("c", 1), o.optLong("l", 0L))
            }.getOrNull()
        }.toMutableList()
    }

    private fun save(list: List<Entry>) {
        val arr = JSONArray()
        // höchstens 60 Einträge behalten: zuerst die häufigsten, dann die neuesten
        list.sortedWith(compareByDescending<Entry> { it.count }.thenByDescending { it.lastUsed })
            .take(60)
            .forEach { arr.put(JSONObject().put("t", it.text).put("c", it.count).put("l", it.lastUsed)) }
        prefs.edit().putString("history", arr.toString()).apply()
    }

    fun record(query: String) {
        val q = query.trim()
        if (q.isEmpty()) return
        val list = load()
        val e = list.firstOrNull { key(it.text) == key(q) }
        if (e != null) {
            e.count++
            e.lastUsed = System.currentTimeMillis()
        } else {
            list.add(Entry(q, 1, System.currentTimeMillis()))
        }
        save(list)
    }

    fun remove(query: String) {
        save(load().filterNot { key(it.text) == key(query) })
    }

    /**
     * Vorschläge zum eingegebenen Text: zuerst die häufigsten (mind. 2×), dann die letzten.
     * Passend zum Text heißt: Wortanfang passt, sonst irgendwo enthalten.
     */
    fun suggestions(typed: String, maxFrequent: Int = 4, maxRecent: Int = 5): Pair<List<Entry>, List<Entry>> {
        val t = key(typed)
        val all = load().filter { key(it.text) != t || t.isEmpty() }
        val matching = if (t.isEmpty()) all else {
            all.filter { e ->
                val k = key(e.text)
                k.startsWith(t) || k.split(' ', '-').any { it.startsWith(t) } || k.contains(t)
            }
        }
        val frequent = matching.filter { it.count >= 2 }
            .sortedWith(compareByDescending<Entry> { it.count }.thenByDescending { it.lastUsed })
            .take(maxFrequent)
        val recent = matching.filter { e -> frequent.none { it === e } }
            .sortedByDescending { it.lastUsed }
            .take(maxRecent)
        return frequent to recent
    }
}
