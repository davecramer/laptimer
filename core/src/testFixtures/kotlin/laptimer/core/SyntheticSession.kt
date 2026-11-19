package laptimer.core

import java.io.File
import java.time.LocalDateTime
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Writes a session log of a car driving laps of a circle at constant speed,
 * as a RaceBox would send it.
 *
 * Frames are split into 20-byte pieces, the payload size of a Bluetooth LE
 * notification at the default MTU, so replaying the file exercises frame
 * reassembly the same way a real connection does.
 */
object SyntheticSession {
    const val NOTIFICATION_SIZE = 20

    fun write(
        file: File,
        laps: Int,
        radiusM: Double = 200.0,
        speedMps: Double = 40.0,
        rateHz: Int = 25,
        centerLatDeg: Double = 28.0,
        centerLonDeg: Double = -81.0,
        start: LocalDateTime = LocalDateTime.of(2026, 11, 18, 14, 0, 0),
    ): Int {
        val lapSeconds = 2 * PI * radiusM / speedMps
        val count = (laps * lapSeconds * rateHz).toInt()
        val metersPerDegLat = 111_320.0
        val metersPerDegLon = metersPerDegLat * cos(Math.toRadians(centerLatDeg))
        // Wednesday 14:00 UTC is 3 days and 14 hours into the GPS week,
        // plus 18 leap seconds.
        val weekStartMs = ((3L * 24 + 14) * 3600 + 18) * 1000

        SessionLogWriter(file).use { log ->
            log.writeMetadata(0, mapOf("device" to "synthetic", "rateHz" to rateHz.toString()))
            for (i in 0 until count) {
                val tMs = i * 1000L / rateHz
                val angle = speedMps * (tMs / 1000.0) / radiusM
                val north = radiusM * cos(angle)
                val east = radiusM * sin(angle)
                // The angle grows from north toward east, which is clockwise
                // seen from above, so the direction of travel is 90 degrees
                // ahead of the angle.
                val headingDeg = (Math.toDegrees(angle) + 90) % 360
                // The device sends whole seconds plus a nanosecond correction.
                val utc = start.plusNanos(tMs * 1_000_000)
                val payload = SyntheticRaceBox.payload(
                    gpsTimeOfWeekMs = weekStartMs + tMs,
                    utc = utc.withNano(0),
                    nanoCorrection = utc.nano,
                    latitudeDeg = centerLatDeg + north / metersPerDegLat,
                    longitudeDeg = centerLonDeg + east / metersPerDegLon,
                    speedMps = speedMps,
                    headingDeg = headingDeg,
                )
                val frame = SyntheticRaceBox.frame(payload)
                var off = 0
                while (off < frame.size) {
                    val len = minOf(NOTIFICATION_SIZE, frame.size - off)
                    log.writeDeviceBytes(tMs * 1_000_000, frame, off, len)
                    off += len
                }
            }
        }
        return count
    }
}
