package com.john.peil

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.speech.RecognizerIntent
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import java.util.concurrent.Executors

class MainActivity : ComponentActivity(), SensorEventListener, LocationListener {

    private lateinit var previewView: PreviewView
    private lateinit var overlay: ArOverlayView
    private lateinit var searchField: EditText
    private lateinit var statusText: TextView
    private lateinit var bottomBar: LinearLayout
    private lateinit var listScroll: HorizontalScrollView
    private lateinit var listContainer: LinearLayout
    private lateinit var chipOpen: TextView
    private lateinit var chipOnly: TextView
    private lateinit var resultCount: TextView
    private lateinit var detailSheet: ScrollView
    private lateinit var detailContent: LinearLayout

    private lateinit var sensorManager: SensorManager
    private var rotationSensor: Sensor? = null
    private var magnetSensor: Sensor? = null
    private var locationManager: LocationManager? = null

    private lateinit var updater: Updater
    private lateinit var favorites: Favorites

    private var currentLocation: Location? = null
    private var declination = 0f
    private var pendingQuery: String? = null
    private var searching = false
    private var cameraStarted = false

    // Ergebnisse & Auswahl
    private var allPlaces: List<Place> = emptyList()
    private var onlyOpen = false
    private var detailPlace: Place? = null
    private var transitBox: LinearLayout? = null
    private var arrivedKey: String? = null
    private var lastListRefresh = 0L
    private var lastCalibrationBuzz = 0L
    private var navInset = 0

    private val rotation = FloatArray(9)
    private val remapped = FloatArray(9)
    private val orientation = FloatArray(3)

