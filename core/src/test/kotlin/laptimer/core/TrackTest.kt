package laptimer.core

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TrackTest {
    private val center = LatLon(36.5843, -121.7535)

    @Test
    fun lineAcrossPutsLeftOnTheDriversLeft() {
        // Driving north: left is west, right is east.
        val line = TimingLine.across(center, 0.0, 20.0)
        assertTrue(line.left.lon < center.lon)
        assertTrue(line.right.lon > center.lon)
        assertEquals(0.0, line.forwardBearingDeg, 1e-6)
        assertEquals(20.0, line.widthM, 1e-6)
        assertEquals(center.lat, line.center.lat, 1e-12)
        assertEquals(center.lon, line.center.lon, 1e-12)
    }

    @Test
    fun flipReversesDirection() {
        val line = TimingLine.across(center, 75.0, 30.0)
        assertEquals(255.0, line.flipped().forwardBearingDeg, 1e-6)
    }

    @Test
    fun withWidthKeepsCenterAndDirection() {
        val line = TimingLine.across(center, 123.0, 30.0).withWidth(12.0)
        assertEquals(12.0, line.widthM, 1e-6)
        assertEquals(123.0, line.forwardBearingDeg, 1e-6)
        assertEquals(0.0, distanceM(center, line.center), 1e-6)
    }

    @Test
    fun problemsListWhatIsMissing() {
        val line = TimingLine.across(center, 0.0, 20.0)
        assertEquals(listOf("The track needs a name.", "Place the start/finish line."), Track(name = " ").problems())
        assertEquals(emptyList(), Track(name = "Laguna", start = line).problems())
        val p2p = Track(name = "Hill", kind = TrackKind.POINT_TO_POINT, start = line)
        assertEquals(listOf("Place the finish line."), p2p.problems())
        assertEquals(emptyList(), p2p.copy(finish = line).problems())
    }

    @Test
    fun jsonRoundTrips() {
        val track = sampleTrack()
        assertEquals(track, TrackJson.decode(TrackJson.encode(track)))
    }

    @Test
    fun compactJsonIsOneLineAndRoundTrips() {
        val text = TrackJson.encodeCompact(sampleTrack())
        assertTrue('\n' !in text)
        assertEquals(sampleTrack(), TrackJson.decode(text))
    }

    @Test
    fun jsonIgnoresUnknownFieldsAndRejectsNewerFormats() {
        val text = TrackJson.encode(sampleTrack())
        val withExtra = text.replaceFirst("\"name\":", "\"addedLater\": 5, \"name\":")
        assertEquals(sampleTrack(), TrackJson.decode(withExtra))

        val newer = text.replaceFirst("\"format\": ${TrackJson.FORMAT}", "\"format\": ${TrackJson.FORMAT + 1}")
        val e = assertFailsWith<IllegalArgumentException> { TrackJson.decode(newer) }
        assertTrue(e.message!!.contains("newer"))
        assertFailsWith<IllegalArgumentException> { TrackJson.decode("{ not json") }
    }

    @Test
    fun storeSavesLoadsAndDeletes() {
        val dir = Files.createTempDirectory("tracks").toFile()
        try {
            val store = TrackStore(dir)
            val b = sampleTrack().copy(name = "b track")
            val a = Track(name = "A track")
            store.save(b)
            store.save(a)
            File(dir, "broken.json").writeText("{")

            val loaded = store.loadAll()
            assertEquals(listOf(a, b), loaded.tracks)
            assertEquals(1, loaded.errors.size)
            assertTrue(loaded.errors[0].startsWith("broken.json"))

            store.save(a.copy(name = "A renamed"))
            store.delete(b.id)
            assertEquals(listOf("A renamed"), store.loadAll().tracks.map { it.name })
            // No temporary files are left behind.
            assertEquals(setOf("${a.id}.json", "broken.json"), dir.list()!!.toSet())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun storeRejectsIdsThatAreNotFileNames() {
        val store = TrackStore(Files.createTempDirectory("tracks").toFile())
        assertFailsWith<IllegalArgumentException> { store.delete("../x") }
    }

    private fun sampleTrack() = Track(
        id = "3f2c9e5a-1b7d-4c8e-9a01-2b3c4d5e6f70",
        name = "Laguna Seca",
        kind = TrackKind.CIRCUIT,
        start = TimingLine.across(center, 10.0, 25.0),
        sectors = listOf(TimingLine.across(offset(center, 90.0, 400.0), 180.0, 20.0)),
        outline = listOf(listOf(center, offset(center, 0.0, 100.0), offset(center, 45.0, 150.0))),
        source = TrackSource(listOf("way/123", "way/456")),
    )
}
