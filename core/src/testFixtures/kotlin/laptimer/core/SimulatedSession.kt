package laptimer.core

import java.io.File
import java.time.Instant

/**
 * Writes a session log the way the app's RecordingService does, from the
 * driving simulator: metadata with the start time and track, then every
 * frame in 20-byte Bluetooth notifications.
 */
object SimulatedSession {
    fun write(
        file: File,
        sim: DrivingSimulator,
        seconds: Double,
        track: Track?,
        startUtc: Instant = Instant.parse("2026-11-19T14:00:00Z"),
        /** Also record an emulated OBD adapter, polled as the app polls a real one. */
        obd: Boolean = false,
        fixOk: (Double) -> Boolean = { true },
    ): Int {
        val startMs = startUtc.toEpochMilli()
        var count = 0
        var engineTime = 0.0
        val elm = Elm327Emulator({ EngineModel.state(sim.stateAt(engineTime), engineTime) })
        val outbox = ArrayDeque<String>()
        val poller = ObdPoller(send = { outbox.addLast(it) })
        SessionLogWriter(file).use { log ->
            if (obd) poller.start(0)
            log.writeMetadata(0, mapOf("device" to "RaceBox Emulator", "startUtc" to startUtc.toString()))
            if (track != null) log.writeMetadata(0, mapOf("trackName" to track.name, "track" to TrackJson.encodeCompact(track)))
            var k = 0
            while (k * 0.04 <= seconds) {
                val t = k * 0.04
                val frame = RaceBox.encodeFrame(RaceBoxFeed.fix(sim.stateAt(t), startMs + k * 40L, fixOk(t)))
                frame.toList().chunked(20).forEach { log.writeDeviceBytes(k * 40_000_000L, it.toByteArray()) }
                // Two adapter exchanges per 40 ms fix: about what an OBDLink manages on CAN.
                if (obd) repeat(2) { half ->
                    val nanos = k * 40_000_000L + half * 20_000_000L
                    engineTime = nanos / 1e9
                    outbox.removeFirstOrNull()?.let { cmd ->
                        log.writeObdSent(nanos, cmd)
                        val reply = elm.reply(cmd.removeSuffix("\r")).toByteArray()
                        log.writeObdReceived(nanos + 10_000_000L, reply)
                        poller.onBytes(reply, nanos + 10_000_000L)
                    }
                    poller.tick(nanos + 10_000_000L)
                }
                count++
                k++
            }
        }
        return count
    }
}