    private val executor = Executors.newFixedThreadPool(2)


    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        if (granted[Manifest.permission.CAMERA] == true) startCamera()
        if (hasLocationPermission()) {
            startLocation()
        } else {
            status("Ich brauche deinen Standort, sonst weiß ich nicht, wo du stehst.")
        }
    }

    private val speechLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val text = result.data
            ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            ?.firstOrNull()
        if (!text.isNullOrBlank()) {
            searchField.setText(text)
            search(text)
        }
    }

    private val backCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = closeDetails()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        previewView = findViewById(R.id.preview)
        overlay = findViewById(R.id.overlay)
        searchField = findViewById(R.id.search_field)
        statusText = findViewById(R.id.status)
        bottomBar = findViewById(R.id.bottom_bar)
        listScroll = findViewById(R.id.list_scroll)
        listContainer = findViewById(R.id.list_container)
        chipOpen = findViewById(R.id.chip_open)
        chipOnly = findViewById(R.id.chip_only)
        resultCount = findViewById(R.id.result_count)
        detailSheet = findViewById(R.id.detail_sheet)
        detailContent = findViewById(R.id.detail_content)
        val topBar = findViewById<View>(R.id.top_bar)

        onBackPressedDispatcher.addCallback(this, backCallback)
        favorites = Favorites(this)
        updater = Updater(this) { status(it) }

        // Platz für Statusleiste oben und Navigationsleiste unten lassen
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.root)) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            topBar.updatePadding(top = bars.top + dp(12))
            navInset = bars.bottom
            bottomBar.updatePadding(bottom = bars.bottom + dp(10))
            detailSheet.updateLayoutParams<ViewGroup.MarginLayoutParams> { bottomMargin = bars.bottom + dp(8) }
            updateGuideInset()
            insets
        }
        bottomBar.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updateGuideInset() }

        searchField.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                search(searchField.text.toString())
                true
            } else {
                false
            }
        }
        findViewById<ImageButton>(R.id.mic_button).setOnClickListener { startSpeech() }
        findViewById<ImageButton>(R.id.settings_button).setOnClickListener { showSettings() }

        chipOpen.setOnClickListener {
            onlyOpen = !onlyOpen
            applyResults()
        }
        chipOnly.setOnClickListener {
            overlay.onlyFocus = !overlay.onlyFocus
            styleChips()
        }
        styleChips()

        overlay.onMarkerTap = { selectPlace(it) }
        overlay.onGuideTap = { showDetails(it) }

        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        magnetSensor = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
        if (rotationSensor == null) {
            status("Dein Handy hat keinen Lagesensor – die Richtung kann ich leider nicht anzeigen.")
        }

        if (savedInstanceState == null) updater.check()

        permissionLauncher.launch(
            arrayOf(
                Manifest.permission.CAMERA,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
        )
    }

    override fun onResume() {
        super.onResume()
        rotationSensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        // Magnetsensor nur, um zu erfahren, wie genau der Kompass gerade ist
        magnetSensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
        if (hasLocationPermission()) startLocation()
        if (!cameraStarted && hasPermission(Manifest.permission.CAMERA)) startCamera()
        updater.resumePendingInstall()
    }

    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(this)
        locationManager?.removeUpdates(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        executor.shutdown()
    }

    // ---------- Kamera ----------

    private fun startCamera() {
        cameraStarted = true
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val provider = future.get()
                val preview = Preview.Builder().build()
                preview.setSurfaceProvider(previewView.surfaceProvider)
                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview)
            } catch (e: Exception) {
                cameraStarted = false
                status("Kamera ließ sich nicht starten: ${e.message}")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    // ---------- Standort ----------

    @SuppressLint("MissingPermission")
    private fun startLocation() {
        val lm = locationManager
            ?: (getSystemService(LOCATION_SERVICE) as LocationManager).also { locationManager = it }
        lm.removeUpdates(this)
        var any = false
        for (provider in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
            if (!lm.allProviders.contains(provider) || !lm.isProviderEnabled(provider)) continue
            lm.getLastKnownLocation(provider)?.let { onLocationChanged(it) }
            lm.requestLocationUpdates(provider, 1000L, 1f, this, Looper.getMainLooper())
            any = true
        }
        if (!any) status("Standort ist aus – bitte in den Schnelleinstellungen einschalten.")
    }

    override fun onLocationChanged(location: Location) {
        val prev = currentLocation
        // eine deutlich ungenauere Position kurz nach einer guten ignorieren
        if (prev != null && prev.hasAccuracy() && location.hasAccuracy() &&
            location.accuracy > prev.accuracy * 2 && location.time - prev.time < 10_000
        ) return

        currentLocation = location
        declination = GeomagneticField(
            location.latitude.toFloat(),
            location.longitude.toFloat(),
            location.altitude.toFloat(),
            System.currentTimeMillis()
        ).declination
        overlay.setLocation(location.latitude, location.longitude)

        checkArrival()

        val now = SystemClock.elapsedRealtime()
        if (allPlaces.isNotEmpty() && now - lastListRefresh > 5_000) rebuildList()

        pendingQuery?.let {
            pendingQuery = null
            search(it)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {}

    /** „Du bist da“: kurz vibrieren, wenn man am Ziel ist. */
    private fun checkArrival() {
        val focus = overlay.focusPlace() ?: return
        val d = overlay.distanceTo(focus) ?: return
        if (d < 25f && arrivedKey != focus.key) {
            arrivedKey = focus.key
            vibrate(longArrayOf(0, 120, 90, 120, 90, 250))
            status("Du bist da: ${focus.name} 🎉")
        }
    }

    // ---------- Lagesensor / Kompass ----------

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_ROTATION_VECTOR) return
        SensorManager.getRotationMatrixFromVector(rotation, event.values)
        // Handy aufrecht wie eine Kamera: Blickrichtung = Rückseite des Handys
        SensorManager.remapCoordinateSystem(rotation, SensorManager.AXIS_X, SensorManager.AXIS_Z, remapped)
        SensorManager.getOrientation(remapped, orientation)
        val azimuth = Math.toDegrees(orientation[0].toDouble()) + declination
        val pitch = Math.toDegrees(orientation[1].toDouble())
        overlay.setOrientation(azimuth, pitch)
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {
        if (sensor.type != Sensor.TYPE_MAGNETIC_FIELD && sensor.type != Sensor.TYPE_ROTATION_VECTOR) return
        val bad = accuracy <= SensorManager.SENSOR_STATUS_ACCURACY_LOW
        val now = SystemClock.elapsedRealtime()
        if (bad && !overlay.compassUnreliable && now - lastCalibrationBuzz > 30_000) {
            lastCalibrationBuzz = now
            vibrate(longArrayOf(0, 60, 70, 60, 70, 60))
            status("Kompass ungenau – schwenk das Handy einmal in Form einer Acht.")
        }
        overlay.compassUnreliable = bad
    }

    // ---------- Suche ----------

    private fun startSpeech() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "de-DE")
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Was suchst du?")
        }
        try {
            speechLauncher.launch(intent)
        } catch (e: ActivityNotFoundException) {
            status("Spracheingabe ist auf diesem Handy nicht verfügbar.")
        }
    }

    private fun search(query: String) {
        val q = query.trim()
        if (q.isEmpty()) return
        hideKeyboard()
        closeDetails()

        val loc = currentLocation
        if (loc == null) {
            pendingQuery = q
            status("Warte auf deinen Standort … dann suche ich „$q“.")
            return
        }
        if (searching) return
        searching = true
        status("Suche „$q“ in der Nähe …")

        executor.execute {
            val result = runCatching { PlaceSearch.search(q, loc.latitude, loc.longitude) }
            runOnUiThread {
                searching = false
                result.onSuccess { places ->
                    showPlaces(places)
                    if (places.isEmpty()) {
                        status("Nichts gefunden für „$q“ im Umkreis von 15 km.")
                    } else {
                        status("${places.size}× „$q“ gefunden – dreh dich mal um.")
                    }
                }.onFailure {
                    status("Suche hat nicht geklappt – bitte gleich nochmal versuchen. (${it.message})")
                }
            }
        }
    }

    /** Neue Ergebnisliste anzeigen und Öffnungszeiten im Hintergrund nachladen. */
    private fun showPlaces(places: List<Place>) {
        allPlaces = places
        arrivedKey = null
        overlay.select(null)
        applyResults()
        listScroll.scrollTo(0, 0)
        if (places.any { !it.detailsLoaded }) {
            executor.execute {
                PlaceDetails.load(places)
                runOnUiThread {
                    if (allPlaces === places) applyResults()
                    detailPlace?.let { if (it in places) renderDetails() }
                }
            }
        }
    }

    private fun shownPlaces(): List<Place> =
        if (onlyOpen) allPlaces.filter { OpeningHours.status(it.openingHours)?.open == true } else allPlaces

    private fun applyResults() {
        val shown = shownPlaces()
        overlay.setTargets(shown)
        bottomBar.visibility = if (allPlaces.isEmpty()) View.GONE else View.VISIBLE
        styleChips()
        val hidden = allPlaces.size - shown.size
        resultCount.text = if (hidden > 0) "${shown.size} von ${allPlaces.size}" else "${shown.size} Treffer"
        rebuildList()
        updateGuideInset()
    }

    private fun selectPlace(p: Place) {
        overlay.select(p)
        arrivedKey = null
        rebuildList()
        // gewählte Karte in den sichtbaren Bereich scrollen
        val index = sortedShown().indexOf(p)
        if (index >= 0) {
            listContainer.post {
                listContainer.getChildAt(index)?.let { listScroll.smoothScrollTo(maxOf(0, it.left - dp(16)), 0) }
            }
        }
    }

    private fun sortedShown(): List<Place> {
        val loc = currentLocation ?: return shownPlaces()
        return shownPlaces().sortedBy { distanceTo(it, loc) }
    }

    // ---------- Trefferliste unten ----------

    private fun rebuildList() {
        lastListRefresh = SystemClock.elapsedRealtime()
        listContainer.removeAllViews()
        val focus = overlay.focusPlace()
        val loc = currentLocation
        for (p in sortedShown()) {
            val isSel = p === focus
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), dp(12), dp(14), dp(12))
                background = rounded(Ui.CARD, 18, if (isSel) Ui.ACCENT else Ui.BORDER, if (isSel) 2 else 1)
                elevation = dp(2).toFloat()
                layoutParams = LinearLayout.LayoutParams(dp(176), ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { marginEnd = dp(8); bottomMargin = dp(2) }
            }
            card.setOnClickListener { if (overlay.focusPlace() === p) showDetails(p) else selectPlace(p) }
            card.addView(text(p.name, 15f, Ui.INK, Ui.MEDIUM, maxLines = 1))
            if (loc != null) {
                val d = distanceTo(p, loc)
                card.addView(
                    text("${ArOverlayView.formatDistance(d)} · ${ArOverlayView.walkMinutes(d)} Min.", 13f, Ui.ACCENT, Ui.BOLD)
                        .apply { setPadding(0, dp(3), 0, dp(6)) }
                )
            }
            card.addView(openPill(p, small = true))
            listContainer.addView(card)
        }
    }

    /** Kleines Etikett „Geöffnet · bis 20:00 Uhr“ im Spur-Stil. */
    private fun openPill(p: Place, small: Boolean): TextView {
        val st = OpeningHours.status(p.openingHours)
        val (label, fg, bg) = when {
            st != null && st.open -> Triple(st.text, Ui.OK, Ui.OK_SOFT)
            st != null -> Triple(st.text, Ui.SOFT_TEXT, Ui.SOFT)
            !p.detailsLoaded -> Triple("Lade Öffnungszeiten …", Ui.CHIP_TEXT, Ui.CHIP)
            p.openingHours != null -> Triple("Öffnungszeiten siehe Details", Ui.CHIP_TEXT, Ui.CHIP)
            else -> Triple("Öffnungszeiten unbekannt", Ui.CHIP_TEXT, Ui.CHIP)
        }
        return text(label, if (small) 11f else 13f, fg, Ui.BOLD, maxLines = 2).apply {
            setPadding(dp(8), dp(3), dp(8), dp(4))
            background = rounded(bg, 20, Color.TRANSPARENT, 0)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
    }

    private fun styleChips() {
        styleChip(chipOpen, onlyOpen, getString(R.string.only_open))
        styleChip(chipOnly, overlay.onlyFocus, getString(R.string.only_target))
        resultCount.setTextColor(Ui.CHIP_TEXT)
        resultCount.typeface = Ui.BOLD
        resultCount.setPadding(dp(9), dp(4), dp(9), dp(4))
        resultCount.background = rounded(Ui.CHIP, 20, Color.TRANSPARENT, 0)
    }

    private fun styleChip(v: TextView, on: Boolean, label: String) {
        v.setPadding(dp(14), dp(8), dp(14), dp(8))
        v.setTextColor(if (on) Color.WHITE else Ui.CHIP_TEXT)
        v.typeface = Ui.REGULAR
        v.background = rounded(if (on) Ui.INK else Ui.CHIP, 30, Color.TRANSPARENT, 0)
        v.elevation = dp(1).toFloat()
        v.text = label
    }

    private fun updateGuideInset() {
        overlay.bottomInset = if (bottomBar.visibility == View.VISIBLE) bottomBar.height else navInset
    }

    // ---------- Detailansicht ----------

    private fun showDetails(p: Place) {
        if (overlay.focusPlace() !== p) selectPlace(p)
        detailPlace = p
        transitBox = null
        renderDetails()
        detailSheet.visibility = View.VISIBLE
        detailSheet.scrollTo(0, 0)
        backCallback.isEnabled = true
        if (!p.detailsLoaded) {
            executor.execute {
                PlaceDetails.load(listOf(p))
                runOnUiThread {
                    if (detailPlace === p) renderDetails()
                    rebuildList()
                }
            }
        }
    }

    private fun closeDetails() {
        detailPlace = null
        transitBox = null
        detailSheet.visibility = View.GONE
        backCallback.isEnabled = false
    }

    private fun renderDetails() {
        val p = detailPlace ?: return
        // maximal ~72 % der Höhe
        detailSheet.layoutParams.height = ViewGroup.LayoutParams.WRAP_CONTENT
        detailSheet.post {
            val max = (resources.displayMetrics.heightPixels * 0.72f).toInt()
            if (detailSheet.height > max) {
                detailSheet.layoutParams.height = max
                detailSheet.requestLayout()
            }
        }

        val keepTransit = transitBox
        detailContent.removeAllViews()

        // Kopf: Kategorie (klein, versal) · Name · Schließen
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
        }
        val titles = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        titles.addView(eyebrow(p.category ?: "Ziel"))
        titles.addView(text(p.name, 26f, Ui.INK, Ui.MEDIUM).apply {
            letterSpacing = -0.03f
            setPadding(0, dp(6), 0, 0)
        })
        p.address?.let { titles.addView(text(it, 14f, Ui.MUTED).apply { setPadding(0, dp(4), 0, 0) }) }
        head.addView(titles)
        head.addView(iconButton(R.drawable.ic_close) { closeDetails() })
        detailContent.addView(head)

        // Entfernung + Öffnungsstatus
        val meta = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(12), 0, 0)
        }
        currentLocation?.let { loc ->
            val d = distanceTo(p, loc)
            meta.addView(text("${ArOverlayView.formatDistance(d)} · ${ArOverlayView.walkMinutes(d)} Min. zu Fuß", 15f, Ui.ACCENT, Ui.BOLD)
                .apply { setPadding(0, 0, dp(10), 0) })
        }
        detailContent.addView(meta)
        detailContent.addView(openPill(p, small = false).apply {
            (layoutParams as LinearLayout.LayoutParams).topMargin = dp(8)
        })

        // Hauptknopf + Werkzeugleiste
        detailContent.addView(primaryButton("Route zu Fuß", R.drawable.ic_navigation) { openMaps(p, "walking") })

        val tools = mutableListOf<Triple<String, Int, () -> Unit>>()
        tools += Triple("Bus & Bahn", R.drawable.ic_train) { loadTransit(p) }
        p.phone?.let { phone -> tools += Triple("Anrufen", R.drawable.ic_phone) { dial(phone) } }
        p.website?.let { site -> tools += Triple("Webseite", R.drawable.ic_globe) { openWeb(site) } }
        tools += Triple("Teilen", R.drawable.ic_share) { share(p) }
        val saved = favorites.contains(p)
        tools += Triple(if (saved) "Gemerkt" else "Merken", if (saved) R.drawable.ic_star_filled else R.drawable.ic_star) {
            val nowSaved = favorites.toggle(p)
            status(if (nowSaved) "„${p.name}“ gemerkt." else "„${p.name}“ nicht mehr gemerkt.")
            renderDetails()
        }
        tools += Triple(if (overlay.onlyFocus) "Alle zeigen" else "Nur Ziel", if (overlay.onlyFocus) R.drawable.ic_eye else R.drawable.ic_target) {
            overlay.onlyFocus = !overlay.onlyFocus
            styleChips()
            renderDetails()
        }
        toolBar(tools)

        // Öffnungszeiten
        section("Öffnungszeiten")
        val hours = p.openingHours
        when {
            !p.detailsLoaded -> detailContent.addView(text("Lade …", 14f, Ui.MUTED))
            hours == null -> detailContent.addView(text("Keine Öffnungszeiten hinterlegt", 14f, Ui.MUTED))
            else -> detailContent.addView(
                text(OpeningHours.pretty(hours), 15f, Ui.INK_SOFT).apply { setLineSpacing(dp(4).toFloat(), 1f) }
            )
        }

        // Bus & Bahn (bleibt beim Neuzeichnen erhalten)
        val box = keepTransit ?: LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        (box.parent as? ViewGroup)?.removeView(box)
        transitBox = box
        detailContent.addView(box)
    }

    private fun loadTransit(p: Place) {
        val box = transitBox ?: return
        val loc = currentLocation ?: return
        box.removeAllViews()
        box.addView(sectionView("Mit Bus & Bahn"))
        box.addView(text("Suche Verbindungen …", 14f, Ui.MUTED))
        detailSheet.post { detailSheet.smoothScrollTo(0, box.top) }

        executor.execute {
            val result = runCatching { Transit.journeys(loc.latitude, loc.longitude, p) }
            runOnUiThread {
                if (detailPlace !== p || transitBox !== box) return@runOnUiThread
                box.removeAllViews()
                box.addView(sectionView("Mit Bus & Bahn"))
                val options = result.getOrNull().orEmpty()
                if (options.isEmpty()) {
                    box.addView(text("Gerade keine Verbindungen abrufbar.", 14f, Ui.MUTED))
                } else {
                    for (o in options) box.addView(transitRow(o))
                }
                box.addView(secondaryButton("In Google Maps öffnen", R.drawable.ic_map) { openMaps(p, "transit") })
                box.addView(text("Verbindungsdaten: DB über transport.rest", 11f, Ui.LABEL).apply { setPadding(0, dp(8), 0, 0) })
            }
        }
    }

    private fun transitRow(o: Transit.Option): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = rounded(Ui.CARD, 15, Ui.BORDER, 1)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(8) }
        }
        val changes = when (o.changes) {
            0 -> "direkt"
            1 -> "1× umsteigen"
            else -> "${o.changes}× umsteigen"
        }
        row.addView(text("${o.departure} → ${o.arrival}", 17f, Ui.INK, Ui.MEDIUM).apply { fontFeatureSettings = "tnum" })
        row.addView(text("${o.minutes} Min. · $changes", 13f, Ui.MUTED).apply { setPadding(0, dp(2), 0, dp(8)) })
        val badges = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        if (o.lineList.isEmpty()) {
            badges.addView(text("Nur zu Fuß", 13f, Ui.CHIP_TEXT))
        } else {
            o.lineList.forEachIndexed { i, name ->
                if (i > 0) badges.addView(text("›", 15f, Ui.LABEL).apply { setPadding(dp(6), 0, dp(6), 0) })
                badges.addView(text(name, 13f, Color.WHITE, Ui.BOLD, maxLines = 1).apply {
                    gravity = Gravity.CENTER
                    minWidth = dp(34)
                    setPadding(dp(7), dp(2), dp(7), dp(2))
                    background = rounded(Ui.lineColor(name), 7, Color.TRANSPARENT, 0)
                })
            }
        }
        row.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(badges)
        })
        o.firstStop?.let { row.addView(text("ab $it", 13f, Ui.MUTED).apply { setPadding(0, dp(8), 0, 0) }) }
        return row
    }

    // ---------- Aktionen ----------

    private fun openMaps(p: Place, mode: String) {
        val uri = Uri.parse(
            "https://www.google.com/maps/dir/?api=1&destination=${p.lat},${p.lon}&travelmode=$mode"
        )
        startSafely(Intent(Intent.ACTION_VIEW, uri))
    }

    private fun dial(phone: String) {
        startSafely(Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + phone.replace(" ", ""))))
    }

    private fun openWeb(site: String) {
        val url = if (site.startsWith("http")) site else "https://$site"
        startSafely(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }

    private fun share(p: Place) {
        val text = buildString {
            append(p.name)
            p.address?.let { append(", ").append(it) }
            append("\nhttps://www.google.com/maps/search/?api=1&query=${p.lat},${p.lon}")
        }
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        startSafely(Intent.createChooser(intent, "Ort teilen"))
    }

    private fun startSafely(intent: Intent) {
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            status("Dafür ist keine passende App installiert.")
        }
    }

    // ---------- Einstellungen ----------

    private fun showSettings() {
        val favCount = favorites.all().size
        val items = arrayOf(
            "Nach Updates suchen",
            "Gemerkte Orte anzeigen ($favCount)",
            "Kompass kalibrieren – so geht's",
            "Über Peil & Datenquellen"
        )
        AlertDialog.Builder(this, R.style.PeilDialog)
            .setTitle("Peil ${versionName()}")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> updater.check(manual = true)
                    1 -> showFavorites()
                    2 -> info(
                        "Kompass kalibrieren",
                        "Halte das Handy vor dich und schwenke es zwei-, dreimal in Form einer liegenden Acht (∞), " +
                            "dabei auch leicht kippen und drehen.\n\nTipp: Abstand zu Autos, Metallgeländern und " +
                            "Magnethüllen hilft. Wenn der Kompass ungenau wird, vibriert Peil kurz."
                    )
                    3 -> info(
                        "Über Peil",
                        "Version ${versionName()}\n\nOrtsdaten und Öffnungszeiten: © OpenStreetMap-Mitwirkende (ODbL)\n" +
                            "Suche: Photon von komoot\nVerbindungen: Deutsche Bahn über transport.rest"
                    )
                }
            }
            .show()
    }

    private fun showFavorites() {
        val favs = favorites.all()
        if (favs.isEmpty()) {
            status("Noch nichts gemerkt – öffne die Details eines Orts und tippe auf ☆ Merken.")
            return
        }
        searchField.setText("")
        showPlaces(favs)
        status("Deine ${favs.size} gemerkten Orte.")
    }

    private fun info(title: String, msg: String) {
        AlertDialog.Builder(this, R.style.PeilDialog).setTitle(title).setMessage(msg).setPositiveButton("OK", null).show()
    }

    private fun versionName(): String =
        runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull() ?: "?"

    // ---------- UI-Helfer (Spur-Stil) ----------

    private fun section(title: String) {
        detailContent.addView(sectionView(title))
    }

    private fun sectionView(title: String) = eyebrow(title).apply { setPadding(0, dp(22), 0, dp(8)) }

    private fun eyebrow(title: String) = text(title.uppercase(), 12f, Ui.LABEL, Ui.BOLD).apply { letterSpacing = 0.14f }

    private fun primaryButton(label: String, icon: Int, action: () -> Unit) =
        text(label, 16f, Color.WHITE, Ui.MEDIUM, maxLines = 1).apply {
            gravity = Gravity.CENTER
            minHeight = dp(52)
            setPadding(dp(20), dp(12), dp(20), dp(12))
            background = rounded(Ui.ACCENT, 13, Color.TRANSPARENT, 0)
            setCompoundDrawablesRelativeWithIntrinsicBounds(tinted(icon, Color.WHITE, 20), null, null, null)
            compoundDrawablePadding = dp(8)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(16) }
            setOnClickListener { action() }
        }

    private fun secondaryButton(label: String, icon: Int, action: () -> Unit) =
        text(label, 15f, Ui.INK, Ui.REGULAR, maxLines = 1).apply {
            gravity = Gravity.CENTER
            minHeight = dp(50)
            setPadding(dp(18), dp(12), dp(18), dp(12))
            background = rounded(Ui.CARD, 13, Ui.BORDER_STRONG, 1)
            setCompoundDrawablesRelativeWithIntrinsicBounds(tinted(icon, Ui.INK, 18), null, null, null)
            compoundDrawablePadding = dp(8)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(12) }
            setOnClickListener { action() }
        }

    /** Werkzeugleiste wie in Spur: weiße Leiste mit Icon über kleinem Text. */
    private fun toolBar(tools: List<Triple<String, Int, () -> Unit>>) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(5), dp(5), dp(5), dp(5))
            background = rounded(Ui.CARD, 16, Ui.BORDER, 1)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(12) }
        }
        val perRow = if (tools.size <= 4) tools.size else 3
        tools.chunked(perRow).forEach { rowItems ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            rowItems.forEach { (label, icon, action) ->
                row.addView(text(label, 12f, Ui.CHIP_TEXT, maxLines = 1).apply {
                    gravity = Gravity.CENTER
                    setPadding(dp(4), dp(10), dp(4), dp(9))
                    setCompoundDrawablesRelativeWithIntrinsicBounds(null, tinted(icon, Ui.CHIP_TEXT, 22), null, null)
                    compoundDrawablePadding = dp(5)
                    background = pressable()
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    setOnClickListener { action() }
                })
            }
            repeat(perRow - rowItems.size) {
                row.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(0, 1, 1f) })
            }
            box.addView(row)
        }
        detailContent.addView(box)
    }

    private fun iconButton(icon: Int, action: () -> Unit) = android.widget.ImageView(this).apply {
        setImageDrawable(tinted(icon, Ui.INK, 20))
        scaleType = android.widget.ImageView.ScaleType.CENTER
        background = rounded(Ui.PRESSED, 22, Color.TRANSPARENT, 0)
        layoutParams = LinearLayout.LayoutParams(dp(42), dp(42)).apply { marginStart = dp(10) }
        contentDescription = "Schließen"
        setOnClickListener { action() }
    }

    private fun tinted(res: Int, color: Int, sizeDp: Int) =
        ContextCompat.getDrawable(this, res)!!.mutate().apply {
            setTint(color)
            setBounds(0, 0, dp(sizeDp), dp(sizeDp))
        }

    private fun pressable() = android.graphics.drawable.StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_pressed), rounded(Ui.CHIP, 12, Color.TRANSPARENT, 0))
        addState(intArrayOf(), rounded(Color.TRANSPARENT, 12, Color.TRANSPARENT, 0))
    }

    private fun text(s: String, sizeSp: Float, color: Int, face: Typeface = Ui.REGULAR, maxLines: Int = 0) =
        TextView(this).apply {
            text = s
            textSize = sizeSp
            setTextColor(color)
            typeface = face
            if (maxLines > 0) {
                this.maxLines = maxLines
                ellipsize = TextUtils.TruncateAt.END
            }
        }

    private fun rounded(fill: Int, radiusDp: Int, stroke: Int, strokeDp: Int) = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = dp(radiusDp).toFloat()
        if (strokeDp > 0) setStroke(dp(strokeDp), stroke)
    }

    private fun distanceTo(p: Place, loc: Location): Float {
        val res = FloatArray(1)
        Location.distanceBetween(loc.latitude, loc.longitude, p.lat, p.lon, res)
        return res[0]
    }

    @Suppress("DEPRECATION")
    private fun vibrate(pattern: LongArray) {
        val v: Vibrator = if (Build.VERSION.SDK_INT >= 31) {
            (getSystemService(VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            getSystemService(VIBRATOR_SERVICE) as Vibrator
        }
        if (v.hasVibrator()) v.vibrate(VibrationEffect.createWaveform(pattern, -1))
    }

    private fun status(text: String) {
        statusText.text = text
    }

    private fun hideKeyboard() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(searchField.windowToken, 0)
        searchField.clearFocus()
    }

    private fun hasPermission(p: String) =
        ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private fun hasLocationPermission() =
        hasPermission(Manifest.permission.ACCESS_FINE_LOCATION) ||
            hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION)

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
