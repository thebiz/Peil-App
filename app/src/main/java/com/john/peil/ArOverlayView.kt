package com.john.peil

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.location.Location
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import java.util.Locale
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Zeichnet die Marker über das Kamerabild – wie bei einer Sternkarte:
 * Jeder Treffer schwebt in seiner Himmelsrichtung, unten zeigt ein Pfeil zum gewählten Ziel.
 */
class ArOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private class Target(val place: Place) {
        var distance = 0f
        var bearing = 0f
    }

    private var targets: List<Target> = emptyList()
    private var selected: Place? = null
    private val hitAreas = mutableListOf<Pair<RectF, Place>>()

    private var myLat = Double.NaN
    private var myLon = Double.NaN

    private var hasHeading = false
    private var sinAz = 0.0
    private var cosAz = 1.0
    private var heading = 0.0
    private var pitch = 0.0

    var compassUnreliable = false
        set(value) {
            field = value
            invalidate()
        }
    var bottomInset = 0
        set(value) {
            field = value
            invalidate()
        }

    /** Ungefährer horizontaler Bildwinkel einer Handy-Hauptkamera im Hochformat. */
    private val fov = 60.0

    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density

    private val accent = Color.parseColor("#FFB547")
    private val panelColor = Color.parseColor("#E6141619")

    private val panelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = panelColor }
    private val accentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accent }
    private val dimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(200, 255, 255, 255) }
    private val stemPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = dp(2f)
        color = Color.WHITE
    }
    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = dp(15f)
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(220, 255, 255, 255)
        textSize = dp(13f)
    }
    private val bigTitlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = dp(19f)
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val accentTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = accent
        textSize = dp(15f)
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = dp(14f)
        textAlign = Paint.Align.CENTER
    }
    private val arrowPath = Path()

    // ---------- Daten von außen ----------

    fun setLocation(lat: Double, lon: Double) {
        myLat = lat
        myLon = lon
        recompute()
        invalidate()
    }

    fun setTargets(places: List<Place>) {
        targets = places.map { Target(it) }
        selected = null
        recompute()
        invalidate()
    }

    fun setOrientation(azimuthDeg: Double, pitchDeg: Double) {
        val r = Math.toRadians(azimuthDeg)
        if (!hasHeading) {
            sinAz = sin(r)
            cosAz = cos(r)
            hasHeading = true
        } else {
            // sanft glätten, damit die Marker nicht zittern
            val a = 0.12
            sinAz += a * (sin(r) - sinAz)
            cosAz += a * (cos(r) - cosAz)
        }
        heading = (Math.toDegrees(atan2(sinAz, cosAz)) + 360.0) % 360.0
        pitch = pitchDeg
        invalidate()
    }

    private fun recompute() {
        if (myLat.isNaN()) return
        val res = FloatArray(2)
        for (t in targets) {
            Location.distanceBetween(myLat, myLon, t.place.lat, t.place.lon, res)
            t.distance = res[0]
            t.bearing = (res[1] + 360f) % 360f
        }
        targets = targets.sortedBy { it.distance }
    }

    /** Winkel zum Ziel relativ zur Blickrichtung: 0 = geradeaus, + = rechts, − = links */
    private fun relAngle(bearing: Float): Double = ((bearing - heading + 540.0) % 360.0) - 180.0

    // ---------- Zeichnen ----------

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        hitAreas.clear()
        if (targets.isEmpty() || myLat.isNaN() || !hasHeading) return

        val w = width.toFloat()
        val h = height.toFloat()
        val focus = selected?.let { s -> targets.firstOrNull { it.place == s } } ?: targets.first()
        val maxDist = max(targets.maxOf { it.distance }, 1f)

        // von fern nach nah, damit die nahen Marker oben liegen
        for (t in targets.asReversed()) {
            val rel = relAngle(t.bearing)
            val isFocus = t === focus
            val norm = t.distance / maxDist
            if (abs(rel) <= fov / 2) {
                val x = (w / 2 + (rel / (fov / 2)) * (w / 2)).toFloat()
                val y = h * 0.30f + (1f - norm) * h * 0.20f
                drawMarker(canvas, x, y, t, isFocus)
            } else {
                // außerhalb: kleiner Punkt am Rand
                val x = if (rel > 0) w - dp(10f) else dp(10f)
                val y = h * 0.30f + (1f - norm) * h * 0.20f
                canvas.drawCircle(x, y, dp(if (isFocus) 7f else 4f), if (isFocus) accentPaint else dimPaint)
            }
        }

        drawHints(canvas, w, h)
        drawGuide(canvas, focus, w, h)
    }

    private fun drawMarker(canvas: Canvas, x: Float, y: Float, t: Target, isFocus: Boolean) {
        val name = ellipsize(t.place.name, 24)
        val sub = formatDistance(t.distance)
        val tp = if (isFocus) bigTitlePaint else titlePaint
        val pad = dp(10f)
        val boxW = max(tp.measureText(name), textPaint.measureText(sub)) + pad * 2
        val boxH = tp.textSize + textPaint.textSize + pad * 2 + dp(4f)

        val left = (x - boxW / 2).coerceIn(dp(4f), width - boxW - dp(4f))
        val box = RectF(left, y - boxH, left + boxW, y)

        // Stiel + Punkt
        stemPaint.color = if (isFocus) accent else Color.WHITE
        canvas.drawLine(x, y, x, y + dp(22f), stemPaint)
        canvas.drawCircle(x, y + dp(22f), dp(if (isFocus) 7f else 5f), if (isFocus) accentPaint else stemPaint)

        canvas.drawRoundRect(box, dp(12f), dp(12f), panelPaint)
        if (isFocus) {
            val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = dp(2f)
                color = accent
            }
            canvas.drawRoundRect(box, dp(12f), dp(12f), border)
        }
        canvas.drawText(name, box.left + pad, box.top + pad + tp.textSize * 0.85f, tp)
        canvas.drawText(sub, box.left + pad, box.bottom - pad, if (isFocus) accentTextPaint else textPaint)

        hitAreas.add(RectF(box.left, box.top, box.right, y + dp(30f)) to t.place)
    }

    private fun drawGuide(canvas: Canvas, t: Target, w: Float, h: Float) {
        val margin = dp(16f)
        val panelH = dp(124f)
        val bottom = h - bottomInset - margin
        val panel = RectF(margin, bottom - panelH, w - margin, bottom)
        canvas.drawRoundRect(panel, dp(22f), dp(22f), panelPaint)

        // Pfeil im Kreis
        val r = dp(38f)
        val cx = panel.left + dp(18f) + r
        val cy = panel.centerY()
        canvas.drawCircle(cx, cy, r, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(40, 255, 181, 71) })
        val rel = relAngle(t.bearing)
        canvas.save()
        canvas.rotate(rel.toFloat(), cx, cy)
        arrowPath.reset()
        arrowPath.moveTo(cx, cy - r * 0.72f)
        arrowPath.lineTo(cx + r * 0.48f, cy + r * 0.52f)
        arrowPath.lineTo(cx, cy + r * 0.22f)
        arrowPath.lineTo(cx - r * 0.48f, cy + r * 0.52f)
        arrowPath.close()
        canvas.drawPath(arrowPath, accentPaint)
        canvas.restore()

        // Texte
        val tx = cx + r + dp(16f)
        val maxChars = ((panel.right - tx - dp(12f)) / (bigTitlePaint.textSize * 0.55f)).toInt().coerceAtLeast(8)
        var line = panel.top + dp(30f)
        canvas.drawText(ellipsize(t.place.name, maxChars), tx, line, bigTitlePaint)
        line += dp(24f)
        canvas.drawText(
            "${formatDistance(t.distance)} · ca. ${walkMinutes(t.distance)} Min. zu Fuß",
            tx, line, accentTextPaint
        )
        line += dp(22f)
        canvas.drawText(directionText(rel), tx, line, textPaint)
        t.place.address?.let {
            line += dp(19f)
            canvas.drawText(ellipsize(it, maxChars + 6), tx, line, textPaint)
        }

        // Blickrichtung klein oben rechts im Panel
        val hdg = "${compassWord(heading)} ${heading.roundToInt()}°"
        val small = Paint(textPaint).apply { textAlign = Paint.Align.RIGHT; textSize = dp(11f) }
        canvas.drawText(hdg, panel.right - dp(14f), panel.top + dp(18f), small)
    }

    private fun drawHints(canvas: Canvas, w: Float, h: Float) {
        val msg = when {
            abs(pitch) > 55 -> "Handy aufrecht halten – wie zum Filmen"
            compassUnreliable -> "Kompass ungenau – Handy einmal in einer Acht schwenken"
            else -> return
        }
        val tw = hintPaint.measureText(msg) + dp(28f)
        val y = h * 0.62f
        val box = RectF(w / 2 - tw / 2, y - dp(18f), w / 2 + tw / 2, y + dp(18f))
        canvas.drawRoundRect(box, dp(18f), dp(18f), panelPaint)
        canvas.drawText(msg, w / 2, y + hintPaint.textSize * 0.35f, hintPaint)
    }

    // ---------- Antippen: anderes Ziel wählen ----------

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val hit = hitAreas.lastOrNull { it.first.contains(event.x, event.y) }
        return when (event.action) {
            MotionEvent.ACTION_DOWN -> hit != null
            MotionEvent.ACTION_UP -> {
                if (hit != null) {
                    selected = hit.second
                    invalidate()
                    performClick()
                }
                true
            }
            else -> hit != null
        }
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    // ---------- Helfer ----------

    private fun directionText(rel: Double): String = when {
        abs(rel) <= 10 -> "Geradeaus"
        abs(rel) >= 150 -> "Hinter dir – umdrehen"
        rel > 0 -> "Nach rechts drehen (${rel.roundToInt()}°)"
        else -> "Nach links drehen (${(-rel).roundToInt()}°)"
    }

    private fun compassWord(deg: Double): String {
        val words = listOf("N", "NO", "O", "SO", "S", "SW", "W", "NW")
        return words[(((deg + 22.5) % 360) / 45).toInt() % 8]
    }

    private fun ellipsize(s: String, maxChars: Int): String =
        if (s.length <= maxChars) s else s.take(max(1, maxChars - 1)).trimEnd() + "…"

    private fun formatDistance(m: Float): String = when {
        m < 100 -> "${max(5, (m / 5).roundToInt() * 5)} m"
        m < 1000 -> "${(m / 10).roundToInt() * 10} m"
        else -> String.format(Locale.GERMANY, "%.1f km", m / 1000f)
    }

    /** ca. 4,8 km/h Gehtempo */
    private fun walkMinutes(m: Float): Int = max(1, min(999, ceil(m / 80.0).toInt()))
}
