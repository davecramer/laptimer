package laptimer.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URLEncoder

/** A geographic rectangle. */
data class Bounds(val south: Double, val west: Double, val north: Double, val east: Double) {
    /** Length of the diagonal in meters. */
    val diagonalM: Double get() = distanceM(LatLon(south, west), LatLon(north, east))
}

/** One possible track found in OpenStreetMap. */
data class OsmTrackCandidate(
    val name: String?,
    /** What the OSM data says it is, for example "raceway" or "track area". */
    val kind: String,
    val polylines: List<List<LatLon>>,
    /** Elements it was built from, as "way/123" or "relation/456". */
    val osmElements: List<String>,
    /**
     * True when every way is tagged one-way. The polylines then run in
     * the driving direction, so new timing lines can face the right way.
     */
    val drivingDirectionKnown: Boolean = false,
) {
    val lengthM: Double get() = polylines.sumOf { lengthM(it) }
}

class OsmException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * Finds race tracks in OpenStreetMap through the Overpass API.
 *
 * Tracks are mapped two ways, and this looks for both:
 * - `highway=raceway`: the driving line, often split into several ways
 *   (main track, pit lane, alternative layouts). Ways that share a node
 *   are joined into one candidate.
 * - `leisure=track` with a motor sport: the area or outline of the track.
 */
object Overpass {
    private const val SPORTS = "motor|karting|motocross"

    fun query(b: Bounds): String {
        val bbox = "(${b.south},${b.west},${b.north},${b.east})"
        return """
            [out:json][timeout:25];
            (
              way["highway"="raceway"]$bbox;
              way["leisure"="track"]["sport"~"$SPORTS"]$bbox;
              relation["leisure"="track"]["sport"~"$SPORTS"]$bbox;
            );
            out geom;
        """.trimIndent()
    }

    /** Parses an Overpass JSON response into candidates, longest first. */
    fun parse(responseJson: String): List<OsmTrackCandidate> {
        val root = try {
            Json.parseToJsonElement(responseJson).jsonObject
        } catch (e: IllegalArgumentException) {
            throw OsmException("Overpass returned something that is not JSON", e)
        }
        // Overpass reports a timeout or memory limit inside a 200 response.
        root.str("remark")?.let { if (it.contains("error", ignoreCase = true)) throw OsmException("Overpass: $it") }

        val raceways = mutableListOf<OsmWay>()
        val result = mutableListOf<OsmTrackCandidate>()
        for (e in root["elements"]?.jsonArray.orEmpty()) {
            val o = e.jsonObject
            val tags = o["tags"]?.jsonObject
            when (o.str("type")) {
                "way" -> {
                    // oneway=-1 means the way is drawn against the driving direction.
                    val oneway = tags?.str("oneway")
                    val reversed = oneway == "-1"
                    val nodes = o["nodes"]?.jsonArray?.mapNotNull { (it as? JsonPrimitive)?.longOrNull }.orEmpty()
                    val geom = geometry(o["geometry"])
                    val way = OsmWay(
                        id = o.long("id") ?: continue,
                        name = tags?.str("name"),
                        nodes = if (reversed) nodes.reversed() else nodes,
                        geometry = if (reversed) geom.reversed() else geom,
                        oneway = reversed || oneway == "yes" || oneway == "true" || oneway == "1",
                    )
                    if (way.geometry.size < 2) continue
                    if (tags?.str("highway") == "raceway") {
                        raceways += way
                    } else {
                        result += OsmTrackCandidate(way.name, "track area", listOf(way.geometry), listOf("way/${way.id}"))
                    }
                }
                "relation" -> {
                    val members = o["members"]?.jsonArray.orEmpty().map { it.jsonObject }
                    val lines = members.filter { it.str("type") == "way" }.map { geometry(it["geometry"]) }.filter { it.size >= 2 }
                    if (lines.isNotEmpty()) {
                        result += OsmTrackCandidate(tags?.str("name"), "track area", lines, listOf("relation/${o.long("id")}"))
                    }
                }
            }
        }
        result += joinRaceways(raceways)
        return result.sortedByDescending { it.lengthM }
    }

    private class OsmWay(val id: Long, val name: String?, val nodes: List<Long>, val geometry: List<LatLon>, val oneway: Boolean)

    /** Groups raceway ways that share a node, so a circuit drawn as several ways becomes one candidate. */
    private fun joinRaceways(ways: List<OsmWay>): List<OsmTrackCandidate> {
        val parent = IntArray(ways.size) { it }
        fun find(i: Int): Int {
            var r = i
            while (parent[r] != r) r = parent[r]
            parent[i] = r
            return r
        }
        val firstWayAtNode = HashMap<String, Int>()
        for ((i, w) in ways.withIndex()) {
            // Node ids come with "out geom". Coordinates are a fallback in
            // case a server leaves them out.
            val keys = w.nodes.map { "n$it" }.ifEmpty { w.geometry.map { "%.7f,%.7f".format(it.lat, it.lon) } }
            for (k in keys) {
                val j = firstWayAtNode.putIfAbsent(k, i) ?: continue
                parent[find(i)] = find(j)
            }
        }
        return ways.indices.groupBy { find(it) }.values.map { group ->
            val members = group.map { ways[it] }
            // Use the most common name; pit lanes are often unnamed or named differently.
            val name = members.mapNotNull { it.name }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
            OsmTrackCandidate(
                name, "raceway", members.map { it.geometry }, members.map { "way/${it.id}" },
                drivingDirectionKnown = members.all { it.oneway },
            )
        }
    }

