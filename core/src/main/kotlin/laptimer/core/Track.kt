package laptimer.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * A timing line between two points.
 *
 * The order of the points sets the direction a lap counts in: seen from
 * the car, [left] is on the driver's left and [right] on the right. A car
 * crossing the other way, for example reversing over the line, does not
 * count.
 */
@Serializable
data class TimingLine(val left: LatLon, val right: LatLon) {
    val center: LatLon get() = midpoint(left, right)

    // Width and direction are measured in a projection around the center,
    // the same one [across] builds lines in, so the two agree exactly.
    val widthM: Double get() {
        val proj = LocalProjection(center)
        val l = proj.toXy(left)
        val r = proj.toXy(right)
        return hypot(r.x - l.x, r.y - l.y)
    }

    /** The direction of travel that counts, in degrees clockwise from north. */
    val forwardBearingDeg: Double get() {
        val proj = LocalProjection(center)
        val l = proj.toXy(left)
        val r = proj.toXy(right)
        return normalizeDeg(Math.toDegrees(atan2(r.x - l.x, r.y - l.y)) - 90.0)
    }

    /** The same line counting the opposite direction. */
    fun flipped() = TimingLine(right, left)

    fun withWidth(widthM: Double) = across(center, forwardBearingDeg, widthM)

    companion object {
        /** A line through [center], at right angles to travel in direction [forwardBearingDeg]. */
        fun across(center: LatLon, forwardBearingDeg: Double, widthM: Double) = TimingLine(
            left = offset(center, forwardBearingDeg - 90.0, widthM / 2),
            right = offset(center, forwardBearingDeg + 90.0, widthM / 2),
        )
    }
}

@Serializable
enum class TrackKind {
    /** Laps start and end at the same line. */
    CIRCUIT,

    /** A run from a start line to a separate finish line, such as a hill climb. */
    POINT_TO_POINT,
}

/** Where a track's outline came from, kept for the OpenStreetMap license. */
@Serializable
data class TrackSource(
    /** OpenStreetMap elements, written as "way/123" or "relation/456". */
    val osmElements: List<String>,
    val attribution: String = OSM_ATTRIBUTION,
)

const val OSM_ATTRIBUTION = "© OpenStreetMap contributors, ODbL"

@Serializable
data class Track(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val kind: TrackKind = TrackKind.CIRCUIT,
    /** The start line, which is also the finish line on a [TrackKind.CIRCUIT]. */
    val start: TimingLine? = null,
    /** Only used on a [TrackKind.POINT_TO_POINT] track. */
    val finish: TimingLine? = null,
    /** Sector lines in driving order. N lines split a lap into N + 1 sectors. */
    val sectors: List<TimingLine> = emptyList(),
    /** The track's centerline from OpenStreetMap, one polyline per way. Only drawn, not used for timing. */
    val outline: List<List<LatLon>> = emptyList(),
    /** True when [outline] is drawn in the driving direction (OSM ways tagged one-way). */
    val outlineHasDirection: Boolean = false,
    val source: TrackSource? = null,
) {
    /** What still has to be set before the track can time laps. Empty when ready. */
    fun problems(): List<String> = buildList {
        if (name.isBlank()) add("The track needs a name.")
        if (start == null) add(if (kind == TrackKind.CIRCUIT) "Place the start/finish line." else "Place the start line.")
        if (kind == TrackKind.POINT_TO_POINT && finish == null) add("Place the finish line.")
    }
}

/**
 * Track file format: one JSON object per file, see [TrackFile].
 *
 * [TrackFile.format] goes up when a change would make older versions of
 * the app misread the file. Adding a field with a default value does not
 * need a new format number, because unknown fields are ignored.
 */
@Serializable
private data class TrackFile(val format: Int, val track: Track)

object TrackJson {
    const val FORMAT = 1

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val compactJson = Json(json) { prettyPrint = false }

    fun encode(track: Track): String = json.encodeToString(TrackFile.serializer(), TrackFile(FORMAT, track))

    /** The same as [encode] on one line, for a session log's metadata. [decode] reads both. */
    fun encodeCompact(track: Track): String = compactJson.encodeToString(TrackFile.serializer(), TrackFile(FORMAT, track))

    /** Throws [IllegalArgumentException] if the text is not a track file this version can read. */
    fun decode(text: String): Track {
        val file = try {
            json.decodeFromString(TrackFile.serializer(), text)
        } catch (e: kotlinx.serialization.SerializationException) {
            throw IllegalArgumentException("not a track file: ${e.message}", e)
        }
        require(file.format <= FORMAT) { "track file format ${file.format} is newer than this app (reads up to $FORMAT)" }
        return file.track
    }
}

/** Saves tracks as one JSON file each in [dir]. */
class TrackStore(private val dir: File) {
    private fun fileFor(id: String): File {
        // Ids are generated UUIDs, but a file copied in by hand could hold anything.
        require(id.matches(Regex("[A-Za-z0-9-]+"))) { "bad track id: $id" }
        return File(dir, "$id.json")
    }

    /** All readable tracks, by name. Files that fail to read are returned in [LoadResult.errors]. */
    fun loadAll(): LoadResult {
        val tracks = mutableListOf<Track>()
        val errors = mutableListOf<String>()
        dir.listFiles { f -> f.isFile && f.name.endsWith(".json") }?.forEach { f ->
            try {
                tracks += TrackJson.decode(f.readText())
            } catch (e: IllegalArgumentException) {
                errors += "${f.name}: ${e.message}"
            } catch (e: IOException) {
                errors += "${f.name}: ${e.message}"
            }
        }
        return LoadResult(tracks.sortedBy { it.name.lowercase() }, errors)
    }

    /**
     * Writes to a temporary file and renames it over the old one, so a
     * crash during the write leaves the previous version intact.
     */
    fun save(track: Track) {
        dir.mkdirs()
        val target = fileFor(track.id)
        val tmp = File(dir, "${track.id}.json.tmp")
        tmp.outputStream().use { out ->
            out.write(TrackJson.encode(track).toByteArray())
            out.fd.sync()
        }
        if (!tmp.renameTo(target)) {
            tmp.delete()
            throw IOException("could not write ${target.path}")
        }
    }

    fun delete(id: String) {
        fileFor(id).delete()
    }

    class LoadResult(val tracks: List<Track>, val errors: List<String>)
}
