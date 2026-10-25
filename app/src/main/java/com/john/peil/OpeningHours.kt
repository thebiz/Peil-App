package com.john.peil

import java.time.LocalDateTime
import java.util.Locale

/**
 * Versteht die üblichen OpenStreetMap-Öffnungszeiten, z. B.
 * „Mo-Sa 07:00-21:00; Su off“ oder „Mo-Fr 08:00-12:00,14:00-18:00“ oder „24/7“.
 * Exotische Angaben (Monate, Wochen, Feiertage) werden nicht ausgewertet.
 */
object OpeningHours {

    class Status(val open: Boolean, val text: String)

    private val osmDays = listOf("Mo", "Tu", "We", "Th", "Fr", "Sa", "Su")
    private val deDays = listOf("Mo", "Di", "Mi", "Do", "Fr", "Sa", "So")
    private val dayToken = Regex("^(Mo|Tu|We|Th|Fr|Sa|Su)(?:-(Mo|Tu|We|Th|Fr|Sa|Su))?$")
    private val timeToken = Regex("^(\\d{1,2}):(\\d{2})-(\\d{1,2}):(\\d{2})\\+?$")

    /** Pro Wochentag (0 = Montag) die Zeitfenster in Minuten; null = nicht verstanden. */
    private fun parse(spec: String): Array<MutableList<IntRange>>? {
        val week = Array(7) { mutableListOf<IntRange>() }
        val s = spec.trim()
        if (s == "24/7") {
            week.forEach { it.add(0 until 1440) }
            return week
        }
        for (raw in s.split(';')) {
            var rule = raw.trim()
            if (rule.isEmpty() || rule.startsWith("PH") || rule.startsWith("SH")) continue

            var days: List<Int> = (0..6).toList()
            val space = rule.indexOf(' ')
            val head = if (space > 0) rule.substring(0, space) else rule
            val headParts = head.split(',')
            if (headParts.all { dayToken.matches(it) || it == "PH" }) {
                days = headParts.filter { it != "PH" }.flatMap { expand(it) }
                rule = if (space > 0) rule.substring(space + 1).trim() else ""
            }
            if (rule.isEmpty()) return null

            // Spätere Regeln überschreiben frühere für dieselben Tage
            days.forEach { week[it].clear() }
            if (rule == "off" || rule == "closed") continue

            for (t in rule.replace(" ", "").split(',')) {
                val m = timeToken.matchEntire(t) ?: return null
                val start = m.groupValues[1].toInt() * 60 + m.groupValues[2].toInt()
                var end = m.groupValues[3].toInt() * 60 + m.groupValues[4].toInt()
                if (end <= start) end += 1440
                days.forEach { week[it].add(start until end) }
            }
        }
        return week
    }

    private fun expand(token: String): List<Int> {
        val m = dayToken.matchEntire(token) ?: return emptyList()
        val a = osmDays.indexOf(m.groupValues[1])
        if (m.groupValues[2].isEmpty()) return listOf(a)
        val b = osmDays.indexOf(m.groupValues[2])
        val out = mutableListOf<Int>()
        var d = a
        while (true) {
            out.add(d)
            if (d == b) break
            d = (d + 1) % 7
        }
        return out
    }

    fun status(spec: String?, now: LocalDateTime = LocalDateTime.now()): Status? {
        if (spec.isNullOrBlank()) return null
        if (spec.trim() == "24/7") return Status(true, "Rund um die Uhr geöffnet")
        val week = parse(spec) ?: return null
        val today = now.dayOfWeek.value - 1
        val minute = now.hour * 60 + now.minute
        val yesterday = (today + 6) % 7

        // geöffnet? (auch Zeiten von gestern, die über Mitternacht gehen)
        val candidates = week[today].map { it to 0 } + week[yesterday].map { it to -1440 }
        for ((r, shift) in candidates) {
            val start = r.first + shift
            val end = r.last + 1 + shift
            if (minute in start until end) return Status(true, "Geöffnet · bis ${fmt(end)} Uhr")
        }

        // nächste Öffnung suchen
        for (offset in 0..7) {
            val day = (today + offset) % 7
            val next = week[day].map { it.first }.filter { offset > 0 || it > minute }.minOrNull() ?: continue
            val whenText = when (offset) {
                0 -> "heute"
                1 -> "morgen"
                else -> deDays[day]
            }
            return Status(false, "Geschlossen · öffnet $whenText ${fmt(next)} Uhr")
        }
        return Status(false, "Geschlossen")
    }

    /** Für die Anzeige: englische Tageskürzel → deutsche, eine Regel pro Zeile. */
    fun pretty(spec: String): String {
        var s = spec
        osmDays.forEachIndexed { i, d -> s = s.replace(Regex("\\b$d\\b"), deDays[i]) }
        return s.replace("off", "geschlossen")
            .replace("PH", "Feiertage")
            .split(';')
            .joinToString("\n") { it.trim() }
    }

    private fun fmt(min: Int): String {
        val m = ((min % 1440) + 1440) % 1440
        return String.format(Locale.GERMANY, "%02d:%02d", m / 60, m % 60)
    }
}
