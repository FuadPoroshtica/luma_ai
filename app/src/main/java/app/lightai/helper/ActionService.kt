package app.lightai.helper

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.InputDevice
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import androidx.annotation.RequiresApi
import app.lightai.data.HardwareKey
import app.lightai.data.HardwareKeyPress
import java.lang.ref.WeakReference

class ActionService : AccessibilityService() {
    private val mainHandler = Handler(Looper.getMainLooper())

    // Per-keycode state — supports up to a handful of distinct hardware keys
    // active at once. Cleared on key resolution (single/double/long emitted).
    private val keyState = mutableMapOf<Int, KeyState>()

    private data class KeyState(
        var downAtMillis: Long,
        var longRunnable: Runnable? = null,
        var pendingSingle: Runnable? = null,
        var longFired: Boolean = false,
    )

    override fun onServiceConnected() {
        instance = WeakReference(this)
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = WeakReference(null)
        cancelAll()
        return super.onUnbind(intent)
    }

    @RequiresApi(Build.VERSION_CODES.P)
    fun lockScreen(): Boolean = performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)

    @RequiresApi(Build.VERSION_CODES.P)
    fun showRecents(): Boolean = performGlobalAction(GLOBAL_ACTION_RECENTS)

    fun goBack(): Boolean = performGlobalAction(GLOBAL_ACTION_BACK)

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    /**
     * Filter hardware key events from the system before any app sees them.
     *
     * Returns:
     * - false: pass through (let other apps and the system handle it)
     * - true:  consume (suppress further dispatch)
     *
     * We early-return false for volume keys (user wants native behavior) and
     * for events sourced from a real keyboard (avoid swallowing typed ENTER).
     */
    override fun onKeyEvent(event: KeyEvent): Boolean {
        val code = event.keyCode
        Log.d(TAG, "onKeyEvent: code=$code action=${event.action} source=0x${event.source.toString(16)}")

        // Volume keys: never intercept
        if (code == KeyEvent.KEYCODE_VOLUME_UP ||
            code == KeyEvent.KEYCODE_VOLUME_DOWN ||
            code == KeyEvent.KEYCODE_VOLUME_MUTE
        ) {
            return false
        }

        // Real alphabetic keyboards: pass through (could be the IME pressing ENTER etc.)
        val src = event.source
        if (src and InputDevice.SOURCE_KEYBOARD == InputDevice.SOURCE_KEYBOARD &&
            event.device != null &&
            event.device.keyboardType == InputDevice.KEYBOARD_TYPE_ALPHABETIC
        ) {
            return false
        }

        val hw = HardwareKey.byKeyCode(code) ?: return false

        return when (event.action) {
            KeyEvent.ACTION_DOWN -> handleDown(hw)
            KeyEvent.ACTION_UP -> handleUp(hw)
            else -> false
        }
    }

    private fun handleDown(hw: HardwareKey): Boolean {
        val code = hw.keyCode
        val existing = keyState[code]
        if (existing != null) {
            // Auto-repeat from a held key — ignore subsequent DOWN events.
            return true
        }
        val state = KeyState(downAtMillis = System.currentTimeMillis())
        keyState[code] = state

        if (hw.supportsLong) {
            val longRunnable =
                Runnable {
                    val s = keyState[code] ?: return@Runnable
                    s.longFired = true
                    emit(hw, HardwareKeyPress.Long)
                }
            state.longRunnable = longRunnable
            mainHandler.postDelayed(longRunnable, LONG_PRESS_MS)
        }

        return true
    }

    private fun handleUp(hw: HardwareKey): Boolean {
        val code = hw.keyCode
        val state = keyState[code] ?: return false
        state.longRunnable?.let { mainHandler.removeCallbacks(it) }

        if (state.longFired) {
            // Long-press already dispatched on the timer; just clear state.
            keyState.remove(code)
            return true
        }

        val pendingSingle = state.pendingSingle
        if (pendingSingle != null) {
            // Second UP within window → DOUBLE
            mainHandler.removeCallbacks(pendingSingle)
            keyState.remove(code)
            emit(hw, HardwareKeyPress.Double)
            return true
        }

        // First UP — wait for a possible second tap before committing to SINGLE
        val singleRunnable =
            Runnable {
                keyState.remove(code)
                emit(hw, HardwareKeyPress.Single)
            }
        state.pendingSingle = singleRunnable
        mainHandler.postDelayed(singleRunnable, DOUBLE_TAP_WINDOW_MS)
        return true
    }

    private fun emit(
        key: HardwareKey,
        press: HardwareKeyPress,
    ) {
        HardwareKeyBus.emit(HardwareKeyEvent(key, press))
    }

    private fun cancelAll() {
        keyState.values.forEach { s ->
            s.longRunnable?.let { mainHandler.removeCallbacks(it) }
            s.pendingSingle?.let { mainHandler.removeCallbacks(it) }
        }
        keyState.clear()
    }

    companion object {
        private const val TAG = "LightAI-ActionSvc"
        private const val LONG_PRESS_MS = 500L
        private const val DOUBLE_TAP_WINDOW_MS = 250L

        private var instance: WeakReference<ActionService> = WeakReference(null)

        fun instance(): ActionService? = instance.get()
    }
}
