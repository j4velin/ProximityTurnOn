package de.j4velin.smarthome.proximityturnon

import android.content.Context
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.ViewTreeObserver
import android.view.WindowManager

/**
 * A 1x1 px transparent, non-touchable overlay window.
 *
 * Since Android 15 holding the "display over other apps" permission alone no
 * longer exempts an app from the foreground service restrictions - it has to
 * have a visible overlay window at the moment the service is started. Without
 * that, a service started at boot may neither declare the camera type nor use
 * the camera (while-in-use rule). Showing this window before starting the
 * service satisfies the requirement; it is invisible and costs nothing.
 */
object Overlay {
    private const val TAG = "ProximityTurnOn"
    private const val VISIBLE_DELAY_MS = 500L

    private var windowManager: WindowManager? = null
    private var view: View? = null

    val isShown: Boolean get() = view != null

    /**
     * Must be called on the main thread. Does nothing without the overlay
     * permission. [onVisible] runs once the window has been drawn and the
     * system counts the app as having overlay UI - only then does a service
     * start get the exemption - or immediately if no overlay can be shown.
     */
    fun show(context: Context, onVisible: () -> Unit = {}) {
        if (view != null) {
            onVisible()
            return
        }
        if (!android.provider.Settings.canDrawOverlays(context)) {
            Log.i(TAG, "overlay permission not granted, not showing overlay")
            onVisible()
            return
        }
        val display = context.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
        val windowContext = context.applicationContext
            .createDisplayContext(display)
            .createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
        val wm = windowContext.getSystemService(WindowManager::class.java)
        val params = WindowManager.LayoutParams(
            1, 1,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START }
        val v = View(windowContext)
        runCatching { wm.addView(v, params) }
            .onSuccess {
                windowManager = wm
                view = v
                // the window manager reports the overlay as visible after the first
                // frame; give it a moment beyond that before relying on it
                v.viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
                    override fun onPreDraw(): Boolean {
                        v.viewTreeObserver.removeOnPreDrawListener(this)
                        v.postDelayed({
                            Log.i(TAG, "overlay shown")
                            onVisible()
                        }, VISIBLE_DELAY_MS)
                        return true
                    }
                })
            }
            .onFailure {
                Log.i(TAG, "overlay failed: $it")
                onVisible()
            }
    }

    fun hide() {
        val v = view ?: return
        runCatching { windowManager?.removeView(v) }
        view = null
        windowManager = null
    }
}
