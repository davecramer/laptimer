package laptimer.core

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ObdTest {
    @Test
    fun decodesReadingsWithOrWithoutSpaces() {
        assertEquals(1726.0, ObdChannel.RPM.decode(ObdReplies.mode01Data(listOf("410C1AF8"), 0x0C, 2)!!))
        assertEquals(1726.0, ObdChannel.RPM.decode(ObdReplies.mode01Data(listOf("41 0C 1A F8 "), 0x0C, 2)!!))
        assertEquals(100.0, ObdChannel.THROTTLE.decode(intArrayOf(255)), 1e-9)
        assertEquals(-40.0, ObdChannel.COOLANT_TEMP.decode(intArrayOf(0)))
        assertEquals(655.35, ObdChannel.MASS_AIRFLOW.decode(intArrayOf(255, 255)), 1e-9)
        // A reply for another PID, or a short one, doesn't count.
        assertNull(ObdReplies.mode01Data(listOf("410D50"), 0x0C, 2))
        assertNull(ObdReplies.mode01Data(listOf("410C1A"), 0x0C, 2))
        // With two control units answering, the first is used.
        assertContentEquals(intArrayOf(0x10, 0x00), ObdReplies.mode01Data(listOf("410C1000", "410C2000"), 0x0C, 2))
    }

    @Test
    fun readsSupportedPidMasks() {
        // A common real-world reply to 0100.
        val pids = ObdReplies.supportedPids(listOf("SEARCHING...", "41 00 BE 3E B8 11"), 0x00)!!
        // B8 is 10111000: PIDs 0x11, 0x13, 0x14 and 0x15.
        assertEquals(setOf(0x01, 0x03, 0x04, 0x05, 0x06, 0x07, 0x0B, 0x0C, 0x0D, 0x0E, 0x0F, 0x11, 0x13, 0x14, 0x15, 0x1C, 0x20), pids)
        assertNull(ObdReplies.supportedPids(listOf("NO DATA"), 0x00))
        // Gearbox first (0x0D only), then engine (0x0C): both count.
        assertEquals(setOf(0x0C, 0x0D), ObdReplies.supportedPids(listOf("4100 00080000", "410000100000"), 0x00))
    }

    @Test
    fun recognizesErrorReplies() {
        assertTrue(ObdReplies.isNoData(listOf("NO DATA")))
        assertTrue(ObdReplies.isBusError(listOf("SEARCHING...", "UNABLE TO CONNECT")))
        assertTrue(ObdReplies.isBusError(listOf("BUS INIT: ...ERROR")))
        assertTrue(!ObdReplies.isBusError(listOf("410C1AF8")))
    }

    @Test
    fun splitterJoinsPiecesAndSplitsAtPrompts() {
        val s = ElmReplySplitter()
        assertEquals(emptyList(), s.feed("410C".toByteArray()))
        assertEquals(listOf(listOf("410C1AF8")), s.feed("1AF8\r\r>".toByteArray()))
        val two = s.feed("OK\r\r>\u0000ELM327 v1.5\r\r>".toByteArray())
        assertEquals(listOf(listOf("OK"), listOf("ELM327 v1.5")), two)
    }

    /**
     * Runs a poller against an adapter. [answer] gets each command and
     * returns the bytes the adapter sends back, or null for silence. Each
     * exchange takes 20 ms.
     */
    private class Loop(val answer: (String) -> String?) {
        val sent = mutableListOf<String>()
        private val outbox = ArrayDeque<String>()
        val poller = ObdPoller(send = { outbox.addLast(it) })
        var now = 0L
        val samples = mutableListOf<ObdSample>()

        fun run(seconds: Double) {
            if (sent.isEmpty() && outbox.isEmpty()) poller.start(now)
            val end = now + (seconds * 1e9).toLong()
            while (now < end) {
                val cmd = outbox.removeFirstOrNull()
                now += 20_000_000L
                if (cmd != null) {
                    sent += cmd
                    answer(cmd.removeSuffix("\r"))?.let { samples += poller.onBytes(it.toByteArray(), now) }
                }
                poller.tick(now)
            }
        }
    }

    private val engineState = EngineState(
        rpm = 5000.0, throttlePercent = 80.0, speedKmh = 120.0, manifoldKpa = 160.0, airflowGs = 150.0,
        coolantC = 88.0, intakeC = 35.0, oilC = 102.0,
    )

    @Test
    fun pollsEveryReadingFromAnEmulatedAdapter() {
        val elm = Elm327Emulator({ engineState })
        val loop = Loop { elm.reply(it) }
        loop.run(10.0)
        val st = loop.poller.status
        assertEquals(ObdPoller.Phase.POLLING, st.phase)
        assertEquals("ELM327 v1.5", st.adapter)
        assertEquals("A6", st.protocol)
        assertEquals(ObdChannel.entries.toSet(), st.supported)
        // Setup ran in order, then discovery of three pages.
        assertEquals(listOf("ATZ", "ATE0", "ATL0", "ATS0", "ATH0", "ATAT1", "ATSP0", "ATI", "STI", "0100", "0120", "0140", "ATDPN"),
            loop.sent.take(13).map { it.trim() })
        // Requests carry the one-reply suffix.
        assertTrue(loop.sent.drop(13).all { it.trim().length == 5 && it.trim().endsWith("1") }, loop.sent.drop(13).take(5).toString())

        val v = st.values
        assertEquals(5000.0, v[ObdChannel.RPM]!!, 0.25)
        assertEquals(80.0, v[ObdChannel.THROTTLE]!!, 0.5)
        assertEquals(120.0, v[ObdChannel.SPEED]!!)
        assertEquals(160.0, v[ObdChannel.MANIFOLD_PRESSURE]!!)
        assertEquals(150.0, v[ObdChannel.MASS_AIRFLOW]!!, 0.01)
        assertEquals(88.0, v[ObdChannel.COOLANT_TEMP]!!)
        assertEquals(102.0, v[ObdChannel.OIL_TEMP]!!)
        assertEquals(59.0, v.boostKpa!!)

        // Fast channels are read much more often than slow ones: about one slow reading a second.
        val counts = loop.samples.groupingBy { it.channel }.eachCount()
        val slowPerSecond = counts.filterKeys { !it.fast }.values.sum() / 9.0
        assertTrue(slowPerSecond in 0.8..1.3, "slow readings per second: $slowPerSecond")
        assertTrue(counts[ObdChannel.RPM]!! > 5 * counts[ObdChannel.COOLANT_TEMP]!!, counts.toString())
        assertNotNull(st.readingsPerSecond)
    }

    @Test
    fun waitsForTheIgnitionAndThenConnects() {
        val elm = Elm327Emulator({ engineState }).apply { carOn = false }
        val loop = Loop { elm.reply(it) }
        loop.run(2.0)
        assertEquals(ObdPoller.Phase.NO_CAR, loop.poller.status.phase)
        assertTrue(loop.poller.status.message!!.contains("ignition"))
        elm.carOn = true
        loop.run(5.0)
        assertEquals(ObdPoller.Phase.POLLING, loop.poller.status.phase)
        assertNotNull(loop.poller.status.values[ObdChannel.RPM])
    }

    @Test
    fun dropsTheReplyCountSuffixIfTheAdapterRejectsIt() {
        val elm = Elm327Emulator({ engineState })
        // An adapter that answers "?" to any request with the suffix.
        val loop = Loop { cmd -> if (cmd.length == 5 && cmd.startsWith("01")) "?\r\r>" else elm.reply(cmd) }
        loop.run(5.0)
        assertEquals(ObdPoller.Phase.POLLING, loop.poller.status.phase)
        assertNotNull(loop.poller.status.values[ObdChannel.RPM])
        assertTrue(loop.sent.takeLast(10).all { it.trim().length == 4 })
    }

    @Test
    fun restartsAnAdapterThatStopsAnswering() {
        val elm = Elm327Emulator({ engineState })
        var silent = false
        val loop = Loop { cmd -> if (silent && !cmd.startsWith("AT")) null else elm.reply(cmd) }
        loop.run(3.0)
        assertEquals(ObdPoller.Phase.POLLING, loop.poller.status.phase)
        silent = true
        loop.run(4.0)
        // Three timeouts of a second each, then a reset.
        assertTrue(loop.sent.count { it.trim() == "ATZ" } >= 2)
    }

    @Test
    fun stopsAskingForAReadingTheCarNeverGives() {
        val elm = Elm327Emulator({ engineState })
        // Listed as supported, but never answered.
        val loop = Loop { cmd -> if (cmd.startsWith("015C")) "NO DATA\r\r>" else elm.reply(cmd) }
        // Four slow readings take turns once a second, so oil is asked every 4 s; five tries take 20 s.
        loop.run(25.0)
        val st = loop.poller.status
        assertTrue(ObdChannel.OIL_TEMP !in st.supported)
        assertTrue(ObdChannel.COOLANT_TEMP in st.supported)
    }

    @Test
    fun emulatorFollowsEchoAndSpaceSettings() {
        val elm = Elm327Emulator({ engineState })
        assertEquals("ATZ\r\rELM327 v1.5\r\r>", elm.reply("ATZ"))
        assertEquals("ATE0\rOK\r\r>", elm.reply("ATE0"))
        assertEquals("SEARCHING...\r41 0C 4E 20\r\r>", elm.reply("010C"))
        elm.reply("ATS0")
        assertEquals("410C4E20\r\r>", elm.reply("010C1"))
        assertEquals("?\r\r>", elm.reply("STI"))
        assertEquals("NO DATA\r\r>", elm.reply("0902"))
    }

    @Test
    fun engineModelIsPlausible() {
        val parked = EngineModel.state(DriveState(LatLon(0.0, 0.0), 0.0, 0.0, 0.0, 0.0, 0.0, 0, 0.0), 0.0)
        assertEquals(850.0, parked.rpm)
        assertEquals(0.0, parked.throttlePercent)
        assertTrue(parked.manifoldKpa < 50)

        val accelerating = EngineModel.state(DriveState(LatLon(0.0, 0.0), 30.0, 0.0, 0.45, 0.0, 0.0, 0, 0.0), 900.0)
        assertTrue(accelerating.rpm in 3000.0..6500.0, "rpm ${accelerating.rpm}")
        assertEquals(100.0, accelerating.throttlePercent)
        assertTrue(accelerating.manifoldKpa > 150, "boost ${accelerating.manifoldKpa}")
        assertEquals(90.0, accelerating.coolantC)

        val braking = EngineModel.state(DriveState(LatLon(0.0, 0.0), 30.0, 0.0, -0.9, 0.0, 0.0, 0, 0.0), 0.0)
        assertEquals(0.0, braking.throttlePercent)
    }

    @Test
    fun repliesInARecordedSessionDecodeToSamples() {
        val s = ObdReplies.samples(listOf("410C1AF8"), 5L)
        assertEquals(listOf(ObdSample(ObdChannel.RPM, 1726.0, 5L)), s)
    }
}
