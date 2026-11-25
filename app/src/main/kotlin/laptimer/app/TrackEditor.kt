package laptimer.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import laptimer.core.Bounds
import laptimer.core.LatLon
import laptimer.core.OsmClient
import laptimer.core.OsmTrackCandidate
import laptimer.core.Place
import laptimer.core.TimingLine
import laptimer.core.Track
import laptimer.core.TrackKind
import laptimer.core.TrackSetup
import laptimer.core.TrackSource
import laptimer.core.distanceM
import laptimer.core.normalizeDeg
import laptimer.core.offset
import laptimer.core.snapToPolylines
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs

/** Which timing line of a track. */
sealed interface Slot {
    data object Start : Slot
    data object Finish : Slot
    data class Sector(val index: Int) : Slot

    /** A sector line that does not exist yet; placing it appends one. */
    data object NewSector : Slot
}

/**
 * State of the track editor screen. Plain Compose state, so the screen
 * redraws when any of it changes.
 */
class TrackEditor(initial: Track, private val osm: OsmClient) {
    // Declared first: initialCamera below uses it.
    private var seq = 0

    var track by mutableStateOf(initial)
        private set

    /** Tracks found in OpenStreetMap that the user has not picked from yet. */
    var candidates by mutableStateOf<List<OsmTrackCandidate>>(emptyList())
        private set
    var places by mutableStateOf<List<Place>>(emptyList())
        private set

    /** The line the next map tap places, or null when taps select lines. */
    var placing by mutableStateOf<Slot?>(null)
        private set

    /** The line the flip, width and delete buttons act on. */
    var selected by mutableStateOf<Slot?>(null)
        private set

    /** When placing by hand, the left end already tapped. */
    var firstTap by mutableStateOf<LatLon?>(null)
        private set

    var message by mutableStateOf<String?>(null)
        private set
    var busy by mutableStateOf(false)
        private set
    var camera by mutableStateOf<CameraMove?>(initialCamera(initial))
        private set
    var changed by mutableStateOf(false)
        private set

    /** Set by the map when it stops moving. */
    var visibleBounds: Bounds? = null

    fun rename(name: String) = update(track.copy(name = name))

    fun setKind(kind: TrackKind) {
        if (kind == track.kind) return
        // Keep a placed finish line in case the user switches back.
        update(track.copy(kind = kind))
        if (kind == TrackKind.CIRCUIT && (selected == Slot.Finish || placing == Slot.Finish)) {
            selected = null
            placing = null
        }
    }

    fun line(slot: Slot): TimingLine? = when (slot) {
        Slot.Start -> track.start
        Slot.Finish -> track.finish
        is Slot.Sector -> track.sectors.getOrNull(slot.index)
        Slot.NewSector -> null
    }

    /** The line slots the screen should list, in driving order. */
    fun slots(): List<Slot> = buildList {
        add(Slot.Start)
        track.sectors.indices.forEach { add(Slot.Sector(it)) }
        if (track.kind == TrackKind.POINT_TO_POINT) add(Slot.Finish)
    }

    fun label(slot: Slot): String = when (slot) {
        Slot.Start -> if (track.kind == TrackKind.CIRCUIT) "Start/finish line" else "Start line"
        Slot.Finish -> "Finish line"
        is Slot.Sector -> "Sector line ${slot.index + 1}"
        Slot.NewSector -> "New sector line"
    }

    fun startPlacing(slot: Slot) {
        placing = slot
        selected = null
        firstTap = null
        message = if (track.outline.isEmpty()) {
            "Tap the end of the line on the driver's left, then the end on the right."
        } else {
            "Tap the track where the ${label(slot).lowercase()} goes. Tap off the track to place both ends by hand."
        }
    }

    fun cancelPlacing() {
        placing = null
        firstTap = null
        message = null
    }

    fun select(slot: Slot?) {
        selected = slot
        placing = null
        firstTap = null
        message = null
    }

    fun onMapTap(p: LatLon) {
        val slot = placing
        if (slot == null) {
            val c = candidateNear(p)
            if (c != null) pickCandidate(c) else selectNear(p)
            return
        }
        val first = firstTap
        if (first != null) {
            if (distanceM(first, p) < MIN_WIDTH_M) {
                message = "The two ends are too close together. Tap the right end further away."
                return
            }
            finishPlacing(slot, TimingLine(left = first, right = p))
            return
        }
        val snap = snapToPolylines(p, track.outline)
        if (snap == null || snap.distanceM > SNAP_DISTANCE_M) {
            firstTap = p
            message = "Now tap the end of the line on the driver's right."
            return
        }
        // A new line follows the way's drawing order, which is the driving
        // direction when the ways are one-way and a guess otherwise. A
        // moved line keeps the direction the user already chose.
        var forward = snap.segmentBearingDeg
        val old = line(slot)
        if (old != null && angleBetween(forward, old.forwardBearingDeg) > 90.0) forward += 180.0
        finishPlacing(slot, TimingLine.across(snap.point, forward, old?.widthM ?: DEFAULT_WIDTH_M), snapped = true)
    }

