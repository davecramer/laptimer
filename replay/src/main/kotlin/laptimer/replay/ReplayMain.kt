package laptimer.replay

import laptimer.core.GpsFix
import laptimer.core.TrackJson
import laptimer.core.formatLapTime
import java.io.File
import java.io.IOException
import java.io.PrintWriter
import java.time.Instant
import kotlin.system.exitProcess

private const val USAGE = """usage: replay <session.ltlog> [--csv <out.csv>] [--track <track.json>]

Reads a recorded session, decodes every GPS fix, and prints a summary.
With --csv, also writes one row per fix.

Laps are timed on the track stored in the session, if the app recorded
with one. --track times them on a track file instead, such as one copied
from the phone's tracks folder."""

fun main(args: Array<String>) {
    val positional = mutableListOf<String>()
    var csvPath: String? = null
    var trackPath: String? = null
    var i = 0
    while (i < args.size) {
        when (args[i]) {
            "--csv" -> csvPath = args.getOrNull(++i) ?: usage()
            "--track" -> trackPath = args.getOrNull(++i) ?: usage()
            "-h", "--help" -> usage(0)
            else -> positional.add(args[i])
        }
        i++
    }
    if (positional.size != 1) usage()

    val track = trackPath?.let { path ->
        try {
            TrackJson.decode(File(path).readText())
        } catch (e: IllegalArgumentException) {
            System.err.println("$path: ${e.message}")
            exitProcess(1)
        } catch (e: IOException) {
            System.err.println("Cannot read $path: ${e.message}")
            exitProcess(1)
        }
    }
    val csv = csvPath?.let { PrintWriter(File(it).bufferedWriter()) }
    csv?.println(CSV_HEADER)
    val summary = try {
        replay(File(positional[0]), track) { fix -> csv?.println(csvRow(fix)) }
    } catch (e: IllegalArgumentException) {
        System.err.println(e.message)
        exitProcess(1)
    } catch (e: IOException) {
        System.err.println("Cannot read ${positional[0]}: ${e.message}")
        exitProcess(1)
    } finally {
        csv?.close()
    }
    print(format(summary))
}

private fun usage(status: Int = 2): Nothing {
    (if (status == 0) System.out else System.err).println(USAGE)
    exitProcess(status)
}

internal fun format(s: ReplaySummary): String = buildString {
    for ((k, v) in s.metadata) appendLine("$k: $v")
    appendLine("notifications: ${s.deviceByteRecords}")
    appendLine("frames: ${s.frames} (fixes ${s.fixes})")
    if (s.otherFrames.isNotEmpty()) appendLine("other messages: ${s.otherFrames}")
    appendLine("checksum errors: ${s.checksumErrors}, skipped bytes: ${s.skippedBytes}")
    appendLine("fix types: ${s.fixTypeCounts}")
    s.medianIntervalMs?.let { median ->
        val rate = if (median > 0) "%.1f Hz".format(1000.0 / median) else "?"
        appendLine("median interval: $median ms ($rate)")
        appendLine("gaps over ${median * 3 / 2} ms: ${s.gaps}, longest interval: ${s.longestGapMs} ms")
    }
    s.medianHorizontalAccuracyM?.let { appendLine("median horizontal accuracy: %.2f m".format(it)) }
    s.maxSpeedMps?.let { appendLine("max speed: %.1f km/h".format(it * 3.6)) }
    appendLine("log end: ${s.logEnd}")
    val timing = s.timing ?: return@buildString
    appendLine()
    appendLine("track: ${s.track?.name}")
    if (timing.laps.isEmpty()) {
        appendLine("no complete laps")
        return@buildString
    }
    val best = timing.bestLap
    for (lap in timing.laps) {
        val sectors = if (lap.sectorTimesS.size > 1) {
            "  " + lap.sectorTimesS.joinToString("  ") { t -> (t?.let { formatLapTime(it) } ?: "-").padStart(9) }
        } else {
            ""
        }
        val notes = listOfNotNull("best".takeIf { lap == best }, "GPS gap".takeIf { lap.hadGap }).joinToString(", ")
        appendLine("lap %3d  %9s%s%s".format(lap.number, formatLapTime(lap.timeS), sectors, if (notes.isEmpty()) "" else "  ($notes)"))
    }
    if (timing.bestSectorsS.size > 1 && timing.bestSectorsS.all { it != null }) {
        val optimal = timing.bestSectorsS.sumOf { it!! }
        appendLine("best sectors: " + timing.bestSectorsS.joinToString("  ") { formatLapTime(it!!) } + ", together ${formatLapTime(optimal)}")
    }
}

private const val CSV_HEADER =
    "gps_tow_ms,utc,fix_type,fix_ok,satellites,lat,lon,h_acc_m,speed_mps,heading_deg," +
        "accel_x_g,accel_y_g,accel_z_g"

private fun csvRow(f: GpsFix): String = listOf(
    f.gpsTimeOfWeekMs,
    f.utcEpochNanos?.let { Instant.ofEpochSecond(0, it) } ?: "",
    f.fixType,
    f.fixOk,
    f.satellites,
    "%.7f".format(f.latitudeDeg),
    "%.7f".format(f.longitudeDeg),
    f.horizontalAccuracyM,
    f.speedMps,
    f.headingDeg,
    f.accelXG,
    f.accelYG,
    f.accelZG,
).joinToString(",")
