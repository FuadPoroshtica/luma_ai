package app.lightai.helper

import app.lightai.data.HardwareKey
import app.lightai.data.HardwareKeyPress
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

data class HardwareKeyEvent(
    val key: HardwareKey,
    val press: HardwareKeyPress,
)

// Singleton hot stream: ActionService.onKeyEvent posts; HomeFragment collects.
// extraBufferCapacity > 0 + DROP_OLDEST lets us emit without suspending the
// accessibility callback (which must return quickly).
object HardwareKeyBus {
    private val _events =
        MutableSharedFlow<HardwareKeyEvent>(
            replay = 0,
            extraBufferCapacity = 8,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )
    val events: SharedFlow<HardwareKeyEvent> = _events.asSharedFlow()

    fun emit(event: HardwareKeyEvent) {
        _events.tryEmit(event)
    }
}
