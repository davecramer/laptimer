package laptimer.core

/**
 * Finds which saved track the car is at, from its GPS position, so a
 * session can start timing without the track being picked by hand.
 */
object TrackLocator {
    /**
     * A track counts as nearby within this distance of its outline or
     * lines: enough to cover a paddock and access roads at a large circuit.
     */
    const val DEFAULT_RANGE_M = 3_000.0

    /** A fix worse than this is not trusted to pick a track. */
    const val MAX_ACCURACY_M = 20.0

    data class Nearby(val track: Track, val distanceM: Double)

    /** True if [fix] is good enough to locate the car by. */
    fun usable(fix: GpsFix): Boolean = fix.fixOk && fix.positionValid && fix.horizontalAccuracyM <= MAX_ACCURACY_M

    /**
     * Tracks ready to time laps (see [Track.problems]) within [rangeM] of
     * [position], nearest first. Several layouts of one circuit all come
     * back, at similar distances.
     */
    fun nearby(tracks: List<Track>, position: LatLon, rangeM: Double = DEFAULT_RANGE_M): List<Nearby> =
        tracks.filter { it.problems().isEmpty() }
            .mapNotNull { t -> distanceM(t, position)?.let { Nearby(t, it) } }
            .filter { it.distanceM <= rangeM }
            .sortedBy { it.distanceM }

    /**
     * Distance from [p] to the nearest part of [track]: its outline from
     * OpenStreetMap if it has one, and its timing lines. Null if the
     * track has neither.
     */
    fun distanceM(track: Track, p: LatLon): Double? {
        val lines = listOfNotNull(track.start, track.finish) + track.sectors
        val candidates = mutableListOf<Double>()
        snapToPolylines(p, track.outline)?.let { candidates += it.distanceM }
        snapToPolylines(p, lines.map { listOf(it.left, it.right) })?.let { candidates += it.distanceM }
        return candidates.minOrNull()
    }
}

/** Setting a track up from OpenStreetMap. */
object TrackSetup {
    /**
     * A start/finish line where [candidate] has a marker: across the track
     * at that point, [widthM] wide, facing the way the track is drawn.
     * That is the driving direction when [OsmTrackCandidate.drivingDirectionKnown];
     * otherwise it may need flipping. Null without a marker.
     */
    fun startFinishLine(candidate: OsmTrackCandidate, widthM: Double = 25.0): TimingLine? {
        val marker = candidate.startFinish ?: return null
        val snap = snapToPolylines(marker, candidate.polylines) ?: return null
        val line = candidate.polylines[snap.polylineIndex]
        // A marker is usually a vertex, where two segments meet: square the
        // line to both by averaging their directions.
        val i = snap.segmentIndex
        val atStart = distanceM(snap.point, line[i]) < 0.5 && i > 0
        val atEnd = distanceM(snap.point, line[i + 1]) < 0.5 && i + 2 < line.size
        val bearing = when {
            atStart -> averageBearing(bearingDeg(line[i - 1], line[i]), bearingDeg(line[i], line[i + 1]))
            atEnd -> averageBearing(bearingDeg(line[i], line[i + 1]), bearingDeg(line[i + 1], line[i + 2]))
            else -> snap.segmentBearingDeg
        }
        return TimingLine.across(snap.point, bearing, widthM)
    }

    private fun averageBearing(a: Double, b: Double): Double {
        val x = kotlin.math.sin(Math.toRadians(a)) + kotlin.math.sin(Math.toRadians(b))
        val y = kotlin.math.cos(Math.toRadians(a)) + kotlin.math.cos(Math.toRadians(b))
        return normalizeDeg(Math.toDegrees(kotlin.math.atan2(x, y)))
    }
}
