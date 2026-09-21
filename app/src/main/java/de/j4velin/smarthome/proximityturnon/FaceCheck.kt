package de.j4velin.smarthome.proximityturnon

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
import java.util.concurrent.Executors
import kotlin.math.abs

/**
 * Opens the front camera for a short moment and reports whether a face is
 * visible. The camera is closed again as soon as a face is found or the
 * timeout expires, so it is only powered for a second or two per check.
 *
 * All callbacks are delivered on the main thread.
 */
class FaceCheck(
    private val context: Context,
    /** Logs a message; the flag marks failures that should also reach logcat */
    private val log: (msg: String, logcat: Boolean) -> Unit,
) : LifecycleOwner {

    private val registry = LifecycleRegistry(this).also { it.currentState = Lifecycle.State.RESUMED }
    override val lifecycle: Lifecycle get() = registry

    private val mainHandler = Handler(Looper.getMainLooper())
    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private var cameraProvider: ProcessCameraProvider? = null
    private var detector: FaceDetector? = null
    private var onResult: ((Boolean) -> Unit)? = null
    private var startedAt = 0L
    private var frames = 0

    val isRunning: Boolean get() = onResult != null

    companion object {
        private const val TIMEOUT_MS = 3_000L

        /**
         * Minimum face width relative to the frame. The tablet is mounted at head
         * level and used from ~50 cm, where a face is ~20% of the frame width, so
         * this ignores people further away in the background.
         */
        private const val MIN_FACE_SIZE = 0.15f

        /**
         * Maximum head rotation around the vertical axis (yaw) for a face to
         * count. Someone looking at the tablet is near 0, someone walking past
         * is seen in profile at 45-90 degrees and must not wake the screen.
         */
        private const val MAX_YAW_DEGREES = 25f
    }

    init {
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            cameraProvider = runCatching { future.get() }.onFailure { log("camera provider failed: $it", true) }.getOrNull()
        }, ContextCompat.getMainExecutor(context))
    }

    /** Starts a check; ignored if one is already running. */
    fun start(onResult: (Boolean) -> Unit) {
        if (isRunning) return
        val provider = cameraProvider ?: run {
            log("camera not ready", true)
            onResult(false)
            return
        }
        this.onResult = onResult
        startedAt = SystemClock.elapsedRealtime()
        frames = 0
        detector = FaceDetection.getClient(
            FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                .setMinFaceSize(MIN_FACE_SIZE)
                .build()
        )
        val analysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setResolutionStrategy(
                        ResolutionStrategy(Size(640, 480), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)
                    ).build()
            )
            .build()
        analysis.setAnalyzer(analysisExecutor, ::analyze)
        runCatching {
            provider.unbindAll()
            provider.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, analysis)
            log("camera check started", false)
        }.onFailure {
            log("camera bind failed: $it", true)
            finish(false)
            return
        }
        mainHandler.postDelayed(timeout, TIMEOUT_MS)
    }

    private val timeout = Runnable { finish(false) }

    @androidx.annotation.OptIn(ExperimentalGetImage::class)
    private fun analyze(proxy: ImageProxy) {
        val detector = detector
        val image = proxy.image
        if (detector == null || image == null || !isRunning) {
            proxy.close()
            return
        }
        frames++
        detector.process(InputImage.fromMediaImage(image, proxy.imageInfo.rotationDegrees))
            .addOnSuccessListener { faces ->
                val facing = faces.filter { abs(it.headEulerAngleY) <= MAX_YAW_DEGREES }
                if (facing.isNotEmpty()) {
                    finish(true)
                } else if (faces.isNotEmpty()) {
                    log("face ignored, not facing the tablet: yaw=%.0f°".format(faces.first().headEulerAngleY), false)
                }
            }
            .addOnFailureListener { log("face detection failed: $it", true) }
            .addOnCompleteListener { proxy.close() }
    }

    private fun finish(faceFound: Boolean) {
        val callback = onResult ?: return
        onResult = null
        mainHandler.removeCallbacks(timeout)
        cameraProvider?.unbindAll()
        detector?.close()
        detector = null
        val ms = SystemClock.elapsedRealtime() - startedAt
        log("camera check: ${if (faceFound) "FACE" else "no face"} after ${ms}ms, $frames frames", false)
        callback(faceFound)
    }

    /** Aborts a running check without reporting a result */
    fun cancel() {
        if (!isRunning) return
        onResult = {}
        finish(false)
    }

    fun release() {
        finish(false)
        registry.currentState = Lifecycle.State.DESTROYED
        analysisExecutor.shutdown()
    }
}
