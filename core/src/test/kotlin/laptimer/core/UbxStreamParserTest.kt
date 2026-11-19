package laptimer.core

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class UbxStreamParserTest {
    private val frame = SyntheticRaceBox.PROTOCOL_DOC_FRAME

    @Test
    fun `a frame split at any point is reassembled`() {
        for (split in 1 until frame.size) {
            val parser = UbxStreamParser()
            val first = parser.feed(frame, 0, split)
            val second = parser.feed(frame, split, frame.size - split)
            assertEquals(0, first.size, "split at $split")
            assertEquals(1, second.size, "split at $split")
            assertEquals(0, parser.skippedBytes, "split at $split")
        }
    }

    @Test
    fun `a frame fed one byte at a time is reassembled`() {
        val parser = UbxStreamParser()
        val frames = frame.indices.flatMap { parser.feed(frame, it, 1) }
        assertEquals(1, frames.size)
        assertEquals(0, parser.checksumErrors)
    }

    @Test
    fun `several frames in one notification are all returned`() {
        val frames = UbxStreamParser().feed(frame + frame + frame)
        assertEquals(3, frames.size)
    }

    @Test
    fun `bytes before a frame are skipped and counted`() {
        val parser = UbxStreamParser()
        val frames = parser.feed(byteArrayOf(1, 2, 3, 0xB5.toByte()) + frame)
        assertEquals(1, frames.size)
        assertEquals(4, parser.skippedBytes)
    }

    @Test
    fun `a corrupted frame is rejected and the next frame is still read`() {
        val bad = frame.copyOf()
        bad[30] = (bad[30] + 1).toByte()
        val parser = UbxStreamParser()
        val frames = parser.feed(bad + frame)
        assertEquals(1, frames.size)
        assertEquals(1, parser.checksumErrors)
        assertContentEquals(frame.copyOfRange(6, 86), frames[0].payload)
    }

    @Test
    fun `sync bytes followed by an impossible length do not stall the stream`() {
        // B5 62 inside data, with a length field of 0xFFFF.
        val fake = byteArrayOf(0xB5.toByte(), 0x62, 0xFF.toByte(), 0x01, 0xFF.toByte(), 0xFF.toByte())
        val parser = UbxStreamParser()
        val frames = parser.feed(fake + frame)
        assertEquals(1, frames.size)
    }

    @Test
    fun `a sync byte at the end of a notification is kept for the next one`() {
        val parser = UbxStreamParser()
        assertEquals(0, parser.feed(frame, 0, 1).size)
        assertEquals(1, parser.feed(frame, 1, frame.size - 1).size)
        assertEquals(0, parser.skippedBytes)
    }
}
