package de.j4velin.smarthome.proximityturnon

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Restarts the service after a reboot or an app update if it was running
 * before (see [Settings.autoStart]).
 *
 * The overlay window is shown first: with it, Android treats the start as
 * privileged (see [Overlay]) and the service may declare and use the camera.
 * Without the overlay permission the service still starts, but only the light
 * sensor path works until it is started from the dashboard again.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                if (context.settingsFlow().first().autoStart) {
                    Log.i(TAG, "${intent.action}: starting service")
                    withContext(Dispatchers.Main) {
                        Overlay.show(context) {
                            context.startForegroundService(Intent(context, LightSensorService::class.java))
                            pending.finish()
                        }
                    }
                } else {
                    Log.i(TAG, "${intent.action}: service was not running, not starting")
                    pending.finish()
                }
            } catch (e: Exception) {
                Log.i(TAG, "failed to start service: $e")
                pending.finish()
            }
        }
    }

    private companion object {
        const val TAG = "ProximityTurnOn"
    }
}
