package com.vessel

import android.Manifest
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.widget.Button
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.ar.core.*
import com.google.ar.core.exceptions.*
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult
import java.io.IOException
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.*

private const val TAG = "ArActivity"

class ArActivity : AppCompatActivity(), GLSurfaceView.Renderer {

    // ── GL & ARCore ──────────────────────────────────────────────────────────
    private lateinit var surfaceView: GLSurfaceView
    private var session: Session? = null
    private var cameraTextureId = -1

    @Volatile private var latestFrame: Frame? = null
    @Volatile private var artifactAnchor: Anchor? = null

    private var viewportWidth  = 1
    private var viewportHeight = 1

    // ── Plasma renderer ──────────────────────────────────────────────────────
    private val plasmaRenderer = PlasmaRenderer()

    // ── Hand tracking ────────────────────────────────────────────────────────
    private lateinit var handTracker: MediaPipeHandTracker

    // Contact spring state (GL thread only)
    private var smoothContact  = 0f
    private var springPos      = 0f
    private var springVel      = 0f
    private var lastFrameNanos = 0L
    private var wasContacting  = false

    // Hand world-position tracking for directional smear (GL thread only)
    private val handWorldPos       = FloatArray(3)
    private val prevHandPos        = FloatArray(3) { Float.NaN }
    private val handVelSmoothed    = FloatArray(3)
    private var smearSpeed         = 0f
    private val smearDir           = floatArrayOf(0f, 0f, 1f)

    // Bleed mode state
    private var bleedEnabled = false
    private var bleedHue     = 0f
    private val bleedBuf     = java.nio.IntBuffer.allocate(32 * 32)

