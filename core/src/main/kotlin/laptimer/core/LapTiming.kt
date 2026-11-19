package laptimer.core

/** One finished lap, or one run of a point-to-point track. */
data class Lap(
    /** 1 for the first timed lap. */
    val number: Int,
    /** When the lap started, in seconds on the timer's clock: GPS time of week, unwrapped. */
    val startS: Double,
    val timeS: Double,
    /**
     * Sector times in driving order: one more than the track's sector
     * lines. Null for a sector whose line was not crossed, for example
     * because the GPS fix was lost there.
     */
    val sectorTimesS: List<Double?>,
    /**
     * True if the GPS had a gap of more than [LapTimer.maxGapS] during the
     * lap. A crossing may have been missed, so the time may cover more than
     * one lap. Such laps don't count as best laps.
     */
    val hadGap: Boolean,
)

/** What the timer knows now, for the live screen. */
data class TimingState(
    val laps: List<Lap> = emptyList(),
    /** The lap in progress, or null before the first crossing of the start line. */
    val currentLapNumber: Int? = null,
    val currentLapStartS: Double? = null,
    /** Time of the latest fix, on the timer's clock. */
    val nowS: Double? = null,
    /** Split times from the start of the current lap to each sector line crossed so far. */
    val currentSplitsS: List<Double?> = emptyList(),
    val currentLapHasGap: Boolean = false,
    /** Best time for each sector, over laps without gaps. */
    val bestSectorsS: List<Double?> = emptyList(),
    /** The lap time of the reference the delta compares against, or null if there is none yet. */
    val referenceLapS: Double? = null,
    /** True if the reference is a lap from this session; false if it was stored from an earlier one. */
    val referenceFromThisSession: Boolean = false,
    /**
     * The current lap's time minus the reference lap's time at the same
     * place on track. Negative is ahead. Null with no reference, or when
     * the car is away from the reference path, for example in the pits.
     */
    val deltaS: Double? = null,
) {
    val lastLap: Lap? get() = laps.lastOrNull()

    /** The fastest lap without a GPS gap. */
    val bestLap: Lap? get() = laps.filter { !it.hadGap }.minByOrNull { it.timeS }

    /**
     * The sum of the best sector times: the lap you would drive by putting
     * your best sectors together. Null until every sector has a time.
     */
    val optimalLapS: Double? get() =
        if (bestSectorsS.isNotEmpty() && bestSectorsS.all { it != null }) bestSectorsS.sumOf { it!! } else null

    /** The reference lap time plus the delta: where this lap is heading. */
    val predictedLapS: Double? get() {
        val ref = referenceLapS ?: return null
        val d = deltaS ?: return null
        return ref + d
    }

    /** Time into the current lap, or null when no lap is running. */
    val currentLapS: Double? get() {
        val start = currentLapStartS ?: return null
        val now = nowS ?: return null
        return (now - start).coerceAtLeast(0.0)
    }
}

/**
 * Times laps from a stream of GPS fixes.
 *
 * Between each pair of consecutive good fixes, it checks whether the
 * car's straight-line path crossed a timing line in the counting
 * direction. The crossing time is interpolated from where along the path
 * the line was crossed, so at 25 Hz and 50 m/s (2 m between fixes) the
 * timing error from interpolation is a few milliseconds.
 *
 * Time comes from the receiver's clock (GPS time of week), never the
 * phone's. Not thread-safe; feed it from one thread.
 */
