package laptimer.core

import java.net.SocketTimeoutException
import java.net.URLDecoder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OsmApiTest {
    /**
     * Shaped like an "out geom" response: ways have node ids and
     * geometry, relation members carry their own geometry.
     */
    private val response = """
        {
          "version": 0.6,
          "generator": "Overpass API",
          "osm3s": { "copyright": "The data included in this document is from www.openstreetmap.org." },
          "elements": [
            { "type": "way", "id": 11, "nodes": [1, 2, 3],
              "geometry": [ {"lat": 36.580, "lon": -121.760}, {"lat": 36.590, "lon": -121.760}, {"lat": 36.590, "lon": -121.750} ],
              "tags": { "highway": "raceway", "name": "Main Circuit" } },
            { "type": "way", "id": 12, "nodes": [3, 4, 1],
              "geometry": [ {"lat": 36.590, "lon": -121.750}, {"lat": 36.580, "lon": -121.750}, {"lat": 36.580, "lon": -121.760} ],
              "tags": { "highway": "raceway", "name": "Main Circuit" } },
            { "type": "way", "id": 13, "nodes": [2, 5],
              "geometry": [ {"lat": 36.590, "lon": -121.760}, {"lat": 36.591, "lon": -121.758} ],
              "tags": { "highway": "raceway", "raceway": "pit_lane" } },
            { "type": "way", "id": 20, "nodes": [7, 8],
              "geometry": [ {"lat": 36.600, "lon": -121.700}, {"lat": 36.601, "lon": -121.700} ],
              "tags": { "highway": "raceway", "name": "Kart Track" } },
            { "type": "way", "id": 21, "nodes": [9],
              "geometry": [ {"lat": 36.600, "lon": -121.700} ],
              "tags": { "highway": "raceway" } },
            { "type": "relation", "id": 30,
              "members": [
                { "type": "way", "ref": 99, "role": "outer",
                  "geometry": [ {"lat": 36.570, "lon": -121.770}, {"lat": 36.600, "lon": -121.770}, {"lat": 36.600, "lon": -121.740} ] },
                { "type": "node", "ref": 5, "role": "" }
              ],
              "tags": { "leisure": "track", "sport": "motor", "name": "Raceway Area" } }
          ]
        }
    """.trimIndent()

    @Test
    fun queryCoversBothMappingStyles() {
        val q = Overpass.query(Bounds(36.5, -121.8, 36.6, -121.7))
        assertTrue(q.contains("way[\"highway\"=\"raceway\"](36.5,-121.8,36.6,-121.7)"))
        assertTrue(q.contains("relation[\"leisure\"=\"track\"]"))
        assertTrue(q.contains("out geom;"))
    }

    @Test
    fun parseJoinsConnectedRacewaysAndKeepsAreas() {
        val candidates = Overpass.parse(response)
        assertEquals(listOf("Raceway Area", "Main Circuit", "Kart Track"), candidates.map { it.name })

        val circuit = candidates[1]
        assertEquals("raceway", circuit.kind)
        // The two circuit ways and the pit lane share nodes; the one-point way is dropped.
        assertEquals(listOf("way/11", "way/12", "way/13"), circuit.osmElements)
        assertEquals(3, circuit.polylines.size)

        val area = candidates[0]
        assertEquals("track area", area.kind)
        assertEquals(listOf("relation/30"), area.osmElements)
        assertEquals(1, area.polylines.size)
    }

    @Test
    fun parseJoinsByCoordinatesWhenNodeIdsAreMissing() {
        val noNodes = response.replace(Regex("\"nodes\": \\[[0-9, ]*],"), "")
        val circuit = Overpass.parse(noNodes).single { it.name == "Main Circuit" }
        assertEquals(listOf("way/11", "way/12", "way/13"), circuit.osmElements)
    }

    @Test
    fun parseReportsServerErrorsAndBadJson() {
        val timedOut = """{"elements": [], "remark": "runtime error: Query timed out in \"query\" at line 3 after 26 seconds."}"""
        assertFailsWith<OsmException> { Overpass.parse(timedOut) }
        assertFailsWith<OsmException> { Overpass.parse("<html>busy</html>") }
        assertEquals(emptyList(), Overpass.parse("""{"elements": []}"""))
    }

    @Test
    fun nominatimParsesStringNumbers() {
        val json = """
            [ { "lat": "36.5843", "lon": "-121.7535", "display_name": "WeatherTech Raceway Laguna Seca, Monterey County",
                "boundingbox": ["36.57", "36.59", "-121.76", "-121.74"] },
              { "lat": "bad", "lon": "0" },
              { "lat": "1.5", "lon": "2.5" } ]
        """.trimIndent()
        val places = Nominatim.parse(json)
        assertEquals(2, places.size)
        assertEquals(LatLon(36.5843, -121.7535), places[0].center)
        assertEquals(Bounds(36.57, -121.76, 36.59, -121.74), places[0].bounds)
        assertNull(places[1].bounds)
    }

    @Test
    fun clientFallsBackWhenServersAreBusy() {
        val tried = mutableListOf<String>()
        val transport = HttpTransport { url, form ->
            tried += url
            val query = URLDecoder.decode(form!!.removePrefix("data="), Charsets.UTF_8)
            assertTrue(query.startsWith("[out:json]"))
            when (url) {
                "https://a.example/api" -> HttpResponse(504, "busy")
                "https://b.example/api" -> throw SocketTimeoutException()
                else -> HttpResponse(200, response)
            }
        }
        val client = OsmClient(transport, listOf("https://a.example/api", "https://b.example/api", "https://c.example/api"))
        assertEquals(3, client.findTracks(smallArea).size)
        assertEquals(3, tried.size)
    }

    @Test
    fun clientStopsOnABadQueryAndExplainsFailures() {
        val tried = mutableListOf<String>()
        val transport = HttpTransport { url, _ ->
            tried += url
            if (url.contains("a.example")) HttpResponse(429, "") else HttpResponse(400, "bad query")
        }
        val client = OsmClient(transport, listOf("https://a.example/api", "https://b.example/api", "https://c.example/api"))
        val e = assertFailsWith<OsmException> { client.findTracks(smallArea) }
        assertEquals(2, tried.size)
        assertTrue(e.message!!.contains("a.example: HTTP 429 (busy)"), e.message)
        assertTrue(e.message!!.contains("b.example: HTTP 400"), e.message)
    }

    @Test
    fun clientRefusesHugeAreas() {
        val client = OsmClient({ _, _ -> error("must not send") })
        assertFailsWith<OsmException> { client.findTracks(Bounds(36.0, -122.0, 37.0, -121.0)) }
    }

    private val smallArea = Bounds(36.57, -121.77, 36.61, -121.69)
}

