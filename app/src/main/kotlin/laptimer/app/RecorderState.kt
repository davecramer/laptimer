package laptimer.app

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import laptimer.core.GpsFix

enum class Phase { IDLE, CONNECTING, STREAMING, RECONNECTING }

data class RecorderStatus(
    val phase: Phase = Phase.IDLE,
    val deviceName: String? = null,
    val sessionPath: String? = null,
    val lastFix: GpsFix? = null,
    /** Fix rate measured from the receiver's clock over the last two seconds. */
    val fixRateHz: Double? = null,
    val fixes: Long = 0,
    val checksumErrors: Long = 0,
    val bytesRecorded: Long = 0,
    val disconnects: Int = 0,
    val lastDisconnectReason: String? = null,
    val error: String? = null,
)

/** Status shared between [RecordingService] and the screen. */
object RecorderState {
    internal val mutable = MutableStateFlow(RecorderStatus())
    val status: StateFlow<RecorderStatus> = mutable
}
