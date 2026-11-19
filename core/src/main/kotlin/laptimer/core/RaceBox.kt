package laptimer.core

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.DateTimeException
import java.time.LocalDateTime
import java.time.ZoneOffset

/**
 * RaceBox Mini / Mini S / Micro Bluetooth LE protocol.
 *
 * The devices expose the Nordic UART Service. The phone subscribes to
 * notifications on [TX_CHARACTERISTIC] and writes commands to
 * [RX_CHARACTERISTIC]. Live data starts streaming as soon as notifications
 * are enabled.
 *
 * The payload layout follows the RaceBox BLE Protocol document, revision 8.
 * Three independent open-source clients agree on the offsets used here.
 */
object RaceBox {
    const val SERVICE_UUID = "6E400001-B5A3-F393-E0A9-E50E24DCCA9E"
    const val RX_CHARACTERISTIC = "6E400002-B5A3-F393-E0A9-E50E24DCCA9E"
    const val TX_CHARACTERISTIC = "6E400003-B5A3-F393-E0A9-E50E24DCCA9E"

    const val MESSAGE_CLASS = 0xFF
    const val ID_LIVE_DATA = 0x01

    /** Data downloaded from the device's internal memory; same payload as live data. */
    const val ID_HISTORY_DATA = 0x21

    const val DATA_PAYLOAD_LENGTH = 80

    // Validity flags at payload offset 11 (u-blox NAV-PVT "valid").
    private const val VALID_DATE = 0x01
    private const val VALID_TIME = 0x02

    /**
     * Decodes a live or history data message. Returns null for any other
     * message, or for a payload shorter than the documented layout.
     */
    fun decodeFix(frame: UbxFrame): GpsFix? {
        if (frame.messageClass != MESSAGE_CLASS) return null
        if (frame.messageId != ID_LIVE_DATA && frame.messageId != ID_HISTORY_DATA) return null
        if (frame.payload.size < DATA_PAYLOAD_LENGTH) return null

        val p = ByteBuffer.wrap(frame.payload).order(ByteOrder.LITTLE_ENDIAN)
        val validity = p.u8(11)

        return GpsFix(
            gpsTimeOfWeekMs = p.u32(0),
            utcEpochNanos = utcNanos(p, validity),
            fixType = p.u8(20),
            fixOk = (p.u8(21) and 0x01) != 0,
            satellites = p.u8(23),
            longitudeDeg = p.getInt(24) / 1e7,
            latitudeDeg = p.getInt(28) / 1e7,
            // Bit 0 of the lat/lon flags marks the position as invalid.
            positionValid = (p.u8(66) and 0x01) == 0,
            altitudeMslM = p.getInt(36) / 1000.0,
            horizontalAccuracyM = p.u32(40) / 1000.0,
            verticalAccuracyM = p.u32(44) / 1000.0,
            speedMps = p.getInt(48) / 1000.0,
            headingDeg = p.getInt(52) / 1e5,
            speedAccuracyMps = p.u32(56) / 1000.0,
            headingAccuracyDeg = p.u32(60) / 1e5,
            pdop = p.u16(64) / 100.0,
            batteryRaw = p.u8(67),
            accelXG = p.getShort(68) / 1000.0,
            accelYG = p.getShort(70) / 1000.0,
            accelZG = p.getShort(72) / 1000.0,
            rotationXDps = p.getShort(74) / 100.0,
            rotationYDps = p.getShort(76) / 100.0,
            rotationZDps = p.getShort(78) / 100.0,
        )
    }

