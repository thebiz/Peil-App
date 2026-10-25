package com.john.peil

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Ein gefundener Ort. Details (Öffnungszeiten usw.) werden nachgeladen. */
class Place(
    val name: String,
    val lat: Double,
    val lon: Double,
    val address: String?,
    val osmType: String?,   // "node", "way" oder "relation"
    val osmId: Long?,
    val category: String?
) {
    var openingHours: String? = null
    var phone: String? = null
    var website: String? = null
    var detailsLoaded = false

    val key: String
        get() = if (osmType != null && osmId != null) "$osmType/$osmId" else "$name|$lat|$lon"

    fun toJson(): JSONObject = JSONObject().apply {
        put("name", name)
        put("lat", lat)
        put("lon", lon)
        put("address", address ?: "")
        put("osmType", osmType ?: "")
        put("osmId", osmId ?: -1L)
        put("category", category ?: "")
    }

    companion object {
        fun fromJson(o: JSONObject): Place {
            val id = o.optLong("osmId", -1L)
            return Place(
                name = o.optString("name"),
                lat = o.optDouble("lat"),
                lon = o.optDouble("lon"),
                address = o.optString("address").ifBlank { null },
                osmType = o.optString("osmType").ifBlank { null },
                osmId = if (id >= 0) id else null,
                category = o.optString("category").ifBlank { null }
            )
        }
    }
}

/** Gemerkte Lieblingsorte, lokal auf dem Handy gespeichert. */
class Favorites(context: Context) {
    private val prefs = context.getSharedPreferences("peil", Context.MODE_PRIVATE)

    fun all(): List<Place> {
        val arr = runCatching { JSONArray(prefs.getString("favorites", "[]")) }.getOrElse { JSONArray() }
        return (0 until arr.length()).mapNotNull { runCatching { Place.fromJson(arr.getJSONObject(it)) }.getOrNull() }
    }

    fun contains(p: Place) = all().any { it.key == p.key }

    /** Gibt zurück, ob der Ort jetzt gemerkt ist. */
    fun toggle(p: Place): Boolean {
        val list = all().toMutableList()
        val existing = list.firstOrNull { it.key == p.key }
        val nowSaved = if (existing != null) {
            list.remove(existing)
            false
        } else {
            list.add(0, p)
            true
        }
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        prefs.edit().putString("favorites", arr.toString()).apply()
        return nowSaved
    }
}
