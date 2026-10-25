package com.john.peil

import org.json.JSONObject

/** Lädt Öffnungszeiten, Telefon und Webseite direkt aus OpenStreetMap nach. */
object PlaceDetails {

    fun load(places: List<Place>) {
        val todo = places.filter { !it.detailsLoaded && it.osmType != null && it.osmId != null }
        for ((type, group) in todo.groupBy { it.osmType!! }) {
            val ok = runCatching { fetch(type, group) }.isSuccess
            if (!ok) {
                // Ein fehlender Eintrag lässt die Sammelabfrage scheitern → einzeln versuchen
                group.forEach { p -> runCatching { fetch(type, listOf(p)) } }
            }
            group.forEach { it.detailsLoaded = true }
        }
    }

    private fun fetch(type: String, group: List<Place>) {
        val plural = type + "s"
        val ids = group.joinToString(",") { it.osmId.toString() }
        val json = Http.get("https://api.openstreetmap.org/api/0.6/$plural.json?$plural=$ids")
        val elements = JSONObject(json).optJSONArray("elements") ?: return
        val byId = group.associateBy { it.osmId }
        for (i in 0 until elements.length()) {
            val el = elements.getJSONObject(i)
            val p = byId[el.optLong("id")] ?: continue
            val tags = el.optJSONObject("tags") ?: continue
            applyTags(p, tags)
        }
    }

    fun applyTags(p: Place, tags: JSONObject) {
        p.openingHours = tags.optString("opening_hours").ifBlank { null }
        p.phone = tags.optString("phone").ifBlank { tags.optString("contact:phone") }.ifBlank { null }
        p.website = tags.optString("website").ifBlank { tags.optString("contact:website") }.ifBlank { null }
        p.detailsLoaded = true
    }
}
