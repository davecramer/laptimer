package laptimer.app

import laptimer.core.LatLon
import laptimer.core.OsmClient
import laptimer.core.OsmTrackCandidate
import laptimer.core.Track
import laptimer.core.TrackKind
import laptimer.core.distanceM
import laptimer.core.offset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TrackEditorTest {
    private val origin = LatLon(36.5843, -121.7535)

    /** A 400 m straight drawn eastward, tagged one-way. */
    private val straight = OsmTrackCandidate(
        name = "Test Circuit",
        kind = "raceway",
        polylines = listOf(listOf(origin, offset(origin, 90.0, 400.0))),
        osmElements = listOf("way/1"),
        drivingDirectionKnown = true,
    )

    private fun editor(track: Track = Track(name = "")) =
        TrackEditor(track, OsmClient({ _, _ -> error("no network in tests") }))

    @Test
    fun pickingACandidateFillsOutlineAndName() {
        val e = editor()
        e.pickCandidate(straight)
        assertEquals("Test Circuit", e.track.name)
        assertEquals(straight.polylines, e.track.outline)
        assertTrue(e.track.outlineHasDirection)
        assertEquals(listOf("way/1"), e.track.source!!.osmElements)
        assertTrue(e.changed)
    }

    @Test
    fun tapNearTheTrackSnapsAcrossIt() {
        val e = editor()
        e.pickCandidate(straight)
        e.startPlacing(Slot.Start)
        // 8 m north of the straight, 100 m along.
        e.onMapTap(offset(offset(origin, 90.0, 100.0), 0.0, 8.0))

        val line = assertNotNull(e.track.start)
        assertEquals(0.0, distanceM(line.center, offset(origin, 90.0, 100.0)), 0.01)
        assertEquals(90.0, line.forwardBearingDeg, 1e-6)
        assertEquals(TrackEditor.DEFAULT_WIDTH_M, line.widthM, 1e-6)
        assertNull(e.placing)
        assertEquals(Slot.Start, e.selected)
    }

    @Test
    fun movingALineKeepsTheChosenDirectionAndWidth() {
        val e = editor()
        e.pickCandidate(straight)
        e.startPlacing(Slot.Start)
        e.onMapTap(offset(origin, 90.0, 100.0))
        e.flipSelected()
        e.changeSelectedWidth(-10.0)

        e.startPlacing(Slot.Start)
        e.onMapTap(offset(origin, 90.0, 300.0))
        val line = assertNotNull(e.track.start)
        assertEquals(270.0, line.forwardBearingDeg, 1e-6)
        assertEquals(15.0, line.widthM, 1e-6)
        assertEquals(0.0, distanceM(line.center, offset(origin, 90.0, 300.0)), 0.01)
    }

    @Test
    fun tapAwayFromTheTrackPlacesBothEndsByHand() {
        val e = editor()
        e.pickCandidate(straight)
        e.startPlacing(Slot.Start)
        val left = offset(origin, 0.0, 200.0)
        val right = offset(left, 180.0, 20.0)
        e.onMapTap(left)
        assertEquals(left, e.firstTap)
        assertNull(e.track.start)
        // Too close to the first tap: ignored.
        e.onMapTap(offset(left, 180.0, 1.0))
        assertNull(e.track.start)
        e.onMapTap(right)
        val line = assertNotNull(e.track.start)
        assertEquals(left, line.left)
        assertEquals(right, line.right)
        assertNull(e.firstTap)
    }

    @Test
    fun sectorsAppendAndRenumberAfterDelete() {
        val e = editor()
        e.pickCandidate(straight)
        for (d in listOf(100.0, 200.0, 300.0)) {
            e.startPlacing(Slot.NewSector)
            e.onMapTap(offset(origin, 90.0, d))
        }
        assertEquals(3, e.track.sectors.size)
        assertEquals(Slot.Sector(2), e.selected)

        // Tapping near a line selects it; then delete the middle one.
        e.onMapTap(offset(origin, 90.0, 205.0))
        assertEquals(Slot.Sector(1), e.selected)
        e.deleteSelected()
        val centers = e.track.sectors.map { distanceM(origin, it.center) }
        assertEquals(listOf(100.0, 300.0), centers.map { Math.round(it).toDouble() })
        assertEquals(listOf(Slot.Start, Slot.Sector(0), Slot.Sector(1)), e.slots())
    }

    @Test
    fun widthStaysWithinLimits() {
        val e = editor()
        e.pickCandidate(straight)
        e.startPlacing(Slot.Start)
        e.onMapTap(offset(origin, 90.0, 100.0))
        repeat(10) { e.changeSelectedWidth(-5.0) }
        assertEquals(TrackEditor.MIN_WIDTH_M, e.track.start!!.widthM, 1e-6)
        repeat(30) { e.changeSelectedWidth(5.0) }
        assertEquals(TrackEditor.MAX_WIDTH_M, e.track.start!!.widthM, 1e-6)
    }

    @Test
    fun pointToPointAddsAFinishSlot() {
        val e = editor()
        assertEquals(listOf(Slot.Start), e.slots())
        e.setKind(TrackKind.POINT_TO_POINT)
        assertEquals(listOf(Slot.Start, Slot.Finish), e.slots())
        e.startPlacing(Slot.Finish)
        e.setKind(TrackKind.CIRCUIT)
        assertNull(e.placing)
    }

    @Test
    fun mapShowsAnArrowForEachLine() {
        val e = editor()
        e.pickCandidate(straight)
        e.startPlacing(Slot.Start)
        e.onMapTap(offset(origin, 90.0, 100.0))
        // Outline, then the line, its arrow shaft and its arrowhead.
        assertEquals(4, e.mapLines().size)
    }

    @Test
    fun anUnnamedTrackTakesTheSearchedPlacesName() {
        val e = editor()
        val unnamed = straight.copy(name = null)
        assertNull(e.nameFor(unnamed))
        e.pickPlace(laptimer.core.Place("Canadian Tire Motorsport Park, Mosport Road, Clarington, Ontario, Canada", origin, null))
        assertEquals("Canadian Tire Motorsport Park", e.nameFor(unnamed))
        // A track's own name still wins.
        assertEquals("Test Circuit", e.nameFor(straight))
        e.pickCandidate(unnamed)
        assertEquals("Canadian Tire Motorsport Park", e.track.name)
    }

    @Test
    fun tappingAFoundTracksOutlinePicksIt() = kotlinx.coroutines.runBlocking {
        // A 400 m circuit straight and, 300 m north, a kart track.
        val overpass = """{"elements": [
            { "type": "way", "id": 1, "nodes": [1, 2], "tags": { "highway": "raceway", "name": "Circuit" },
              "geometry": [ {"lat": 36.5843, "lon": -121.7535}, {"lat": 36.5843, "lon": -121.7490} ] },
            { "type": "way", "id": 2, "nodes": [3, 4], "tags": { "highway": "raceway", "name": "Kart Track" },
              "geometry": [ {"lat": 36.5870, "lon": -121.7535}, {"lat": 36.5870, "lon": -121.7510} ] } ]}"""
        val e = TrackEditor(Track(name = ""), OsmClient({ _, _ -> laptimer.core.HttpResponse(200, overpass) }))
        e.visibleBounds = laptimer.core.Bounds(36.58, -121.76, 36.59, -121.74)
        e.findTracks()
        assertEquals(2, e.candidates.size)
        // Far from both: nothing is picked.
        e.onMapTap(LatLon(36.5800, -121.7520))
        assertTrue(e.track.outline.isEmpty())
        // About 10 m off the kart track.
        e.onMapTap(LatLon(36.5871, -121.7520))
        assertEquals("Kart Track", e.track.name)
        assertTrue(e.candidates.isEmpty())
    }

    @Test
    fun theBestSearchMatchNamesTracksBeforeAPlaceIsPicked() = kotlinx.coroutines.runBlocking {
        val nominatim = """[{"lat": "44.05", "lon": "-78.68", "display_name": "Canadian Tire Motorsport Park, Clarington, Ontario"}]"""
        val e = TrackEditor(Track(name = ""), OsmClient({ _, _ -> laptimer.core.HttpResponse(200, nominatim) }))
        e.searchPlaces("mosport")
        assertEquals("Canadian Tire Motorsport Park", e.nameFor(straight.copy(name = null)))
    }
}
