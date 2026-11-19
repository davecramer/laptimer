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
}
