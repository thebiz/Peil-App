package com.john.peil

import android.app.Activity
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import com.google.ar.core.Config
import com.google.ar.core.Coordinates2d
import com.google.ar.core.Earth
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * ARCore-Kamera mit Geospatial (Street-View-Abgleich).
 * Zeichnet das Kamerabild per OpenGL und meldet pro Bild, wo das Handy steht und wohin es schaut.
 */
class ArCamera(
    private val activity: Activity,
    private val listener: Listener
) : GLSurfaceView.Renderer {

    class FrameInfo(
        /** Kamera → Ost-Oben-Süd, zeilenweise 3×3 (null = Geospatial noch nicht bereit) */
        val geoRotation: FloatArray?,
        val fx: Float,
        val fy: Float,
        val geoLat: Double,
        val geoLon: Double,
        val horizontalAccuracy: Double,
        val headingAccuracy: Double,
        val earthState: Earth.EarthState?,
        val cameraTracking: Boolean
    )

    interface Listener {
        fun onArFrame(info: FrameInfo)
    }

    val view: GLSurfaceView = GLSurfaceView(activity).apply {
        preserveEGLContextOnPause = true
        setEGLContextClientVersion(2)
        setEGLConfigChooser(8, 8, 8, 8, 16, 0)
        setRenderer(this@ArCamera)
        renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
    }

    var session: Session? = null
        private set
    var geospatialSupported = false
        private set

    private var textureId = -1
    private var textureSet = false
    private var program = 0
    private var aPos = 0
    private var aTex = 0
    private var viewW = 0
    private var viewH = 0
    private val proj = FloatArray(16)

    private val quad: FloatBuffer = floatBuffer(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f))
    private val texCoords: FloatBuffer = floatBuffer(FloatArray(8))

    /** Legt die ARCore-Sitzung an. Wirft eine Ausnahme, wenn das nicht geht. */
    fun createSession() {
        val s = Session(activity)
        val cfg = Config(s)
        cfg.updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
        cfg.focusMode = Config.FocusMode.AUTO
        geospatialSupported = s.isGeospatialModeSupported(Config.GeospatialMode.ENABLED)
        if (geospatialSupported) cfg.geospatialMode = Config.GeospatialMode.ENABLED
        s.configure(cfg)
        session = s
    }

    fun resume() {
        session?.resume()
        view.onResume()
    }

    fun pause() {
        view.onPause()
        session?.pause()
    }

    fun close() {
        session?.close()
        session = null
    }

    // ---------- OpenGL ----------

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0.96f, 0.96f, 0.95f, 1f)
        val tex = IntArray(1)
        GLES20.glGenTextures(1, tex, 0)
        textureId = tex[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        textureSet = false

        val vs = shader(
            GLES20.GL_VERTEX_SHADER,
            "attribute vec4 a_Position; attribute vec2 a_TexCoord; varying vec2 v_TexCoord;" +
                "void main(){ gl_Position = a_Position; v_TexCoord = a_TexCoord; }"
        )
        val fs = shader(
            GLES20.GL_FRAGMENT_SHADER,
            "#extension GL_OES_EGL_image_external : require\n" +
                "precision mediump float; varying vec2 v_TexCoord; uniform samplerExternalOES sTexture;" +
                "void main(){ gl_FragColor = texture2D(sTexture, v_TexCoord); }"
        )
        program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vs)
        GLES20.glAttachShader(program, fs)
        GLES20.glLinkProgram(program)
        aPos = GLES20.glGetAttribLocation(program, "a_Position")
        aTex = GLES20.glGetAttribLocation(program, "a_TexCoord")
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        viewW = width
        viewH = height
        session?.setDisplayGeometry(android.view.Surface.ROTATION_0, width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        val s = session ?: return
        if (textureId < 0) return
        if (!textureSet) {
            s.setCameraTextureName(textureId)
            s.setDisplayGeometry(android.view.Surface.ROTATION_0, viewW, viewH)
            textureSet = true
        }
        val frame = try {
            s.update()
        } catch (e: Exception) {
            return
        }
        if (frame.hasDisplayGeometryChanged()) {
            quad.position(0)
            texCoords.position(0)
            frame.transformCoordinates2d(
                Coordinates2d.OPENGL_NORMALIZED_DEVICE_COORDINATES, quad,
                Coordinates2d.TEXTURE_NORMALIZED, texCoords
            )
        }
        if (frame.timestamp == 0L) return

        // Kamerabild als Hintergrund
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glDepthMask(false)
        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        quad.position(0)
        texCoords.position(0)
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 0, quad)
        GLES20.glVertexAttribPointer(aTex, 2, GLES20.GL_FLOAT, false, 0, texCoords)
        GLES20.glEnableVertexAttribArray(aPos)
        GLES20.glEnableVertexAttribArray(aTex)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(aPos)
        GLES20.glDisableVertexAttribArray(aTex)
        GLES20.glDepthMask(true)

        // Lage und Brennweite für das Overlay
        val camera = frame.camera
        camera.getProjectionMatrix(proj, 0, 0.1f, 2000f)
        val fx = proj[0] * viewW / 2f
        val fy = proj[5] * viewH / 2f
        val cameraTracking = camera.trackingState == TrackingState.TRACKING

        var rot: FloatArray? = null
        var lat = Double.NaN
        var lon = Double.NaN
        var hAcc = Double.MAX_VALUE
        var yawAcc = Double.MAX_VALUE
        val earth = if (geospatialSupported) s.earth else null
        if (earth != null && cameraTracking && earth.trackingState == TrackingState.TRACKING) {
            val gp = earth.cameraGeospatialPose
            rot = quatToMatrix(gp.eastUpSouthQuaternion)
            lat = gp.latitude
            lon = gp.longitude
            hAcc = gp.horizontalAccuracy
            yawAcc = gp.orientationYawAccuracy
        }
        listener.onArFrame(
            FrameInfo(rot, fx, fy, lat, lon, hAcc, yawAcc, earth?.earthState, cameraTracking)
        )
    }

    // ---------- Helfer ----------

    private fun shader(type: Int, code: String): Int {
        val sh = GLES20.glCreateShader(type)
        GLES20.glShaderSource(sh, code)
        GLES20.glCompileShader(sh)
        return sh
    }

    private fun floatBuffer(data: FloatArray): FloatBuffer =
        ByteBuffer.allocateDirect(data.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
            put(data)
            position(0)
        }

    /** Quaternion (x, y, z, w) → 3×3-Rotationsmatrix, zeilenweise. */
    private fun quatToMatrix(q: FloatArray): FloatArray {
        val x = q[0]
        val y = q[1]
        val z = q[2]
        val w = q[3]
        return floatArrayOf(
            1 - 2 * (y * y + z * z), 2 * (x * y - z * w), 2 * (x * z + y * w),
            2 * (x * y + z * w), 1 - 2 * (x * x + z * z), 2 * (y * z - x * w),
            2 * (x * z - y * w), 2 * (y * z + x * w), 1 - 2 * (x * x + y * y)
        )
    }
}
