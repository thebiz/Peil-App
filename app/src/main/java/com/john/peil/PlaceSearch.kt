package com.john.peil

import android.location.Location
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

data class Place(
    val name: String,
    val lat: Double,
    val lon: Double,
    val address: String?
)

/**
 * Sucht Orte in der Nähe – kostenlos und ohne Schlüssel.
 *
 * 1. Photon (komoot, basiert auf OpenStreetMap): antwortet meist in 1–2 Sekunden.
 *    - Namen wie „Penny“ oder „Fielmann“ → Volltextsuche rund um deinen Standort
 *    - Begriffe wie „Apotheke“ → Suche nach der passenden Kategorie im Umkreis
 * 2. Nur wenn Photon nicht erreichbar ist: Overpass als Notlösung.
 */
object PlaceSearch {

    private const val PHOTON = "https://photon.komoot.io"
    private const val OVERPASS = "https://overpass-api.de/api/interpreter"
    private const val MAX_DISTANCE_M = 15_000f
    private const val USER_AGENT = "Peil (Android; github.com/thebiz/Peil-App)"

    private class Category(val label: String, val osmTags: List<String>)

    // Allgemeine Begriffe → OSM-Kategorie (Schlüssel:Wert). Alles andere wird als Name gesucht.
    private val categories: Map<String, Category> = buildMap {
        fun add(label: String, vararg words: String, tags: List<String>) {
            val c = Category(label, tags)
            words.forEach { put(it, c) }
        }
        add("Apotheke", "apotheke", "apotheken", tags = listOf("amenity:pharmacy"))
        add("Post", "post", "postfiliale", "postamt", "dhl", tags = listOf("amenity:post_office"))
        add("Briefkasten", "briefkasten", tags = listOf("amenity:post_box"))
        add("Bäckerei", "bäcker", "bäckerei", "baecker", tags = listOf("shop:bakery"))
        add("Supermarkt", "supermarkt", "supermärkte", "einkaufen", tags = listOf("shop:supermarket"))
        add("Drogerie", "drogerie", tags = listOf("shop:chemist"))
        add("Optiker", "optiker", tags = listOf("shop:optician"))
        add("Café", "café", "cafe", "kaffee", tags = listOf("amenity:cafe"))
        add("Restaurant", "restaurant", "essen", tags = listOf("amenity:restaurant"))
        add("Imbiss", "imbiss", "fast food", tags = listOf("amenity:fast_food"))
        add("Geldautomat", "geldautomat", "bankautomat", "atm", tags = listOf("amenity:atm"))
        add("Bank", "bank", tags = listOf("amenity:bank"))
        add("Toilette", "toilette", "wc", "klo", tags = listOf("amenity:toilets"))
        add("Tankstelle", "tankstelle", tags = listOf("amenity:fuel"))
        add("Arzt", "arzt", "ärzte", "praxis", tags = listOf("amenity:doctors"))
        add("Krankenhaus", "krankenhaus", "klinik", tags = listOf("amenity:hospital"))
        add("Fahrradladen", "fahrradladen", "fahrradgeschäft", tags = listOf("shop:bicycle"))
        add("Bahnhof", "bahnhof", "s-bahn", "sbahn", "u-bahn", "ubahn", tags = listOf("railway:station"))
        add("Haltestelle", "bushaltestelle", "haltestelle", "bus", tags = listOf("highway:bus_stop"))
        add("Spielplatz", "spielplatz", tags = listOf("leisure:playground"))
        add("Park", "park", tags = listOf("leisure:park"))
        add("Fitnessstudio", "fitnessstudio", "gym", "fitness", tags = listOf("leisure:fitness_centre"))
        add("Kino", "kino", tags = listOf("amenity:cinema"))
    }

    // Bei der Namenssuche keine Straßen, Orte oder Grenzen anzeigen
    private val skipKeys = setOf("highway", "place", "boundary", "landuse", "waterway", "natural", "route")

    fun search(query: String, lat: Double, lon: Double): List<Place> {
        val key = query.trim().lowercase(Locale.GERMAN)
        val category = categories[key]
        return try {
            val found = if (category != null) photonCategory(category, lat, lon) else photonName(query.trim(), lat, lon)
            sortAndLimit(found, lat, lon)
        } catch (e: Exception) {
            // Photon nicht erreichbar → Overpass versuchen
            sortAndLimit(overpass(query, category, lat, lon), lat, lon)
        }
    }

    // ---------- Photon ----------

