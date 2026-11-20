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
        /** Instead, record an emulated OBDLink monitoring this profile's CAN messages. */
        can: CanProfile? = null,
        fixOk: (Double) -> Boolean = { true },
    ): Int {
        val startMs = startUtc.toEpochMilli()
        var count = 0
        var engineTime = 0.0
        val elm = Elm327Emulator({ EngineModel.state(sim.stateAt(engineTime), engineTime) })
        val outbox = ArrayDeque<String>()
        val poller = ObdPoller(send = { outbox.addLast(it) })
        val canElm = Elm327Emulator({ null }, stn = true, canFrames = { ms ->
            val t = ms / 1000.0
            if (can == null) emptyList() else CanEmulation.frames(can, sim.stateAt(t), EngineModel.state(sim.stateAt(t), t), ms / 40)
        })
        val monitor = can?.let { CanMonitor(it, send = { c -> outbox.addLast(c) }) }
        SessionLogWriter(file).use { log ->
            if (obd) poller.start(0)
            if (can != null) {
                // As RecordingService stores it, so the session decodes with the same definitions.
                log.writeMetadata(0, mapOf(CAN_PROFILE_KEY to can.toJson()))
                monitor!!.start(0)
            }
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
                if (monitor != null) {
                    val nanos = k * 40_000_000L
                    outbox.removeFirstOrNull()?.let { cmd ->
                        log.writeObdSent(nanos, cmd)
                        val reply = canElm.reply(cmd.removeSuffix("\r")).toByteArray()
                        log.writeObdReceived(nanos + 5_000_000L, reply)
                        monitor.onBytes(reply, nanos + 5_000_000L)
                    }
                    val traffic = canElm.monitorOutput(startMs + k * 40L - startMs).toByteArray()
                    if (traffic.isNotEmpty()) {
                        log.writeObdReceived(nanos + 10_000_000L, traffic)
                        monitor.onBytes(traffic, nanos + 10_000_000L)
                    }
                    monitor.tick(nanos + 10_000_000L)
                }
                count++
                k++
            }
        }
        return count
    }
}
