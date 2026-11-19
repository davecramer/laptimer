package laptimer.core

/**
 * One position fix from the GPS receiver, in SI units.
 *
 * Lap timing must use [gpsTimeOfWeekMs], the receiver's own clock. The phone
 * receives Bluetooth notifications in bursts, so the time a fix arrives at
 * the phone can be off by tens of milliseconds.
 */
data class GpsFix(
    /** GPS time of week in milliseconds. Wraps to 0 every Sunday at 00:00 GPS time. */
    val gpsTimeOfWeekMs: Long,
    /** UTC time in nanoseconds since 1970, or null until the receiver has valid date and time. */
    val utcEpochNanos: Long?,
    /** 0 = no fix, 2 = 2D, 3 = 3D (other values follow u-blox NAV-PVT). */
    val fixType: Int,
    /** The receiver's own judgment that the fix is usable (u-blox gnssFixOK). */
    val fixOk: Boolean,
    val satellites: Int,
    val latitudeDeg: Double,
    val longitudeDeg: Double,
    /** False when the receiver flags the latitude and longitude as invalid. */
    val positionValid: Boolean,
    val altitudeMslM: Double,
    val horizontalAccuracyM: Double,
    val verticalAccuracyM: Double,
    val speedMps: Double,
    val speedAccuracyMps: Double,
    /** Direction of motion, degrees clockwise from true north. */
    val headingDeg: Double,
    val headingAccuracyDeg: Double,
    val pdop: Double,
    /** X is forward/back, Y is right/left, Z is up/down. */
    val accelXG: Double,
    val accelYG: Double,
    val accelZG: Double,
    /** X is roll, Y is pitch, Z is yaw. */
    val rotationXDps: Double,
    val rotationYDps: Double,
    val rotationZDps: Double,
    /**
     * Battery byte as the device sent it. Its meaning depends on the model:
     * see [RaceBox.miniBatteryPercent].
     */
    val batteryRaw: Int,
)
