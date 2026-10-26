package com.john.peil

import android.location.Location
import kotlin.math.max

/**
 * Beruhigt die Standortdaten:
 * - deutlich ungenauere Messungen kurz nach guten fliegen raus,
 * - einzelne Ausreißer-Sprünge werden ignoriert (erst wenn mehrere Messungen dasselbe sagen, wird gesprungen),
 * - sonst wird sanft geglättet – genaue Messungen zählen mehr als ungenaue.
 */
class LocationFilter {

    private var lat = Double.NaN
    private var lon = Double.NaN
    private var acc = 0f
    private var lastTime = 0L
    private var outlierCount = 0

    fun reset() {
        lat = Double.NaN
        outlierCount = 0
    }

    /** Gibt die geglättete Position zurück oder null, wenn die Messung verworfen wurde. */
    fun add(loc: Location): Location? {
        val a = if (loc.hasAccuracy()) max(loc.accuracy, 3f) else 30f
        val now = loc.elapsedRealtimeNanos / 1_000_000

        if (lat.isNaN()) {
            set(loc.latitude, loc.longitude, a, now)
            return out(loc)
        }

        val dt = ((now - lastTime) / 1000f).coerceAtLeast(0.1f)
        // Mit der Zeit wird unsere eigene Schätzung unsicherer (man bewegt sich ja)
        val ourAcc = acc + dt * 1.5f

        // sehr ungenau, während wir eine gute Position haben → verwerfen
        if (a > 60f && ourAcc < 25f) return null
        if (a > ourAcc * 3f && dt < 10f) return null

        val res = FloatArray(1)
        Location.distanceBetween(lat, lon, loc.latitude, loc.longitude, res)
        val jump = res[0]

        // Sprung deutlich größer als beide Ungenauigkeiten zusammen → erst mal als Ausreißer behandeln
        val plausible = ourAcc + a + dt * 8f  // bis ca. 30 km/h ist eine echte Bewegung
        if (jump > plausible * 1.5f) {
            outlierCount++
            if (outlierCount < 3) return null
            // dreimal hintereinander → offenbar echt (z. B. in der S-Bahn): übernehmen
            set(loc.latitude, loc.longitude, a, now)
            return out(loc)
        }
        outlierCount = 0

        // Gewichtung nach Genauigkeit (vereinfachter Kalman-Filter)
        val k = (ourAcc * ourAcc / (ourAcc * ourAcc + a * a)).coerceIn(0.15f, 0.9f)
        val nLat = lat + k * (loc.latitude - lat)
        val nLon = lon + k * (loc.longitude - lon)
        val nAcc = kotlin.math.sqrt(1f - k) * ourAcc
        set(nLat, nLon, max(nAcc, 3f), now)
        return out(loc)
    }

    private fun set(la: Double, lo: Double, a: Float, t: Long) {
        lat = la
        lon = lo
        acc = a
        lastTime = t
    }

    private fun out(src: Location): Location = Location(src).apply {
        latitude = lat
        longitude = lon
        accuracy = acc
    }
}
