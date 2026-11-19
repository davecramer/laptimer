package laptimer.core

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.random.Random

/** Builds a closed driving loop from a track's OpenStreetMap ways. */
object TrackLoop {
    /** Ends closer than this count as joined. OSM ways that share a node meet exactly. */
    private const val JOIN_M = 3.0
    private const val SEARCH_BUDGET = 20_000

    /**
     * Finds the longest closed loop made of whole polylines joined end to
     * start. A pit lane that branches off partway along a way can't be part
     * of one, so the main circuit wins.
     *
     * When [directed] is false, polylines may also be used reversed, and the
     * loop runs in the direction its first polyline is drawn.
     *
     * Returns the loop's points without repeating the first at the end, or
     * null if the polylines don't close.
     */
    fun find(polylines: List<List<LatLon>>, directed: Boolean): List<LatLon>? {
        val lines = polylines.filter { it.size >= 2 }
        if (lines.isEmpty()) return null
        // Each usable piece: index and whether it is reversed.
        data class Piece(val index: Int, val reversed: Boolean) {
            fun points() = if (reversed) lines[index].reversed() else lines[index]
        }
        val lengths = lines.map { lengthM(it) }

        var best: List<Piece>? = null
        var bestLength = 0.0
        var budget = SEARCH_BUDGET

        fun search(path: MutableList<Piece>, used: BooleanArray, length: Double, loopStart: LatLon) {
            if (--budget < 0) return
            val end = path.last().points().last()
            if (distanceM(end, loopStart) <= JOIN_M && length > bestLength) {
                best = path.toList()
                bestLength = length
            }
            for (i in lines.indices) {
                if (used[i]) continue
                for (rev in if (directed) listOf(false) else listOf(false, true)) {
                    val p = Piece(i, rev)
                    if (distanceM(p.points().first(), end) > JOIN_M) continue
                    used[i] = true
                    path += p
                    search(path, used, length + lengths[i], loopStart)
                    path.removeAt(path.size - 1)
                    used[i] = false
                }
            }
        }

        for (seed in lines.indices.sortedByDescending { lengths[it] }) {
            val used = BooleanArray(lines.size).also { it[seed] = true }
            val first = Piece(seed, false)
            search(mutableListOf(first), used, lengths[seed], first.points().first())
        }

        val pieces = best ?: return null
        val points = mutableListOf<LatLon>()
        for (p in pieces) {
            val pts = p.points()
            // Drop the joining point, which the previous piece already added.
            points += if (points.isEmpty()) pts else pts.drop(1)
        }
        // The last point is where the loop closes onto the first.
        if (points.size > 2 && distanceM(points.first(), points.last()) <= JOIN_M) points.removeAt(points.size - 1)
        return if (points.size >= 3) points else null
    }

    /**
     * A test track with no map data: two 400 m straights joined by
     * 60 m-radius hairpins, driven clockwise, starting at the middle of the
     * western straight heading north.
     */
    fun testOval(center: LatLon): List<LatLon> {
        val proj = LocalProjection(center)
        val half = 200.0
        val r = 60.0
        val points = mutableListOf<Xy>()
        // Western straight, going north.
        for (i in 0..20) points += Xy(-r, -half + i * (2 * half / 20))
        // Northern hairpin, west to east over the top.
        for (i in 1..30) {
            val a = PI - i * PI / 30
            points += Xy(r * kotlin.math.cos(a), half + r * kotlin.math.sin(a))
        }
        // Eastern straight, going south.
        for (i in 1..20) points += Xy(r, half - i * (2 * half / 20))
        // Southern hairpin, east to west along the bottom.
        for (i in 1..29) {
            val a = -i * PI / 30
            points += Xy(r * kotlin.math.cos(a), -half + r * kotlin.math.sin(a))
        }
        // Start the loop at the middle of the western straight.
        val startIndex = 10
        val rotated = points.drop(startIndex) + points.take(startIndex)
        return rotated.map { proj.toLatLon(it) }
    }
}