class LapTimer(
    val track: Track,
    /**
     * A start/finish crossing less than this long after the lap started is
     * ignored, so GPS jitter while creeping over the line can't start a new lap.
     */
    val minLapS: Double = 10.0,
    /** Fixes further apart than this are a gap: no crossing is detected across it. */
    val maxGapS: Double = 2.0,
    /**
     * The lap to compare against at first, usually the track's best from
     * an earlier session. Ignored if it doesn't fit [track]. A faster lap
     * without a GPS gap replaces it.
     */
    initialReference: ReferenceLap? = null,
) {
    private val start = track.start
    private val finish = if (track.kind == TrackKind.POINT_TO_POINT) track.finish else track.start
    private val proj = LocalProjection(start?.center ?: track.outline.firstOrNull()?.firstOrNull() ?: LatLon(0.0, 0.0))
    private val sectorCount = track.sectors.size + 1

    private class Line(val a: Xy, val b: Xy) {
        /** The counting direction: a-to-b turned 90 degrees to the left. */
        val forwardX = -(b.y - a.y)
        val forwardY = b.x - a.x
    }

    private val startLine = start?.let { toLine(it) }
    private val finishLine = finish?.let { toLine(it) }
    private val sectorLines = track.sectors.map { toLine(it) }

    private var weekOffsetMs = 0L

    /** The latest fix's time, unwrapped, in milliseconds. */
    private var lastTimeMs: Long? = null
    private var lastGood: Pair<Double, Xy>? = null

    private val laps = mutableListOf<Lap>()
    private var lapStartS: Double? = null
    private var splits = arrayOfNulls<Double>(sectorCount - 1)
    private var nextSector = 0
    private var lapHasGap = false
    private var nowS: Double? = null
    private var lastUtcNanos: Long? = null

    /** The current lap's path so far: time into the lap, and position. */
    private val path = ArrayList<Pair<Double, Xy>>()
    private var pathFull = false

    /** The lap the delta compares against. */
    var reference: ReferenceLap? = initialReference?.takeIf { it.fits(track) }
        private set
    private var referenceFromThisSession = false
    private var tracker: DeltaTracker? = reference?.let { DeltaTracker(it, proj) }
    private var newReference: ReferenceLap? = null
    private var deltaS: Double? = null

    /**
     * Returns the reference set by a lap of this session since the last
     * call, for saving, then forgets it. Null if there is none.
     */
    fun takeNewReference(): ReferenceLap? = newReference.also { newReference = null }

    /** The state after the fixes so far. */
    var state = TimingState(bestSectorsS = List(sectorCount) { null })
        private set

    /*
     * The timer's clock is GPS time of week in seconds, unwrapped across
     * the weekly rollover, starting from the first fix's week.
     */

    /** Feeds one fix. Returns the laps it finished, usually none. */
    fun feed(fix: GpsFix): List<Lap> {
        val last = lastTimeMs
        // The receiver's clock went back by more than half a week: the
        // week rolled over to zero.
        if (last != null && fix.gpsTimeOfWeekMs + weekOffsetMs < last - WEEK_MS / 2) weekOffsetMs += WEEK_MS
        val timeMs = fix.gpsTimeOfWeekMs + weekOffsetMs
        // A repeated or older fix carries no new information.
        if (last != null && timeMs <= last) return emptyList()
        lastTimeMs = timeMs
        val t = timeMs / 1000.0
        nowS = t
        fix.utcEpochNanos?.let { lastUtcNanos = it }

        if (!fix.fixOk || !fix.positionValid) {
            publish()
            return emptyList()
        }
        val p = proj.toXy(LatLon(fix.latitudeDeg, fix.longitudeDeg))
        val prev = lastGood
        lastGood = t to p
        val finished = mutableListOf<Lap>()
        if (prev != null) {
            val (t0, p0) = prev
            if (t - t0 > maxGapS) {
                lapHasGap = true
            } else {
                for ((s, what) in crossings(p0, p)) {
                    val at = Xy(p0.x + s * (p.x - p0.x), p0.y + s * (p.y - p0.y))
                    handleCrossing(what, t0 + s * (t - t0), at)?.let { finished += it }
                }
            }
        }
        val begun = lapStartS
        if (begun != null) {
            if (path.size < MAX_PATH_POINTS) path += (t - begun) to p else pathFull = true
            deltaS = tracker?.referenceTimeAt(p, t - begun)?.let { (t - begun) - it }
        }
        publish()
        return finished
    }

    private sealed interface Crossed {
        data object Start : Crossed
        data object Finish : Crossed
        data class Sector(val index: Int) : Crossed
    }

    /** Lines crossed in the counting direction between [p0] and [p1], in order along the path. */
    private fun crossings(p0: Xy, p1: Xy): List<Pair<Double, Crossed>> {
        val out = mutableListOf<Pair<Double, Crossed>>()
        val circuit = track.kind == TrackKind.CIRCUIT
        startLine?.let { l -> cross(p0, p1, l)?.let { out += it to Crossed.Start } }
        if (!circuit) finishLine?.let { l -> cross(p0, p1, l)?.let { out += it to Crossed.Finish } }
        sectorLines.forEachIndexed { i, l -> cross(p0, p1, l)?.let { out += it to Crossed.Sector(i) } }
        return out.sortedBy { it.first }
    }

    /**
     * Where along p0-p1 (0 to 1) it crosses [l] in the counting direction,
     * or null. A path ending exactly on the line counts in the next
     * segment, which starts there, so one crossing is never seen twice.
     */
    private fun cross(p0: Xy, p1: Xy, l: Line): Double? {
        val dx = p1.x - p0.x
        val dy = p1.y - p0.y
        if (dx * l.forwardX + dy * l.forwardY <= 0) return null
        val ex = l.b.x - l.a.x
        val ey = l.b.y - l.a.y
        val denom = dx * ey - dy * ex
        if (denom == 0.0) return null
        val wx = l.a.x - p0.x
        val wy = l.a.y - p0.y
        val s = (wx * ey - wy * ex) / denom
        val u = (wx * dy - wy * dx) / denom
        return if (s >= 0.0 && s < 1.0 && u in 0.0..1.0) s else null
    }

    private fun handleCrossing(what: Crossed, t: Double, at: Xy): Lap? {
        val circuit = track.kind == TrackKind.CIRCUIT
        when (what) {
            Crossed.Start -> {
                val begun = lapStartS
                if (begun == null) {
                    beginLap(t, at)
                    return null
                }
                // On a point-to-point track, a run in progress ignores the start line.
                if (!circuit || t - begun < minLapS) return null
                val lap = endLap(t, at)
                beginLap(t, at)
                return lap
            }
            Crossed.Finish -> {
                val begun = lapStartS ?: return null
                if (t - begun < minLapS) return null
                val lap = endLap(t, at)
                lapStartS = null
                deltaS = null
                return lap
            }
            is Crossed.Sector -> {
                val begun = lapStartS ?: return null
                // Only forward through the sectors; a line behind is ignored.
                if (what.index < nextSector) return null
                splits[what.index] = t - begun
                nextSector = what.index + 1
                return null
            }
        }
    }

    private fun beginLap(t: Double, at: Xy) {
        lapStartS = t
        splits = arrayOfNulls(sectorCount - 1)
        nextSector = 0
        lapHasGap = false
        path.clear()
        path += 0.0 to at
        pathFull = false
        tracker?.reset()
        deltaS = 0.0.takeIf { tracker != null }
    }

    private fun endLap(t: Double, at: Xy): Lap {
        val begun = lapStartS!!
        val total = t - begun
        // Sector boundaries: 0, each split, then the lap time.
        val bounds = listOf<Double?>(0.0) + splits.toList() + total
        val sectors = (0 until sectorCount).map { i ->
            val a = bounds[i]
            val b = bounds[i + 1]
            if (a == null || b == null) null else b - a
        }
        val lap = Lap(laps.size + 1, begun, total, sectors, lapHasGap)
        laps += lap
        path += total to at
        val ref = reference
        if (!lapHasGap && !pathFull && (ref == null || total < ref.lapTimeS)) {
            val r = ReferenceLap(
                trackId = track.id,
                kind = track.kind,
                start = track.start,
                finish = track.finish.takeIf { track.kind == TrackKind.POINT_TO_POINT },
                lapTimeS = total,
                recordedUtc = lastUtcNanos?.let { java.time.Instant.ofEpochSecond(0, it).toString() },
                points = path.map { (pt, xy) -> proj.toLatLon(xy).let { RefPoint(it.lat, it.lon, pt) } },
            )
            reference = r
            referenceFromThisSession = true
            tracker = DeltaTracker(r, proj)
            newReference = r
        }
        return lap
    }

    private fun publish() {
        val clean = laps.filter { !it.hadGap }
        state = TimingState(
            laps = laps.toList(),
            currentLapNumber = lapStartS?.let { laps.size + 1 },
            currentLapStartS = lapStartS,
            nowS = nowS,
            currentSplitsS = if (lapStartS == null) emptyList() else splits.toList(),
            currentLapHasGap = lapHasGap,
            bestSectorsS = (0 until sectorCount).map { i -> clean.mapNotNull { it.sectorTimesS[i] }.minOrNull() },
            referenceLapS = reference?.lapTimeS,
            referenceFromThisSession = referenceFromThisSession,
            deltaS = if (lapStartS == null) null else deltaS,
        )
    }

    private fun toLine(l: TimingLine) = Line(proj.toXy(l.left), proj.toXy(l.right))

    companion object {
        private const val WEEK_MS = 7L * 24 * 3600 * 1000

        /** An hour at 25 Hz. A lap that long, say parked with the timer running, is not kept as a reference. */
        private const val MAX_PATH_POINTS = 90_000
    }
}

/** Lap times as people read them: "1:54.263", or "54.263" under a minute. */
fun formatLapTime(seconds: Double, decimals: Int = 3): String {
    val scale = when (decimals) {
        1 -> 10L
        2 -> 100L
        else -> 1000L
    }
    val total = Math.round(seconds * scale)
    val minutes = total / (60 * scale)
    val rest = total % (60 * scale)
    val secs = rest / scale
    val frac = (rest % scale).toString().padStart(decimals, '0')
    return if (minutes > 0) "%d:%02d.%s".format(minutes, secs, frac) else "%d.%s".format(secs, frac)
}

/** A difference in seconds with its sign: "+0.412" or "-1.050". */
fun formatDelta(seconds: Double): String {
    val sign = if (seconds < 0) "-" else "+"
    return sign + formatLapTime(kotlin.math.abs(seconds))
}
