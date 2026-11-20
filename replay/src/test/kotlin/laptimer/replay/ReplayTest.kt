package laptimer.replay

import laptimer.core.DriverProfile
import laptimer.core.DrivingSimulator
import laptimer.core.LatLon
import laptimer.core.LogEnd
import laptimer.core.RaceBox
import laptimer.core.RaceBoxFeed
import laptimer.core.SessionLogWriter
import laptimer.core.SyntheticSession
import laptimer.core.TimingLine
import laptimer.core.Track
import laptimer.core.TrackJson
import laptimer.core.TrackLoop
import laptimer.core.bearingDeg
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

    @Test
    fun `laps are timed on the track stored in the session`() {
        val sim = DrivingSimulator(TrackLoop.testOval(LatLon(36.5843, -121.7535)), DriverProfile(positionNoiseM = 0.0), seed = 2)
        val a = sim.stateAt(sim.passTimeS(0, 300.0)).position
        val b = sim.stateAt(sim.passTimeS(0, 301.0)).position
        val track = Track(name = "Oval", start = TimingLine.across(a, bearingDeg(a, b), 25.0))
        val file = File(dir, "oval.ltlog")
        val startMs = 1_795_000_000_000L
        SessionLogWriter(file).use { log ->
            // As RecordingService writes it.
            log.writeMetadata(0, mapOf("device" to "emulator", "trackName" to track.name, "track" to TrackJson.encodeCompact(track)))
            var k = 0
            while (k * 0.04 < sim.lapStartS(4)) {
                val frame = RaceBox.encodeFrame(RaceBoxFeed.fix(sim.stateAt(k * 0.04), startMs + k * 40L))
                // 20-byte pieces, as at the default Bluetooth MTU.
                frame.toList().chunked(20).forEach { log.writeDeviceBytes(k * 40_000_000L, it.toByteArray()) }
                k++
            }
        }

        val summary = replay(file)
        val laps = assertNotNull(summary.timing).laps
        assertEquals(3, laps.size)
        for (lap in laps) {
            val j = lap.number - 1
            assertEquals(sim.passTimeS(j + 1, 300.0) - sim.passTimeS(j, 300.0), lap.timeS, 0.002)
        }
        val report = format(summary)
        assertTrue(report.contains("track: Oval"), report)
        assertTrue(report.contains("(best)"), report)
        // The stored track isn't dumped into the summary.
        assertTrue(!report.contains("\"format\""), report)
    }

    @Test
    fun `a CAN session counts CAN values and no OBD readings`() {
        val sim = DrivingSimulator(TrackLoop.testOval(LatLon(36.5843, -121.7535)), DriverProfile(positionNoiseM = 0.0), seed = 2)
        val file = File(dir, "can.ltlog")
        laptimer.core.SimulatedSession.write(file, sim, 20.0, track = null, can = laptimer.core.CanProfiles.PORSCHE_718)
        val summary = replay(file)
        assertTrue(summary.obdReadings.isEmpty(), summary.obdReadings.toString())
        assertTrue(summary.canValues.getValue("RPM") > 300)
        val report = format(summary)
        assertTrue(report.contains("CAN values: "), report)
        // The stored profile isn't dumped into the summary.
        assertTrue(!report.contains("\"signals\""), report)
    }
}