    private fun finishPlacing(slot: Slot, line: TimingLine, snapped: Boolean = false) {
        val placed = setLine(slot, line)
        placing = null
        firstTap = null
        selected = placed
        message = if (snapped && track.outlineHasDirection) {
            "The arrow follows the driving direction from OpenStreetMap. Check it, and tap Flip if it's wrong."
        } else {
            "Check that the arrow points the way you drive. If it doesn't, tap Flip."
        }
    }

    /** Stores [line] in [slot] and returns the slot it ended up in. */
    private fun setLine(slot: Slot, line: TimingLine): Slot {
        val t = track
        return when (slot) {
            Slot.Start -> { update(t.copy(start = line)); slot }
            Slot.Finish -> { update(t.copy(finish = line)); slot }
            is Slot.Sector -> { update(t.copy(sectors = t.sectors.toMutableList().also { it[slot.index] = line })); slot }
            Slot.NewSector -> { update(t.copy(sectors = t.sectors + line)); Slot.Sector(t.sectors.size) }
        }
    }

    fun flipSelected() = editSelected { it.flipped() }

    fun changeSelectedWidth(deltaM: Double) =
        editSelected { it.withWidth((it.widthM + deltaM).coerceIn(MIN_WIDTH_M, MAX_WIDTH_M)) }

    private fun editSelected(change: (TimingLine) -> TimingLine) {
        val slot = selected ?: return
        val l = line(slot) ?: return
        setLine(slot, change(l))
    }

    fun deleteSelected() {
        val t = track
        when (val slot = selected ?: return) {
            Slot.Start -> update(t.copy(start = null))
            Slot.Finish -> update(t.copy(finish = null))
            is Slot.Sector -> update(t.copy(sectors = t.sectors.filterIndexed { i, _ -> i != slot.index }))
            Slot.NewSector -> {}
        }
        selected = null
    }

    /** The found track drawn nearest [p], if the tap was on one. */
    private fun candidateNear(p: LatLon): OsmTrackCandidate? = candidates
        .mapNotNull { c -> snapToPolylines(p, c.polylines)?.let { c to it.distanceM } }
        .filter { it.second <= PICK_DISTANCE_M }
        .minByOrNull { it.second }
        ?.first

    private fun selectNear(p: LatLon) {
        val nearest = slots()
            .mapNotNull { s -> line(s)?.let { s to distanceM(p, it.center) } }
            .minByOrNull { it.second }
        select(nearest?.takeIf { it.second <= SELECT_DISTANCE_M }?.first)
    }

    fun pickCandidate(c: OsmTrackCandidate) {
        // Where a mapper marked the start/finish line, place it there, unless one is placed already.
        val marked = if (track.start == null) TrackSetup.startFinishLine(c) else null
        update(
            track.copy(
                name = track.name.ifBlank { nameFor(c).orEmpty() },
                outline = c.polylines,
                outlineHasDirection = c.drivingDirectionKnown,
                source = TrackSource(c.osmElements),
                start = track.start ?: marked,
            ),
        )
        candidates = emptyList()
        message = if (marked != null) {
            selected = Slot.Start
            "Start/finish line placed where OpenStreetMap marks it (${c.startFinishTag}). Check that it sits on the " +
                "painted line, and move it if not. " + if (c.drivingDirectionKnown) {
                    "The arrow follows the mapped driving direction."
                } else {
                    "The map doesn't give the driving direction here: check the arrow, and tap Flip if it's wrong."
                }
        } else {
            "Now place the start/finish line."
        }
        camera = CameraMove(nextSeq(), bounds = boundsOf(c.polylines.flatten()))
    }

    fun clearOutline() {
        update(track.copy(outline = emptyList(), outlineHasDirection = false, source = null))
    }

    /** The place last picked from the search, to name tracks whose ways carry no circuit name. */
    var placeName: String? = null
        private set

    /**
     * What to call [c] in the list and when saving: its own name, or the
     * searched place's, as at Canadian Tire Motorsport Park, whose ways
     * are named only after corners.
     */
    fun nameFor(c: OsmTrackCandidate): String? = c.name ?: placeName

