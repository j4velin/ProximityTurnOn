package de.j4velin.smarthome.proximityturnon

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Binder
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Monitors the ambient light sensor and wakes the screen when someone casts a
 * shadow onto the tablet, i.e. the lux value drops suddenly below a slowly
 * moving baseline (see [ShadowDetector]).
 *
 * Every sensor event is logged together with the current screen state so it
 * can be verified that the sensor keeps delivering while the screen is off.
 */
class LightSensorService : Service(), SensorEventListener {

    private var sensorManager: SensorManager? = null
    private var lightSensor: Sensor? = null
    private var partialWakeLock: PowerManager.WakeLock? = null
    private var screenWakeLock: PowerManager.WakeLock? = null
    private var powerManager: PowerManager? = null
    private var faceCheck: FaceCheck? = null
    private val toneGenerator by lazy { runCatching { ToneGenerator(AudioManager.STREAM_NOTIFICATION, TONE_VOLUME) }.getOrNull() }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val detector = ShadowDetector(dropPercent = Settings().shadowDropPercent)
    private var lastWakeAt = 0L
    private var started = false
    private var startedFromUi = false
    private var monitoring = false

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val binder = LocalBinder()

    inner class LocalBinder : Binder() {
        fun getService(): LightSensorService = this@LightSensorService
    }

