package com.john.peil

import android.Manifest
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import android.speech.RecognizerIntent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import java.util.concurrent.Executors

class MainActivity : ComponentActivity(), SensorEventListener, LocationListener {

    private lateinit var previewView: PreviewView
    private lateinit var overlay: ArOverlayView
    private lateinit var searchField: EditText
    private lateinit var statusText: TextView

    private lateinit var sensorManager: SensorManager
    private var rotationSensor: Sensor? = null
    private var locationManager: LocationManager? = null

    private var currentLocation: Location? = null
    private var declination = 0f
    private var pendingQuery: String? = null
    private var searching = false
    private var cameraStarted = false

    private val rotation = FloatArray(9)
    private val remapped = FloatArray(9)
    private val orientation = FloatArray(3)

    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var updater: Updater

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        previewView = findViewById(R.id.preview)
        overlay = findViewById(R.id.overlay)
        searchField = findViewById(R.id.search_field)
        statusText = findViewById(R.id.status)
        val topBar = findViewById<View>(R.id.top_bar)

        // Platz für Statusleiste oben und Navigationsleiste unten lassen
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.root)) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            topBar.updatePadding(top = bars.top + dp(12))
            overlay.bottomInset = bars.bottom
            insets
        }

        searchField.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                search(searchField.text.toString())
                true
            } else {
                false
            }
        }
        findViewById<ImageButton>(R.id.mic_button).setOnClickListener { startSpeech() }

        // Updates: automatisch beim Start, lange auf den Hinweistext drücken = manuell prüfen
        updater = Updater(this) { status(it) }
        statusText.setOnLongClickListener {
            updater.check(manual = true)
            true
        }
        if (savedInstanceState == null) updater.check()

        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        if (rotationSensor == null) {
            status("Dein Handy hat keinen Lagesensor – die Richtung kann ich leider nicht anzeigen.")
        }

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

        pendingQuery?.let {
            pendingQuery = null
            search(it)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {}

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
        overlay.compassUnreliable = accuracy <= SensorManager.SENSOR_STATUS_ACCURACY_LOW
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
                    overlay.setTargets(places)
                    if (places.isEmpty()) {
                        status("Nichts gefunden für „$q“ im Umkreis von 10 km.")
                    } else {
                        status("${places.size}× „$q“ gefunden – dreh dich mal um. Antippen wählt ein anderes Ziel.")
                    }
                }.onFailure {
                    status("Suche hat nicht geklappt – bitte gleich nochmal versuchen. (${it.message})")
                }
            }
        }
    }

    // ---------- Helfer ----------

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
