package com.john.peil

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RectF
import android.graphics.Typeface
import android.location.Location
import android.os.SystemClock
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import java.util.Locale
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Zeichnet die Marker über das Kamerabild – wie bei einer Sternkarte.
 * Handy flach halten → Kartenansicht von oben (Radar).
 * Unten zeigt ein Pfeil zum gewählten Ziel; Antippen öffnet die Details.
 */
class ArOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private class Target(val place: Place) {
        var distance = 0f
        var bearing = 0f
        var hasBearing = false
    }

    /** Am Ziel angekommen? Mit Hysterese, damit es nicht hin und her springt. */
    private var arrived = false
    private var arrivedPlace: Place? = null

    private var targets: List<Target> = emptyList()
    private var selected: Place? = null
    private val hitAreas = mutableListOf<Pair<RectF, Place>>()
    private val guideRect = RectF()

    private var myLat = Double.NaN
    private var myLon = Double.NaN

    // Lage der Kamera: Kamera → Ost-Oben-Süd, zeilenweise 3×3
    private val camRot = FloatArray(9)
    private var hasHeading = false
    private var heading = 0.0
    private var pitch = 0.0
    private var camFx = 0f
    private var camFy = 0f
    /** Abweichung des Bezugsnordens (Kompass: Missweisung, ARCore: 0) */
    private var bearingOffset = 0.0

    /** true, wenn ARCore die Lage per Street View kennt (Marker „kleben“ an der Wand) */
    var arLocked = false
        set(value) { field = value; invalidate() }

    var onMarkerTap: ((Place) -> Unit)? = null
    var onGuideTap: ((Place) -> Unit)? = null

    var compassUnreliable = false
        set(value) { field = value; invalidate() }
    var onlyFocus = false
        set(value) { field = value; invalidate() }
    var bottomInset = 0
        set(value) { field = value; invalidate() }

    val isFlat: Boolean get() = abs(pitch) > 55

    /** Ungefährer horizontaler Bildwinkel einer Handy-Hauptkamera im Hochformat (ohne ARCore). */
    private val fov = 60.0

    private val tmp = PointF()
    private val placedBoxes = mutableListOf<RectF>()

    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density

    private val accent = Ui.ACCENT
    private val shadowColor = Color.argb(45, 20, 25, 20)

    // Weiße Karten mit feinem Rand und weichem Schatten – wie in Spur
    private val cardPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Ui.CARD
        setShadowLayer(dp(8f), 0f, dp(2f), shadowColor)
    }
    private val cardBorder = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
        color = Ui.BORDER
    }
    private val accentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accent }
    private val softPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Ui.SOFT }
    private val inkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Ui.INK }
    private val whitePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        setShadowLayer(dp(3f), 0f, dp(1f), shadowColor)
    }
    private val dotRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(3f)
        color = Ui.INK
    }
    private val stemPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = dp(2.5f)
        strokeCap = Paint.Cap.ROUND
    }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
        color = accent
    }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.5f)
        color = Ui.RING
    }
    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Ui.INK
        textSize = dp(15f)
        typeface = Ui.MEDIUM
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Ui.MUTED
        textSize = dp(13f)
        typeface = Ui.REGULAR
    }
    private val smallPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Ui.LABEL
        textSize = dp(11f)
        typeface = Ui.BOLD
        letterSpacing = 0.12f
    }
    private val bigTitlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Ui.INK
        textSize = dp(19f)
        typeface = Ui.MEDIUM
        letterSpacing = -0.02f
    }
    private val accentTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = accent
        textSize = dp(15f)
        typeface = Ui.BOLD
    }
    private val centerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Ui.SOFT_TEXT
        textSize = dp(14f)
        typeface = Ui.MEDIUM
        textAlign = Paint.Align.CENTER
    }
    private val northPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = accent
        textSize = dp(15f)
        typeface = Ui.BOLD
        textAlign = Paint.Align.CENTER
    }
    private val arrowPath = Path()

    init {
        // Ziffern gleich breit (Entfernungen, Uhrzeiten)
        listOf(titlePaint, textPaint, smallPaint, bigTitlePaint, accentTextPaint).forEach { it.fontFeatureSettings = "tnum" }
    }

    // ---------- Daten von außen ----------

    fun setLocation(lat: Double, lon: Double) {
        myLat = lat
        myLon = lon
        recompute()
        invalidate()
    }

    fun setTargets(places: List<Place>) {
        targets = places.map { Target(it) }
        noFocus = false
        radarZoom = 1f
        if (selected != null && places.none { it === selected }) selected = null
        recompute()
        invalidate()
    }

    fun select(place: Place?) {
        if (place !== selected) radarZoom = 1f // neues Ziel: Radar passt sich wieder automatisch an
        selected = place
        noFocus = false
        invalidate()
    }

    /** Ziel abwählen: keine Hervorhebung, kein Pfeil, alle Treffer gleichwertig. */
    fun deselect() {
        selected = null
        noFocus = true
        radarZoom = 1f
        invalidate()
    }

    /** Hat der Nutzer ein Ziel ausdrücklich gewählt? */
    fun selectedPlace(): Place? = if (noFocus) null else selected

    private var noFocus = false

    fun focusPlace(): Place? = focusTarget()?.place

    fun distanceTo(place: Place): Float? = targets.firstOrNull { it.place === place }?.distance

    /**
     * Neue Kameralage. [rot] = Kamera → Ost-Oben-Süd (zeilenweise 3×3).
     * [fxPx]/[fyPx] = Brennweite in Pixeln (0 = Schätzwert), [offsetDeg] = Missweisung bei Kompass-Daten.
     */
    fun setCameraPose(rot: FloatArray, fxPx: Float, fyPx: Float, offsetDeg: Double, smooth: Boolean) {
        if (!hasHeading || !smooth) {
            System.arraycopy(rot, 0, camRot, 0, 9)
        } else {
            // sanft glätten, damit die Marker nicht zittern
            for (i in 0 until 9) camRot[i] += 0.25f * (rot[i] - camRot[i])
        }
        hasHeading = true
        camFx = fxPx
        camFy = fyPx
        bearingOffset = offsetDeg

        // Blickrichtung = −Z der Kamera; bei flach gehaltenem Handy die Oberkante (+Y)
        val fx = -camRot[2].toDouble()
        val fy = -camRot[5].toDouble()
        val fz = -camRot[8].toDouble()
        pitch = Math.toDegrees(asin(fy.coerceIn(-1.0, 1.0)))
        val h = if (isFlat) atan2(camRot[1].toDouble(), -camRot[7].toDouble()) else atan2(fx, -fz)
        heading = (Math.toDegrees(h) + offsetDeg + 720.0) % 360.0
        invalidate()
    }

    private fun effFx(): Float = if (camFx > 0f) camFx else (width / 2f) / tan(Math.toRadians(fov / 2)).toFloat()
    private fun effFy(): Float = if (camFy > 0f) camFy else effFx()

    /** Weltpunkt (Ost, Oben, Süd in Metern, relativ zur Kamera) → Bildschirm. false = hinter der Kamera. */
    private fun project(x: Double, y: Double, z: Double, out: PointF): Boolean {
        val r = camRot
        val cx = r[0] * x + r[3] * y + r[6] * z
        val cy = r[1] * x + r[4] * y + r[7] * z
        val cz = r[2] * x + r[5] * y + r[8] * z
        if (-cz < 0.15) return false
        out.x = (width / 2.0 + effFx() * cx / -cz).toFloat()
        out.y = (height / 2.0 - effFy() * cy / -cz).toFloat()
        return true
    }

    /** Richtung zum Ziel im Bezugssystem der Kamera-Daten (Ost, Süd) als Einheitsvektor. */
    private fun groundDir(t: Target): DoubleArray {
        val b = Math.toRadians(t.bearing - bearingOffset)
        return doubleArrayOf(sin(b), -cos(b))
    }

    /** Zielpunkt etwas über Augenhöhe – bei Läden etwa an der Fassade über den Köpfen. */
    private fun targetPoint(t: Target): DoubleArray {
        val d = groundDir(t)
        val dist = t.distance.toDouble()
        val up = if (dist < 15) 0.3 else 1.6
        return doubleArrayOf(d[0] * dist, up, d[1] * dist)
    }

    private fun focusTarget(): Target? = if (noFocus) null else
        selected?.let { s -> targets.firstOrNull { it.place === s } } ?: targets.firstOrNull()

    private fun recompute() {
        if (myLat.isNaN()) return
        val res = FloatArray(2)
        for (t in targets) {
            Location.distanceBetween(myLat, myLon, t.place.lat, t.place.lon, res)
            t.distance = res[0]
            val b = (res[1] + 360f) % 360f
            if (!t.hasBearing) {
                t.bearing = b
                t.hasBearing = true
            } else {
                // nah am Ziel springt die Richtung bei kleinen GPS-Fehlern stark → dort kräftig glätten
                val k = when {
                    t.distance < 30f -> 0.12
                    t.distance < 80f -> 0.3
                    else -> 0.6
                }
                val o = Math.toRadians(t.bearing.toDouble())
                val n = Math.toRadians(b.toDouble())
                val x = (1 - k) * sin(o) + k * sin(n)
                val y = (1 - k) * cos(o) + k * cos(n)
                t.bearing = ((Math.toDegrees(atan2(x, y)) + 360.0) % 360.0).toFloat()
            }
        }
        targets = targets.sortedBy { it.distance }
    }

    /** Winkel zum Ziel relativ zur Blickrichtung: 0 = geradeaus, + = rechts, − = links */
    private fun relAngle(bearing: Float): Double = ((bearing - heading + 540.0) % 360.0) - 180.0

    // ---------- Zeichnen ----------

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        hitAreas.clear()
        guideRect.setEmpty()
        placedBoxes.clear()
        if (targets.isEmpty() || myLat.isNaN() || !hasHeading) return

        val focus = focusTarget()
        if (focus == null) {
            // kein Ziel gewählt: alle Treffer gleichwertig, kein Pfeil, kein Zielpanel
            if (isFlat) drawRadar(canvas, targets, null) else {
                val layouts = targets.mapNotNull { layoutMarker(it, false) }
                layouts.sortedByDescending { it.t.distance }.forEach { drawMarker(canvas, it) }
                for (t in targets) if (layouts.none { it.t === t }) drawEdgeDot(canvas, t, false)
                if (compassUnreliable && !arLocked) drawHint(canvas, "Kompass ungenau – Handy einmal in einer Acht schwenken")
            }
            return
        }
        val visible = if (onlyFocus) listOf(focus) else targets
        if (arrivedPlace !== focus.place) {
            arrivedPlace = focus.place
            arrived = false
        }
        arrived = if (arrived) focus.distance < 22f else focus.distance < 15f

        if (isFlat) {
            drawRadar(canvas, visible, focus)
        } else {
            // Erst das Ziel platzieren (bekommt den besten Platz), dann die anderen.
            val layouts = mutableListOf<MarkerLayout>()
            if (!arrived) layoutMarker(focus, true)?.let { layouts.add(it) }
            for (t in visible) if (t !== focus) layoutMarker(t, false)?.let { layouts.add(it) }
            // Zeichnen: andere zuerst (fern → nah), das Ziel ganz vorne
            layouts.filter { !it.isFocus }.sortedByDescending { it.t.distance }.forEach { drawMarker(canvas, it) }
            layouts.firstOrNull { it.isFocus }?.let { drawMarker(canvas, it) }
            for (t in visible) if (layouts.none { it.t === t } && !(arrived && t === focus)) drawEdgeDot(canvas, t, t === focus)

            if (compassUnreliable && !arLocked) drawHint(canvas, "Kompass ungenau – Handy einmal in einer Acht schwenken")
        }
        drawGuide(canvas, focus)
        // Der 3D-Pfeil kommt zuletzt, damit nichts ihn verdeckt
        if (!isFlat && !arrived) draw3dArrow(canvas, focus)
    }

    private class MarkerLayout(val t: Target, val isFocus: Boolean, val px: Float, val py: Float, val box: RectF)

    /** Rechnet aus, wo Punkt und Schild hinkommen. null = nicht im Bild. */
    private fun layoutMarker(t: Target, isFocus: Boolean): MarkerLayout? {
        val p = targetPoint(t)
        if (!project(p[0], p[1], p[2], tmp)) return null
        val margin = dp(24f)
        if (tmp.x < -margin || tmp.x > width + margin || tmp.y < -margin || tmp.y > height + margin) return null
        val px = tmp.x
        val py = tmp.y

        val name = ellipsize(t.place.name, 24)
        val tp = if (isFocus) bigTitlePaint else titlePaint
        val pad = dp(10f)
        val boxW = max(tp.measureText(name), textPaint.measureText(formatDistance(t.distance))) + pad * 2
        val boxH = tp.textSize + textPaint.textSize + pad * 2 + dp(4f)
        val left = (px - boxW / 2).coerceIn(dp(4f), max(dp(4f), width - boxW - dp(4f)))
        var bottom = py - dp(if (isFocus) 34f else 22f)
        val box = RectF(left, bottom - boxH, left + boxW, bottom)
        // Überlappung mit schon platzierten Schildern vermeiden: nach oben ausweichen
        var tries = 0
        while (tries < 6 && placedBoxes.any { RectF.intersects(it, box) }) {
            bottom -= boxH + dp(6f)
            box.offsetTo(left, bottom - boxH)
            tries++
        }
        placedBoxes.add(RectF(box))
        return MarkerLayout(t, isFocus, px, py, box)
    }

    private fun drawMarker(canvas: Canvas, m: MarkerLayout) {
        val t = m.t
        val isFocus = m.isFocus
        val box = m.box
        val name = ellipsize(t.place.name, 24)
        val sub = formatDistance(t.distance)
        val tp = if (isFocus) bigTitlePaint else titlePaint
        val pad = dp(10f)

        // Stiel vom Schild zum Punkt
        stemPaint.color = if (isFocus) Color.WHITE else Color.argb(220, 255, 255, 255)
        val stemX = m.px.coerceIn(box.left + dp(12f), box.right - dp(12f))
        canvas.drawLine(stemX, box.bottom, m.px, m.py, stemPaint)

        if (isFocus) drawTargetDisc(canvas, m.px, m.py) else {
            canvas.drawCircle(m.px, m.py, dp(6f), whitePaint)
            dotRing.strokeWidth = dp(2.5f)
            canvas.drawCircle(m.px, m.py, dp(4.5f), dotRing)
        }

        val radius = dp(13f)
        canvas.drawRoundRect(box, radius, radius, cardPaint)
        canvas.drawRoundRect(box, radius, radius, if (isFocus) borderPaint else cardBorder)
        canvas.drawText(name, box.left + pad, box.top + pad + tp.textSize * 0.85f, tp)
        canvas.drawText(sub, box.left + pad, box.bottom - pad, if (isFocus) accentTextPaint else textPaint)

        hitAreas.add(RectF(box.left, box.top, box.right, box.bottom + dp(10f)) to t.place)
        hitAreas.add(RectF(m.px - dp(24f), m.py - dp(24f), m.px + dp(24f), m.py + dp(24f)) to t.place)
    }

    /** Pulsierende Zielscheibe: dunkler Hof, weißer Ring, farbiger Kern – auf jeder Fassade gut sichtbar. */
    private fun drawTargetDisc(canvas: Canvas, x: Float, y: Float) {
        val phase = (SystemClock.uptimeMillis() % 1600L) / 1600f
        pulsePaint.alpha = ((1f - phase) * 170).toInt()
        canvas.drawCircle(x, y, dp(16f) + phase * dp(22f), pulsePaint)
        canvas.drawCircle(x, y, dp(19f), haloPaint)
        canvas.drawCircle(x, y, dp(16f), whitePaint)
        canvas.drawCircle(x, y, dp(12.5f), accentPaint)
        canvas.drawCircle(x, y, dp(7.5f), Paint(whitePaint).apply { clearShadowLayer() })
        canvas.drawCircle(x, y, dp(4f), accentPaint)
        postInvalidateOnAnimation()
    }

    private fun drawEdgeDot(canvas: Canvas, t: Target, isFocus: Boolean) {
        val rel = relAngle(t.bearing)
        val x = if (rel > 0) width - dp(12f) else dp(12f)
        val y = height * 0.45f
        canvas.drawCircle(x, y, dp(if (isFocus) 8f else 5f), whitePaint)
        canvas.drawCircle(x, y, dp(if (isFocus) 6f else 3.5f), if (isFocus) accentPaint else inkPaint)
    }

    /**
     * 3D-Pfeil im Kamerabild: schwebt vor dir knapp unter der Bildmitte, liegt flach im Raum
     * und dreht/neigt sich mit dem Handy zum Ziel – wie ein Kompass im Raum.
     */
    private fun draw3dArrow(canvas: Canvas, focus: Target) {
        val r = camRot
        // Mittelpunkt in Kamerakoordinaten → Welt
        // Platz: frei über dem Zielpanel (etwa 85 dp darüber)
        val panelTop = height - bottomInset - dp(8f) - dp(124f)
        val wantY = (panelTop - dp(85f)).coerceAtLeast(height * 0.5f)
        val bob = sin(SystemClock.uptimeMillis() / 450.0) * 0.008
        val ccz = -1.25
        val ccx = 0.0
        val ccy = (height / 2.0 - wantY) * -ccz / effFy() + bob
        val wx = r[0] * ccx + r[1] * ccy + r[2] * ccz
        val wy = r[3] * ccx + r[4] * ccy + r[5] * ccz
        val wz = r[6] * ccx + r[7] * ccy + r[8] * ccz

        val d = groundDir(focus)     // Richtung (Ost, Süd)
        val dx = d[0]
        val dz = d[1]
        val sx = -dz                 // Querachse = Richtung × oben
        val sz = dx
        val len = 0.22
        val back = 0.10
        val notch = 0.03
        val wing = 0.13
        val thick = 0.035

        val top = arrayOf(
            doubleArrayOf(wx + dx * len, wy, wz + dz * len),
            doubleArrayOf(wx - dx * back + sx * wing, wy, wz - dz * back + sz * wing),
            doubleArrayOf(wx - dx * notch, wy, wz - dz * notch),
            doubleArrayOf(wx - dx * back - sx * wing, wy, wz - dz * back - sz * wing)
        )
        val topPts = Array(4) { PointF() }
        val botPts = Array(4) { PointF() }
        for (i in 0 until 4) {
            if (!project(top[i][0], top[i][1], top[i][2], topPts[i])) return
            if (!project(top[i][0], top[i][1] - thick, top[i][2], botPts[i])) return
        }

        // Seitenflächen (dunkel), dann Oberseite (Akzent mit weißem Rand)
        arrowSidePaint.color = darker(accent)
        for (i in 0 until 4) {
            val j = (i + 1) % 4
            arrowPath3d.reset()
            arrowPath3d.moveTo(topPts[i].x, topPts[i].y)
            arrowPath3d.lineTo(topPts[j].x, topPts[j].y)
            arrowPath3d.lineTo(botPts[j].x, botPts[j].y)
            arrowPath3d.lineTo(botPts[i].x, botPts[i].y)
            arrowPath3d.close()
            canvas.drawPath(arrowPath3d, arrowSidePaint)
        }
        // Von oben sieht man die orange Oberseite, von unten den dunklen Boden
        val seenFromBelow = wy - thick / 2 > 0.0
        val face = if (seenFromBelow) botPts else topPts
        arrowPath3d.reset()
        arrowPath3d.moveTo(face[0].x, face[0].y)
        for (i in 1 until 4) arrowPath3d.lineTo(face[i].x, face[i].y)
        arrowPath3d.close()
        if (seenFromBelow) {
            arrowBottomPaint.color = darker(accent)
            canvas.drawPath(arrowPath3d, arrowBottomPaint)
        } else {
            canvas.drawPath(arrowPath3d, arrowTopPaint)
            canvas.drawPath(arrowPath3d, arrowStrokePaint) // weißer Rand nur oben
        }
    }

    private val arrowBottomPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private fun darker(c: Int): Int =
        Color.rgb((Color.red(c) * 0.6f).toInt(), (Color.green(c) * 0.6f).toInt(), (Color.blue(c) * 0.6f).toInt())

    private val pulsePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(3f)
        color = Color.WHITE
    }
    private val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(90, 0, 0, 0) }
    private val arrowPath3d = Path()
    private val arrowSidePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val arrowTopPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Ui.ACCENT
        setShadowLayer(dp(10f), 0f, dp(4f), Color.argb(90, 0, 0, 0))
    }
    private val arrowStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2.5f)
        strokeJoin = Paint.Join.ROUND
        color = Color.WHITE
    }

    /** Kartenansicht von oben: Mitte = du, oben = Blickrichtung. */
    private fun drawRadar(canvas: Canvas, visible: List<Target>, focus: Target?) {
        canvas.drawColor(Color.argb(245, 245, 245, 242))
        val w = width.toFloat()
        val cx = w / 2
        val cy = height * 0.42f
        val radius = min(w * 0.40f, height * 0.26f)
        // Zoom per Pinch: kleinerer Radius = nahe Ziele größer sichtbar
        // Automatisch auf das Ziel zoomen: es liegt bei etwa zwei Dritteln des äußeren Rings.
        // Weiter entfernte Treffer landen als Punkte am Rand.
        val allRange = niceScale(visible.maxOf { it.distance })
        val autoRange = if (focus == null) allRange else niceScale(max(focus.distance * 1.5f, 30f)).coerceAtMost(allRange)
        // Rauszoomen bis alles sichtbar ist, reinzoomen bis etwa 30 m
        radarZoom = radarZoom.coerceIn(min(1f, autoRange / allRange), max(1f, autoRange / 30f))
        val scale = autoRange / radarZoom

        for (i in 1..3) {
            val r = radius * i / 3f
            canvas.drawCircle(cx, cy, r, ringPaint)
            canvas.drawText(formatDistance(scale * i / 3f), cx + dp(4f), cy - r - dp(3f), smallPaint)
        }

        // Norden
        val nAng = Math.toRadians(-heading)
        val nx = (cx + sin(nAng) * (radius + dp(16f))).toFloat()
        val ny = (cy - cos(nAng) * (radius + dp(16f))).toFloat()
        canvas.drawText("N", nx, ny + northPaint.textSize * 0.35f, northPaint)

        // du
        arrowPath.reset()
        arrowPath.moveTo(cx, cy - dp(11f))
        arrowPath.lineTo(cx + dp(8f), cy + dp(8f))
        arrowPath.lineTo(cx, cy + dp(3f))
        arrowPath.lineTo(cx - dp(8f), cy + dp(8f))
        arrowPath.close()
        canvas.drawPath(arrowPath, inkPaint)

        val ordered = visible.filter { it !== focus } + listOfNotNull(focus)
        for (t in ordered) {
            val isFocus = t === focus
            val rel = Math.toRadians(relAngle(t.bearing))
            val r = radius * min(t.distance / scale, 1f)
            val x = (cx + sin(rel) * r).toFloat()
            val y = (cy - cos(rel) * r).toFloat()
            if (isFocus) {
                canvas.drawCircle(x, y, dp(11f), softPaint)
                canvas.drawCircle(x, y, dp(7f), accentPaint)
            } else {
                canvas.drawCircle(x, y, dp(6f), whitePaint)
                dotRing.strokeWidth = dp(2.5f)
                canvas.drawCircle(x, y, dp(4.5f), dotRing)
            }
            if (isFocus) {
                val label = ellipsize(t.place.name, 22)
                val tw = titlePaint.measureText(label)
                val lx = (x - tw / 2).coerceIn(dp(8f), max(dp(8f), w - tw - dp(8f)))
                canvas.drawText(label, lx, y - dp(14f), titlePaint)
            }
            val hit = dp(22f)
            hitAreas.add(RectF(x - hit, y - hit, x + hit, y + hit) to t.place)
        }

        val hintPaint = Paint(smallPaint).apply { textAlign = Paint.Align.CENTER }
        canvas.drawText("KARTENANSICHT · HANDY AUFRICHTEN FÜR KAMERA", cx, cy + radius + dp(44f), hintPaint)
        canvas.drawText("ZOOMEN MIT ZWEI FINGERN", cx, cy + radius + dp(62f), hintPaint)
    }

    private fun drawGuide(canvas: Canvas, t: Target) {
        val w = width.toFloat()
        val margin = dp(16f)
        val panelH = dp(124f)
        val bottom = height - bottomInset - dp(8f)
        val panel = RectF(margin, bottom - panelH, w - margin, bottom)
        guideRect.set(panel)
        canvas.drawRoundRect(panel, dp(21f), dp(21f), cardPaint)
        canvas.drawRoundRect(panel, dp(21f), dp(21f), cardBorder)

        val r = dp(38f)
        val cx = panel.left + dp(18f) + r
        val cy = panel.centerY()
        canvas.drawCircle(cx, cy, r, softPaint)
        val rel = relAngle(t.bearing)
        if (arrived) {
            drawArrivedGuide(canvas, t, panel, cx, cy, r)
            return
        }
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

        val tx = cx + r + dp(16f)
        val maxChars = ((panel.right - tx - dp(12f)) / (bigTitlePaint.textSize * 0.55f)).toInt().coerceAtLeast(8)
        var line = panel.top + dp(30f)
        canvas.drawText(ellipsize(t.place.name, maxChars), tx, line, bigTitlePaint)
        line += dp(24f)
        canvas.drawText(
            "${formatDistance(t.distance)} · ca.\u00A0${walkMinutes(t.distance)}\u00A0Min. zu Fuß",
            tx, line, accentTextPaint
        )
        line += dp(22f)
        canvas.drawText(directionText(rel), tx, line, textPaint)
        line += dp(19f)
        canvas.drawText("TIPPEN FÜR DETAILS ›", tx, line, smallPaint)

        val hdg = "${compassWord(heading)} ${heading.roundToInt()}°"
        val small = Paint(smallPaint).apply { textAlign = Paint.Align.RIGHT }
        canvas.drawText(hdg, panel.right - dp(14f), panel.top + dp(18f), small)
    }

    /** Am Ziel: ruhiger, sanft pulsierender Ring statt zappelndem Pfeil. */
    private fun drawArrivedGuide(canvas: Canvas, t: Target, panel: RectF, cx: Float, cy: Float, r: Float) {
        val phase = (SystemClock.uptimeMillis() % 2000L) / 2000f
        val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = dp(3f)
            color = accent
            alpha = ((1f - phase) * 200).toInt()
        }
        canvas.drawCircle(cx, cy, r * (0.45f + 0.5f * phase), ring)
        canvas.drawCircle(cx, cy, r * 0.32f, accentPaint)
        canvas.drawCircle(cx, cy, r * 0.14f, Paint(whitePaint).apply { clearShadowLayer() })
        postInvalidateOnAnimation()

        val tx = cx + r + dp(16f)
        val maxChars = ((panel.right - tx - dp(12f)) / (bigTitlePaint.textSize * 0.55f)).toInt().coerceAtLeast(8)
        var line = panel.top + dp(30f)
        canvas.drawText(ellipsize(t.place.name, maxChars), tx, line, bigTitlePaint)
        line += dp(24f)
        canvas.drawText("Du bist da", tx, line, accentTextPaint)
        line += dp(22f)
        canvas.drawText("Ziel in unmittelbarer Nähe", tx, line, textPaint)
        line += dp(19f)
        canvas.drawText("TIPPEN FÜR DETAILS ›", tx, line, smallPaint)
    }

    private fun drawHint(canvas: Canvas, msg: String) {
        val w = width.toFloat()
        val tw = centerPaint.measureText(msg) + dp(28f)
        val y = height * 0.62f
        val box = RectF(w / 2 - tw / 2, y - dp(18f), w / 2 + tw / 2, y + dp(18f))
        canvas.drawRoundRect(box, dp(15f), dp(15f), softPaint)
        canvas.drawText(msg, w / 2, y + centerPaint.textSize * 0.35f, centerPaint)
    }

    // ---------- Antippen ----------

    /** Zoomfaktor der Draufsicht (1 = alle Ziele passen hinein). */
    private var radarZoom = 1f
    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            radarZoom *= detector.scaleFactor
            invalidate()
            return true
        }
    })

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (isFlat) {
            scaleDetector.onTouchEvent(event)
            // Bei zwei Fingern nur zoomen, nichts antippen
            if (scaleDetector.isInProgress || event.pointerCount > 1) return true
            if (event.actionMasked == MotionEvent.ACTION_DOWN) return true
        }
        val onGuide = guideRect.contains(event.x, event.y)
        val hit = hitAreas.lastOrNull { it.first.contains(event.x, event.y) }
        return when (event.action) {
            MotionEvent.ACTION_DOWN -> onGuide || hit != null
            MotionEvent.ACTION_UP -> {
                if (onGuide) {
                    focusPlace()?.let { onGuideTap?.invoke(it) }
                } else if (hit != null) {
                    onMarkerTap?.invoke(hit.second)
                }
                performClick()
                true
            }
            else -> onGuide || hit != null
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
        rel > 0 -> "Nach rechts drehen · ${rel.roundToInt()}°"
        else -> "Nach links drehen · ${(-rel).roundToInt()}°"
    }

    private fun compassWord(deg: Double): String {
        val words = listOf("N", "NO", "O", "SO", "S", "SW", "W", "NW")
        return words[(((deg + 22.5) % 360) / 45).toInt() % 8]
    }

    private fun niceScale(d: Float): Float {
        val steps = listOf(30f, 45f, 60f, 75f, 100f, 150f, 200f, 300f, 400f, 500f, 750f, 1000f, 1500f, 2000f, 3000f, 5000f, 7500f, 10000f, 15000f)
        return steps.firstOrNull { it >= d } ?: d
    }

    private fun ellipsize(s: String, maxChars: Int): String =
        if (s.length <= maxChars) s else s.take(max(1, maxChars - 1)).trimEnd() + "…"

    companion object {
        fun formatDistance(m: Float): String = when {
            m < 100 -> "${max(5, (m / 5).roundToInt() * 5)}\u202Fm"
            m < 1000 -> "${(m / 10).roundToInt() * 10}\u202Fm"
            else -> String.format(Locale.GERMANY, "%.1f\u202Fkm", m / 1000f)
        }

        /** ca. 4,8 km/h Gehtempo */
        fun walkMinutes(m: Float): Int = max(1, min(999, ceil(m / 80.0).toInt()))
    }
}