/** How hard the simulated driver goes. The defaults are a quick road car on track. */
data class DriverProfile(
    val topSpeedMps: Double = 55.0,
    /** Cornering grip in g. */
    val lateralG: Double = 1.0,
    val accelG: Double = 0.45,
    val brakeG: Double = 1.0,
    /** Each lap is up to this fraction faster or slower than the ideal, at random. */
    val lapVariation: Double = 0.01,
    /** Standard deviation of the slowly wandering GPS position error, in meters. */
    val positionNoiseM: Double = 0.3,
)

/** Where the simulated car is and what it is doing. */
data class DriveState(
    val position: LatLon,
    val speedMps: Double,
    /** Direction of travel, degrees clockwise from north. */
    val headingDeg: Double,
    /** Positive when speeding up. */
    val longitudinalG: Double,
    /** Positive when the car turns right. */
    val lateralG: Double,
    /** Positive when turning right (heading increasing). */
    val yawRateDps: Double,
    val lap: Int,
    /** Distance along the loop from its first point, in meters. */
    val distanceM: Double,
)

/**
 * Drives laps of [loop] with a simple speed model: the fastest speed
 * that stays within the grip limit in each corner, joined by straight-line
 * acceleration and braking.
 *
 * The model is deterministic for a given [seed], so the exact lap time of
 * every lap is known. That makes it a reference for testing lap timing.
 */
