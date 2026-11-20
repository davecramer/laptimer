package laptimer.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CanTest {
    private val profile = CanProfiles.PORSCHE_718
    private fun sig(name: String) = profile.signals.single { it.name == name }

    private fun bytes(vararg b: Int) = ByteArray(b.size) { b[it].toByte() }

    @Test
    fun littleEndianMatchesTheRaceChronoEquations() {
        // RPM: bitsToUintLe(raw,16,16)*0.25. 5000 rpm is 20000 = 0x4E20, stored low byte first.
        assertEquals(5000.0, sig("RPM").decode(bytes(0, 0, 0x20, 0x4E, 0, 0, 0, 0)))
        // Brake pressure: bitsToUintLe(raw,16,10)*0.3-30. Raw 0x2AB (683) spans byte 2 and the low 2 bits of byte 3.
        assertEquals(683 * 0.3 - 30, sig("Brake_pressure").decode(bytes(0, 0, 0xAB, 0xFE, 0, 0, 0, 0))!!, 1e-9)
        // Wheel speed FR: 12 bits from bit 28, the high nibble of byte 3 and all of byte 4.
        assertEquals(0xABC * 0.103, sig("Wheel_speed_FR").decode(bytes(0, 0, 0, 0xC0, 0xAB, 0, 0, 0))!!, 1e-9)
        // Manual gear: bits 49 to 52.
        assertEquals(5.0, sig("Gear_manual").decode(bytes(0, 0, 0, 0, 0, 0, 0x0A, 0)))
    }

    @Test
    fun bigEndianFollowsTheDbcConvention() {
        // Start bit 7, 16 bits, Motorola: bytes 0 and 1 in order.
        val s = CanSignal("x", 1, startBit = 7, length = 16, order = BitOrder.BIG_ENDIAN)
        assertEquals(0x1234.toDouble(), s.decode(bytes(0x12, 0x34)))
        // PDK gear: the low four bits of byte 3.
        assertEquals(7.0, sig("Gear_PDK").decode(bytes(0, 0, 0, 0xF7, 0, 0, 0, 0)))
    }

    @Test
    fun signedValuesAndShortMessages() {
        val s = CanSignal("x", 1, startBit = 0, length = 8, signed = true, factor = 0.5)
        assertEquals(-1.0, s.decode(bytes(0xFE)))
        assertEquals(63.5, s.decode(bytes(0x7F)))
        // Too short to hold the signal.
        assertNull(sig("RPM").decode(bytes(0, 0, 1)))
    }

    @Test
    fun everyProfileSignalRoundTrips() {
        for (s in profile.signals) {
            val max = s.offset + s.factor * ((1L shl s.length) - 1)
            for (f in listOf(0.0, 0.37, 1.0)) {
                val v = s.offset + (max - s.offset) * f
                val data = ByteArray(8)
                s.encode(v, data)
                assertEquals(v, s.decode(data)!!, s.factor / 2 + 1e-9, "${s.name} at $v")
            }
        }
        // Signals sharing a message don't overwrite each other.
        val data = ByteArray(8)
        sig("RPM").encode(6250.0, data)
        sig("Throttle").encode(80.0, data)
        assertEquals(6250.0, sig("RPM").decode(data))
        assertEquals(80.0, sig("Throttle").decode(data)!!, 0.2)
    }

    @Test
    fun readsDbcFiles() {
        val dbc = """
            VERSION ""
            BO_ 261 Throttle: 8 XXX
             SG_ Throttle_Position : 48|8@1+ (0.4,0) [0|1] "Unit_PerCent" XXX
             SG_ Throttle_RPM : 16|16@1+ (0.25,0) [0|10000] "RPM" XXX
            BO_ 2147483905 Extended: 8 XXX
             SG_ Ignored : 0|8@1+ (1,0) [0|255] "" XXX
            BO_ 300 Muxed: 8 XXX
             SG_ Mux_signal m3 : 8|8@0- (1,-5) [0|0] "x" XXX
             SG_ Signed_big : 15|12@0- (0.5,-5) [0|0] "deg" XXX
        """.trimIndent()
        val r = Dbc.parse(dbc)
        assertEquals(listOf("Throttle_Position", "Throttle_RPM", "Signed_big"), r.signals.map { it.name })
        val rpm = r.signals[1]
        assertEquals(261, rpm.id)
        assertEquals(16, rpm.startBit)
        assertEquals(0.25, rpm.factor)
        assertEquals(CanMeaning.RPM, rpm.meaning)
        val big = r.signals[2]
        assertEquals(BitOrder.BIG_ENDIAN, big.order)
        assertTrue(big.signed)
        assertEquals(-5.0, big.offset)
        assertEquals(2, r.skipped.size)
    }

    @Test
    fun parsesMonitorLines() {
        val f = assertNotNull(CanMonitorLines.parse("1050000204E00000000"))
        assertEquals(0x105, f.id)
        assertEquals(8, f.data.size)
        assertEquals(0x4E.toByte(), f.data[3])
        assertNotNull(CanMonitorLines.parse("105 00 00 20 4E"))
        assertNull(CanMonitorLines.parse("OK"))
        assertNull(CanMonitorLines.parse("BUFFER FULL"))
        // Any line of hex digits of the right length is a valid message, which is why the
        // session file records which mode the adapter was in.
    }

    /** A monitor talking to an emulated adapter: one command or one 40 ms of bus traffic per step. */
    private class Loop(stn: Boolean = true, val frames: (Long) -> List<CanFrame>, val profile: CanProfile = CanProfiles.PORSCHE_718) {
        val sent = mutableListOf<String>()
        private val outbox = ArrayDeque<String>()
        val elm = Elm327Emulator({ null }, stn = stn, canFrames = frames)
        val monitor = CanMonitor(profile, send = { outbox.addLast(it) })
        var nowMs = 0L
        val samples = mutableListOf<CanSample>()
        var answer: (String) -> String = { elm.reply(it) }

        fun run(seconds: Double) {
            if (sent.isEmpty()) monitor.start(0)
            val end = nowMs + (seconds * 1000).toLong()
            while (nowMs < end) {
                nowMs += 40
                val nanos = nowMs * 1_000_000
                outbox.removeFirstOrNull()?.let { cmd ->
                    sent += cmd
                    samples += monitor.onBytes(answer(cmd.removeSuffix("\r")).toByteArray(), nanos)
                }
                samples += monitor.onBytes(elm.monitorOutput(nowMs).toByteArray(), nanos)
                monitor.tick(nanos)
            }
        }
    }

    private val drive = DriveState(LatLon(0.0, 0.0), 40.0, 0.0, -0.8, 1.1, 30.0, 0, 0.0)
    private val engine = EngineModel.state(drive, 900.0)

    /** The 718's messages, plus unrelated traffic the filters should keep out. */
    private fun bus(nowMs: Long): List<CanFrame> =
        CanEmulation.frames(profile, drive, engine, nowMs / 40) + CanFrame(0x3C0, ByteArray(8)) + CanFrame(0x6A0, ByteArray(4))

    @Test
    fun surveysThenFiltersThenDecodes() {
        val loop = Loop(frames = ::bus)
        loop.run(8.0)
        val st = loop.monitor.status
        assertEquals(CanMonitor.Phase.MONITORING, st.phase)
        assertEquals("STN2255 v5.10.3", st.adapter)
        val commands = loop.sent.map { it.trim() }
        // Silent monitoring is set before anything listens.
        assertTrue(commands.indexOf("ATCSM1") < commands.indexOf("STMA"))
        // The survey saw everything, unrelated traffic included.
        assertTrue(0x3C0 in st.surveyCounts && 0x105 in st.surveyCounts, st.surveyCounts.keys.toString())
        // Then one pass filter per profile ID, and filtered monitoring.
        val filters = commands.filter { it.startsWith("STFAP") }
        assertEquals(profile.ids.map { "STFAP %03X,7FF".format(it) }, filters)
        assertEquals("STM", commands.last())
        assertEquals(emptySet(), st.missingIds)

        val v = st.values
        assertEquals(engine.rpm, v["RPM"]!!, 0.25)
        assertEquals(144.0, v["Displayed_speed"]!!, 0.32)
        assertEquals(1.1, v["Lateral_accel"]!!, 0.01)
        assertEquals(0.8 * 110, v["Brake_pressure"]!!, 0.3)
        assertEquals(engine.gear.toDouble(), v["Gear_manual"])
        assertTrue(v["Wheel_speed_FL"]!! > v["Wheel_speed_FR"]!!, "outside wheel faster in a right turn")
        assertEquals(engine.coolantC, v["Coolant_temp"]!!, 1.0)

        // After filtering, unrelated messages no longer arrive: 10 fast IDs at 25 Hz plus slow ones.
        val fps = assertNotNull(st.framesPerSecond)
        assertTrue(fps in 230.0..290.0, "frames per second $fps")
    }

    @Test
    fun aPlainElm327MonitorsEverything() {
        val loop = Loop(stn = false, frames = ::bus)
        loop.run(6.0)
        val commands = loop.sent.map { it.trim() }
        assertTrue("STMA" !in commands && commands.none { it.startsWith("STFAP") })
        assertEquals("ATMA", commands.last())
        assertEquals(engine.rpm, loop.monitor.status.values["RPM"]!!, 0.25)
    }

    @Test
    fun saysSoWhenTheProfilesMessagesAreMissing() {
        // Only unrelated traffic, as on a port behind a gateway.
        val loop = Loop(frames = { listOf(CanFrame(0x7E8, ByteArray(8))) })
        loop.run(10.0)
        val st = loop.monitor.status
        assertEquals(setOf(0x7E8), st.surveyCounts.keys)
        assertEquals(profile.ids.toSet(), st.missingIds)
        assertTrue(st.message!!.contains("gateway"), st.message)
    }

    @Test
    fun restartsMonitoringIfTheAdapterDropsOut() {
        val loop = Loop(frames = ::bus)
        loop.run(6.0)
        val before = loop.sent.count { it.trim() == "STM" }
        // The adapter leaves monitoring by itself and shows its prompt.
        loop.elm.reply("")
        loop.monitor.onBytes(">".toByteArray(), loop.nowMs * 1_000_000)
        loop.run(1.0)
        assertEquals(before + 1, loop.sent.count { it.trim() == "STM" })
        assertEquals(CanMonitor.Phase.MONITORING, loop.monitor.status.phase)
    }

    @Test
    fun emulatorSendsSlowMessagesOncePerSecond() {
        val counts = (0L until 50L).flatMap { CanEmulation.frames(profile, drive, engine, it) }.groupingBy { it.id }.eachCount()
        assertEquals(50, counts[0x105])
        assertEquals(2, counts[0x640])
    }
}
