package com.john.peil

import org.json.JSONObject
import java.time.Duration
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

/**
 * Verbindungen mit Bus & Bahn vom aktuellen Standort zum Ziel.
 * Quelle: transport.rest (offene Schnittstelle auf Basis der Deutschen-Bahn-Daten,
 * enthält auch S-Bahn, U-Bahn und Busse im HVV).
 */
object Transit {

    class Option(
        val departure: String,
        val arrival: String,
        val minutes: Long,
        val lines: String,
        val lineList: List<String>,
        val changes: Int,
        val firstStop: String?
    )

    private val hhmm = DateTimeFormatter.ofPattern("HH:mm")

    fun journeys(fromLat: Double, fromLon: Double, to: Place): List<Option> {
        val url = "https://v6.db.transport.rest/journeys" +
            "?from.latitude=$fromLat&from.longitude=$fromLon&from.address=${Http.enc("Mein Standort")}" +
            "&to.latitude=${to.lat}&to.longitude=${to.lon}&to.address=${Http.enc(to.name)}" +
            "&results=4&stopovers=false&remarks=false&language=de"
        val json = JSONObject(Http.get(url, readTimeout = 15_000))
        val journeys = json.optJSONArray("journeys") ?: return emptyList()
        val result = mutableListOf<Option>()
        for (i in 0 until journeys.length()) {
            val legs = journeys.getJSONObject(i).optJSONArray("legs") ?: continue
            if (legs.length() == 0) continue
            val first = legs.getJSONObject(0)
            val last = legs.getJSONObject(legs.length() - 1)
            val dep = parse(first.optString("departure")) ?: continue
            val arr = parse(last.optString("arrival")) ?: continue

            val lineNames = mutableListOf<String>()
            var firstStop: String? = null
            for (j in 0 until legs.length()) {
                val leg = legs.getJSONObject(j)
                if (leg.optBoolean("walking", false)) continue
                val line = leg.optJSONObject("line") ?: continue
                lineNames.add(line.optString("name").ifBlank { line.optString("productName") })
                if (firstStop == null) firstStop = leg.optJSONObject("origin")?.optString("name")
            }
            result.add(
                Option(
                    departure = dep.format(hhmm),
                    arrival = arr.format(hhmm),
                    minutes = Duration.between(dep, arr).toMinutes(),
                    lines = if (lineNames.isEmpty()) "Nur zu Fuß" else lineNames.joinToString(" → "),
                    lineList = lineNames.toList(),
                    changes = (lineNames.size - 1).coerceAtLeast(0),
                    firstStop = firstStop
                )
            )
        }
        return result
    }

    private fun parse(s: String): OffsetDateTime? =
        if (s.isBlank()) null else runCatching { OffsetDateTime.parse(s) }.getOrNull()
}
