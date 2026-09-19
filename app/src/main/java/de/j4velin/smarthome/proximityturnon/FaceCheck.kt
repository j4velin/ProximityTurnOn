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

/**
 * Opens the front camera for a short moment and reports whether a face is
 * visible. The camera is closed again as soon as a face is found or the
 * timeout expires, so it is only powered for a second or two per check.
 *
 * All callbacks are delivered on the main thread.
 */
class FaceCheck(private val context: Context, private val log: (String) -> Unit) : LifecycleOwner {

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
    }

    init {
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            cameraProvider = runCatching { future.get() }.onFailure { log("camera provider failed: $it") }.getOrNull()
        }, ContextCompat.getMainExecutor(context))
    }

    /** Starts a check; ignored if one is already running. */
    fun start(onResult: (Boolean) -> Unit) {
        if (isRunning) return
        val provider = cameraProvider ?: run {
            log("camera not ready")
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
            log("camera check started")
        }.onFailure {
            log("camera bind failed: $it")
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
            .addOnSuccessListener { faces -> if (faces.isNotEmpty()) finish(true) }
            .addOnFailureListener { log("face detection failed: $it") }
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
        log("camera check: ${if (faceFound) "FACE" else "no face"} after ${ms}ms, $frames frames")
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
