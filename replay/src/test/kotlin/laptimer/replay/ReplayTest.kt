package laptimer.replay

import laptimer.core.LogEnd
import laptimer.core.SyntheticSession
import java.io.File
import java.nio.file.Files
import kotlin.math.PI
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ReplayTest {
    private val dir: File = Files.createTempDirectory("replay").toFile()

    @AfterTest
    fun cleanUp() {
        dir.deleteRecursively()
    }

    @Test
    fun `a synthetic session replays with every fix and no errors`() {
        val file = File(dir, "circle.ltlog")
        val written = SyntheticSession.write(file, laps = 2)
        val headings = ArrayList<Double>()
        val summary = replay(file) { headings.add(it.headingDeg) }

        assertEquals(written.toLong(), summary.fixes)
        assertEquals(0, summary.checksumErrors)
        assertEquals(0, summary.skippedBytes)
        assertEquals(40L, summary.medianIntervalMs)
        assertEquals(0, summary.gaps)
        assertEquals(LogEnd.CLEAN, summary.logEnd)
        assertEquals("synthetic", summary.metadata["device"])
        assertEquals(40.0, assertNotNull(summary.maxSpeedMps), 1e-9)
        // The first fix is at the north point heading east.
        assertEquals(90.0, headings.first(), 1e-4)

        val report = format(summary)
        assertTrue("median interval: 40 ms (25.0 Hz)" in report, report)
        assertTrue("max speed: 144.0 km/h" in report, report)
    }

    @Test
    fun `fixes come back in time order at the expected spacing`() {
        val file = File(dir, "order.ltlog")
        val written = SyntheticSession.write(file, laps = 1, radiusM = 100.0, speedMps = 20.0)
        // One lap of a 100 m radius at 20 m/s takes 2 * pi * 5 s.
        assertEquals((2 * PI * 5 * 25).toInt(), written)
        val times = ArrayList<Long>()
        replay(file) { times.add(it.gpsTimeOfWeekMs) }
        assertEquals(written, times.size)
        assertTrue(times.zipWithNext().all { (a, b) -> b - a == 40L })
    }

    @Test
    fun `a session cut off mid-record still replays the fixes before the cut`() {
        val file = File(dir, "cut.ltlog")
        SyntheticSession.write(file, laps = 1)
        val bytes = file.readBytes()
        val cutFile = File(dir, "cut2.ltlog")
        cutFile.writeBytes(bytes.copyOf(bytes.size - 7))
        val summary = replay(cutFile)
        assertEquals(LogEnd.TRUNCATED, summary.logEnd)
        assertTrue(summary.fixes > 0)
    }

    @Test
    fun `time of week differences allow for the weekly wrap`() {
        val week = 7L * 24 * 3600 * 1000
        assertEquals(40, towDifferenceMs(week - 20, 20))
        assertEquals(40, towDifferenceMs(100, 140))
    }
}
