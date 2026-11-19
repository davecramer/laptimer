package laptimer.core

import kotlinx.serialization.Serializable
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

@Serializable
data class LatLon(val lat: Double, val lon: Double)

/** Mean Earth radius in meters (IUGG). */
const val EARTH_RADIUS_M = 6_371_008.8

/** A point in a [LocalProjection], in meters: x east, y north. */
data class Xy(val x: Double, val y: Double)

/**
 * Flat east/north coordinates in meters around [origin].
 *
 * A race track fits in a few kilometers, where treating the Earth as flat
 * is accurate to well under a centimeter per 100 m. That keeps the
 * geometry simple: distances, bearings and intersections are plane
 * geometry. Do not use one projection for points more than about 50 km
 * apart.
 */
class LocalProjection(val origin: LatLon) {
    private val metersPerDegLat = EARTH_RADIUS_M * PI / 180.0
    private val metersPerDegLon = metersPerDegLat * cos(Math.toRadians(origin.lat))

    fun toXy(p: LatLon) = Xy((p.lon - origin.lon) * metersPerDegLon, (p.lat - origin.lat) * metersPerDegLat)

    fun toLatLon(p: Xy) = LatLon(origin.lat + p.y / metersPerDegLat, origin.lon + p.x / metersPerDegLon)
}

/** Distance in meters. Uses a local flat projection, so only for nearby points. */
fun distanceM(a: LatLon, b: LatLon): Double {
    val proj = LocalProjection(a)
    val p = proj.toXy(b)
    return hypot(p.x, p.y)
}

/** Bearing from [a] to [b] in degrees clockwise from north, in [0, 360). */
fun bearingDeg(a: LatLon, b: LatLon): Double {
    val p = LocalProjection(a).toXy(b)
    return normalizeDeg(Math.toDegrees(atan2(p.x, p.y)))
}

/** The point [distanceM] meters from [from] in direction [bearingDeg]. */
fun offset(from: LatLon, bearingDeg: Double, distanceM: Double): LatLon {
    val r = Math.toRadians(bearingDeg)
    return LocalProjection(from).toLatLon(Xy(distanceM * sin(r), distanceM * cos(r)))
}

fun midpoint(a: LatLon, b: LatLon) = LatLon((a.lat + b.lat) / 2, (a.lon + b.lon) / 2)

fun normalizeDeg(deg: Double): Double {
    val d = deg % 360.0
    return if (d < 0) d + 360.0 else d
}

/** Length of a polyline in meters. */
fun lengthM(points: List<LatLon>): Double =
    points.zipWithNext { a, b -> distanceM(a, b) }.sum()

/** The nearest point on a set of polylines to a given point. */
data class Snap(
    val point: LatLon,
    /** Distance from the query point to [point], in meters. */
    val distanceM: Double,
    /** Bearing of the polyline segment at [point], in its drawing order. */
    val segmentBearingDeg: Double,
    val polylineIndex: Int,
    val segmentIndex: Int,
)

/** Finds the nearest point to [p] on any segment of [polylines], or null if there are no segments. */
fun snapToPolylines(p: LatLon, polylines: List<List<LatLon>>): Snap? {
    val proj = LocalProjection(p)
    var best: Snap? = null
    for ((pi, line) in polylines.withIndex()) {
        for (si in 0 until line.size - 1) {
            val a = proj.toXy(line[si])
            val b = proj.toXy(line[si + 1])
            val dx = b.x - a.x
            val dy = b.y - a.y
            val len2 = dx * dx + dy * dy
            if (len2 == 0.0) continue
            // The query point is the projection origin, so it is (0, 0).
            val t = (-(a.x * dx + a.y * dy) / len2).coerceIn(0.0, 1.0)
            val q = Xy(a.x + t * dx, a.y + t * dy)
            val d = hypot(q.x, q.y)
            if (best == null || d < best.distanceM) {
                val bearing = normalizeDeg(Math.toDegrees(atan2(dx, dy)))
                best = Snap(proj.toLatLon(q), d, bearing, pi, si)
            }
        }
    }
    return best
}