    fun pickPlace(p: Place) {
        placeName = shortName(p)
        places = emptyList()
        camera = if (p.bounds != null && p.bounds!!.diagonalM < OsmClient.MAX_SEARCH_DIAGONAL_M) {
            CameraMove(nextSeq(), bounds = p.bounds)
        } else {
            CameraMove(nextSeq(), center = p.center, zoom = 14.0)
        }
    }

    // Nominatim's display name runs from the place itself out to the country.
    private fun shortName(p: Place) = p.name.substringBefore(",").trim().ifBlank { null }

    fun dismissPlaces() {
        places = emptyList()
    }

    /** Looks up places by name. The request runs on the IO thread; state changes stay on the caller's. */
    suspend fun searchPlaces(text: String) = runRequest {
        val found = withContext(Dispatchers.IO) { osm.searchPlaces(text) }
        if (found.isEmpty()) message = "No places found for \"$text\"."
        // Until one is picked, the best match names tracks found nearby.
        found.firstOrNull()?.let { placeName = shortName(it) }
        places = found
    }

    /** Looks up tracks in the visible area. The request runs on the IO thread. */
    suspend fun findTracks() = runRequest {
        val b = visibleBounds ?: run {
            message = "Wait for the map to load."
            return@runRequest
        }
        val found = withContext(Dispatchers.IO) { osm.findTracks(b) }
        candidates = found
        message = if (found.isEmpty()) {
            "No tracks are mapped in this area. You can still place the lines by hand."
        } else {
            "Found ${found.size}. Tap one in the list below, or its outline on the map."
        }
    }

    private suspend fun runRequest(block: suspend () -> Unit) {
        if (busy) return
        busy = true
        message = null
        try {
            block()
        } catch (e: java.io.IOException) {
            message = e.message ?: "Network error."
        } catch (e: IllegalArgumentException) {
            message = "OpenStreetMap sent an answer this app could not read: ${e.message}"
        } finally {
            busy = false
        }
    }

    /** What the map should draw: candidates, then the outline, then the timing lines. */
    fun mapLines(): List<MapLine> = buildList {
        for (c in candidates) for (p in c.polylines) add(MapLine(p, "#757575", 4f))
        for (p in track.outline) add(MapLine(p, "#1E88E5", 5f))
        for (slot in slots()) {
            val l = line(slot) ?: continue
            val color = when (slot) {
                Slot.Start -> "#2E7D32"
                Slot.Finish -> "#C62828"
                else -> "#F9A825"
            }
            val width = if (slot == selected) 7f else 4f
            add(MapLine(listOf(l.left, l.right), color, width))
            addAll(arrow(l).map { MapLine(it, color, width) })
        }
    }

    fun mapDots(): List<MapDot> = listOfNotNull(firstTap?.let { MapDot(it, "#C62828") })

    private fun arrow(l: TimingLine): List<List<LatLon>> {
        val len = (l.widthM * 0.5).coerceIn(6.0, 20.0)
        val f = l.forwardBearingDeg
        val tip = offset(l.center, f, len)
        return listOf(
            listOf(l.center, tip),
            listOf(offset(tip, f + 150.0, len / 3), tip, offset(tip, f - 150.0, len / 3)),
        )
    }

    private fun update(t: Track) {
        track = t
        changed = true
    }

    private fun nextSeq() = ++seq

    private fun initialCamera(t: Track): CameraMove? {
        val points = t.outline.flatten() + listOfNotNull(t.start, t.finish).flatMap { listOf(it.left, it.right) } +
            t.sectors.flatMap { listOf(it.left, it.right) }
        return if (points.isEmpty()) null else CameraMove(nextSeq(), bounds = boundsOf(points))
    }

    companion object {
        /** Wide enough for the track and GPS error, narrow enough to miss a parallel straight. */
        const val DEFAULT_WIDTH_M = 25.0
        const val MIN_WIDTH_M = 5.0
        const val MAX_WIDTH_M = 100.0

        /** A tap closer than this to the OSM centerline snaps to it. */
        const val SNAP_DISTANCE_M = 40.0

        /** A tap closer than this to a line's center selects it. */
        const val SELECT_DISTANCE_M = 30.0

        /** A tap closer than this to a found track's outline picks it. */
        const val PICK_DISTANCE_M = 50.0
    }
}

fun boundsOf(points: List<LatLon>) = Bounds(
    south = points.minOf { it.lat },
    west = points.minOf { it.lon },
    north = points.maxOf { it.lat },
    east = points.maxOf { it.lon },
)

private fun angleBetween(a: Double, b: Double): Double {
    val d = abs(normalizeDeg(a) - normalizeDeg(b))
    return if (d > 180.0) 360.0 - d else d
}
