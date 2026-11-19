package laptimer.app

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import laptimer.core.GpsFix
import laptimer.core.TimingState

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
    /** The track laps are timed on, or null when only recording. */
    val trackName: String? = null,
    val timing: TimingState? = null,
    /**
     * SystemClock.elapsedRealtimeNanos() when [timing] was last updated by a
     * fix, so the screen can run the lap clock smoothly between updates.
     */
    val timingAtNanos: Long = 0,
)

/** Status shared between [RecordingService] and the screen. */
object RecorderState {
    internal val mutable = MutableStateFlow(RecorderStatus())
    val status: StateFlow<RecorderStatus> = mutable
}