    private fun photonName(q: String, lat: Double, lon: Double): List<Place> {
        val url = "$PHOTON/api/?q=${enc(q)}&lat=$lat&lon=$lon&limit=50&lang=de" +
            "&location_bias_scale=0.1&zoom=14"
        return parsePhoton(get(url), fallbackName = q, skipGeneric = true)
            .filter { Regex(Regex.escape(q), RegexOption.IGNORE_CASE).containsMatchIn(it.name) }
    }

    private fun photonCategory(c: Category, lat: Double, lon: Double): List<Place> {
        val tags = c.osmTags.joinToString("") { "&osm_tag=${enc(it)}" }
        val url = "$PHOTON/reverse?lat=$lat&lon=$lon&radius=5&limit=40&lang=de$tags"
        return parsePhoton(get(url), fallbackName = c.label, skipGeneric = false)
    }

    private fun parsePhoton(json: String, fallbackName: String, skipGeneric: Boolean): List<Place> {
        val features = JSONObject(json).optJSONArray("features") ?: return emptyList()
        val seen = HashSet<String>()
        val result = mutableListOf<Place>()
        for (i in 0 until features.length()) {
            val f = features.getJSONObject(i)
            val props = f.optJSONObject("properties") ?: continue
            if (skipGeneric && props.optString("osm_key") in skipKeys) continue
            val coords = f.optJSONObject("geometry")?.optJSONArray("coordinates") ?: continue
            val lon = coords.optDouble(0)
            val lat = coords.optDouble(1)
            if (lat.isNaN() || lon.isNaN()) continue

            val name = props.optString("name").ifBlank { fallbackName }
            val street = props.optString("street")
            val number = props.optString("housenumber")
            val address = if (street.isNotBlank()) "$street $number".trim() else null

            if (!seen.add(dedupKey(name, lat, lon))) continue
            result.add(Place(name, lat, lon, address))
        }
        return result
    }

    // ---------- Overpass (Notlösung) ----------

    private fun overpass(query: String, category: Category?, lat: Double, lon: Double): List<Place> {
        val around = "(around:4000,$lat,$lon)"
        val body = if (category != null) {
            category.osmTags.joinToString("") {
                val (k, v) = it.split(":", limit = 2)
                "nwr[\"$k\"=\"$v\"]$around;"
            }
        } else {
            val safe = query.replace(Regex("[^\\p{L}\\p{N} &\\-]"), " ").trim().replace(Regex("\\s+"), " ")
            if (safe.isEmpty()) return emptyList()
            "nwr[\"name\"~\"$safe\",i]$around;nwr[\"brand\"~\"$safe\",i]$around;"
        }
        val ql = "[out:json][timeout:12];($body);out center tags 40;"
        val json = request(OVERPASS, "data=" + enc(ql), readTimeout = 15_000)

        val elements = JSONObject(json).optJSONArray("elements") ?: return emptyList()
        val seen = HashSet<String>()
        val result = mutableListOf<Place>()
        for (i in 0 until elements.length()) {
            val el = elements.getJSONObject(i)
            val tags = el.optJSONObject("tags") ?: continue
            val center = if (el.has("lat")) el else el.optJSONObject("center") ?: continue
            val pLat = center.optDouble("lat")
            val pLon = center.optDouble("lon")
            if (pLat.isNaN() || pLon.isNaN()) continue
            var name = tags.optString("name")
            if (name.isBlank()) name = tags.optString("brand")
            if (name.isBlank()) name = category?.label ?: query
            val street = tags.optString("addr:street")
            val address = if (street.isNotBlank()) "$street ${tags.optString("addr:housenumber")}".trim() else null
            if (!seen.add(dedupKey(name, pLat, pLon))) continue
            result.add(Place(name, pLat, pLon, address))
        }
        return result
    }

    // ---------- Helfer ----------

    private fun sortAndLimit(places: List<Place>, lat: Double, lon: Double): List<Place> {
        val res = FloatArray(1)
        return places
            .map { p ->
                Location.distanceBetween(lat, lon, p.lat, p.lon, res)
                p to res[0]
            }
            .filter { it.second <= MAX_DISTANCE_M }
            .sortedBy { it.second }
            .take(15)
            .map { it.first }
    }

    private fun dedupKey(name: String, lat: Double, lon: Double) =
        "${name.lowercase(Locale.GERMAN)}|${Math.round(lat * 2000)}|${Math.round(lon * 2000)}"

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun get(url: String): String = request(url, body = null, readTimeout = 10_000)

    private fun request(url: String, body: String?, readTimeout: Int): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 6_000
            conn.readTimeout = readTimeout
            conn.setRequestProperty("User-Agent", USER_AGENT)
            conn.setRequestProperty("Accept-Language", "de")
            if (body != null) {
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            if (conn.responseCode != 200) throw IOException("Server antwortet mit ${conn.responseCode}")
            return conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }
}
