package com.john.peil

import android.graphics.Color
import android.graphics.Typeface

/** Farben und Schriften im Look der Spur-App, Akzent in Türkis. */
object Ui {
    val BG = Color.parseColor("#F5F5F2")          // Hintergrund
    val INK = Color.parseColor("#252927")         // Haupttext, dunkle Chips
    val INK_SOFT = Color.parseColor("#3D433F")
    val ACCENT = Color.parseColor("#0F9E91")      // Türkis
    val CARD = Color.WHITE
    val BORDER = Color.parseColor("#E4E6DF")
    val BORDER_STRONG = Color.parseColor("#D6DCCF")
    val CHIP = Color.parseColor("#E9ECE4")
    val CHIP_TEXT = Color.parseColor("#596452")
    val MUTED = Color.parseColor("#7E8778")
    val LABEL = Color.parseColor("#9A9F96")
    val RING = Color.parseColor("#E2E5DD")
    val SOFT = Color.parseColor("#DCF3F0")        // helles Türkis
    val SOFT_TEXT = Color.parseColor("#0A5650")
    val OK = Color.parseColor("#3B7049")          // grün = geöffnet
    val OK_SOFT = Color.parseColor("#E3EFE6")
    val PRESSED = Color.parseColor("#F2F3EE")
    val CLOSED = Color.parseColor("#9A4A1C")       // geschlossen
    val CLOSED_SOFT = Color.parseColor("#FBEBE1")

    val MEDIUM: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    val BOLD: Typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
    val REGULAR: Typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)

    /** Linienfarbe für Badges (HVV-ähnlich). */
    fun lineColor(name: String): Int {
        val n = name.trim().uppercase()
        return when {
            n.startsWith("S") && n.length <= 4 -> Color.parseColor("#2E9A3F")
            n.startsWith("U") && n.length <= 3 -> Color.parseColor("#1B62A8")
            n.startsWith("RE") || n.startsWith("RB") || n.startsWith("ICE") || n.startsWith("IC") ->
                Color.parseColor("#6B7280")
            n.startsWith("F") || n.contains("FÄHRE") -> Color.parseColor("#0B8FB3")
            else -> Color.parseColor("#D4202A") // Bus
        }
    }
}
