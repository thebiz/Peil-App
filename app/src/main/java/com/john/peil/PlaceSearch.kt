package com.john.peil

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
 * Sucht Orte in der Nähe über OpenStreetMap (Overpass API) – kostenlos, ohne Schlüssel.
 * Später lässt sich das gegen Google Places austauschen, der Rest der App bleibt gleich.
 */
object PlaceSearch {

    private val endpoints = listOf(
        "https://overpass-api.de/api/interpreter",
        "https://overpass.kumi.systems/api/interpreter"
    )

    // Allgemeine Begriffe werden auf OSM-Kategorien abgebildet, alles andere wird als Name/Marke gesucht.
    private val categories: Map<String, String> = mapOf(
        "apotheke" to """["amenity"="pharmacy"]""",
        "post" to """["amenity"="post_office"]""",
        "postfiliale" to """["amenity"="post_office"]""",
        "briefkasten" to """["amenity"="post_box"]""",
        "bäcker" to """["shop"="bakery"]""",
        "bäckerei" to """["shop"="bakery"]""",
        "supermarkt" to """["shop"="supermarket"]""",
        "drogerie" to """["shop"="chemist"]""",
        "optiker" to """["shop"="optician"]""",
        "café" to """["amenity"="cafe"]""",
        "cafe" to """["amenity"="cafe"]""",
        "restaurant" to """["amenity"="restaurant"]""",
        "imbiss" to """["amenity"="fast_food"]""",
        "geldautomat" to """["amenity"="atm"]""",
        "bank" to """["amenity"="bank"]""",
        "toilette" to """["amenity"="toilets"]""",
        "wc" to """["amenity"="toilets"]""",
        "tankstelle" to """["amenity"="fuel"]""",
        "arzt" to """["amenity"="doctors"]""",
        "krankenhaus" to """["amenity"="hospital"]""",
        "fahrradladen" to """["shop"="bicycle"]""",
        "bahnhof" to """["railway"="station"]""",
        "s-bahn" to """["railway"="station"]["station"="light_rail"]""",
        "u-bahn" to """["railway"="station"]["station"="subway"]""",
        "bushaltestelle" to """["highway"="bus_stop"]""",
        "spielplatz" to """["leisure"="playground"]""",
        "park" to """["leisure"="park"]"""
    )

    /** Sucht erst im Umkreis von 2,5 km, wenn nichts kommt, bis 10 km. */
    fun search(query: String, lat: Double, lon: Double): List<Place> {
        for (radius in listOf(2500, 10000)) {
            val ql = buildQuery(query, lat, lon, radius) ?: return emptyList()
            val places = runQuery(ql)
            if (places.isNotEmpty()) return places
        }
        return emptyList()
    }

    private fun buildQuery(query: String, lat: Double, lon: Double, radius: Int): String? {
        val around = "(around:$radius,$lat,$lon)"
        val key = query.trim().lowercase(Locale.GERMAN)
        val filter = categories[key]
        val body = if (filter != null) {
            "nwr$filter$around;"
        } else {
            val safe = query
                .replace(Regex("[^\\p{L}\\p{N} &\\-]"), " ")
                .trim()
                .replace(Regex("\\s+"), " ")
            if (safe.isEmpty()) return null
            "nwr[\"name\"~\"$safe\",i]$around;nwr[\"brand\"~\"$safe\",i]$around;"
        }
        return "[out:json][timeout:20];($body);out center tags 60;"
    }

    private fun runQuery(ql: String): List<Place> {
        var lastError: Exception? = null
        for (endpoint in endpoints) {
            try {
                return parse(post(endpoint, ql))
            } catch (e: Exception) {
                lastError = e
            }
        }
        throw lastError ?: IOException("Keine Verbindung")
    }

    private fun post(endpoint: String, ql: String): String {
        val conn = URL(endpoint).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = 10_000
            conn.readTimeout = 25_000
            conn.setRequestProperty("User-Agent", "Peil/0.1 (Android, private Testversion)")
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
            val body = "data=" + URLEncoder.encode(ql, "UTF-8")
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            if (conn.responseCode != 200) throw IOException("Server antwortet mit ${conn.responseCode}")
            return conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private fun parse(json: String): List<Place> {
        val elements = JSONObject(json).optJSONArray("elements") ?: return emptyList()
        val seen = HashSet<String>()
        val result = mutableListOf<Place>()
        for (i in 0 until elements.length()) {
            val el = elements.getJSONObject(i)
            val tags = el.optJSONObject("tags") ?: continue

            val lat: Double
            val lon: Double
            if (el.has("lat")) {
                lat = el.getDouble("lat")
                lon = el.getDouble("lon")
            } else {
                val center = el.optJSONObject("center") ?: continue
                lat = center.getDouble("lat")
                lon = center.getDouble("lon")
            }

            var name = tags.optString("name")
            if (name.isBlank()) name = tags.optString("brand")
            if (name.isBlank()) name = tags.optString("operator")
            if (name.isBlank()) continue

            val street = tags.optString("addr:street")
            val number = tags.optString("addr:housenumber")
            val address = if (street.isNotBlank()) "$street $number".trim() else null

            // Doppelte Einträge (z. B. Gebäude + Punkt im Gebäude) zusammenfassen
            val key = "$name|${Math.round(lat * 2000)}|${Math.round(lon * 2000)}"
            if (!seen.add(key)) continue

            result.add(Place(name, lat, lon, address))
        }
        return result
    }
}
