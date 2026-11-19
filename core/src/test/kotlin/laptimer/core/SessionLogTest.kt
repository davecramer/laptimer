package laptimer.core

import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class SessionLogTest {
    private val dir: File = Files.createTempDirectory("sessionlog").toFile()

    @AfterTest
    fun cleanUp() {
        dir.deleteRecursively()
    }

    private fun readAll(file: File): Pair<List<SessionLogRecord>, LogEnd?> =
        SessionLogReader(file).use { reader ->
            val records = generateSequence { reader.next() }.toList()
            records to reader.end
        }

    private fun writeSample(file: File) {
        SessionLogWriter(file).use { log ->
            log.writeMetadata(1, mapOf("device" to "RaceBox Mini S 1234", "start" to "2026-11-18T14:00:00Z"))
            log.writeDeviceBytes(2, byteArrayOf(1, 2, 3))
            log.writeDeviceBytes(3, ByteArray(20) { it.toByte() })
        }
    }

    @Test
    fun `records read back as written`() {
        val file = File(dir, "a.ltlog")
        writeSample(file)
        val (records, end) = readAll(file)
        assertEquals(LogEnd.CLEAN, end)
        assertEquals(3, records.size)
        assertEquals(
            mapOf("device" to "RaceBox Mini S 1234", "start" to "2026-11-18T14:00:00Z"),
            SessionLogReader.parseMetadata(records[0]),
        )
        assertEquals(SessionLogFormat.TYPE_DEVICE_BYTES, records[1].type)
        assertEquals(2, records[1].timeNanos)
        assertContentEquals(byteArrayOf(1, 2, 3), records[1].payload)
        assertEquals(20, records[2].payload.size)
    }

    @Test
    fun `a log cut off at any byte keeps every complete record before the cut`() {
        val full = File(dir, "full.ltlog")
        writeSample(full)
        val bytes = full.readBytes()
        val (allRecords, _) = readAll(full)
        val recordEnds = mutableListOf(5) // header length
        for (r in allRecords) recordEnds.add(recordEnds.last() + 13 + r.payload.size + 4)

        for (cut in 5 until bytes.size) {
            val file = File(dir, "cut$cut.ltlog")
            file.writeBytes(bytes.copyOf(cut))
            val (records, end) = readAll(file)
            val complete = recordEnds.count { it <= cut } - 1
            assertEquals(complete, records.size, "cut at $cut")
            val expectedEnd = if (cut in recordEnds) LogEnd.CLEAN else LogEnd.TRUNCATED
            assertEquals(expectedEnd, end, "cut at $cut")
        }
    }

    @Test
    fun `a changed byte stops reading at that record`() {
        val file = File(dir, "corrupt.ltlog")
        writeSample(file)
        // The first device-bytes record starts after the header and the
        // metadata record; flip a byte inside its payload.
        val (records, _) = readAll(file)
        val offset = 5L + 13 + records[0].payload.size + 4 + 13 + 1
        RandomAccessFile(file, "rw").use { raf ->
            raf.seek(offset)
            val b = raf.read()
            raf.seek(offset)
            raf.write(b xor 0xFF)
        }
        val (after, end) = readAll(file)
        assertEquals(1, after.size)
        assertEquals(LogEnd.CORRUPT, end)
    }

    @Test
    fun `the writer refuses to overwrite an existing file`() {
        val file = File(dir, "exists.ltlog")
        file.writeText("keep me")
        assertFailsWith<IllegalArgumentException> { SessionLogWriter(file) }
        assertEquals("keep me", file.readText())
    }

    @Test
    fun `the reader rejects files that are not session logs`() {
        val file = File(dir, "other.txt")
        file.writeText("hello world")
        assertFailsWith<IllegalArgumentException> { SessionLogReader(file) }
    }

    @Test
    fun `next keeps returning null after the end`() {
        val file = File(dir, "end.ltlog")
        writeSample(file)
        SessionLogReader(file).use { reader ->
            repeat(3) { assertNotNull(reader.next()) }
            assertNull(reader.next())
            assertNull(reader.next())
            assertEquals(LogEnd.CLEAN, reader.end)
        }
    }
}
