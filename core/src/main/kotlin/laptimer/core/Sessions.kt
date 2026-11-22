package laptimer.core

import java.io.File
import java.io.Writer
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/** The metadata key under which a session stores the CAN profile it was recorded with. */
const val CAN_PROFILE_KEY = "canProfile"

/**
 * Reads a session log in order: metadata records go to [onMetadata], OBD
 * readings to [onObd], CAN signal values to [onCan], and each decoded GPS
 * fix to [onFix]. Returns how the file ended.
 *
 * The adapter's bytes are OBD replies unless the session stored a CAN
 * profile, in which case they are monitor lines decoded with it.
 */
fun readSession(
    file: File,
    onMetadata: (Map<String, String>) -> Unit = {},
    onObd: (ObdSample) -> Unit = {},
    onCan: (CanSample) -> Unit = {},
    onFix: (GpsFix) -> Unit,
): LogEnd {
    val parser = UbxStreamParser()
    val obd = ElmReplySplitter()
    var can: Map<Int, List<CanSignal>>? = null
    val canLine = StringBuilder()
    return SessionLogReader(file).use { reader ->
        while (true) {
            val record = reader.next() ?: break
            when (record.type) {
                SessionLogFormat.TYPE_METADATA -> {
                    val m = SessionLogReader.parseMetadata(record)
                    m[CAN_PROFILE_KEY]?.let { json ->
                        can = runCatching { CanProfile.fromJson(json) }.getOrNull()?.signals?.groupBy { it.id }
                    }
                    onMetadata(m)
                }
                SessionLogFormat.TYPE_DEVICE_BYTES -> for (frame in parser.feed(record.payload)) {
                    RaceBox.decodeFix(frame)?.let(onFix)
                }
                SessionLogFormat.TYPE_OBD_RECEIVED -> {
                    val signals = can
                    if (signals == null) {
                        for (lines in obd.feed(record.payload)) ObdReplies.samples(lines, record.timeNanos).forEach(onObd)
                    } else {
                        for (b in record.payload) {
                            val c = (b.toInt() and 0xFF).toChar()
                            if (c == '\r' || c == '\n' || c == '>') {
                                CanMonitorLines.parse(canLine.toString().trim())?.let { f ->
                                    for (s in signals[f.id].orEmpty()) s.decode(f.data)?.let { onCan(CanSample(s, it, record.timeNanos)) }
                                }
                                canLine.setLength(0)
                            } else if (canLine.length < 256) {
                                canLine.append(c)
                            }
                        }
                    }
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
    /** The engine readings the session has, empty if no OBD adapter was used. */
    val obdChannels: Set<ObdChannel> = emptySet(),
    val obdReadings: Long = 0,
    /** Where the car first had a good fix, for suggesting a track. */
    val firstPosition: LatLon? = null,
    /** The CAN profile the session was recorded with, if any. */
    val canProfile: CanProfile? = null,
    /** Names of the profile's signals that had at least one value. */
    val canSignalsSeen: Set<String> = emptySet(),
    val canValues: Long = 0,
)

/**
 * Reads a whole session and times its laps on [track], or on the track
 * stored in the session if [track] is null. If the session stored a new
 * track partway through, because it was found by GPS or switched by
 * hand, timing restarts on it from there, as it did live.
 */
fun summarizeSession(file: File, track: Track? = null): SessionSummary {
    var startUtc: Instant? = null
    var device: String? = null
    var timer: LapTimer? = track?.let { LapTimer(it) }
    var fixes = 0L
    var firstTowMs: Long? = null
    var lastTowMs: Long? = null
    var elapsedMs = 0L
    val obdChannels = mutableSetOf<ObdChannel>()
    var obdReadings = 0L
    var canProfile: CanProfile? = null
    var firstPosition: LatLon? = null
    val canSeen = mutableSetOf<String>()
    var canValues = 0L
    val end = readSession(
        file,
        onObd = { s ->
            obdChannels += s.channel
            obdReadings++
        },
        onCan = { s ->
            canSeen += s.signal.name
            canValues++
        },
        onMetadata = { m ->
            m[CAN_PROFILE_KEY]?.let { canProfile = runCatching { CanProfile.fromJson(it) }.getOrNull() }
            m["startUtc"]?.let { s -> startUtc = runCatching { Instant.parse(s) }.getOrNull() }
            m["device"]?.let { device = it }
            val stored = m["track"]
            if (stored != null && track == null) {
                runCatching { LapTimer(TrackJson.decode(stored)) }.getOrNull()?.let { timer = it }
            }
        },
    ) { fix ->
        fixes++
        if (firstPosition == null && TrackLocator.usable(fix)) firstPosition = LatLon(fix.latitudeDeg, fix.longitudeDeg)
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
        obdChannels = obdChannels,
        obdReadings = obdReadings,
        firstPosition = firstPosition,
        canProfile = canProfile,
        canSignalsSeen = canSeen,
        canValues = canValues,
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

    /** Engine columns, in the order written; empty until the first reading. */
    private val CSV_OBD = listOf(
        ObdChannel.RPM to "rpm",
        ObdChannel.THROTTLE to "throttle_pct",
        ObdChannel.SPEED to "obd_speed_kmh",
        ObdChannel.MANIFOLD_PRESSURE to "manifold_kpa",
        ObdChannel.MASS_AIRFLOW to "airflow_gs",
        ObdChannel.COOLANT_TEMP to "coolant_c",
        ObdChannel.INTAKE_TEMP to "intake_c",
        ObdChannel.OIL_TEMP to "oil_c",
        ObdChannel.BAROMETRIC_PRESSURE to "barometric_kpa",
    )

    val DATA_CSV_HEADER =
        "utc,gps_tow_ms,lap,lap_time_s,fix_ok,satellites,lat,lon,altitude_m,h_acc_m,speed_kmh,heading_deg," +
            "accel_x_g,accel_y_g,accel_z_g,rotation_x_dps,rotation_y_dps,rotation_z_dps," +
            CSV_OBD.joinToString(",") { it.second } + ",boost_kpa"

    /**
     * One row per GPS fix, with the lap it was in and the time into that
     * lap, timed on [track] or on the track stored in the session. Engine
     * columns hold the latest reading received before the fix, so a
     * reading is at most one polling round old.
     */
    fun writeDataCsv(file: File, track: Track?, out: Writer, canProfile: CanProfile? = null) {
        // One column per CAN signal, as the latest value before each fix.
        val canSignals = canProfile?.signals.orEmpty()
        out.write(DATA_CSV_HEADER + canSignals.joinToString("") { ",can_" + columnName(it.name) } + "\n")
        val can = HashMap<String, Double>()
        forEachTimedFix(file, track, onCan = { can[it.signal.name] = it.value }) { fix, lap, lapTimeS, obd ->
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
                ).joinToString(",") + "," +
                    CSV_OBD.joinToString(",") { (c, _) -> obd[c]?.let { "%.2f".format(Locale.ROOT, it) } ?: "" } + "," +
                    (obd.boostKpa?.let { "%.1f".format(Locale.ROOT, it) } ?: "") +
                    canSignals.joinToString("") { s -> "," + (can[s.name]?.let { "%.3f".format(Locale.ROOT, it) } ?: "") } + "\n",
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
        // Engine readings become user channels, each named in the header and given a unit.
        val engine = VBO_OBD.filter { it.channel in summary.obdChannels }
        val boost = ObdChannel.MANIFOLD_PRESSURE in summary.obdChannels && ObdChannel.BAROMETRIC_PRESSURE in summary.obdChannels
        // CAN signals that had values, after the OBD ones.
        val canSignals = summary.canProfile?.signals.orEmpty().filter { it.name in summary.canSignalsSeen }
        val userNames = engine.map { it.name } + (if (boost) listOf("Boost") else emptyList()) + canSignals.map { columnName(it.name) }
        val userUnits = engine.map { it.unit } + (if (boost) listOf("kPa") else emptyList()) + canSignals.map { asciiUnit(it.unit) }
        line("File created on " + DateTimeFormatter.ofPattern("dd/MM/yyyy 'at' HH:mm:ss", Locale.ROOT).withZone(ZoneOffset.UTC).format(createdAt))
        line()
        line("[header]")
        (listOf("satellites", "time", "latitude", "longitude", "velocity kmh", "heading", "height") + userNames).forEach { line(it) }
        line()
        if (userNames.isNotEmpty()) {
            line("[channel units]")
            userUnits.forEach { line(it) }
            line()
        }
        line("[comments]")
        line("Exported by laptimer")
        summary.track?.let { line("Track: ${it.name}") }
        summary.device?.let { line("Device: $it") }
        summary.startUtc?.let { line("Recorded: $it") }
        line()
        line("[column names]")
        line((listOf("sats", "time", "lat", "long", "velocity", "heading", "height") + userNames).joinToString(" "))
        line()
        line("[data]")
        var obd = ObdValues()
        val can = HashMap<String, Double>()
        readSession(file, onObd = { obd = obd.with(it) }, onCan = { can[it.signal.name] = it.value }) { fix ->
            val utc = fix.utcEpochNanos
            if (!fix.fixOk || !fix.positionValid || utc == null) return@readSession
            // A channel not read yet is written as 0: every row needs every column.
            val user = engine.map { "%.2f".format(Locale.ROOT, obd[it.channel] ?: 0.0) } +
                (if (boost) listOf("%.1f".format(Locale.ROOT, obd.boostKpa ?: 0.0)) else emptyList()) +
                canSignals.map { "%.3f".format(Locale.ROOT, can[it.name] ?: 0.0) }
            line((listOf(vboRow(fix, utc)) + user).joinToString(" "))
        }
    }

    /** Letters, digits and underscores only: VBO columns are separated by spaces. */
    private fun columnName(name: String) = name.replace(Regex("[^A-Za-z0-9_]"), "_")

    private fun asciiUnit(unit: String) = unit.replace("°", "").replace("²", "2")

    private class VboChannel(val channel: ObdChannel, val name: String, val unit: String)

    /** Names without spaces, as columns are separated by spaces; units in plain ASCII. */
    private val VBO_OBD = listOf(
        VboChannel(ObdChannel.RPM, "RPM", "rpm"),
        VboChannel(ObdChannel.THROTTLE, "Throttle", "%"),
        VboChannel(ObdChannel.SPEED, "OBD_Speed", "km/h"),
        VboChannel(ObdChannel.MANIFOLD_PRESSURE, "MAP", "kPa"),
        VboChannel(ObdChannel.MASS_AIRFLOW, "MAF", "g/s"),
        VboChannel(ObdChannel.COOLANT_TEMP, "Coolant", "C"),
        VboChannel(ObdChannel.INTAKE_TEMP, "IAT", "C"),
        VboChannel(ObdChannel.OIL_TEMP, "Oil_Temp", "C"),
        VboChannel(ObdChannel.BAROMETRIC_PRESSURE, "Baro", "kPa"),
    )

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

    /** Each fix with its lap and time into the lap (null outside a lap) and the latest engine readings. */
    private fun forEachTimedFix(
        file: File,
        track: Track?,
        onCan: (CanSample) -> Unit = {},
        onFix: (GpsFix, Int?, Double?, ObdValues) -> Unit,
    ) {
        var timer: LapTimer? = track?.let { LapTimer(it) }
        var obd = ObdValues()
        readSession(
            file,
            onObd = { obd = obd.with(it) },
            onCan = onCan,
            onMetadata = { m ->
                val stored = m["track"]
                if (stored != null && track == null) runCatching { LapTimer(TrackJson.decode(stored)) }.getOrNull()?.let { timer = it }
            },
        ) { fix ->
            val t = timer
            if (t == null) {
                onFix(fix, null, null, obd)
            } else {
                t.feed(fix)
                onFix(fix, t.state.currentLapNumber, t.state.currentLapS, obd)
            }
        }
    }

    private fun seconds(s: Double) = "%.3f".format(Locale.ROOT, s)
}
