package laptimer.core

import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DrivingSimulatorTest {
    private val center = LatLon(36.5843, -121.7535)

    @Test
    fun encodeFixIsTheReverseOfDecode() {
        val original = RaceBox.decodeFix(decodeOne(SyntheticRaceBox.PROTOCOL_DOC_FRAME))!!
        val again = RaceBox.decodeFix(decodeOne(RaceBox.encodeFrame(original)))!!
        assertEquals(original, again)
    }

    @Test
    fun encodeFixRoundsUtcToTheNearestSecondWithACorrection() {
        val base = RaceBox.decodeFix(decodeOne(SyntheticRaceBox.PROTOCOL_DOC_FRAME))!!
        for (nanos in listOf(0L, 400_000_000L, 600_000_000L, 999_999_999L)) {
            val utc = 1_795_000_000L * 1_000_000_000L + nanos
            val decoded = RaceBox.decodeFix(decodeOne(RaceBox.encodeFrame(base.copy(utcEpochNanos = utc))))!!
            assertEquals(utc, decoded.utcEpochNanos)
        }
        val noTime = RaceBox.decodeFix(decodeOne(RaceBox.encodeFrame(base.copy(utcEpochNanos = null))))!!
        assertNull(noTime.utcEpochNanos)
    }

    @Test
    fun lagunaSecaLoopIsTheCircuitWithoutThePitLane() {
        val text = javaClass.getResource("/overpass-laguna-seca.json")!!.readText()
        val c = Overpass.parse(text).single()
        val loop = assertNotNull(TrackLoop.find(c.polylines, directed = true))
        assertEquals(3_602.0, lengthM(loop + loop.first()), 5.0)
        // Undirected search finds the same circuit length.
        val undirected = assertNotNull(TrackLoop.find(c.polylines, directed = false))
        assertEquals(3_602.0, lengthM(undirected + undirected.first()), 5.0)
    }

    @Test
    fun openPolylinesHaveNoLoop() {
        val line = listOf(center, offset(center, 90.0, 500.0))
        assertNull(TrackLoop.find(listOf(line), directed = false))
        assertNull(TrackLoop.find(emptyList(), directed = true))
    }

    @Test
    fun testOvalHasTheDocumentedShape() {
        val loop = TrackLoop.testOval(center)
        // Two 400 m straights and two semicircles of radius 60 m.
        assertEquals(800 + 2 * Math.PI * 60, lengthM(loop + loop.first()), 1.0)
        // It starts heading north.
        assertEquals(0.0, bearingDeg(loop[0], loop[1]), 1e-6)
    }

    @Test
    fun speedsStayWithinTheGripLimit() {
        val profile = DriverProfile(lapVariation = 0.0, positionNoiseM = 0.0)
        val sim = DrivingSimulator(TrackLoop.testOval(center), profile)
        val hairpinLimit = sqrt(profile.lateralG * 9.80665 * 60.0)
        var minSpeed = Double.MAX_VALUE
        var maxSpeed = 0.0
        var t = 0.0
        while (t < sim.idealLapS) {
            val s = sim.stateAt(t)
            minSpeed = minOf(minSpeed, s.speedMps)
            maxSpeed = maxOf(maxSpeed, s.speedMps)
            assertTrue(abs(s.lateralG) <= profile.lateralG * 1.05, "lateral ${s.lateralG} g at $t s")
            t += 0.04
        }
        // The hairpins are taken at about the grip limit; smoothing the
        // curvature makes the slowest point a little faster than the ideal.
        assertEquals(hairpinLimit, minSpeed, hairpinLimit * 0.1)
        assertTrue(maxSpeed > hairpinLimit + 5, "the straights should be faster than the hairpins")
        assertTrue(maxSpeed <= profile.topSpeedMps)
    }

    @Test
    fun theCarFollowsTheLoopAndTheHeadingMatchesItsMotion() {
        val loop = TrackLoop.testOval(center)
        val sim = DrivingSimulator(loop, DriverProfile(positionNoiseM = 0.0))
        var prev = sim.stateAt(0.0)
        var t = 0.04
        while (t < 3 * sim.idealLapS) {
            val s = sim.stateAt(t)
            val snap = snapToPolylines(s.position, listOf(loop + loop.first()))!!
            assertTrue(snap.distanceM < 0.5, "off the loop by ${snap.distanceM} m at $t s")
            val moved = distanceM(prev.position, s.position)
            if (moved > 0.5) {
                val d = abs(normalizeDeg(bearingDeg(prev.position, s.position) - s.headingDeg))
                assertTrue(minOf(d, 360 - d) < 10.0, "heading off by $d at $t s")
            }
            prev = s
            t += 0.04
        }
    }

    @Test
    fun lapsVaryAndStartWhereThePreviousEnded() {
        val sim = DrivingSimulator(TrackLoop.testOval(center), DriverProfile(lapVariation = 0.02))
        val laps = (0 until 5).map { sim.lapTimeS(it) }
        assertTrue(laps.toSet().size == 5, "lap times should differ: $laps")
        laps.forEach { assertEquals(sim.idealLapS, it, sim.idealLapS * 0.021) }
        assertEquals(laps.take(3).sum(), sim.lapStartS(3), 1e-9)
        // Just after the start of lap 3, the car is back at the start of the loop.
        val s = sim.stateAt(sim.lapStartS(3) + 0.001)
        assertEquals(3, s.lap)
        assertTrue(s.distanceM < 1.0)
    }

    @Test
    fun noiseWandersAboutTheConfiguredSize() {
        val loop = TrackLoop.testOval(center)
        val sim = DrivingSimulator(loop, DriverProfile(positionNoiseM = 0.3), seed = 7)
        val clean = DrivingSimulator(loop, DriverProfile(positionNoiseM = 0.0), seed = 7)
        var sumSq = 0.0
        var count = 0
        var t = 0.0
        while (t < 600.0) {
            val e = distanceM(sim.stateAt(t).position, clean.stateAt(t).position)
            if (t > 30) {
                sumSq += e * e
                count++
            }
            t += 0.04
        }
        // Noise must not change lap times.
        assertEquals(clean.lapTimeS(5), sim.lapTimeS(5))
        // Two axes of 0.3 m each give a total RMS of about 0.42 m.
        val rms = sqrt(sumSq / count)
        assertTrue(rms in 0.25..0.6, "rms $rms")
    }

    @Test
    fun feedProducesValidFixesWithGpsTime() {
        val sim = DrivingSimulator(TrackLoop.testOval(center))
        // 2026-11-19 12:00:00 UTC is a Thursday: 4 days and 12 hours into the GPS week.
        val unixMs = java.time.Instant.parse("2026-11-19T12:00:00Z").toEpochMilli()
        val fix = RaceBoxFeed.fix(sim.stateAt(10.0), unixMs)
        assertEquals(((4L * 24 + 12) * 3600 + RaceBoxFeed.LEAP_SECONDS) * 1000, fix.gpsTimeOfWeekMs)
        val decoded = RaceBox.decodeFix(decodeOne(RaceBox.encodeFrame(fix)))!!
        assertTrue(decoded.fixOk)
        assertEquals(unixMs * 1_000_000, decoded.utcEpochNanos)
        assertEquals(fix.latitudeDeg, decoded.latitudeDeg, 1e-7)

        val lost = RaceBoxFeed.fix(sim.stateAt(10.0), unixMs, fixOk = false)
        assertTrue(!lost.fixOk && !lost.positionValid)
    }

    @Test
    fun framesMatchTheSyntheticHelper() {
        // The test helper and the encoder agree on the fields they share.
        val p = SyntheticRaceBox.payload(1000, null, latitudeDeg = 1.0, longitudeDeg = 2.0, speedMps = 3.0, headingDeg = 4.0)
        val f = RaceBox.decodeFix(decodeOne(SyntheticRaceBox.frame(p)))!!
        val again = RaceBox.encodeFix(f)
        assertContentEquals(p.copyOfRange(24, 32), again.copyOfRange(24, 32))
        assertContentEquals(p.copyOfRange(48, 56), again.copyOfRange(48, 56))
    }
}

private fun decodeOne(frame: ByteArray): UbxFrame = UbxStreamParser().feed(frame).single()