    // ── Recording ────────────────────────────────────────────────────────────
    private lateinit var recordingManager: RecordingManager
    private lateinit var recordBtn: Button
    private var recordPulse: ObjectAnimator? = null

    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        recordingManager.onProjectionResult(result.resultCode, result.data)
        if (result.resultCode == RESULT_OK) startRecordingUi()
    }

    private val audioPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) launchProjectionConsent()
        else Toast.makeText(this, "Microphone permission required to record", Toast.LENGTH_SHORT).show()
    }

    // ── Settings passed from MenuActivity ────────────────────────────────────
    private var idleSoundEnabled = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        idleSoundEnabled = intent.getBooleanExtra("idle_sound", true)
        bleedEnabled     = intent.getBooleanExtra("bleed_mode", false)

        surfaceView = GLSurfaceView(this).also {
            it.preserveEGLContextOnPause = true
            it.setEGLContextClientVersion(3)
            it.setRenderer(this)
            it.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        }
        val root = FrameLayout(this)
        root.addView(surfaceView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        val dp = resources.displayMetrics.density
        val sz = (56 * dp).toInt(); val mg = (20 * dp).toInt()
        root.addView(Button(this).apply {
            text = "←"
            textSize = 22f
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundColor(0x77000000)
            setOnClickListener { finish() }
        }, FrameLayout.LayoutParams(sz, sz).apply {
            gravity = Gravity.TOP or Gravity.START
            topMargin = mg; leftMargin = mg
        })

        recordBtn = Button(this).apply {
            text = "⏺"
            textSize = 20f
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundColor(0x77000000)
            setOnClickListener { onRecordTapped() }
        }
        root.addView(recordBtn, FrameLayout.LayoutParams(sz, sz).apply {
            gravity = Gravity.BOTTOM or Gravity.END
            bottomMargin = mg; rightMargin = mg
        })

        setContentView(root)

        recordingManager = RecordingManager(this)
        handTracker = MediaPipeHandTracker(this)
        handTracker.onLandmarksResult = ::onHandLandmarks

        GranularBridge.nativeSetIdleMode(idleSoundEnabled)
    }

    private fun onRecordTapped() {
        if (recordingManager.isRecording) {
            recordingManager.stopRecording()
            stopRecordingUi()
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                    == PackageManager.PERMISSION_GRANTED) {
                launchProjectionConsent()
            } else {
                audioPermLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
    }

    private fun launchProjectionConsent() {
        val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projectionLauncher.launch(mgr.createScreenCaptureIntent())
    }

    private fun startRecordingUi() {
        recordBtn.text = "⏹"
        recordBtn.setTextColor(0xFFFF3333.toInt())
        recordPulse = ObjectAnimator.ofFloat(recordBtn, "alpha", 0.6f, 1.0f).apply {
            duration = 1000L
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            start()
        }
    }

    private fun stopRecordingUi() {
        recordPulse?.cancel()
        recordBtn.alpha = 1f
        recordBtn.text = "⏺"
        recordBtn.setTextColor(0xFFFFFFFF.toInt())
        Toast.makeText(this, "Saved to Gallery", Toast.LENGTH_SHORT).show()
    }

    override fun onResume() {
        super.onResume()
        if (session == null) {
            try {
                when (ArCoreApk.getInstance().requestInstall(this, true)) {
                    ArCoreApk.InstallStatus.INSTALL_REQUESTED -> { finish(); return }
                    ArCoreApk.InstallStatus.INSTALLED -> {}
                }
                session = Session(this).also { s ->
                    val config = Config(s).apply {
                        planeFindingMode = Config.PlaneFindingMode.HORIZONTAL
                        focusMode = Config.FocusMode.AUTO
                        updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
                    }
                    s.configure(config)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create AR session", e)
                finish()
                return
            }
        }
        try {
            session?.resume()
        } catch (e: CameraNotAvailableException) {
            Log.e(TAG, "Camera not available on resume", e)
            session = null
            Toast.makeText(this, "Camera unavailable — go back and re-enter AR", Toast.LENGTH_LONG).show()
            return
        }
        surfaceView.onResume()
        GranularBridge.nativeStart()
    }

    override fun onPause() {
        super.onPause()
        surfaceView.onPause()
        session?.pause()
        GranularBridge.nativeStop()
    }

    override fun onDestroy() {
        super.onDestroy()
        recordingManager.release()
        handTracker.close()
        session?.close()
        GranularBridge.nativeRelease()
    }

    // ── GLSurfaceView.Renderer ───────────────────────────────────────────────

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES30.glClearColor(0f, 0f, 0f, 1f)

        // Create the external OES texture ARCore will draw into
        val textures = IntArray(1)
        GLES30.glGenTextures(1, textures, 0)
        cameraTextureId = textures[0]

        session?.setCameraTextureName(cameraTextureId)
        plasmaRenderer.init(cameraTextureId, this)
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES30.glViewport(0, 0, width, height)
        viewportWidth  = width
        viewportHeight = height
        session?.setDisplayGeometry(display?.rotation ?: 0, width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)

        val sess = session ?: return
        try {
            val frame = sess.update()
            latestFrame = frame

            // ── Feed camera frames to MediaPipe (Phase 1 camera sharing) ──
            try {
                frame.acquireCameraImage().use { img ->
                    handTracker.submitFrame(img, frame.timestamp / 1_000L)
                }
            } catch (_: com.google.ar.core.exceptions.NotYetAvailableException) { }
              catch (_: com.google.ar.core.exceptions.ResourceExhaustedException) { }

            // ── Plane detection: anchor artifact on first good plane ──
            if (artifactAnchor == null) {
                for (plane in sess.getAllTrackables(Plane::class.java)) {
                    if (plane.type == Plane.Type.HORIZONTAL_UPWARD_FACING &&
                        plane.trackingState == TrackingState.TRACKING) {
                        val cameraPose = frame.camera.pose
                        // Place artifact 0.5m in front of camera at plane height
                        val translation = cameraPose.translation
                        val forward = cameraPose.zAxis  // camera looks along -Z; use +Z for forward in world
                        val anchorPose = Pose.makeTranslation(
                            translation[0] - forward[0] * 0.5f,
                            plane.centerPose.ty(),
                            translation[2] - forward[2] * 0.5f
                        )
                        artifactAnchor = sess.createAnchor(anchorPose)
                        Log.i(TAG, "Artifact anchored at ${anchorPose.translation.toList()}")
                        break
                    }
                }
            }

            // ── Spring physics update ──
            val nowNanos = System.nanoTime()
            val dt = if (lastFrameNanos == 0L) 0.016f
                     else ((nowNanos - lastFrameNanos) / 1e9f).coerceIn(0.001f, 0.05f)
            lastFrameNanos = nowNanos
            updateSpring(smoothContact, dt)

            // ── Bleed mode: sample pixels behind artifact ──
            if (bleedEnabled && artifactAnchor != null) {
                bleedHue = sampleBleedHue(frame)
                GranularBridge.nativeSetMasterPosition(bleedHue)
            }

            // ── Render plasma ──
            val artifactPos = artifactAnchor?.pose?.translation ?: floatArrayOf(0f, 0f, -1f)
            val viewMatrix = FloatArray(16)
            val projMatrix = FloatArray(16)
            frame.camera.getViewMatrix(viewMatrix, 0)
            frame.camera.getProjectionMatrix(projMatrix, 0, 0.1f, 100f)

            val vpMatrix = FloatArray(16)
            Matrix.multiplyMM(vpMatrix, 0, projMatrix, 0, viewMatrix, 0)

            plasmaRenderer.draw(
                frame        = frame,
                vpMatrix     = vpMatrix,
                artifactPos  = artifactPos,
                contactMag   = springPos,
                contactPoint = handWorldPos,   // actual hand world position, not artifact center
                smearDir     = smearDir,
                smearSpeed   = smearSpeed,
                breathPhase  = (System.nanoTime() / 1e9f) * 0.7f + 1.57f,
                bleedHue     = bleedHue,
                bleedEnabled = if (bleedEnabled) 1f else 0f,
                resolution   = floatArrayOf(viewportWidth.toFloat(), viewportHeight.toFloat()),
                timeSec      = (System.nanoTime() / 1e9f)
            )

        } catch (e: CameraNotAvailableException) {
            Log.e(TAG, "Camera not available", e)
        }
    }

    // ── Hand landmark result (called from MediaPipe HandlerThread, posted to GL) ──

    private fun onHandLandmarks(result: HandLandmarkerResult) {
        surfaceView.queueEvent {
            val frame = latestFrame ?: return@queueEvent
            val anchor = artifactAnchor ?: return@queueEvent

            val landmarks = result.landmarks()
            if (landmarks.isEmpty()) {
                smoothContact = (smoothContact - 0.03f).coerceAtLeast(0f)
                smearSpeed *= 0.8f
                prevHandPos[0] = Float.NaN
                wasContacting = false
                GranularBridge.nativeSetContactMagnitude(smoothContact)
                return@queueEvent
            }

            val tip = landmarks[0].getOrNull(8) ?: return@queueEvent  // index fingertip

            val sx = tip.x() * viewportWidth
            val sy = tip.y() * viewportHeight

            val (origin, dir) = screenToWorldRay(sx, sy, frame)
            val ap = anchor.pose.translation
            val artifactVec = floatArrayOf(ap[0], ap[1], ap[2])

            // Screen-space proximity: project artifact to NDC, compare with fingertip NDC.
            // More natural than world-space ray distance for a screen-space visual effect.
            val proj = FloatArray(16); val view = FloatArray(16)
            frame.camera.getProjectionMatrix(proj, 0, 0.1f, 100f)
            frame.camera.getViewMatrix(view, 0)
            val vp = FloatArray(16)
            android.opengl.Matrix.multiplyMM(vp, 0, proj, 0, view, 0)
            val artClip = FloatArray(4)
            android.opengl.Matrix.multiplyMV(artClip, 0, vp, 0,
                floatArrayOf(artifactVec[0], artifactVec[1], artifactVec[2], 1f), 0)
            val raw = if (artClip[3] > 0f) {
                val artNx = artClip[0] / artClip[3]
                val artNy = artClip[1] / artClip[3]
                val tipNx = (sx / viewportWidth)  * 2f - 1f
                val tipNy = 1f - (sy / viewportHeight) * 2f
                val ndcDist = sqrt((tipNx - artNx) * (tipNx - artNx) +
                                   (tipNy - artNy) * (tipNy - artNy))
                // Artifact screen radius in NDC ≈ screenRadius(0.52) * 2
                (1f - ndcDist / 1.1f).coerceIn(0f, 1f)
            } else 0f
            smoothContact = smoothContact * 0.60f + raw * 0.40f
            GranularBridge.nativeSetContactMagnitude(smoothContact)

            // Edge-trigger: randomize grain params on each new touch
            val isContacting = smoothContact > 0.25f
            if (isContacting && !wasContacting) randomizeGrainParams()
            wasContacting = isContacting

            // Compute hand world position: closest point on finger ray to artifact center.
            // This is the 3D point where the finger ray comes nearest the blob — used as
            // the actual contact point so deformation has real directional information.
            val dx = artifactVec[0] - origin[0]
            val dy = artifactVec[1] - origin[1]
            val dz = artifactVec[2] - origin[2]
            val t  = dx*dir[0] + dy*dir[1] + dz*dir[2]
            handWorldPos[0] = origin[0] + dir[0]*t
            handWorldPos[1] = origin[1] + dir[1]*t
            handWorldPos[2] = origin[2] + dir[2]*t

            // Velocity from frame-to-frame position delta, smoothed
            if (!prevHandPos[0].isNaN()) {
                val vx = handWorldPos[0] - prevHandPos[0]
                val vy = handWorldPos[1] - prevHandPos[1]
                val vz = handWorldPos[2] - prevHandPos[2]
                handVelSmoothed[0] = handVelSmoothed[0] * 0.55f + vx * 0.45f
                handVelSmoothed[1] = handVelSmoothed[1] * 0.55f + vy * 0.45f
                handVelSmoothed[2] = handVelSmoothed[2] * 0.55f + vz * 0.45f

                val speed = sqrt(
                    handVelSmoothed[0]*handVelSmoothed[0] +
                    handVelSmoothed[1]*handVelSmoothed[1] +
                    handVelSmoothed[2]*handVelSmoothed[2]
                )
                // Scale: ~0.05 m per callback = full smear. Callbacks arrive ~15-30fps.
                smearSpeed = (speed * 18f).coerceIn(0f, 1f)

                if (speed > 0.0001f) {
                    val inv = 1f / speed
                    smearDir[0] = handVelSmoothed[0] * inv
                    smearDir[1] = handVelSmoothed[1] * inv
                    smearDir[2] = handVelSmoothed[2] * inv
                }
            } else {
                smearSpeed = 0f
            }
            prevHandPos[0] = handWorldPos[0]
            prevHandPos[1] = handWorldPos[1]
            prevHandPos[2] = handWorldPos[2]
        }
    }

    // ── Grain randomization ──────────────────────────────────────────────────

    private fun randomizeGrainParams() {
        val r = java.util.Random()
        GranularBridge.nativeSetGrainDuration(10f + r.nextFloat() * 490f)
        GranularBridge.nativeSetGrainDensity(0.5f + r.nextFloat() * 19.5f)
        GranularBridge.nativeSetPositionScatter(r.nextFloat())
        GranularBridge.nativeSetPitchRandom(r.nextFloat() * 12f)
        GranularBridge.nativeSetEnvelopeShape((0..2).random())
    }

    // ── Spring physics ──────────────────────────────────────────────────────

    private fun updateSpring(target: Float, dt: Float) {
        val force = 32f * (target - springPos) - 3.8f * springVel
        springVel += force * dt
        springPos = (springPos + springVel * dt).coerceIn(0f, 1.6f)
    }

    // ── Coordinate math ──────────────────────────────────────────────────────

    private fun screenToWorldRay(x: Float, y: Float, frame: Frame): Pair<FloatArray, FloatArray> {
        val proj = FloatArray(16)
        val view = FloatArray(16)
        frame.camera.getProjectionMatrix(proj, 0, 0.1f, 100f)
        frame.camera.getViewMatrix(view, 0)

        val vp = FloatArray(16)
        Matrix.multiplyMM(vp, 0, proj, 0, view, 0)
        val invVp = FloatArray(16)
        Matrix.invertM(invVp, 0, vp, 0)

        val ndcX = (x / viewportWidth)  * 2f - 1f
        val ndcY = 1f - (y / viewportHeight) * 2f

        val near = FloatArray(4)
        Matrix.multiplyMV(near, 0, invVp, 0, floatArrayOf(ndcX, ndcY, -1f, 1f), 0)
        val far  = FloatArray(4)
        Matrix.multiplyMV(far,  0, invVp, 0, floatArrayOf(ndcX, ndcY,  1f, 1f), 0)

        val origin = floatArrayOf(near[0]/near[3], near[1]/near[3], near[2]/near[3])
        val dirRaw = floatArrayOf(far[0]/far[3] - origin[0], far[1]/far[3] - origin[1],
                                  far[2]/far[3] - origin[2])
        val len = sqrt(dirRaw[0]*dirRaw[0] + dirRaw[1]*dirRaw[1] + dirRaw[2]*dirRaw[2])
        val dir = floatArrayOf(dirRaw[0]/len, dirRaw[1]/len, dirRaw[2]/len)

        return origin to dir
    }

    private fun rayPointDistance(origin: FloatArray, dir: FloatArray, point: FloatArray): Float {
        val dx = point[0] - origin[0]
        val dy = point[1] - origin[1]
        val dz = point[2] - origin[2]
        val t  = dx*dir[0] + dy*dir[1] + dz*dir[2]
        val cx = origin[0] + dir[0]*t - point[0]
        val cy = origin[1] + dir[1]*t - point[1]
        val cz = origin[2] + dir[2]*t - point[2]
        return sqrt(cx*cx + cy*cy + cz*cz)
    }

    // ── Bleed mode ───────────────────────────────────────────────────────────

    private fun sampleBleedHue(frame: Frame): Float {
        val anchor = artifactAnchor ?: return bleedHue

        // Project artifact center to screen space
        val viewMatrix = FloatArray(16)
        val projMatrix = FloatArray(16)
        frame.camera.getViewMatrix(viewMatrix, 0)
        frame.camera.getProjectionMatrix(projMatrix, 0, 0.1f, 100f)
        val vp = FloatArray(16)
        Matrix.multiplyMM(vp, 0, projMatrix, 0, viewMatrix, 0)

        val ap = anchor.pose.translation
        val clip = FloatArray(4)
        Matrix.multiplyMV(clip, 0, vp, 0, floatArrayOf(ap[0], ap[1], ap[2], 1f), 0)
        if (clip[3] == 0f) return bleedHue

        val ndcX = clip[0] / clip[3]
        val ndcY = clip[1] / clip[3]
        val cx = ((ndcX + 1f) * 0.5f * viewportWidth).toInt()
        val cy = ((1f - ndcY) * 0.5f * viewportHeight).toInt()

        // Read 32×32 pixels around artifact center
        val size = 32
        val x0 = (cx - size/2).coerceIn(0, viewportWidth  - size)
        val y0 = (cy - size/2).coerceIn(0, viewportHeight - size)
        bleedBuf.clear()
        GLES30.glReadPixels(x0, y0, size, size, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, bleedBuf)

        return extractDominantHue(bleedBuf.array())
    }

    private fun extractDominantHue(pixels: IntArray): Float {
        var sumH = 0f; var count = 0
        for (pixel in pixels) {
            val r = (pixel and 0xFF) / 255f
            val g = ((pixel shr 8) and 0xFF) / 255f
            val b = ((pixel shr 16) and 0xFF) / 255f
            val max = maxOf(r, g, b)
            val delta = max - minOf(r, g, b)
            if (delta < 0.1f || max < 0.1f) continue
            val h = when (max) {
                r -> (g - b) / delta
                g -> 2f + (b - r) / delta
                else -> 4f + (r - g) / delta
            }.let { if (it < 0f) it + 6f else it } / 6f
            sumH += h; count++
        }
        return if (count > 0) sumH / count else bleedHue
    }
}