class DrivingSimulator(
    loop: List<LatLon>,
    private val profile: DriverProfile = DriverProfile(),
    seed: Long = 1,
) {
    private val proj = LocalProjection(loop.first())
    private val xs: DoubleArray
    private val ys: DoubleArray
    private val speeds: DoubleArray
    private val curvatures: DoubleArray

    /** Time from the loop's first point to point i, at ideal pace. */
    private val times: DoubleArray

    /** Distance from the loop's first point to point i. */
    private val distances: DoubleArray

    val lengthM: Double

    /** Lap time at ideal pace, in seconds. */
    val idealLapS: Double

    // Separate streams, so lap times don't depend on how often positions
    // are sampled or whether noise is on.
    private val paceRandom = Random(seed)
    private val noiseRandom = Random(seed xor 0x5DEECE66DL)
    private val lapPace = mutableListOf<Double>()
    private val lapStarts = mutableListOf(0.0)

    // Slowly wandering GPS error, like a real receiver's.
    private var noiseX = 0.0
    private var noiseY = 0.0

    init {
        require(loop.size >= 3) { "a loop needs at least 3 points" }
        val pts = resample(loop.map { proj.toXy(it) }, SPACING_M)
        val n = pts.size
        xs = DoubleArray(n) { pts[it].x }
        ys = DoubleArray(n) { pts[it].y }
        curvatures = smoothedCurvature(pts)

        val g = 9.80665
        val v = DoubleArray(n) { i ->
            val k = abs(curvatures[i])
            if (k < 1e-6) profile.topSpeedMps else min(profile.topSpeedMps, sqrt(profile.lateralG * g / k))
        }
        val seg = DoubleArray(n) { i -> hypot(xs[(i + 1) % n] - xs[i], ys[(i + 1) % n] - ys[i]) }
        // Passes around the loop twice each way so the limits carry across
        // the start of the loop.
        repeat(2) {
            for (i in 0 until n) {
                val j = (i + 1) % n
                v[j] = min(v[j], sqrt(v[i] * v[i] + 2 * profile.accelG * g * seg[i]))
            }
            for (i in n - 1 downTo 0) {
                val j = (i + 1) % n
                v[i] = min(v[i], sqrt(v[j] * v[j] + 2 * profile.brakeG * g * seg[i]))
            }
        }
        speeds = v
        times = DoubleArray(n + 1)
        distances = DoubleArray(n + 1)
        for (i in 0 until n) {
            val j = (i + 1) % n
            times[i + 1] = times[i] + seg[i] / ((v[i] + v[j]) / 2)
            distances[i + 1] = distances[i] + seg[i]
        }
        lengthM = distances[n]
        idealLapS = times[n]
    }

    /** Lap time of lap [lap] (0 is the first) in seconds. */
    fun lapTimeS(lap: Int): Double = idealLapS / pace(lap)

    /** Time from the start of driving to the start of lap [lap], in seconds. */
    fun lapStartS(lap: Int): Double {
        while (lapStarts.size <= lap) lapStarts += lapStarts.last() + lapTimeS(lapStarts.size - 1)
        return lapStarts[lap]
    }

    /**
     * The car's state [t] seconds after it set off from the loop's first
     * point. Calls must come in increasing [t] for the GPS noise to wander
     * smoothly; the positions themselves don't depend on call order.
     */
    fun stateAt(t: Double): DriveState {
        require(t >= 0) { "time must not be negative" }
        var lap = (t / idealLapS).toInt().coerceAtLeast(0)
        while (lap > 0 && lapStartS(lap) > t) lap--
        while (lapStartS(lap + 1) <= t) lap++
        val pace = pace(lap)
        // Time into the lap at ideal pace.
        val ti = ((t - lapStartS(lap)) * pace).coerceIn(0.0, idealLapS)

        val n = xs.size
        val i = times.binarySearch(ti).let { if (it >= 0) it else -it - 2 }.coerceIn(0, n - 1)
        val j = (i + 1) % n
        val f = if (times[i + 1] > times[i]) (ti - times[i]) / (times[i + 1] - times[i]) else 0.0
        val x = xs[i] + f * (xs[j] - xs[i])
        val y = ys[i] + f * (ys[j] - ys[i])
        val speed = (speeds[i] + f * (speeds[j] - speeds[i])) * pace
        val heading = normalizeDeg(Math.toDegrees(atan2(xs[j] - xs[i], ys[j] - ys[i])))
        val dt = (times[i + 1] - times[i]) / pace
        val longG = if (dt > 0) (speeds[j] - speeds[i]) * pace / dt / 9.80665 else 0.0
        val k = curvatures[i]
        // Curvature is positive for left turns, so negate for "positive right".
        val latG = -speed * speed * k / 9.80665
        val yaw = -Math.toDegrees(speed * k)

        val sigma = profile.positionNoiseM
        if (sigma > 0) {
            // A random walk pulled back toward zero settles at about sigma.
            noiseX = noiseX * NOISE_DECAY + noiseRandom.nextGaussian() * sigma * NOISE_STEP
            noiseY = noiseY * NOISE_DECAY + noiseRandom.nextGaussian() * sigma * NOISE_STEP
        }
        return DriveState(
            position = proj.toLatLon(Xy(x + noiseX, y + noiseY)),
            speedMps = speed,
            headingDeg = heading,
            longitudinalG = longG,
            lateralG = latG,
            yawRateDps = yaw,
            lap = lap,
            distanceM = distances[i] + f * (distances[i + 1] - distances[i]),
        )
    }

    /** Standing still at the loop's first point. */
    fun parkedState(): DriveState =
        DriveState(proj.toLatLon(Xy(xs[0], ys[0])), 0.0, normalizeDeg(Math.toDegrees(atan2(xs[1] - xs[0], ys[1] - ys[0]))), 0.0, 0.0, 0.0, 0, 0.0)

    private fun pace(lap: Int): Double {
        while (lapPace.size <= lap) {
            lapPace += 1.0 + profile.lapVariation * (paceRandom.nextDouble() * 2 - 1)
        }
        return lapPace[lap]
    }

    private fun Random.nextGaussian(): Double {
        // Box-Muller.
        val u = nextDouble().coerceAtLeast(1e-12)
        val v = nextDouble()
        return sqrt(-2 * kotlin.math.ln(u)) * kotlin.math.cos(2 * PI * v)
    }

    companion object {
        private const val SPACING_M = 2.0

        /** Curvature is averaged over this many points each side, to hide kinks between OSM nodes. */
        private const val SMOOTHING_POINTS = 6

        private const val NOISE_DECAY = 0.98

        // With decay d, the steady-state deviation is step / sqrt(1 - d^2).
        private val NOISE_STEP = sqrt(1 - NOISE_DECAY * NOISE_DECAY)

        /** Points every [spacing] meters along the closed polyline [pts]. */
        private fun resample(pts: List<Xy>, spacing: Double): List<Xy> {
            val closed = pts + pts.first()
            val out = mutableListOf(closed.first())
            var carry = 0.0
            for (s in 0 until closed.size - 1) {
                val a = closed[s]
                val b = closed[s + 1]
                val len = hypot(b.x - a.x, b.y - a.y)
                var d = spacing - carry
                while (d <= len) {
                    val f = d / len
                    out += Xy(a.x + f * (b.x - a.x), a.y + f * (b.y - a.y))
                    d += spacing
                }
                carry = len - (d - spacing)
            }
            // The last sample may sit on top of the first.
            if (out.size > 3 && hypot(out.last().x - out[0].x, out.last().y - out[0].y) < spacing / 2) out.removeAt(out.size - 1)
            return out
        }

        /** Signed curvature (1/m, positive turning left) at each point of a closed loop. */
        private fun smoothedCurvature(pts: List<Xy>): DoubleArray {
            val n = pts.size
            val raw = DoubleArray(n) { i ->
                val a = pts[(i - 1 + n) % n]
                val b = pts[i]
                val c = pts[(i + 1) % n]
                val cross = (b.x - a.x) * (c.y - b.y) - (b.y - a.y) * (c.x - b.x)
                val la = hypot(b.x - a.x, b.y - a.y)
                val lb = hypot(c.x - b.x, c.y - b.y)
                val lc = hypot(c.x - a.x, c.y - a.y)
                val denom = la * lb * lc
                if (denom == 0.0) 0.0 else 2 * cross / denom
            }
            val w = SMOOTHING_POINTS
            return DoubleArray(n) { i ->
                var sum = 0.0
                for (k in -w..w) sum += raw[(i + k + n) % n]
                sum / (2 * w + 1)
            }
        }
    }
}

