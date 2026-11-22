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
    /** The OBD adapter's state, or null when the session doesn't use one. */
    val obd: ObdStatus? = null,
    /** The CAN profile the adapter reads with, or null for standard OBD-II. */
    val canProfile: laptimer.core.CanProfile? = null,
    /** True when the track was found from the car's GPS position rather than picked. */
    val trackFoundByGps: Boolean = false,
    /** While looking for the track by GPS: what is happening, for the screen. */
    val trackSearch: String? = null,
    /** Other saved tracks near the car, such as other layouts of the same circuit, to switch to. */
    val otherTracks: List<TrackChoice> = emptyList(),
)

data class TrackChoice(val id: String, val name: String, val distanceM: Double)

/** Status shared between [RecordingService] and the screen. */
object RecorderState {
    internal val mutable = MutableStateFlow(RecorderStatus())
    val status: StateFlow<RecorderStatus> = mutable
}
