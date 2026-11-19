package laptimer.raceboxemulator

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import laptimer.core.DriverProfile
import laptimer.core.DrivingSimulator
import laptimer.core.GpsFix
import laptimer.core.LatLon
import laptimer.core.RaceBoxFeed

data class DriveStatus(
    val trackName: String? = null,
    val trackLengthM: Double = 0.0,
    val driving: Boolean = false,
    val lap: Int = 0,
    /** The simulator's exact time for the last finished lap, measured at the loop's start point. */
    val lastLapS: Double? = null,
    val speedKmh: Double = 0.0,
    val fixLost: Boolean = false,
    val position: LatLon? = null,
)

/** A named way to drive. */
data class Pace(val label: String, val profile: DriverProfile)

val PACES = listOf(
    Pace("Road car", DriverProfile()),
    Pace("Road car, identical laps", DriverProfile(lapVariation = 0.0)),
    Pace("Race car", DriverProfile(topSpeedMps = 75.0, lateralG = 1.6, accelG = 0.7, brakeG = 1.8)),
)

/**
 * What the simulated car is doing. The screen changes it and the
 * Bluetooth thread reads fixes from it, so every method is synchronized.
 */
class DriveController {
    private var loop: List<LatLon>? = null
    private var trackName: String? = null
    private var pace = PACES[0]
    private var sim: DrivingSimulator? = null
    private var driveStartMs: Long? = null
    private var fixLostUntilMs = 0L
    private var lastStatusMs = 0L

    private val _status = MutableStateFlow(DriveStatus())
    val status: StateFlow<DriveStatus> = _status

    val currentPace: Pace @Synchronized get() = pace

    @Synchronized
    fun setTrack(name: String, points: List<LatLon>) {
        loop = points
        trackName = name
        rebuild()
    }

    @Synchronized
    fun setPace(p: Pace) {
        pace = p
        rebuild()
    }

    private fun rebuild() {
        val l = loop ?: return
        sim = DrivingSimulator(l, pace.profile, seed = System.currentTimeMillis())
        driveStartMs = null
        publish(null, System.currentTimeMillis())
    }

    @Synchronized
    fun drive() {
        if (sim != null && driveStartMs == null) driveStartMs = System.currentTimeMillis()
        publish(null, System.currentTimeMillis())
    }

    /** Stops at the loop's start point. Driving again starts a fresh first lap. */
    @Synchronized
    fun park() {
        val l = loop
        // A new simulator, so the next run's lap times are fresh too.
        if (l != null) sim = DrivingSimulator(l, pace.profile, seed = System.currentTimeMillis())
        driveStartMs = null
        publish(null, System.currentTimeMillis())
    }

    /** The receiver loses its fix for [seconds], as under a bridge. */
    @Synchronized
    fun loseFix(seconds: Int) {
        fixLostUntilMs = System.currentTimeMillis() + seconds * 1000L
    }

    /** Called by the Bluetooth thread 25 times a second. */
    @Synchronized
    fun fixAt(unixMs: Long): GpsFix {
        val s = sim
        val start = driveStartMs
        val fixOk = unixMs >= fixLostUntilMs
        if (s == null) {
            // No track yet: a receiver indoors, still searching.
            return RaceBoxFeed.fix(EMPTY_STATE, unixMs, fixOk = false)
        }
        val state = if (start == null) s.parkedState() else s.stateAt((unixMs - start).coerceAtLeast(0) / 1000.0)
        if (unixMs - lastStatusMs >= 250) publish(state, unixMs)
        return RaceBoxFeed.fix(state, unixMs, fixOk)
    }

    private fun publish(state: laptimer.core.DriveState?, unixMs: Long) {
        lastStatusMs = unixMs
        val s = sim
        val lap = state?.lap ?: 0
        _status.value = DriveStatus(
            trackName = trackName,
            trackLengthM = s?.lengthM ?: 0.0,
            driving = driveStartMs != null,
            lap = lap,
            lastLapS = if (driveStartMs != null && lap > 0) s?.lapTimeS(lap - 1) else null,
            speedKmh = (state?.speedMps ?: 0.0) * 3.6,
            fixLost = unixMs < fixLostUntilMs,
            position = state?.position,
        )
    }

    companion object {
        private val EMPTY_STATE = laptimer.core.DriveState(LatLon(0.0, 0.0), 0.0, 0.0, 0.0, 0.0, 0.0, 0, 0.0)
    }
}
