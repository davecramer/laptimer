package laptimer.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class GeoTest {
    private val origin = LatLon(36.5843, -121.7535)

    @Test
    fun projectionRoundTrips() {
        val proj = LocalProjection(origin)
        val p = LatLon(36.5900, -121.7400)
        val back = proj.toLatLon(proj.toXy(p))
        assertEquals(p.lat, back.lat, 1e-12)
        assertEquals(p.lon, back.lon, 1e-12)
    }

    @Test
    fun oneDegreeOfLatitudeIsAbout111Km() {
        assertEquals(111_195.0, distanceM(LatLon(0.0, 0.0), LatLon(1.0, 0.0)), 1.0)
    }

    @Test
    fun offsetMatchesDistanceAndBearing() {
        for (bearing in listOf(0.0, 45.0, 90.0, 180.0, 271.5)) {
            val p = offset(origin, bearing, 250.0)
            assertEquals(250.0, distanceM(origin, p), 1e-6, "bearing $bearing")
            assertEquals(bearing, bearingDeg(origin, p), 1e-6, "bearing $bearing")
        }
    }

    @Test
    fun snapFindsNearestSegmentAndItsDirection() {
        // A polyline running east, then a separate one running north 500 m away.
        val east = listOf(origin, offset(origin, 90.0, 200.0))
        val far = offset(origin, 0.0, 500.0)
        val north = listOf(far, offset(far, 0.0, 200.0))
        // 10 m north of the middle of the east-running line.
        val tap = offset(offset(origin, 90.0, 100.0), 0.0, 10.0)

        val snap = assertNotNull(snapToPolylines(tap, listOf(north, east)))
        assertEquals(1, snap.polylineIndex)
        assertEquals(0, snap.segmentIndex)
        assertEquals(10.0, snap.distanceM, 1e-6)
        assertEquals(90.0, snap.segmentBearingDeg, 1e-6)
        assertEquals(100.0, distanceM(origin, snap.point), 1e-6)
    }

    @Test
    fun snapPastTheEndUsesTheEndpoint() {
        val end = offset(origin, 90.0, 200.0)
        val tap = offset(end, 90.0, 30.0)
        val snap = assertNotNull(snapToPolylines(tap, listOf(listOf(origin, end))))
        assertEquals(30.0, snap.distanceM, 1e-6)
        assertEquals(0.0, distanceM(end, snap.point), 1e-6)
    }

    @Test
    fun snapWithNoSegmentsIsNull() {
        assertNull(snapToPolylines(origin, emptyList()))
        // A single point is not a segment, and neither is a zero-length one.
        assertNull(snapToPolylines(origin, listOf(listOf(origin), listOf(origin, origin))))
    }

    @Test
    fun normalizeWrapsIntoRange() {
        assertEquals(350.0, normalizeDeg(-10.0), 1e-12)
        assertEquals(10.0, normalizeDeg(370.0), 1e-12)
        assertEquals(0.0, normalizeDeg(360.0), 1e-12)
    }
}