    /**
     * Builds an 80-byte data payload from [fix], the reverse of
     * [decodeFix]. Used by the RaceBox emulator and by tests.
     *
     * The device sends the UTC time as whole seconds plus a signed
     * nanosecond correction; this rounds to the nearest second, so the
     * correction is within half a second either way, as on the device.
     */
    fun encodeFix(fix: GpsFix): ByteArray {
        val b = ByteBuffer.allocate(DATA_PAYLOAD_LENGTH).order(ByteOrder.LITTLE_ENDIAN)
        b.putInt(0, fix.gpsTimeOfWeekMs.toInt())
        val utc = fix.utcEpochNanos
        if (utc != null) {
            val seconds = Math.floorDiv(utc + 500_000_000L, 1_000_000_000L)
            val t = LocalDateTime.ofEpochSecond(seconds, 0, ZoneOffset.UTC)
            b.putShort(4, t.year.toShort())
            b.put(6, t.monthValue.toByte())
            b.put(7, t.dayOfMonth.toByte())
            b.put(8, t.hour.toByte())
            b.put(9, t.minute.toByte())
            b.put(10, t.second.toByte())
            // Date valid, time valid, fully resolved.
            b.put(11, 0x07)
            // Time accuracy in nanoseconds: a locked receiver is within tens of ns.
            b.putInt(12, 25)
            b.putInt(16, (utc - seconds * 1_000_000_000L).toInt())
        }
        b.put(20, fix.fixType.toByte())
        b.put(21, if (fix.fixOk) 0x01 else 0x00)
        b.put(23, fix.satellites.toByte())
        b.putInt(24, Math.round(fix.longitudeDeg * 1e7).toInt())
        b.putInt(28, Math.round(fix.latitudeDeg * 1e7).toInt())
        // Height above the ellipsoid; the decoder does not read it, so use MSL.
        b.putInt(32, Math.round(fix.altitudeMslM * 1000).toInt())
        b.putInt(36, Math.round(fix.altitudeMslM * 1000).toInt())
        b.putInt(40, Math.round(fix.horizontalAccuracyM * 1000).toInt())
        b.putInt(44, Math.round(fix.verticalAccuracyM * 1000).toInt())
        b.putInt(48, Math.round(fix.speedMps * 1000).toInt())
        b.putInt(52, Math.round(fix.headingDeg * 1e5).toInt())
        b.putInt(56, Math.round(fix.speedAccuracyMps * 1000).toInt())
        b.putInt(60, Math.round(fix.headingAccuracyDeg * 1e5).toInt())
        b.putShort(64, Math.round(fix.pdop * 100).toInt().toShort())
        b.put(66, if (fix.positionValid) 0x00 else 0x01)
        b.put(67, fix.batteryRaw.toByte())
        b.putShort(68, Math.round(fix.accelXG * 1000).toInt().toShort())
        b.putShort(70, Math.round(fix.accelYG * 1000).toInt().toShort())
        b.putShort(72, Math.round(fix.accelZG * 1000).toInt().toShort())
        b.putShort(74, Math.round(fix.rotationXDps * 100).toInt().toShort())
        b.putShort(76, Math.round(fix.rotationYDps * 100).toInt().toShort())
        b.putShort(78, Math.round(fix.rotationZDps * 100).toInt().toShort())
        return b.array()
    }

    /** A complete UBX frame carrying [fix] as live data. */
    fun encodeFrame(fix: GpsFix): ByteArray = Ubx.encode(MESSAGE_CLASS, ID_LIVE_DATA, encodeFix(fix))

    /**
     * Battery percentage for the Mini and Mini S. On the Micro the same byte
     * is the input voltage times ten, so this value is meaningless there.
     */
    fun miniBatteryPercent(batteryRaw: Int): Int = batteryRaw and 0x7F

    /** Mini and Mini S only: true while charging. */
    fun miniIsCharging(batteryRaw: Int): Boolean = (batteryRaw and 0x80) != 0

    /**
     * The date fields are rounded to whole seconds, and the signed
     * nanosecond field corrects them. The correction can be negative, so it
     * is applied to the total rather than treated as a fraction.
     */
    private fun utcNanos(p: ByteBuffer, validity: Int): Long? {
        if ((validity and VALID_DATE) == 0 || (validity and VALID_TIME) == 0) return null
        val seconds = try {
            LocalDateTime.of(
                p.u16(4), p.u8(6), p.u8(7), p.u8(8), p.u8(9), p.u8(10),
            ).toEpochSecond(ZoneOffset.UTC)
        } catch (_: DateTimeException) {
            return null
        }
        return seconds * 1_000_000_000L + p.getInt(16)
    }

    private fun ByteBuffer.u8(index: Int): Int = get(index).toInt() and 0xFF
    private fun ByteBuffer.u16(index: Int): Int = getShort(index).toInt() and 0xFFFF
    private fun ByteBuffer.u32(index: Int): Long = getInt(index).toLong() and 0xFFFFFFFFL
}
