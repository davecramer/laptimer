package laptimer.core

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.CRC32

/**
 * File format for a recorded session.
 *
 * The log stores the raw bytes of every Bluetooth notification, not decoded
 * fixes. A bug found later in the parser or decoder can then be fixed and the
 * old sessions replayed through the corrected code.
 *
 * Layout (all integers big-endian):
 *
 *     header:  magic "LTLG", u8 version
 *     record:  u8 type, i64 time (ns), i32 length, payload, u32 CRC-32
 *
 * The CRC covers type, time, length and payload. A crash can leave a partly
 * written record at the end of the file. The reader stops at the first record
 * that is short or fails its CRC and reports it, so every record before it
 * is still usable.
 */
object SessionLogFormat {
    val MAGIC = byteArrayOf('L'.code.toByte(), 'T'.code.toByte(), 'L'.code.toByte(), 'G'.code.toByte())
    const val VERSION = 1

    /** Payload is UTF-8 "key=value" lines describing the session. */
    const val TYPE_METADATA = 1

    /** Payload is one Bluetooth notification exactly as received. */
    const val TYPE_DEVICE_BYTES = 2

    /** Refuse lengths above this when reading, so a corrupt length cannot exhaust memory. */
    const val MAX_PAYLOAD_LENGTH = 1 shl 20
}

class SessionLogRecord(val type: Int, val timeNanos: Long, val payload: ByteArray)

/**
 * Writes a session log.
 *
 * Data is forced to storage at least every [syncIntervalNanos], so a crash
 * or a killed process loses at most that much. Syncing on every record would
 * mean about 25 storage flushes per second at 25 Hz.
 */
class SessionLogWriter(
    file: File,
    private val syncIntervalNanos: Long = 1_000_000_000L,
    private val clock: () -> Long = System::nanoTime,
) : Closeable {
    private val fileStream: FileOutputStream
    private val out: DataOutputStream
    private val crc = CRC32()
    private var lastSyncNanos: Long
    private var closed = false

    init {
        // Never overwrite an existing session by accident.
        require(file.createNewFile()) { "Session log already exists: $file" }
        fileStream = FileOutputStream(file)
        out = DataOutputStream(BufferedOutputStream(fileStream, 64 * 1024))
        out.write(SessionLogFormat.MAGIC)
        out.writeByte(SessionLogFormat.VERSION)
        lastSyncNanos = clock()
        sync()
    }

    /** Keys and values must not contain '=' (keys) or line breaks (either). */
    fun writeMetadata(timeNanos: Long, entries: Map<String, String>) {
        val text = buildString {
            for ((key, value) in entries) {
                require('=' !in key && '\n' !in key && '\n' !in value) {
                    "Metadata key or value contains '=' or a line break: $key"
                }
                append(key).append('=').append(value).append('\n')
            }
        }
        writeRecord(SessionLogFormat.TYPE_METADATA, timeNanos, text.toByteArray(Charsets.UTF_8))
    }

    fun writeDeviceBytes(timeNanos: Long, data: ByteArray, offset: Int = 0, length: Int = data.size - offset) {
        writeRecord(SessionLogFormat.TYPE_DEVICE_BYTES, timeNanos, data, offset, length)
    }

    private fun writeRecord(type: Int, timeNanos: Long, data: ByteArray, offset: Int = 0, length: Int = data.size) {
        check(!closed) { "Session log is closed" }
        crc.reset()
        val header = ByteArray(13)
        header[0] = type.toByte()
        for (i in 0 until 8) header[1 + i] = (timeNanos ushr (56 - 8 * i)).toByte()
        for (i in 0 until 4) header[9 + i] = (length ushr (24 - 8 * i)).toByte()
        crc.update(header)
        crc.update(data, offset, length)
        out.write(header)
        out.write(data, offset, length)
        out.writeInt(crc.value.toInt())
        if (clock() - lastSyncNanos >= syncIntervalNanos) sync()
    }

    /** Pushes buffered records to the storage device. */
    fun sync() {
        out.flush()
        fileStream.fd.sync()
        lastSyncNanos = clock()
    }

    override fun close() {
        if (closed) return
        sync()
        out.close()
        closed = true
    }
}

/** How a session log ended. */
enum class LogEnd {
    /** The file ends exactly after a complete record. */
    CLEAN,

    /** The last record is incomplete, as after a crash during a write. */
    TRUNCATED,

    /** A record failed its CRC or had an impossible length. */
    CORRUPT,
}

/** Reads a session log one record at a time. */
class SessionLogReader(file: File) : Closeable {
    private val input = DataInputStream(BufferedInputStream(FileInputStream(file), 64 * 1024))
    private val crc = CRC32()

    /** Set once [next] has returned null. */
    var end: LogEnd? = null
        private set

    init {
        val magic = ByteArray(4)
        try {
            input.readFully(magic)
        } catch (e: EOFException) {
            input.close()
            throw IllegalArgumentException("Not a session log (too short): $file", e)
        }
        if (!magic.contentEquals(SessionLogFormat.MAGIC)) {
            input.close()
            throw IllegalArgumentException("Not a session log (bad magic): $file")
        }
        val version = input.read()
        if (version != SessionLogFormat.VERSION) {
            input.close()
            throw IllegalArgumentException("Unsupported session log version $version: $file")
        }
    }

    /** Returns the next record, or null at the end of the usable data. */
    fun next(): SessionLogRecord? {
        if (end != null) return null
        val header = ByteArray(13)
        val headerRead = input.readNBytes(header, 0, header.size)
        if (headerRead == 0) return finish(LogEnd.CLEAN)
        if (headerRead < header.size) return finish(LogEnd.TRUNCATED)

        val type = header[0].toInt() and 0xFF
        var time = 0L
        for (i in 0 until 8) time = (time shl 8) or (header[1 + i].toLong() and 0xFF)
        var length = 0
        for (i in 0 until 4) length = (length shl 8) or (header[9 + i].toInt() and 0xFF)
        if (length < 0 || length > SessionLogFormat.MAX_PAYLOAD_LENGTH) return finish(LogEnd.CORRUPT)

        val payload = ByteArray(length)
        if (input.readNBytes(payload, 0, length) < length) return finish(LogEnd.TRUNCATED)
        val crcBytes = ByteArray(4)
        if (input.readNBytes(crcBytes, 0, 4) < 4) return finish(LogEnd.TRUNCATED)
        var stored = 0L
        for (b in crcBytes) stored = (stored shl 8) or (b.toLong() and 0xFF)

        crc.reset()
        crc.update(header)
        crc.update(payload)
        if (crc.value != stored) return finish(LogEnd.CORRUPT)
        return SessionLogRecord(type, time, payload)
    }

    private fun finish(how: LogEnd): SessionLogRecord? {
        end = how
        return null
    }

    override fun close() = input.close()

    companion object {
        /** Parses the "key=value" lines of a metadata record. */
        fun parseMetadata(record: SessionLogRecord): Map<String, String> {
            require(record.type == SessionLogFormat.TYPE_METADATA) { "Not a metadata record" }
            return record.payload.toString(Charsets.UTF_8)
                .lineSequence()
                .filter { it.isNotEmpty() }
                .associate { line ->
                    val eq = line.indexOf('=')
                    if (eq < 0) line to "" else line.substring(0, eq) to line.substring(eq + 1)
                }
        }
    }
}
