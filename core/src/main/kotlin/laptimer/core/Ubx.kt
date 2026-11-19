package laptimer.core

/**
 * One UBX frame: sync (B5 62), class, id, 16-bit little-endian payload
 * length, payload, and a two-byte Fletcher checksum over class through
 * payload. RaceBox devices use this framing for every message.
 */
class UbxFrame(val messageClass: Int, val messageId: Int, val payload: ByteArray) {
    override fun toString(): String =
        "UbxFrame(class=0x%02X, id=0x%02X, len=%d)"
            .format(messageClass, messageId, payload.size)
}

object Ubx {
    const val SYNC_1: Byte = 0xB5.toByte()
    const val SYNC_2: Byte = 0x62

    /** Sync, class, id and length come before the payload. */
    const val HEADER_LENGTH = 6
    const val CHECKSUM_LENGTH = 2

    /**
     * Computes the UBX checksum over [length] bytes of [data] starting at
     * [offset]. Returns ck_a in the low byte and ck_b in the next byte.
     */
    fun checksum(data: ByteArray, offset: Int, length: Int): Int {
        var a = 0
        var b = 0
        for (i in offset until offset + length) {
            a = (a + (data[i].toInt() and 0xFF)) and 0xFF
            b = (b + a) and 0xFF
        }
        return a or (b shl 8)
    }

    /** Builds a complete frame, including sync bytes and checksum. */
    fun encode(messageClass: Int, messageId: Int, payload: ByteArray): ByteArray {
        val out = ByteArray(HEADER_LENGTH + payload.size + CHECKSUM_LENGTH)
        out[0] = SYNC_1
        out[1] = SYNC_2
        out[2] = messageClass.toByte()
        out[3] = messageId.toByte()
        out[4] = (payload.size and 0xFF).toByte()
        out[5] = ((payload.size shr 8) and 0xFF).toByte()
        payload.copyInto(out, HEADER_LENGTH)
        val ck = checksum(out, 2, 4 + payload.size)
        out[out.size - 2] = (ck and 0xFF).toByte()
        out[out.size - 1] = (ck shr 8).toByte()
        return out
    }
}

/**
 * Reassembles UBX frames from a byte stream that arrives in arbitrary
 * pieces, such as Bluetooth LE notifications.
 *
 * A frame can be split across notifications, and one notification can hold
 * several frames, so the parser keeps unconsumed bytes between calls. Bytes
 * that are not part of a frame with a valid checksum are skipped, and the
 * parser searches for the next sync pair. One corrupted frame must not stop
 * the frames after it from being read.
 */
class UbxStreamParser(private val maxPayloadLength: Int = 4096) {
    private var buffer = ByteArray(1024)
    private var size = 0

    /** Frames whose length was plausible but whose checksum did not match. */
    var checksumErrors: Long = 0
        private set

    /** Bytes skipped because they were not part of a valid frame. */
    var skippedBytes: Long = 0
        private set

    fun feed(data: ByteArray, offset: Int = 0, length: Int = data.size - offset): List<UbxFrame> {
        append(data, offset, length)
        val frames = ArrayList<UbxFrame>()
        var pos = 0
        while (true) {
            val start = findSync(pos)
            if (start < 0) {
                // A lone B5 at the end may be the first half of a sync pair
                // whose second byte arrives in the next notification.
                val keep = if (size > pos && buffer[size - 1] == Ubx.SYNC_1) 1 else 0
                skippedBytes += size - pos - keep
                pos = size - keep
                break
            }
            skippedBytes += start - pos
            pos = start
            if (size - pos < Ubx.HEADER_LENGTH) break

            val payloadLength = (buffer[pos + 4].toInt() and 0xFF) or
                ((buffer[pos + 5].toInt() and 0xFF) shl 8)
            if (payloadLength > maxPayloadLength) {
                // These sync bytes were data, not a frame start. Waiting
                // for a giant frame here would stall the stream.
                skippedBytes += 1
                pos += 1
                continue
            }
            val frameLength = Ubx.HEADER_LENGTH + payloadLength + Ubx.CHECKSUM_LENGTH
            if (size - pos < frameLength) break

            val expected = Ubx.checksum(buffer, pos + 2, 4 + payloadLength)
            val actual = (buffer[pos + frameLength - 2].toInt() and 0xFF) or
                ((buffer[pos + frameLength - 1].toInt() and 0xFF) shl 8)
            if (expected != actual) {
                checksumErrors += 1
                skippedBytes += 1
                pos += 1
                continue
            }
            val payload = buffer.copyOfRange(
                pos + Ubx.HEADER_LENGTH,
                pos + Ubx.HEADER_LENGTH + payloadLength,
            )
            frames.add(
                UbxFrame(buffer[pos + 2].toInt() and 0xFF, buffer[pos + 3].toInt() and 0xFF, payload),
            )
            pos += frameLength
        }
        compact(pos)
        return frames
    }

    private fun findSync(from: Int): Int {
        for (i in from until size - 1) {
            if (buffer[i] == Ubx.SYNC_1 && buffer[i + 1] == Ubx.SYNC_2) return i
        }
        return -1
    }

    private fun append(data: ByteArray, offset: Int, length: Int) {
        if (size + length > buffer.size) {
            buffer = buffer.copyOf(maxOf(buffer.size * 2, size + length))
        }
        data.copyInto(buffer, size, offset, offset + length)
        size += length
    }

    private fun compact(consumed: Int) {
        if (consumed == 0) return
        buffer.copyInto(buffer, 0, consumed, size)
        size -= consumed
    }
}
