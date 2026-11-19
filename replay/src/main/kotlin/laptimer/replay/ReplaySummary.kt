package laptimer.replay

import laptimer.core.GpsFix
import laptimer.core.LapTimer
import laptimer.core.LogEnd
import laptimer.core.RaceBox
import laptimer.core.SessionLogFormat
import laptimer.core.SessionLogReader
import laptimer.core.TimingState
import laptimer.core.Track
import laptimer.core.TrackJson
import laptimer.core.UbxStreamParser
import java.io.File

/** What a replayed session contains, for checking a recording. */
data class ReplaySummary(
    val metadata: Map<String, String>,
    val deviceByteRecords: Long,
    val frames: Long,
    val fixes: Long,
    /** Frames other than data messages, keyed by "class/id" in hex. */
    val otherFrames: Map<String, Long>,
    val checksumErrors: Long,
    val skippedBytes: Long,
    val fixTypeCounts: Map<Int, Long>,
    /** Median interval between fixes, from the receiver's clock. */
    val medianIntervalMs: Long?,
    /** Intervals longer than 1.5 times the median. */
    val gaps: Long,
    val longestGapMs: Long?,
    val medianHorizontalAccuracyM: Double?,
    val maxSpeedMps: Double?,
    val logEnd: LogEnd,
    /** The track laps were timed on, or null if none was given or stored in the log. */
    val track: Track? = null,
    val timing: TimingState? = null,
)

/**
 * Feeds every fix in a session log to [onFix] and summarizes the session.
 *
 * Laps are timed on [track] if given, otherwise on the track the app
 * stored in the log when the session started, if any.
 */
fun replay(file: File, track: Track? = null, onFix: (GpsFix) -> Unit = {}): ReplaySummary {
    var timer: LapTimer? = track?.let { LapTimer(it) }
    val parser = UbxStreamParser()
    val metadata = linkedMapOf<String, String>()
    val otherFrames = sortedMapOf<String, Long>()
    val fixTypes = sortedMapOf<Int, Long>()
    val intervals = ArrayList<Long>()
    val accuracies = ArrayList<Double>()
    var deviceRecords = 0L
    var frames = 0L
    var fixes = 0L
    var maxSpeed: Double? = null
    var previousTow: Long? = null

    val end = SessionLogReader(file).use { reader ->
        while (true) {
            val record = reader.next() ?: break
            when (record.type) {
                SessionLogFormat.TYPE_METADATA -> {
                    val entries = SessionLogReader.parseMetadata(record)
                    val stored = entries["track"]
                    if (stored != null && timer == null) timer = LapTimer(TrackJson.decode(stored))
                    // The track itself is long; the summary shows its name.
                    metadata.putAll(entries - "track")
                }
                SessionLogFormat.TYPE_DEVICE_BYTES -> {
                    deviceRecords++
                    for (frame in parser.feed(record.payload)) {
                        frames++
                        val fix = RaceBox.decodeFix(frame)
                        if (fix == null) {
                            val key = "%02X/%02X".format(frame.messageClass, frame.messageId)
                            otherFrames[key] = (otherFrames[key] ?: 0) + 1
                            continue
                        }
                        fixes++
                        fixTypes[fix.fixType] = (fixTypes[fix.fixType] ?: 0) + 1
                        previousTow?.let { intervals.add(towDifferenceMs(it, fix.gpsTimeOfWeekMs)) }
                        previousTow = fix.gpsTimeOfWeekMs
                        if (fix.fixOk) {
                            accuracies.add(fix.horizontalAccuracyM)
                            maxSpeed = maxOf(maxSpeed ?: fix.speedMps, fix.speedMps)
                        }
                        timer?.feed(fix)
                        onFix(fix)
                    }
                }
            }
        }
        checkNotNull(reader.end)
    }

    val medianInterval = intervals.sorted().let { if (it.isEmpty()) null else it[it.size / 2] }
    val gapThreshold = medianInterval?.let { it * 3 / 2 }
    return ReplaySummary(
        metadata = metadata,
        deviceByteRecords = deviceRecords,
        frames = frames,
        fixes = fixes,
        otherFrames = otherFrames,
        checksumErrors = parser.checksumErrors,
        skippedBytes = parser.skippedBytes,
        fixTypeCounts = fixTypes,
        medianIntervalMs = medianInterval,
        gaps = if (gapThreshold == null) 0 else intervals.count { it > gapThreshold }.toLong(),
        longestGapMs = intervals.maxOrNull(),
        medianHorizontalAccuracyM = accuracies.sorted().let { if (it.isEmpty()) null else it[it.size / 2] },
        maxSpeedMps = maxSpeed,
        logEnd = end,
        track = timer?.track,
        timing = timer?.state,
    )
}

private const val WEEK_MS = 7L * 24 * 3600 * 1000

/** Interval between two times of week, allowing for the weekly wrap to zero. */
internal fun towDifferenceMs(earlier: Long, later: Long): Long =
    ((later - earlier) % WEEK_MS + WEEK_MS) % WEEK_MS