/** A real Overpass response for Laguna Seca, fetched 2026-11-19. Data © OpenStreetMap contributors, ODbL. */
class OsmRealDataTest {
    private val text = javaClass.getResource("/overpass-laguna-seca.json")!!.readText()

    @Test
    fun lagunaSecaBecomesOneDirectedCircuit() {
        val candidates = Overpass.parse(text)
        val c = candidates.single()
        assertEquals("WeatherTech Raceway Laguna Seca", c.name)
        // Eight circuit ways plus the pit lane, which joins at a node in the middle of a way.
        assertEquals(9, c.osmElements.size)
        assertTrue(c.drivingDirectionKnown)
        // Without the pit lane, the ways add up to the official lap length of 3.602 km.
        val pit = c.osmElements.indexOf("way/109859349")
        val circuitM = c.polylines.filterIndexed { i, _ -> i != pit }.sumOf { lengthM(it) }
        assertEquals(3_602.0, circuitM, 5.0)
    }

    @Test
    fun onewayMinusOneIsReversed() {
        val forward = Overpass.parse(text).single()
        val flipped = Overpass.parse(text.replace("\"oneway\": \"yes\"", "\"oneway\": \"-1\""))
            .single()
        assertTrue(flipped.drivingDirectionKnown)
        assertEquals(forward.polylines.map { it.reversed() }, flipped.polylines)
    }
}
