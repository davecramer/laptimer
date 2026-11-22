package laptimer.core

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TrackLocatorTest {
    private val laguna = LatLon(36.5843, -121.7535)
    private val sonoma = LatLon(38.1611, -122.4547)

    private fun track(name: String, at: LatLon, outline: Boolean = true) = Track(
        name = name,
        start = TimingLine.across(at, 0.0, 25.0),
        outline = if (outline) listOf(listOf(offset(at, 90.0, -500.0), offset(at, 90.0, 500.0))) else emptyList(),
    )

    @Test
    fun findsTheNearestReadyTrackWithinRange() {
        val tracks = listOf(track("Sonoma", sonoma), track("Laguna", laguna), Track(name = "Unfinished"))
        // In the paddock, 400 m north of the main straight.
        val here = offset(laguna, 0.0, 400.0)
        val found = TrackLocator.nearby(tracks, here)
        assertEquals(listOf("Laguna"), found.map { it.track.name })
        assertEquals(400.0, found[0].distanceM, 1.0)
        // Far from both: nothing.
        assertEquals(emptyList(), TrackLocator.nearby(tracks, LatLon(37.0, -120.0)))
    }

    @Test
    fun usesTheOutlineWhenItIsCloserThanTheLines() {
        // At the far end of the straight: 500 m from the start line, 0 m from the outline.
        val end = offset(laguna, 90.0, 500.0)
        assertEquals(0.0, TrackLocator.distanceM(track("Laguna", laguna), end)!!, 0.5)
        assertEquals(500.0, TrackLocator.distanceM(track("Laguna", laguna, outline = false), end)!!, 13.0)
        assertNull(TrackLocator.distanceM(Track(name = "Empty"), end))
    }

    @Test
    fun severalLayoutsOfOneCircuitAllComeBack() {
        val full = track("Laguna full", laguna)
        val short = track("Laguna short", offset(laguna, 0.0, 150.0))
        val found = TrackLocator.nearby(listOf(short, full), offset(laguna, 0.0, 20.0))
        assertEquals(listOf("Laguna full", "Laguna short"), found.map { it.track.name })
    }

    @Test
    fun onlyGoodFixesLocateTheCar() {
        val state = DriveState(laguna, 0.0, 0.0, 0.0, 0.0, 0.0, 0, 0.0)
        val good = RaceBoxFeed.fix(state, 1_795_000_000_000L)
        assertTrue(TrackLocator.usable(good))
        assertTrue(!TrackLocator.usable(good.copy(fixOk = false)))
        assertTrue(!TrackLocator.usable(good.copy(horizontalAccuracyM = 35.0)))
    }

    @Test
    fun aTrackStoredPartwayThroughRestartsTimingFromThere() {
        val dir = Files.createTempDirectory("locator").toFile()
        try {
            val sim = DrivingSimulator(TrackLoop.testOval(laguna), DriverProfile(positionNoiseM = 0.0, lapVariation = 0.0))
            val a = sim.stateAt(sim.passTimeS(0, 300.0)).position
            val b = sim.stateAt(sim.passTimeS(0, 301.0)).position
            val oval = Track(name = "Oval", start = TimingLine.across(a, bearingDeg(a, b), 25.0))
            val file = File(dir, "s.ltlog")
            // Recorded without a track; the track is found after the first lap, as if by GPS.
            SessionLogWriter(file).use { log ->
                var k = 0
                val switchAt = (sim.lapStartS(1) / 0.04).toInt()
                while (k * 0.04 < sim.lapStartS(4)) {
                    if (k == switchAt) log.writeMetadata(k * 40_000_000L, mapOf("trackName" to oval.name, "track" to TrackJson.encodeCompact(oval)))
                    val frame = RaceBox.encodeFrame(RaceBoxFeed.fix(sim.stateAt(k * 0.04), 1_795_000_000_000L + k * 40L))
                    log.writeDeviceBytes(k * 40_000_000L, frame)
                    k++
                }
            }
            val s = summarizeSession(file)
            assertEquals("Oval", s.track?.name)
            // Timing starts at the switch: laps 2 and 3 of the drive are complete after it.
            assertEquals(2, s.timing!!.laps.size)
            val first = assertNotNull(s.firstPosition)
            assertTrue(distanceM(first, sim.stateAt(0.0).position) < 1.0)
        } finally {
            dir.deleteRecursively()
        }
    }
}
