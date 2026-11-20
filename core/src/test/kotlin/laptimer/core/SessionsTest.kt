package laptimer.core

import java.io.File
import java.io.StringWriter
import java.nio.file.Files
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SessionsTest {
    private val dir: File = Files.createTempDirectory("sessions").toFile()
    private val center = LatLon(36.5843, -121.7535)
    private val loop = TrackLoop.testOval(center)

    @AfterTest
    fun cleanUp() {
        dir.deleteRecursively()
    }

    private fun sim() = DrivingSimulator(loop, DriverProfile(positionNoiseM = 0.0, lapVariation = 0.02), seed = 6)

    private fun lineAt(sim: DrivingSimulator, d: Double): TimingLine {
        val a = sim.stateAt(sim.passTimeS(0, d)).position
        val b = sim.stateAt(sim.passTimeS(0, d + 1)).position
        return TimingLine.across(a, bearingDeg(a, b), 25.0)
    }

    private fun track(sim: DrivingSimulator) =
        Track(name = "Oval", start = lineAt(sim, 300.0), sectors = listOf(lineAt(sim, 700.0)))

    @Test
    fun summaryTimesLapsOnTheStoredTrack() {
        val sim = sim()
        val file = File(dir, "a.ltlog")
        val fixes = SimulatedSession.write(file, sim, sim.lapStartS(4), track(sim))
        val s = summarizeSession(file)
        assertEquals("a.ltlog", s.fileName)
        assertEquals(Instant.parse("2026-11-19T14:00:00Z"), s.startUtc)
        assertEquals("RaceBox Emulator", s.device)
        assertEquals("Oval", s.track?.name)
        assertEquals(fixes.toLong(), s.fixes)
        assertEquals((fixes - 1) * 0.04, s.durationS!!, 1e-9)
        assertEquals(LogEnd.CLEAN, s.logEnd)
        val laps = assertNotNull(s.timing).laps
        assertEquals(3, laps.size)
        laps.forEach { lap ->
            val j = lap.number - 1
            assertEquals(sim.passTimeS(j + 1, 300.0) - sim.passTimeS(j, 300.0), lap.timeS, 0.002)
        }
    }

    @Test
    fun aSessionWithoutATrackCanBeTimedOnOne() {
        val sim = sim()
        val file = File(dir, "b.ltlog")
        SimulatedSession.write(file, sim, sim.lapStartS(3), track = null)
        assertNull(summarizeSession(file).timing)
        val timed = summarizeSession(file, track(sim))
        assertEquals(2, timed.timing!!.laps.size)
    }

    @Test
    fun lapsCsvHasOneRowPerLap() {
        val sim = sim()
        val file = File(dir, "c.ltlog")
        SimulatedSession.write(file, sim, sim.lapStartS(4), track(sim))
        val s = summarizeSession(file)
        val out = StringWriter().also { SessionExport.writeLapsCsv(s, it) }.toString().lines().filter { it.isNotEmpty() }
        assertEquals("lap,time_s,time,delta_to_best_s,sector1_s,sector2_s,gps_gap", out[0])
        assertEquals(4, out.size)
        val best = s.timing!!.bestLap!!
        val bestRow = out[best.number].split(",")
        assertEquals("0.000", bestRow[3])
        // Sector times add up to the lap time.
        for (row in out.drop(1).map { it.split(",") }) {
            assertEquals(row[1].toDouble(), row[4].toDouble() + row[5].toDouble(), 0.0015)
            assertEquals("false", row[6])
        }
    }

    @Test
    fun dataCsvHasEveryFixWithItsLap() {
        val sim = sim()
        val file = File(dir, "d.ltlog")
        val fixes = SimulatedSession.write(file, sim, sim.lapStartS(3), track(sim))
        val rows = StringWriter().also { SessionExport.writeDataCsv(file, null, it) }.toString().lines().filter { it.isNotEmpty() }
        assertEquals(SessionExport.DATA_CSV_HEADER, rows[0])
        assertEquals(fixes, rows.size - 1)
        val laps = rows.drop(1).map { it.split(",")[2] }
        // Before the first crossing there is no lap; then laps 1, 2, 3 in order.
        assertEquals("", laps.first())
        assertEquals(listOf("", "1", "2", "3"), laps.distinct())
        val first = rows[1].split(",")
        assertEquals("2026-11-19T14:00:00Z", first[0])
        assertEquals(DATA_COLUMNS, first.size)
    }

    @Test
    fun vboRowsUseMinutesWestPositiveAndUtcTimeOfDay() {
        val state = DriveState(LatLon(36.5843, -121.7535), 30.0, 123.456, 0.0, 0.0, 0.0, 0, 0.0)
        val unixMs = Instant.parse("2026-11-19T14:05:09.04Z").toEpochMilli()
        val fix = RaceBoxFeed.fix(state, unixMs)
        assertEquals(
            "014 140509.04 +02195.05800 +07305.21000 108.000 123.46 +00250.00",
            SessionExport.vboRow(fix, fix.utcEpochNanos!!),
        )
        // East and south come out negative.
        val other = RaceBoxFeed.fix(state.copy(position = LatLon(-33.5, 151.25)), unixMs)
        val cols = SessionExport.vboRow(other, other.utcEpochNanos!!).split(" ")
        assertEquals("-02010.00000", cols[2])
        assertEquals("-09075.00000", cols[3])
    }

    @Test
    fun vboFileHasTheSectionsAndOnlyGoodFixes() {
        val sim = sim()
        val file = File(dir, "e.ltlog")
        // Lose the fix between 10 and 12 seconds in.
        val written = SimulatedSession.write(file, sim, 30.0, track(sim)) { t -> t !in 10.0..12.0 }
        val lost = (0..(30.0 / 0.04).toInt()).count { k -> k * 0.04 in 10.0..12.0 }
        val text = StringWriter().also {
            SessionExport.writeVbo(file, summarizeSession(file), it, Instant.parse("2026-11-20T08:00:00Z"))
        }.toString()
        assertTrue(text.startsWith("File created on 20/11/2026 at 08:00:00\r\n"))
        // Every line ends in CR LF.
        assertTrue(text.replace("\r\n", "").none { it == '\n' || it == '\r' })
        val lines = text.split("\r\n")
        val sections = lines.filter { it.startsWith("[") }
        assertEquals(listOf("[header]", "[comments]", "[column names]", "[data]"), sections)
        assertTrue("velocity kmh" in lines)
        assertTrue("Track: Oval" in lines)
        val data = lines.dropWhile { it != "[data]" }.drop(1).filter { it.isNotEmpty() }
        assertEquals(written - lost, data.size)
        // Positions read back to within the format's 0.00001 minute (2 cm).
        val firstCols = data[0].split(" ")
        val p = sim.stateAt(0.0).position
        assertEquals(p.lat, firstCols[2].toDouble() / 60, 1e-6)
        assertEquals(p.lon, -firstCols[3].toDouble() / 60, 1e-6)
        assertEquals("140000.00", firstCols[1])
        assertEquals("140000.04", data[1].split(" ")[1])
    }

    @Test
    fun obdReadingsAppearInTheSummaryAndExports() {
        val sim = sim()
        val file = File(dir, "f.ltlog")
        SimulatedSession.write(file, sim, 60.0, track(sim), obd = true)
        val s = summarizeSession(file)
        assertEquals(ObdChannel.entries.toSet(), s.obdChannels)
        // About 50 exchanges a second, less the setup.
        assertTrue(s.obdReadings in 2_000..3_000, "readings ${s.obdReadings}")

        val rows = StringWriter().also { SessionExport.writeDataCsv(file, null, it) }.toString().lines().filter { it.isNotEmpty() }
        val header = rows[0].split(",")
        val rpm = header.indexOf("rpm")
        val boost = header.indexOf("boost_kpa")
        val last = rows.last().split(",")
        assertEquals(header.size, last.size)
        val rpmValue = last[rpm].toDouble()
        assertTrue(rpmValue in 850.0..6600.0, "rpm $rpmValue")
        assertTrue(last[boost].isNotEmpty())
        // The first fix comes before any reading.
        assertEquals("", rows[1].split(",")[rpm])

        val vbo = StringWriter().also { SessionExport.writeVbo(file, s, it) }.toString().split("\r\n")
        val sections = vbo.filter { it.startsWith("[") }
        assertEquals(listOf("[header]", "[channel units]", "[comments]", "[column names]", "[data]"), sections)
        val columns = vbo[vbo.indexOf("[column names]") + 1].split(" ")
        assertEquals(listOf("sats", "time", "lat", "long", "velocity", "heading", "height", "RPM", "Throttle", "OBD_Speed", "MAP", "MAF", "Coolant", "IAT", "Oil_Temp", "Baro", "Boost"), columns)
        val units = vbo.subList(vbo.indexOf("[channel units]") + 1, vbo.indexOf("[comments]") - 1)
        assertEquals(10, units.size)
        val data = vbo.dropWhile { it != "[data]" }.drop(1).filter { it.isNotEmpty() }
        assertTrue(data.all { it.split(" ").size == columns.size })
    }

    @Test
    fun canSignalsAppearInTheSummaryAndExports() {
        val sim = sim()
        val file = File(dir, "g.ltlog")
        val profile = CanProfiles.PORSCHE_718
        SimulatedSession.write(file, sim, 30.0, track(sim), can = profile)
        val s = summarizeSession(file)
        assertEquals(profile, s.canProfile)
        assertEquals(profile.signals.map { it.name }.toSet(), s.canSignalsSeen)
        // The fast messages carry 19 signals between them, 25 times a second, for most of 30 s.
        assertTrue(s.canValues in 12_000..16_000, "values ${s.canValues}")
        // No OBD readings are decoded from CAN traffic.
        assertEquals(emptySet(), s.obdChannels)

        val rows = StringWriter().also { SessionExport.writeDataCsv(file, null, it, s.canProfile) }.toString().lines().filter { it.isNotEmpty() }
        val header = rows[0].split(",")
        val rpm = header.indexOf("can_RPM")
        val speed = header.indexOf("speed_kmh")
        val canSpeed = header.indexOf("can_Displayed_speed")
        assertTrue(rpm > 0 && canSpeed > 0)
        // Late in the drive, the car's speed signal agrees with GPS speed to within its 0.32 km/h step.
        val late = rows[rows.size - 10].split(",")
        assertEquals(late[speed].toDouble(), late[canSpeed].toDouble(), 3.0)
        assertTrue(late[rpm].toDouble() in 850.0..6600.0)

        val vbo = StringWriter().also { SessionExport.writeVbo(file, s, it) }.toString().split("\r\n")
        val columns = vbo[vbo.indexOf("[column names]") + 1].split(" ")
        assertTrue("Wheel_speed_FL" in columns && "Brake_pressure" in columns)
        val units = vbo.subList(vbo.indexOf("[channel units]") + 1, vbo.indexOf("[comments]") - 1)
        assertTrue(units.all { u -> u.all { it.code < 128 } }, units.toString())
        val data = vbo.dropWhile { it != "[data]" }.drop(1).filter { it.isNotEmpty() }
        assertTrue(data.all { it.split(" ").size == columns.size })
    }

    private companion object {
        val DATA_COLUMNS = SessionExport.DATA_CSV_HEADER.split(",").size
    }
}
