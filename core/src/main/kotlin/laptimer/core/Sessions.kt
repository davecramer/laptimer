package laptimer.core

import java.io.File
import java.io.Writer
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Reads a session log in order: metadata records go to [onMetadata] and
 * each decoded GPS fix to [onFix]. Returns how the file ended.
 */
fun readSession(
    file: File,
    onMetadata: (Map<String, String>) -> Unit = {},
    onFix: (GpsFix) -> Unit,
): LogEnd {
    val parser = UbxStreamParser()
    return SessionLogReader(file).use { reader ->
        while (true) {
            val record = reader.next() ?: break
            when (record.type) {
                SessionLogFormat.TYPE_METADATA -> onMetadata(SessionLogReader.parseMetadata(record))
                SessionLogFormat.TYPE_DEVICE_BYTES -> for (frame in parser.feed(record.payload)) {
                    RaceBox.decodeFix(frame)?.let(onFix)
                }
            }
        }
        checkNotNull(reader.end)
    }
}

/** What the session list and lap table show about one session. */
data class SessionSummary(
    val fileName: String,
    /** When recording started, from the session's metadata. */
    val startUtc: Instant?,
    val device: String?,
    /** The track laps were timed on: the one stored in the session, or the one given to [summarizeSession]. */
    val track: Track?,
    /** Null when there was no track to time on. */
    val timing: TimingState?,
    val fixes: Long,
    /** From the first fix to the last, on the receiver's clock. */
    val durationS: Double?,
    val logEnd: LogEnd,
)

/**
 * Reads a whole session and times its laps on [track], or on the track
 * stored in the session if [track] is null.
 */
fun summarizeSession(file: File, track: Track? = null): SessionSummary {
    var startUtc: Instant? = null
    var device: String? = null
    var timer: LapTimer? = track?.let { LapTimer(it) }
    var fixes = 0L
    var firstTowMs: Long? = null
    var lastTowMs: Long? = null
    var elapsedMs = 0L
    val end = readSession(
        file,
        onMetadata = { m ->
            m["startUtc"]?.let { s -> startUtc = runCatching { Instant.parse(s) }.getOrNull() }
            m["device"]?.let { device = it }
            val stored = m["track"]
            if (stored != null && timer == null) {
                timer = runCatching { LapTimer(TrackJson.decode(stored)) }.getOrNull()
            }
        },
    ) { fix ->
        fixes++
        timer?.feed(fix)
        val tow = fix.gpsTimeOfWeekMs
        val last = lastTowMs
        if (firstTowMs == null) firstTowMs = tow
        // Time of week wraps once a week; add up the steps instead.
        if (last != null) elapsedMs += Math.floorMod(tow - last, WEEK_MS)
        lastTowMs = tow
    }
    return SessionSummary(
        fileName = file.name,
        startUtc = startUtc,
        device = device,
        track = timer?.track,
        timing = timer?.state,
        fixes = fixes,
        durationS = if (firstTowMs == null) null else elapsedMs / 1000.0,
        logEnd = end,
    )
}

private const val WEEK_MS = 7L * 24 * 3600 * 1000

/** Exports a session for spreadsheets and for Racelogic's analysis software. */
object SessionExport {
    /** One row per lap: lap time, sector times, and whether a GPS gap made it doubtful. */
    fun writeLapsCsv(summary: SessionSummary, out: Writer) {
        val timing = summary.timing
        val sectorCount = timing?.bestSectorsS?.size ?: 1
        val sectorColumns = if (sectorCount > 1) (1..sectorCount).joinToString("") { ",sector${it}_s" } else ""
        out.write("lap,time_s,time,delta_to_best_s$sectorColumns,gps_gap\n")
        val best = timing?.bestLap
        for (lap in timing?.laps.orEmpty()) {
            val sectors = if (sectorCount > 1) lap.sectorTimesS.joinToString("") { "," + (it?.let(::seconds) ?: "") } else ""
            val delta = best?.let { seconds(lap.timeS - it.timeS) } ?: ""
            out.write("${lap.number},${seconds(lap.timeS)},${formatLapTime(lap.timeS)},$delta$sectors,${lap.hadGap}\n")
        }
    }

    const val DATA_CSV_HEADER =
        "utc,gps_tow_ms,lap,lap_time_s,fix_ok,satellites,lat,lon,altitude_m,h_acc_m,speed_kmh,heading_deg," +
            "accel_x_g,accel_y_g,accel_z_g,rotation_x_dps,rotation_y_dps,rotation_z_dps"