    private fun geometry(e: JsonElement?): List<LatLon> = (e as? JsonArray).orEmpty().mapNotNull {
        val p = it as? JsonObject ?: return@mapNotNull null
        val lat = (p["lat"] as? JsonPrimitive)?.doubleOrNull
        val lon = (p["lon"] as? JsonPrimitive)?.doubleOrNull
        if (lat == null || lon == null) null else LatLon(lat, lon)
    }
}

/** A place found by name. */
data class Place(val name: String, val center: LatLon, val bounds: Bounds?)

/** Looks up places by name through OpenStreetMap's Nominatim service. */
object Nominatim {
    fun searchUrl(baseUrl: String, text: String): String =
        "$baseUrl/search?format=jsonv2&limit=8&q=" + URLEncoder.encode(text, Charsets.UTF_8)

    fun parse(responseJson: String): List<Place> {
        val arr = try {
            Json.parseToJsonElement(responseJson).jsonArray
        } catch (e: IllegalArgumentException) {
            throw OsmException("Nominatim returned something that is not a list", e)
        }
        return arr.mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            // Nominatim sends numbers as strings.
            val lat = o.str("lat")?.toDoubleOrNull() ?: return@mapNotNull null
            val lon = o.str("lon")?.toDoubleOrNull() ?: return@mapNotNull null
            // boundingbox is [south, north, west, east].
            val bb = (o["boundingbox"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull() }
            val bounds = if (bb != null && bb.size == 4) Bounds(bb[0], bb[2], bb[1], bb[3]) else null
            Place(o.str("display_name") ?: "%.5f, %.5f".format(lat, lon), LatLon(lat, lon), bounds)
        }
    }
}

/** A response from an HTTP server. */
class HttpResponse(val code: Int, val body: String)

/** Sends one HTTP request. Replaced by a fake in tests. */
fun interface HttpTransport {
    /** Sends a GET, or a POST of a form when [formBody] is not null. */
    fun send(url: String, formBody: String?): HttpResponse
}

/** Uses [HttpURLConnection], which works on both the JVM and Android. */
class UrlConnectionTransport(
    private val userAgent: String,
    private val timeoutMs: Int = 30_000,
) : HttpTransport {
    override fun send(url: String, formBody: String?): HttpResponse {
        val c = URI(url).toURL().openConnection() as HttpURLConnection
        try {
            c.connectTimeout = timeoutMs
            c.readTimeout = timeoutMs
            // OSM services ask every client to identify itself.
            c.setRequestProperty("User-Agent", userAgent)
            if (formBody != null) {
                c.requestMethod = "POST"
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=utf-8")
                c.outputStream.use { it.write(formBody.toByteArray()) }
            }
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            return HttpResponse(code, stream?.use { it.readBytes().decodeToString() }.orEmpty())
        } finally {
            c.disconnect()
        }
    }
}

/**
 * Talks to OpenStreetMap services, trying each server in turn.
 *
 * The public Overpass servers are run by volunteers and are often at
 * capacity, answering 429, 502 or 504. Their guidance is that an app
 * published to many users should run its own server, so the server lists
 * are parameters.
 */
class OsmClient(
    private val transport: HttpTransport,
    private val overpassUrls: List<String> = DEFAULT_OVERPASS_URLS,
    private val nominatimUrl: String = DEFAULT_NOMINATIM_URL,
) {
    /** Finds track candidates in [bounds]. Blocks; call it off the main thread. */
    fun findTracks(bounds: Bounds): List<OsmTrackCandidate> {
        if (bounds.diagonalM > MAX_SEARCH_DIAGONAL_M) {
            throw OsmException("The area is too large. Zoom in to the track, then search again.")
        }
        val form = "data=" + URLEncoder.encode(Overpass.query(bounds), Charsets.UTF_8)
        val failures = mutableListOf<String>()
        for (url in overpassUrls) {
            val response = try {
                transport.send(url, form)
            } catch (e: SocketTimeoutException) {
                failures += "${host(url)}: timed out"
                continue
            } catch (e: IOException) {
                failures += "${host(url)}: ${e.message}"
                continue
            }
            if (response.code == 200) return Overpass.parse(response.body)
            failures += "${host(url)}: HTTP ${response.code}${if (response.code in BUSY_CODES) " (busy)" else ""}"
            // Any other error is a bad query, which every server would reject.
            if (response.code !in BUSY_CODES) break
        }
        throw OsmException("Could not reach OpenStreetMap. ${failures.joinToString("; ")}")
    }

    /** Finds places by name. Blocks; call it off the main thread. */
    fun searchPlaces(text: String): List<Place> {
        val response = try {
            transport.send(Nominatim.searchUrl(nominatimUrl, text), null)
        } catch (e: IOException) {
            throw OsmException("Place search failed: ${e.message}", e)
        }
        if (response.code != 200) throw OsmException("Place search failed: HTTP ${response.code}")
        return Nominatim.parse(response.body)
    }

    private fun host(url: String) = runCatching { URI(url).host }.getOrNull() ?: url

    companion object {
        val DEFAULT_OVERPASS_URLS = listOf(
            "https://overpass-api.de/api/interpreter",
            "https://overpass.private.coffee/api/interpreter",
            "https://maps.mail.ru/osm/tools/overpass/api/interpreter",
        )
        const val DEFAULT_NOMINATIM_URL = "https://nominatim.openstreetmap.org"

        /** Searching a whole region is slow for the server and not useful. */
        const val MAX_SEARCH_DIAGONAL_M = 30_000.0

        /** Codes that mean "busy or overloaded, try another server". */
        private val BUSY_CODES = setOf(429, 500, 502, 503, 504)
    }
}

private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
private fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull
