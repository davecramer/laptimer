package laptimer.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import kotlin.math.abs
import kotlin.math.hypot

/** One point of a recorded lap: where the car was, [t] seconds into the lap. */
@Serializable
data class RefPoint(val lat: Double, val lon: Double, val t: Double)

/**
 * The path and timing of a lap to compare against: the predictive delta
 * is the difference between the current lap and this one at the same
 * place on track.
 */
@Serializable
data class ReferenceLap(
    val trackId: String,
    /** The lines the lap was timed with. The lap only fits a track whose lines are the same. */
    val kind: TrackKind,
    val start: TimingLine?,
    val finish: TimingLine?,
    val lapTimeS: Double,
    /** When the lap was driven, as ISO-8601 UTC, if known. */
    val recordedUtc: String? = null,
    /** From the start crossing (t = 0) to the finish crossing (t = [lapTimeS]). */
    val points: List<RefPoint>,
) {
    /** True if this lap was timed on [track] as it is now. */
    fun fits(track: Track): Boolean =
        trackId == track.id && kind == track.kind && start == track.start &&
            (track.kind == TrackKind.CIRCUIT || finish == track.finish)
}

/**
 * Finds where the current lap is on a reference lap's path, and so how
 * long the reference lap took to get there.
 *
 * Matching by position, not by distance driven, keeps the delta right
 * when the current lap takes a different line: a lap 1% longer would
 * otherwise drift by about a second over a lap.
 */
class DeltaTracker(reference: ReferenceLap, proj: LocalProjection) {
    private val xs: DoubleArray
    private val ys: DoubleArray
    private val ts: DoubleArray

    /** The last match: index into the path, and the lap time it was made at. */
    private var cursor = 0
    private var cursorLapS = 0.0

    init {
        val pts = reference.points.map { proj.toXy(LatLon(it.lat, it.lon)) }
        xs = DoubleArray(pts.size) { pts[it].x }
        ys = DoubleArray(pts.size) { pts[it].y }
        ts = DoubleArray(pts.size) { reference.points[it].t }
    }

    /** Call when a new lap starts, so matching starts from the line. */
    fun reset() {
        cursor = 0
        cursorLapS = 0.0
    }

    /**
     * The reference lap's time at the point on its path nearest [p], or
     * null if the car is too far from the path, for example in the pits.
     * [lapS] is the time into the current lap.
     *
     * The search covers the stretch of the reference the car can have
     * reached since the last match: from 1 s behind it to 1.5 times the
     * time since, plus a second, ahead. Where a track doubles back past
     * itself, as at a hairpin, the other side is outside that stretch even
     * when it is nearer.
     *
     * If the car isn't near that stretch, it has been away from the path
     * (off track, or a long GPS gap). It is then matched to the whole lap,
     * choosing among the nearby stretches the one the reference reached at
     * about the same lap time.
     */
    fun referenceTimeAt(p: Xy, lapS: Double): Double? {
        if (xs.size < 2) return null
        val elapsed = (lapS - cursorLapS).coerceAtLeast(0.0)
        val fromT = ts[cursor] - 1.0
        val toT = ts[cursor] + 1.5 * elapsed + 1.0
        val from = indexAtOrBefore(fromT).coerceAtLeast(0)
        val to = (indexAtOrBefore(toT) + 1).coerceAtMost(xs.size - 2)
        val near = nearest(p, from, to)
        val match = if (near != null && near.distance <= MAX_DISTANCE_M) near else reacquire(p, lapS) ?: return null
        cursor = match.index
        cursorLapS = lapS
        return match.time
    }

    private inner class Match(val index: Int, val fraction: Double, val distance: Double) {
        val time: Double get() = ts[index] + fraction * (ts[index + 1] - ts[index])
    }

    /** The last index whose time is at most [t], or 0. */
    private fun indexAtOrBefore(t: Double): Int {
        val i = ts.binarySearch(t)
        return if (i >= 0) i else (-i - 2).coerceAtLeast(0)
    }

    private fun reacquire(p: Xy, lapS: Double): Match? {
        var best: Match? = null
        for (i in 0..xs.size - 2) {
            val m = matchSegment(p, i)
            if (m.distance > MAX_DISTANCE_M) continue
            if (best == null || abs(m.time - lapS) < abs(best.time - lapS)) best = m
        }
        return best
    }

    private fun matchSegment(p: Xy, i: Int): Match {
        val ax = xs[i]
        val ay = ys[i]
        val dx = xs[i + 1] - ax
        val dy = ys[i + 1] - ay
        val len2 = dx * dx + dy * dy
        val f = if (len2 == 0.0) 0.0 else (((p.x - ax) * dx + (p.y - ay) * dy) / len2).coerceIn(0.0, 1.0)
        return Match(i, f, hypot(ax + f * dx - p.x, ay + f * dy - p.y))
    }

    private fun nearest(p: Xy, from: Int, to: Int): Match? {
        var best: Match? = null
        for (i in from..to) {
            val m = matchSegment(p, i)
            if (best == null || m.distance < best.distance) best = m
        }
        return best
    }

    companion object {
        /** Further from the reference path than this, there is no delta. A track is 10 to 15 m wide. */
        const val MAX_DISTANCE_M = 20.0
    }
}

/**
 * Keeps each track's fastest lap as a reference, one JSON file per track
 * in [dir], so the delta works from the first lap of the next session.
 */
class ReferenceStore(private val dir: File) {
    private val json = Json { ignoreUnknownKeys = true }

    private fun fileFor(trackId: String): File {
        require(trackId.matches(Regex("[A-Za-z0-9-]+"))) { "bad track id: $trackId" }
        return File(dir, "$trackId.json")
    }

    /** The stored reference for [track], or null if there is none, it can't be read, or the track's lines have changed. */
    fun load(track: Track): ReferenceLap? {
        val f = fileFor(track.id)
        if (!f.isFile) return null
        return try {
            json.decodeFromString(ReferenceLap.serializer(), f.readText()).takeIf { it.fits(track) }
        } catch (e: IOException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    /** Writes via a temporary file, so a crash leaves the old reference intact. */
    fun save(reference: ReferenceLap) {
        dir.mkdirs()
        val target = fileFor(reference.trackId)
        val tmp = File(dir, "${reference.trackId}.json.tmp")
        tmp.outputStream().use { out ->
            out.write(json.encodeToString(ReferenceLap.serializer(), reference).toByteArray())
            out.fd.sync()
        }
        if (!tmp.renameTo(target)) {
            tmp.delete()
            throw IOException("could not write ${target.path}")
        }
    }

    fun delete(trackId: String) {
        fileFor(trackId).delete()
    }
}