/**
 * Turns simulated driving into the GPS fixes a RaceBox would send,
 * stamped with real time so the lap timer sees a live device.
 */
object RaceBoxFeed {
    /** GPS time started 1980-01-06; this is that instant in Unix milliseconds. */
    private const val GPS_EPOCH_UNIX_MS = 315_964_800_000L

    /** GPS time is ahead of UTC by the leap seconds since 1980: 18 since 2017. */
    const val LEAP_SECONDS = 18
    private const val WEEK_MS = 7L * 24 * 3600 * 1000

    fun gpsTimeOfWeekMs(unixMs: Long): Long =
        Math.floorMod(unixMs - GPS_EPOCH_UNIX_MS + LEAP_SECONDS * 1000L, WEEK_MS)

    /**
     * A fix for [state] at [unixMs]. With [fixOk] false the receiver has
     * lost its fix, as in a tunnel: no position, and accuracy is poor.
     */
    fun fix(state: DriveState, unixMs: Long, fixOk: Boolean = true, batteryPercent: Int = 80): GpsFix = GpsFix(
        gpsTimeOfWeekMs = gpsTimeOfWeekMs(unixMs),
        utcEpochNanos = unixMs * 1_000_000L,
        fixType = if (fixOk) 3 else 0,
        fixOk = fixOk,
        satellites = if (fixOk) 14 else 2,
        latitudeDeg = if (fixOk) state.position.lat else 0.0,
        longitudeDeg = if (fixOk) state.position.lon else 0.0,
        positionValid = fixOk,
        altitudeMslM = if (fixOk) 250.0 else 0.0,
        horizontalAccuracyM = if (fixOk) 0.5 else 50.0,
        verticalAccuracyM = if (fixOk) 0.8 else 80.0,
        speedMps = if (fixOk) state.speedMps else 0.0,
        speedAccuracyMps = if (fixOk) 0.15 else 20.0,
        headingDeg = state.headingDeg,
        headingAccuracyDeg = if (fixOk) 0.5 else 180.0,
        pdop = if (fixOk) 1.2 else 99.0,
        // Sign conventions of the device's axes are not documented in the
        // sources used here; the lap timer does not use these yet.
        accelXG = state.longitudinalG,
        accelYG = state.lateralG,
        accelZG = 1.0,
        rotationXDps = 0.0,
        rotationYDps = 0.0,
        rotationZDps = state.yawRateDps,
        batteryRaw = batteryPercent.coerceIn(0, 100),
    )
}
