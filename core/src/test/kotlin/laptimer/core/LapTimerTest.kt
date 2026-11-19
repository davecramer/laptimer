package laptimer.core

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LapTimerTest {
    private val center = LatLon(36.5843, -121.7535)
    private val loop = TrackLoop.testOval(center)

    /** A Thursday afternoon, nowhere near the end of a GPS week. */
    private val baseUnixMs = Instant.parse("2026-11-19T14:00:00Z").toEpochMilli()

    /** A line across the loop at [distanceM], facing the driving direction. */
    private fun lineAt(sim: DrivingSimulator, distanceM: Double, widthM: Double = 25.0): TimingLine {
        val a = sim.stateAt(sim.passTimeS(0, distanceM)).position
        val b = sim.stateAt(sim.passTimeS(0, distanceM + 1.0)).position
        return TimingLine.across(a, bearingDeg(a, b), widthM)
    }

    /** Fixes every 40 ms for [seconds], as the RaceBox sends them. */
    private fun drive(sim: DrivingSimulator, seconds: Double, startUnixMs: Long = baseUnixMs, fixOk: (Double) -> Boolean = { true }): List<GpsFix> =
        (0..(seconds * 25).toInt()).map { k ->
            val t = k * 0.04
            RaceBoxFeed.fix(sim.stateAt(t), startUnixMs + k * 40L, fixOk(t))
        }

    private fun time(timer: LapTimer, fixes: List<GpsFix>): List<Lap> = fixes.flatMap { timer.feed(it) }

    @Test
    fun lapAndSectorTimesMatchTheSimulatorWithoutNoise() {
        val sim = DrivingSimulator(loop, DriverProfile(positionNoiseM = 0.0, lapVariation = 0.02), seed = 3)
        val dStart = 300.0
        val dSectors = listOf(700.0, 1_000.0)
        val track = Track(name = "Oval", start = lineAt(sim, dStart), sectors = dSectors.map { lineAt(sim, it) })
        val laps = time(LapTimer(track), drive(sim, sim.lapStartS(6)))

        // The car starts before the line, so lap 1 starts on simulator lap 0.
        assertEquals(5, laps.size)
        for (lap in laps) {
            val j = lap.number - 1
            val expected = sim.passTimeS(j + 1, dStart) - sim.passTimeS(j, dStart)
            assertEquals(expected, lap.timeS, 0.002, "lap ${lap.number}")
            val bounds = listOf(sim.passTimeS(j, dStart)) + dSectors.map { sim.passTimeS(j, it) } + sim.passTimeS(j + 1, dStart)
            for (i in 0 until 3) {
                assertEquals(bounds[i + 1] - bounds[i], lap.sectorTimesS[i]!!, 0.002, "lap ${lap.number} sector $i")
            }
            assertTrue(!lap.hadGap)
        }
    }

    @Test
    fun gpsNoiseCostsOnlyMilliseconds() {
        val sim = DrivingSimulator(loop, DriverProfile(positionNoiseM = 0.3), seed = 11)
        val d = 300.0
        val laps = time(LapTimer(Track(name = "Oval", start = lineAt(sim, d))), drive(sim, sim.lapStartS(5)))
        assertEquals(4, laps.size)
        for (lap in laps) {
            val j = lap.number - 1
            val expected = sim.passTimeS(j + 1, d) - sim.passTimeS(j, d)
            // About 0.3 m of noise along the track at 40-50 m/s.
            assertEquals(expected, lap.timeS, 0.03, "lap ${lap.number}")
        }
    }

    @Test
    fun theWrongDirectionDoesNotCount() {
        val sim = DrivingSimulator(loop, DriverProfile(positionNoiseM = 0.0))
        val track = Track(name = "Oval", start = lineAt(sim, 300.0).flipped())
        val timer = LapTimer(track)
        assertEquals(emptyList(), time(timer, drive(sim, sim.lapStartS(3))))
        assertNull(timer.state.currentLapNumber)
    }

    @Test
    fun jitterOnTheLineDoesNotStartLaps() {
        // Creeping north over an east-west line, rolling back, and over again.
        val line = TimingLine.across(center, 0.0, 20.0)
        val timer = LapTimer(Track(name = "Line", start = line))
        val norths = listOf(-1.0, -0.5, 0.3, -0.2, 0.4, -0.1, 0.5, 1.0, 1.5)
        val fixes = norths.mapIndexed { k, n ->
            fix(offset(center, 0.0, n), baseUnixMs + k * 200L)
        }
        assertEquals(emptyList(), time(timer, fixes))
        // The first forward crossing started lap 1; later ones were within minLapS.
        assertEquals(1, timer.state.currentLapNumber)
    }

    @Test
    fun aGpsGapMarksTheLapAndKeepsItOutOfBests() {
        val sim = DrivingSimulator(loop, DriverProfile(positionNoiseM = 0.0, lapVariation = 0.0))
        val track = Track(name = "Oval", start = lineAt(sim, 300.0))
        // Lose the fix for 3 s in the middle of simulator lap 1.
        val lossStart = sim.lapStartS(1) + sim.lapTimeS(1) / 2
        val laps = time(LapTimer(track), drive(sim, sim.lapStartS(4)) { t -> t !in lossStart..(lossStart + 3.0) })
        assertEquals(3, laps.size)
        assertEquals(listOf(false, true, false), laps.map { it.hadGap })
        // Without the line being lost, the lap time itself is still right.
        assertEquals(sim.lapTimeS(1), laps[1].timeS, 0.002)

        val timer = LapTimer(track)
        drive(sim, sim.lapStartS(4)) { t -> t !in lossStart..(lossStart + 3.0) }.forEach { timer.feed(it) }
        assertTrue(timer.state.bestLap!!.number != 2)
    }

    @Test
    fun timingCarriesOnOverTheGpsWeekRollover() {
        val sim = DrivingSimulator(loop, DriverProfile(positionNoiseM = 0.0, lapVariation = 0.0))
        val track = Track(name = "Oval", start = lineAt(sim, 300.0))
        // Start 60 s before the week rolls over: Saturday/Sunday midnight GPS time.
        val rolloverUnixMs = Instant.parse("2026-11-22T00:00:00Z").toEpochMilli() - RaceBoxFeed.LEAP_SECONDS * 1000L
        assertEquals(0L, RaceBoxFeed.gpsTimeOfWeekMs(rolloverUnixMs))
        val laps = time(LapTimer(track), drive(sim, sim.lapStartS(3), startUnixMs = rolloverUnixMs - 60_000))
        assertEquals(2, laps.size)
        laps.forEach { assertEquals(sim.idealLapS, it.timeS, 0.002) }
    }

    @Test
    fun repeatedFixesAreIgnored() {
        val sim = DrivingSimulator(loop, DriverProfile(positionNoiseM = 0.0, lapVariation = 0.0))
        val track = Track(name = "Oval", start = lineAt(sim, 300.0))
        val fixes = drive(sim, sim.lapStartS(3)).flatMap { listOf(it, it) }
        val laps = time(LapTimer(track), fixes)
        assertEquals(2, laps.size)
        laps.forEach { assertEquals(sim.idealLapS, it.timeS, 0.002) }
    }

    @Test
    fun pointToPointTimesStartToFinish() {
        val sim = DrivingSimulator(loop, DriverProfile(positionNoiseM = 0.0, lapVariation = 0.0))
        val track = Track(
            name = "Hill",
            kind = TrackKind.POINT_TO_POINT,
            start = lineAt(sim, 100.0),
            finish = lineAt(sim, 900.0),
            sectors = listOf(lineAt(sim, 500.0)),
        )
        val timer = LapTimer(track)
        // Stop as the car begins its third time round, before the start line.
        val runs = time(timer, drive(sim, sim.lapStartS(2)))
        // Each time round the oval is one run, start line to finish line.
        assertEquals(2, runs.size)
        for (r in runs) {
            val j = r.number - 1
            assertEquals(sim.passTimeS(j, 900.0) - sim.passTimeS(j, 100.0), r.timeS, 0.002)
            assertEquals(sim.passTimeS(j, 500.0) - sim.passTimeS(j, 100.0), r.sectorTimesS[0]!!, 0.002)
        }
        // After a finish, no run is in progress until the start line.
        assertNull(timer.state.currentLapNumber)
    }

    @Test
    fun sectorLinesOnlyCountInDrivingOrder() {
        val sim = DrivingSimulator(loop, DriverProfile(positionNoiseM = 0.0, lapVariation = 0.0))
        // Listed in the wrong order: the car reaches the second line first.
        val track = Track(name = "Oval", start = lineAt(sim, 300.0), sectors = listOf(lineAt(sim, 1_000.0), lineAt(sim, 700.0)))
        val laps = time(LapTimer(track), drive(sim, sim.lapStartS(2)))
        val lap = laps.single()
        // Line 2 (700 m) was crossed, so line 1 (1000 m), crossed after it, was ignored.
        assertNull(lap.sectorTimesS[0])
        assertNull(lap.sectorTimesS[1])
        assertNotNull(lap.sectorTimesS[2])
    }

    @Test
    fun stateTracksTheCurrentLapAndBests() {
        val sim = DrivingSimulator(loop, DriverProfile(positionNoiseM = 0.0, lapVariation = 0.02), seed = 5)
        val track = Track(name = "Oval", start = lineAt(sim, 300.0), sectors = listOf(lineAt(sim, 700.0)))
        val timer = LapTimer(track)
        val stop = sim.passTimeS(3, 800.0)
        drive(sim, stop).forEach { timer.feed(it) }
        val s = timer.state
        assertEquals(3, s.laps.size)
        assertEquals(4, s.currentLapNumber)
        assertEquals(stop - sim.passTimeS(3, 300.0), s.currentLapS!!, 0.05)
        assertNotNull(s.currentSplitsS[0])
        assertEquals(s.laps.minOf { it.timeS }, s.bestLap!!.timeS)
        assertEquals(s.laps.minOf { it.sectorTimesS[0]!! }, s.bestSectorsS[0]!!)
    }

    @Test
    fun formatsLapTimes() {
        assertEquals("1:54.263", formatLapTime(114.2634))
        assertEquals("59.999", formatLapTime(59.9994))
        assertEquals("1:00.000", formatLapTime(59.9996))
        assertEquals("1:23.4", formatLapTime(83.44, decimals = 1))
        assertEquals("+0.412", formatDelta(0.412))
        assertEquals("-1:01.050", formatDelta(-61.05))
    }

    private fun fix(p: LatLon, unixMs: Long) = RaceBoxFeed.fix(
        DriveState(p, 0.5, 0.0, 0.0, 0.0, 0.0, 0, 0.0), unixMs,
    )
}
