package laptimer.core

import java.nio.file.Files
import java.time.Instant
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DeltaTest {
    private val center = LatLon(36.5843, -121.7535)
    private val loop = TrackLoop.testOval(center)
    private val baseUnixMs = Instant.parse("2026-11-19T14:00:00Z").toEpochMilli()

    /**
     * The start line sits where the simulator's loop begins, so each timed
     * lap is exactly one simulator lap, driven at one pace throughout.
     */
    private fun setUp(variation: Double = 0.02, seed: Long = 4): Pair<DrivingSimulator, Track> {
        val sim = DrivingSimulator(loop, DriverProfile(positionNoiseM = 0.0, lapVariation = variation), seed)
        val a = loop[0]
        val track = Track(name = "Oval", start = TimingLine.across(a, bearingDeg(a, loop[1]), 25.0))
        return sim to track
    }

    /** Fix k of a drive: [shiftM] moves it sideways, to the driver's left. */
    private fun fixAt(sim: DrivingSimulator, k: Int, shiftM: Double = 0.0): GpsFix {
        val s = sim.stateAt(k * 0.04)
        val moved = if (shiftM == 0.0) s else s.copy(position = offset(s.position, s.headingDeg - 90.0, shiftM))
        return RaceBoxFeed.fix(moved, baseUnixMs + k * 40L)
    }

    private fun fixCount(sim: DrivingSimulator, laps: Int) = (sim.lapStartS(laps) / 0.04).toInt()

    /**
     * Checks the delta at every fix against the exact value. The
     * reference is the fastest finished lap so far.
     */
    private fun checkDelta(sim: DrivingSimulator, timer: LapTimer, laps: Int, tolerance: Double, shift: (lap: Int) -> Double = { 0.0 }) {
        var checked = 0
        for (k in 0 until fixCount(sim, laps)) {
            val t = k * 0.04
            val lapIndex = sim.stateAt(t).lap
            timer.feed(fixAt(sim, k, shift(lapIndex)))
            val done = (0 until lapIndex).map { sim.lapTimeS(it) }
            if (done.isEmpty()) continue
            val tau = t - sim.lapStartS(lapIndex)
            // Skip the instant around the line, where the lap number changes.
            if (tau < 0.1) continue
            val expected = tau * (1 - done.min() / sim.lapTimeS(lapIndex))
            val delta = assertNotNull(timer.state.deltaS, "no delta at $t s")
            assertEquals(expected, delta, tolerance, "delta at $t s, lap $lapIndex")
            checked++
        }
        assertTrue(checked > 1000)
    }

    @Test
    fun deltaMatchesTheExactDifference() {
        val (sim, track) = setUp()
        val timer = LapTimer(track)
        checkDelta(sim, timer, laps = 5, tolerance = 0.01)
        // The timed laps are the simulator's laps.
        assertEquals(sim.lapTimeS(0), timer.state.laps[0].timeS, 0.002)
    }

    @Test
    fun aDifferentLineDoesNotMakeTheDeltaDrift() {
        val (sim, track) = setUp()
        val timer = LapTimer(track)
        // From lap 2 on, drive 3 m to the left of the reference path. On
        // this oval that is 19 m less per lap, so a delta from distance
        // driven would be off by about 0.4 s by the end of the lap.
        checkDelta(sim, timer, laps = 4, tolerance = 0.03) { lap -> if (lap >= 1) 3.0 else 0.0 }
    }

    @Test
    fun theFirstLapHasNoDeltaWithoutAReference() {
        val (sim, track) = setUp()
        val timer = LapTimer(track)
        for (k in 0 until fixCount(sim, 1) - 5) timer.feed(fixAt(sim, k))
        assertNotNull(timer.state.currentLapNumber)
        assertNull(timer.state.deltaS)
        assertNull(timer.state.referenceLapS)
    }

    @Test
    fun aStoredReferenceGivesADeltaFromTheFirstLap() {
        val (sim, track) = setUp(variation = 0.0)
        val first = LapTimer(track)
        for (k in 0 until fixCount(sim, 2)) first.feed(fixAt(sim, k))
        val stored = assertNotNull(first.takeNewReference())
        assertNull(first.takeNewReference(), "a reference is handed out once")
        assertTrue(stored.fits(track))
        assertEquals(sim.idealLapS, stored.lapTimeS, 0.002)

        val (sim2, _) = setUp(variation = 0.0)
        val second = LapTimer(track, initialReference = stored)
        for (k in 0 until 500) second.feed(fixAt(sim2, k))
        assertEquals(0.0, assertNotNull(second.state.deltaS), 0.01)
        assertTrue(!second.state.referenceFromThisSession)

        // A moved start line makes the stored lap meaningless.
        val moved = track.copy(start = track.start!!.withWidth(30.0))
        assertTrue(!stored.fits(moved))
        assertNull(LapTimer(moved, initialReference = stored).state.referenceLapS)
    }

    @Test
    fun onlyAFasterCleanLapReplacesTheReference() {
        val (sim, track) = setUp(variation = 0.03, seed = 9)
        val timer = LapTimer(track)
        val n = fixCount(sim, 6)
        // Lose the fix for 3 s in lap 3.
        val lossStart = sim.lapStartS(3) + 10
        for (k in 0 until n) {
            val t = k * 0.04
            val fix = fixAt(sim, k)
            timer.feed(if (t in lossStart..(lossStart + 3)) fix.copy(fixOk = false) else fix)
        }
        val laps = timer.state.laps
        val clean = laps.filter { !it.hadGap }
        assertEquals(clean.minOf { it.timeS }, timer.state.referenceLapS!!, 1e-9)
        assertTrue(timer.state.referenceFromThisSession)
    }

    @Test
    fun noDeltaAwayFromTheTrack() {
        val (sim, track) = setUp(variation = 0.0)
        val timer = LapTimer(track)
        val n = fixCount(sim, 2)
        for (k in 0 until n) timer.feed(fixAt(sim, k))
        // 60 m off to the side, as if in a paddock.
        for (k in n until n + 50) timer.feed(fixAt(sim, k, shiftM = 60.0))
        assertNull(timer.state.deltaS)
        // Back on track, the delta comes back.
        for (k in n + 50 until n + 100) timer.feed(fixAt(sim, k))
        assertNotNull(timer.state.deltaS)
    }

    @Test
    fun predictedAndOptimalLaps() {
        val state = TimingState(
            bestSectorsS = listOf(30.0, 40.5, 25.25),
            referenceLapS = 100.0,
            deltaS = -0.5,
        )
        assertEquals(95.75, state.optimalLapS!!, 1e-9)
        assertEquals(99.5, state.predictedLapS!!, 1e-9)
        assertNull(state.copy(bestSectorsS = listOf(30.0, null)).optimalLapS)
        assertNull(state.copy(deltaS = null).predictedLapS)
    }

    @Test
    fun storeKeepsOneReferencePerTrack() {
        val (sim, track) = setUp(variation = 0.0)
        val timer = LapTimer(track)
        for (k in 0 until fixCount(sim, 2)) timer.feed(fixAt(sim, k))
        val ref = assertNotNull(timer.takeNewReference())

        val dir = Files.createTempDirectory("refs").toFile()
        try {
            val store = ReferenceStore(dir)
            assertNull(store.load(track))
            store.save(ref)
            assertEquals(ref, store.load(track))
            // The track's lines changed: the stored lap no longer applies.
            assertNull(store.load(track.copy(start = track.start!!.flipped())))
            store.delete(track.id)
            assertNull(store.load(track))
            assertEquals(0, dir.list()!!.size)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun deltaTrackerDoesNotJumpAcrossAHairpin() {
        // A reference running north up x = 0, then back south down x = 10.
        val proj = LocalProjection(center)
        val up = (0..50).map { Xy(0.0, it * 2.0) }
        val down = (0..50).map { Xy(10.0, 100.0 - it * 2.0) }
        val pts = (up + down).mapIndexed { i, xy -> proj.toLatLon(xy).let { RefPoint(it.lat, it.lon, i * 0.1) } }
        val ref = ReferenceLap("id", TrackKind.CIRCUIT, null, null, pts.last().t, null, pts)
        val tracker = DeltaTracker(ref, proj)
        // Driving north at the reference's pace (one point, 2 m, every 0.1 s) at x = 4.
        for (y in 0..40 step 2) {
            val t = tracker.referenceTimeAt(Xy(4.0, y.toDouble()), lapS = y / 20.0)!!
            assertTrue(abs(t - y / 20.0) < 0.2, "matched the wrong leg at y=$y: t=$t")
        }
        // Drifting to x = 7, nearer the down leg (3 m) than the up leg (7 m).
        // Beside y = 42 the down leg is 6 s further on, outside the window,
        // so the match holds.
        val t = tracker.referenceTimeAt(Xy(7.0, 42.0), lapS = 2.1)!!
        assertTrue(abs(t - 2.1) < 0.2, "jumped across the hairpin: t=$t")

        // Coming back after time away, the match goes to the leg reached at about that lap time.
        val back = DeltaTracker(ref, proj)
        val t2 = back.referenceTimeAt(Xy(5.0, 50.0), lapS = 7.5)!!
        assertTrue(abs(t2 - 7.6) < 0.2, "re-acquired the wrong leg: t=$t2")
    }

    @Test
    fun lagunaSecaDeltaIsSmoothAndEndsAtTheLapDifference() {
        val text = javaClass.getResource("/overpass-laguna-seca.json")!!.readText()
        val lagunaLoop = TrackLoop.find(Overpass.parse(text).single().polylines, directed = true)!!
        val sim = DrivingSimulator(lagunaLoop, DriverProfile(positionNoiseM = 0.3, lapVariation = 0.01), seed = 21)
        val a = lagunaLoop[0]
        val track = Track(name = "Laguna", start = TimingLine.across(a, bearingDeg(a, lagunaLoop[1]), 25.0))
        val timer = LapTimer(track)
        var previous: Double? = null
        var previousLap: Int? = null
        var lastDeltaBeforeLine: Double? = null
        val endings = mutableListOf<Pair<Double, Double>>()
        for (k in 0 until fixCount(sim, 5)) {
            val finished = timer.feed(RaceBoxFeed.fix(sim.stateAt(k * 0.04), baseUnixMs + k * 40L))
            val state = timer.state
            if (finished.isNotEmpty() && lastDeltaBeforeLine != null) {
                val lap = finished.single()
                // The reference before this lap: the fastest earlier lap.
                val earlier = state.laps.dropLast(1).filter { !it.hadGap }
                if (earlier.isNotEmpty()) endings += lastDeltaBeforeLine!! to (lap.timeS - earlier.minOf { it.timeS })
            }
            val d = state.deltaS
            // Within a lap, consecutive deltas 40 ms apart never jump.
            if (d != null && previous != null && previousLap == state.currentLapNumber) {
                assertTrue(abs(d - previous!!) < 0.1, "delta jumped from $previous to $d at fix $k")
            }
            if (d != null) lastDeltaBeforeLine = d
            previous = d
            previousLap = state.currentLapNumber
        }
        assertEquals(3, endings.size)
        for ((delta, actual) in endings) assertEquals(actual, delta, 0.06)
    }
}