    data class State(
        val sensorName: String? = null,
        val sensorIsWakeUp: Boolean = false,
        val lux: Float? = null,
        val baseline: Float? = null,
        val noise: Float = 0f,
        /** Lux value below which the next reading counts as a shadow */
        val triggerLux: Float = 0f,
        val screenOn: Boolean = true,
        val lastEventAt: Long? = null,
        val eventsScreenOn: Int = 0,
        val eventsScreenOff: Int = 0,
        val lastEventWhileScreenOff: Long? = null,
        val settings: Settings = Settings(),
        val wakeCount: Int = 0,
        val cameraChecking: Boolean = false,
        val cameraConfirmed: Int = 0,
        val cameraRejected: Int = 0,
        val log: List<String> = emptyList(),
    )

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val on = intent.action == Intent.ACTION_SCREEN_ON
            _state.update { it.copy(screenOn = on) }
            log("screen ${if (on) "ON" else "OFF"}")
            // the display's own light leaks into the sensor; without a new baseline
            // its disappearance could look like a shadow and wake the screen again
            if (!on) detector.reset(warmupMs = SCREEN_OFF_WARMUP_MS)
        }
    }

    /**
     * Lets Home Assistant pause/resume detection, e.g. at night or when nobody
     * is home, via the companion app's `command_broadcast_intent` notification.
     * Only reachable while the service is running - there is nothing to pause
     * otherwise.
     */
    private val controlReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_ENABLE -> setEnabled(true, source = "broadcast")
                ACTION_DISABLE -> setEnabled(false, source = "broadcast")
            }
        }
    }

    companion object {
        const val ACTION_ENABLE = "de.j4velin.smarthome.proximityturnon.ENABLE"
        const val ACTION_DISABLE = "de.j4velin.smarthome.proximityturnon.DISABLE"

        /** Boolean extra on the start intent: the service was started from the dashboard */
        const val EXTRA_FROM_UI = "from_ui"

        private const val TAG = "ProximityTurnOn"
        private const val CHANNEL_ID = "light_sensor_service_channel"
        private const val NOTIFICATION_ID = 1
        private const val LOG_LINES = 200

        private const val SCREEN_ON_MS = 30_000L
        private const val WAKE_COOLDOWN_MS = 10_000L

        /** Shorter warm-up after the display went off, the noise estimate is already known */
        private const val SCREEN_OFF_WARMUP_MS = 2_000L

        private const val TONE_VOLUME = 80
        private const val TONE_MS = 150
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        // prefer the wake-up variant if the device has one, otherwise the default
        lightSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_LIGHT, true)
            ?: sensorManager?.getDefaultSensor(Sensor.TYPE_LIGHT)

        powerManager = getSystemService(POWER_SERVICE) as PowerManager
        partialWakeLock = powerManager?.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK, "ProximityTurnOn:Monitor"
        )
        @Suppress("DEPRECATION")
        screenWakeLock = powerManager?.newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE,
            "ProximityTurnOn:Screen"
        )

        faceCheck = FaceCheck(this, ::log)

        registerReceiver(controlReceiver, IntentFilter().apply {
            addAction(ACTION_ENABLE)
            addAction(ACTION_DISABLE)
        }, RECEIVER_EXPORTED)

        scope.launch {
            settingsFlow().collect { settings ->
                detector.dropPercent = settings.shadowDropPercent
                _state.update { it.copy(settings = settings, triggerLux = detector.triggerLux) }
                if (started) applyEnabled(settings.enabled)
            }
        }

        _state.update {
            it.copy(
                sensorName = lightSensor?.let { s -> "${s.name} (${s.vendor})" },
                sensorIsWakeUp = lightSensor?.isWakeUpSensor == true,
                screenOn = powerManager?.isInteractive != false,
            )
        }
        log(
            "light sensor: ${lightSensor?.name ?: "NONE"}, wakeUp=${lightSensor?.isWakeUpSensor}, " +
                    "maxRange=${lightSensor?.maximumRange}, power=${lightSensor?.power}mA"
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.getBooleanExtra(EXTRA_FROM_UI, false) == true) startedFromUi = true
        // keep the overlay for the lifetime of the service so a system restart
        // of the service (START_STICKY) is privileged as well
        Overlay.show(this)
        startForeground()
        started = true
        applyEnabled(_state.value.settings.enabled)
        return START_STICKY
    }

    /**
     * Android only lets a foreground service use the camera if it was started
     * while the app was visible, or while the app shows an overlay window (see
     * [Overlay]). After a boot the former is never true, so the latter is what
     * makes the camera confirmation survive reboots.
     */
    private val cameraUsable: Boolean
        get() = checkSelfPermission(android.Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED &&
                (startedFromUi || Overlay.isShown)

    /** Pauses or resumes detection. Persisted, so it survives a service restart. */
    fun setEnabled(enabled: Boolean, source: String = "dashboard") {
        log("detection ${if (enabled) "enabled" else "disabled"} ($source)")
        scope.launch { updateSettings { it.copy(enabled = enabled) } }
    }

    private fun applyEnabled(enabled: Boolean) {
        if (enabled == monitoring) return
        if (enabled) {
            startMonitoring()
        } else {
            stopMonitoring()
            faceCheck?.cancel()
            _state.update { it.copy(cameraChecking = false) }
        }
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, createNotification())
    }

    /**
     * (Re-)declares the foreground service types. The camera type can only be
     * declared once the camera permission is granted, so this is called again
     * when the camera confirmation is enabled.
     */
    private fun startForeground() {
        val types = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or
                if (cameraUsable) ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA else 0
        runCatching {
            startForeground(NOTIFICATION_ID, createNotification(), types)
        }.onFailure {
            // the system refused the camera type (background start without exemption)
            log("startForeground with camera type failed: $it")
            startForeground(NOTIFICATION_ID, createNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        }
    }

    // the partial wake lock is intentionally held for the lifetime of the service:
    // the tablet is on power 24/7 and the sensor must be processed with the screen off
    @SuppressLint("WakelockTimeout")
    private fun startMonitoring() {
        if (monitoring) return // onStartCommand may run again for an already running service
        monitoring = true
        registerReceiver(screenReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        })
        partialWakeLock?.takeIf { !it.isHeld }?.acquire()
        lightSensor?.let {
            sensorManager?.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
            log("listener registered")
        } ?: log("no light sensor - nothing to monitor")
    }

    private fun stopMonitoring() {
        if (!monitoring) return
        monitoring = false
        sensorManager?.unregisterListener(this)
        runCatching { unregisterReceiver(screenReceiver) }
        partialWakeLock?.takeIf { it.isHeld }?.release()
        log("listener unregistered")
    }

    override fun onDestroy() {
        stopMonitoring()
        runCatching { unregisterReceiver(controlReceiver) }
        Overlay.hide()
        faceCheck?.release()
        toneGenerator?.release()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder = binder

    fun setWakeOnShadow(enabled: Boolean) {
        scope.launch { updateSettings { it.copy(wakeOnShadow = enabled) } }
        log("wake on shadow ${if (enabled) "enabled" else "disabled"}")
    }

    fun setShadowDropPercent(percent: Int) {
        scope.launch { updateSettings { it.copy(shadowDropPercent = percent) } }
    }

    fun setBeepOnShadow(enabled: Boolean) {
        scope.launch { updateSettings { it.copy(beepOnShadow = enabled) } }
    }

    fun setConfirmWithCamera(enabled: Boolean) {
        scope.launch { updateSettings { it.copy(confirmWithCamera = enabled) } }
        if (enabled) startForeground() // picks up the camera type now that the permission is granted
        log("camera confirmation ${if (enabled) "enabled" else "disabled"}")
    }

    /** Manually triggers a camera check, e.g. to test detection range from the dashboard */
    fun testCameraCheck() {
        val check = faceCheck ?: return
        if (check.isRunning) return
        _state.update { it.copy(cameraChecking = true) }
        check.start { faceFound ->
            _state.update {
                it.copy(
                    cameraChecking = false,
                    cameraConfirmed = it.cameraConfirmed + if (faceFound) 1 else 0,
                    cameraRejected = it.cameraRejected + if (faceFound) 0 else 1,
                )
            }
        }
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type != Sensor.TYPE_LIGHT) return
        val lux = event.values[0]
        val shadow = detector.update(lux, SystemClock.elapsedRealtime())
        val screenOn = _state.value.screenOn
        val now = System.currentTimeMillis()
        _state.update {
            it.copy(
                lux = lux,
                baseline = detector.baseline,
                noise = detector.noise,
                triggerLux = detector.triggerLux,
                lastEventAt = now,
                eventsScreenOn = it.eventsScreenOn + if (screenOn) 1 else 0,
                eventsScreenOff = it.eventsScreenOff + if (screenOn) 0 else 1,
                lastEventWhileScreenOff = if (screenOn) it.lastEventWhileScreenOff else now,
            )
        }
        log(
            "lux=%.1f base=%.1f noise=%.1f screen=%s%s".format(
                lux, detector.baseline, detector.noise, if (screenOn) "ON" else "OFF", if (shadow) " SHADOW" else ""
            )
        )

        if (shadow && !screenOn && _state.value.settings.wakeOnShadow) onShadowDetected()
    }

    private fun onShadowDetected() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastWakeAt < WAKE_COOLDOWN_MS) return
        // this is the moment the light sensor alone would wake the screen
        if (_state.value.settings.beepOnShadow) toneGenerator?.startTone(ToneGenerator.TONE_PROP_BEEP, TONE_MS)
        val check = faceCheck
        if (!_state.value.settings.confirmWithCamera || check == null || !cameraUsable) {
            wakeScreen()
            return
        }
        if (check.isRunning) return
        lastWakeAt = now // also throttles camera checks
        _state.update { it.copy(cameraChecking = true) }
        check.start { faceFound ->
            _state.update {
                it.copy(
                    cameraChecking = false,
                    cameraConfirmed = it.cameraConfirmed + if (faceFound) 1 else 0,
                    cameraRejected = it.cameraRejected + if (faceFound) 0 else 1,
                )
            }
            if (faceFound) {
                lastWakeAt = 0 // bypass the cooldown we just set
                wakeScreen()
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        log("accuracy changed: $accuracy")
    }

    private fun wakeScreen() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastWakeAt < WAKE_COOLDOWN_MS) return
        lastWakeAt = now
        _state.update { it.copy(wakeCount = it.wakeCount + 1) }
        log("waking screen for ${SCREEN_ON_MS / 1000}s")
        screenWakeLock?.acquire(SCREEN_ON_MS)
    }

    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    private fun log(msg: String) {
        Log.i(TAG, msg) // Log.d is dropped for third-party apps on the Lenovo build
        val line = "${timeFormat.format(Date())} $msg"
        _state.update { it.copy(log = (listOf(line) + it.log).take(LOG_LINES)) }
    }

    private fun createNotificationChannel() {
        val serviceChannel = NotificationChannel(
            CHANNEL_ID,
            "Light Sensor Service Channel",
            NotificationManager.IMPORTANCE_LOW
        )
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(serviceChannel)
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Proximity Turn On")
            .setContentText(if (monitoring) "Monitoring light sensor..." else "Paused")
            .setSmallIcon(android.R.drawable.ic_menu_compass) // Placeholder icon
            .build()
    }
}