    /**
     * One row per GPS fix, with the lap it was in and the time into that
     * lap, timed on [track] or on the track stored in the session.
     */
    fun writeDataCsv(file: File, track: Track?, out: Writer) {
        out.write(DATA_CSV_HEADER + "\n")
        forEachTimedFix(file, track) { fix, lap, lapTimeS ->
            out.write(
                listOf(
                    fix.utcEpochNanos?.let { Instant.ofEpochSecond(0, it).toString() } ?: "",
                    fix.gpsTimeOfWeekMs.toString(),
                    lap?.toString() ?: "",
                    lapTimeS?.let(::seconds) ?: "",
                    fix.fixOk.toString(),
                    fix.satellites.toString(),
                    "%.7f".format(Locale.ROOT, fix.latitudeDeg),
                    "%.7f".format(Locale.ROOT, fix.longitudeDeg),
                    "%.2f".format(Locale.ROOT, fix.altitudeMslM),
                    "%.3f".format(Locale.ROOT, fix.horizontalAccuracyM),
                    "%.3f".format(Locale.ROOT, fix.speedMps * 3.6),
                    "%.2f".format(Locale.ROOT, fix.headingDeg),
                    "%.3f".format(Locale.ROOT, fix.accelXG),
                    "%.3f".format(Locale.ROOT, fix.accelYG),
                    "%.3f".format(Locale.ROOT, fix.accelZG),
                    "%.2f".format(Locale.ROOT, fix.rotationXDps),
                    "%.2f".format(Locale.ROOT, fix.rotationYDps),
                    "%.2f".format(Locale.ROOT, fix.rotationZDps),
                ).joinToString(",") + "\n",
            )
        }
    }

    /**
     * Racelogic VBO text format, read by Circuit Tools and other analysis
     * software. Only fixes with a valid position and time are written.
     *
     * Format details, from two independent open-source writers that agree
     * (lbulej/vbo-tools and ioan-l/AmgGpx2Vbo): positions in minutes of
     * arc, with longitude positive to the west; time as UTC HHMMSS.SS;
     * velocity in km/h under the title "velocity kmh" (Circuit Tools is
     * reported to crash without the unit); lines end in CR LF.
     *
     * The optional [laptiming] section with the start/finish line is left
     * out: its exact syntax could not be confirmed. Set the start line in
     * the analysis software instead.
     */
    fun writeVbo(file: File, summary: SessionSummary, out: Writer, createdAt: Instant = Instant.now()) {
        fun line(s: String = "") = out.write(s + "\r\n")
        line("File created on " + DateTimeFormatter.ofPattern("dd/MM/yyyy 'at' HH:mm:ss", Locale.ROOT).withZone(ZoneOffset.UTC).format(createdAt))
        line()
        line("[header]")
        listOf("satellites", "time", "latitude", "longitude", "velocity kmh", "heading", "height").forEach { line(it) }
        line()
        line("[comments]")
        line("Exported by laptimer")
        summary.track?.let { line("Track: ${it.name}") }
        summary.device?.let { line("Device: $it") }
        summary.startUtc?.let { line("Recorded: $it") }
        line()
        line("[column names]")
        line("sats time lat long velocity heading height")
        line()
        line("[data]")
        readSession(file) { fix ->
            val utc = fix.utcEpochNanos
            if (!fix.fixOk || !fix.positionValid || utc == null) return@readSession
            line(vboRow(fix, utc))
        }
    }

    internal fun vboRow(fix: GpsFix, utcEpochNanos: Long): String {
        val dayNanos = Math.floorMod(utcEpochNanos, 86_400_000_000_000L)
        // Hundredths of a second, rounded; 25 Hz fixes fall on 0.04 s steps.
        val hundredths = Math.round(dayNanos / 1e7).let { if (it >= 8_640_000L) 8_639_999L else it }
        val h = hundredths / 360_000
        val m = hundredths / 6_000 % 60
        val s = hundredths / 100 % 60
        val cs = hundredths % 100
        return listOf(
            // Circuit Tools is reported to reject a satellite count of zero.
            "%03d".format(Locale.ROOT, fix.satellites.coerceAtLeast(1)),
            "%02d%02d%02d.%02d".format(Locale.ROOT, h, m, s, cs),
            "%+012.5f".format(Locale.ROOT, fix.latitudeDeg * 60),
            "%+012.5f".format(Locale.ROOT, -fix.longitudeDeg * 60),
            "%07.3f".format(Locale.ROOT, fix.speedMps * 3.6),
            "%06.2f".format(Locale.ROOT, normalizeDeg(fix.headingDeg)),
            "%+09.2f".format(Locale.ROOT, fix.altitudeMslM),
        ).joinToString(" ")
    }

    /** Each fix with the lap it falls in and the time into that lap, or nulls outside a lap. */
    private fun forEachTimedFix(file: File, track: Track?, onFix: (GpsFix, Int?, Double?) -> Unit) {
        var timer: LapTimer? = track?.let { LapTimer(it) }
        readSession(
            file,
            onMetadata = { m ->
                val stored = m["track"]
                if (stored != null && timer == null) timer = runCatching { LapTimer(TrackJson.decode(stored)) }.getOrNull()
            },
        ) { fix ->
            val t = timer
            if (t == null) {
                onFix(fix, null, null)
            } else {
                t.feed(fix)
                onFix(fix, t.state.currentLapNumber, t.state.currentLapS)
            }
        }
    }

    private fun seconds(s: Double) = "%.3f".format(Locale.ROOT, s)
}
