package laptimer.core

/**
 * Listens to a car's CAN bus through an OBDLink's STN chip and decodes a
 * profile's signals.
 *
 * Steps:
 * 1. Setup: headers on, so each line carries its identifier; CAN
 *    formatting off, so all data bytes come raw; CAN 11-bit 500 kbit/s;
 *    and silent monitoring, so the adapter never acknowledges or sends on
 *    the car's bus.
 * 2. Survey: a few seconds of all traffic (STMA), counting identifiers,
 *    so a port that carries nothing useful shows at once.
 * 3. Filters: one pass filter per identifier in the profile (STFAP), so
 *    the Bluetooth link carries only what is decoded.
 * 4. Monitor (STM) from then on, restarting it if the adapter drops out.
 *
 * A plain ELM327 has no STN commands: it monitors everything with ATMA
 * instead, and may overflow on a busy bus.
 *
 * Like [ObdPoller], it knows nothing about Bluetooth. Not thread-safe.
 */
class CanMonitor(
    val profile: CanProfile,
    private val send: (String) -> Unit,
    private val surveyNanos: Long = 3_000_000_000L,
) {
    enum class Phase { STARTING, SURVEY, FILTERING, MONITORING }

    data class Status(
        val phase: Phase = Phase.STARTING,
        val adapter: String? = null,
        /** Every identifier seen during the survey, with how many messages. */
        val surveyCounts: Map<Int, Int> = emptyMap(),
        /** The profile's identifiers not seen at all so far. */
        val missingIds: Set<Int> = emptySet(),
        val framesPerSecond: Double? = null,
        /** The latest value of each signal, by name. */
        val values: Map<String, Double> = emptyMap(),
        /** "BUFFER FULL" reports: the adapter dropped messages. */
        val overflows: Int = 0,
        val message: String? = null,
    )

    var status = Status(missingIds = profile.ids.toSet())
        private set

    private sealed interface Step
    private data class Command(val text: String) : Step
    private data object Survey : Step
    private data object Stop : Step
    private data object Monitor : Step

    private val byId: Map<Int, List<CanSignal>> = profile.signals.groupBy { it.id }
    private val line = StringBuilder()
    private val reply = mutableListOf<String>()
    private var pending: Step? = null
    private val queue = ArrayDeque<String>()
    private var sentAtNanos = 0L
    private var nowNanos = 0L
    private var monitoring = false
    private var isStn = false
    private var surveyEndNanos = 0L
    private var lastFrameNanos = 0L
    private var timeouts = 0
    private var restarts = 0
    private val surveyCounts = mutableMapOf<Int, Int>()
    private val seenIds = mutableSetOf<Int>()
    private val frameTimes = ArrayDeque<Long>()

    fun start(nowNanos: Long) {
        this.nowNanos = nowNanos
        status = Status(adapter = status.adapter, missingIds = profile.ids.toSet())
        monitoring = false
        isStn = false
        timeouts = 0
        restarts = 0
        surveyCounts.clear()
        queue.clear()
        queue.addAll(SETUP)
        nextCommand()
    }

    fun onBytes(bytes: ByteArray, nowNanos: Long): List<CanSample> {
        this.nowNanos = nowNanos
        val samples = mutableListOf<CanSample>()
        for (b in bytes) {
            when (val c = (b.toInt() and 0xFF).toChar()) {
                '\r', '\n' -> endLine(samples)
                '>' -> {
                    endLine(samples)
                    onPrompt()
                }
                '\u0000' -> {}
                else -> if (line.length < 256) line.append(c)
            }
        }
        return samples
    }

    fun tick(nowNanos: Long) {
        this.nowNanos = nowNanos
        when (pending) {
            Survey -> if (nowNanos >= surveyEndNanos) stopMonitoring()
            Monitor -> if (lastFrameNanos != 0L && nowNanos - lastFrameNanos > QUIET_NANOS || lastFrameNanos == 0L && nowNanos - sentAtNanos > QUIET_NANOS) {
                status = status.copy(
                    message = "No messages for this car's signals. If the adapter is on the factory OBD port, " +
                        "it may be behind the gateway; the bus has to be tapped behind it.",
                )
            }
            is Command, Stop -> {
                val cmd = pending
                val limit = if (cmd is Command && cmd.text == "ATZ") 3_000_000_000L else 1_000_000_000L
                if (nowNanos - sentAtNanos < limit) return
                pending = null
                if (++timeouts >= 3) {
                    status = status.copy(message = "The adapter stopped answering. Restarting it.")
                    start(nowNanos)
                } else if (cmd is Stop) {
                    monitoring = false
                    afterMonitorStopped()
                } else {
                    nextCommand()
                }
            }
            null -> {}
        }
        status = status.copy(framesPerSecond = rate())
    }

    private fun endLine(samples: MutableList<CanSample>) {
        if (line.isEmpty()) return
        val text = line.toString().trim()
        line.setLength(0)
        if (text.isEmpty()) return
        if (!monitoring) {
            reply += text
            return
        }
        if (text.contains("BUFFER FULL", ignoreCase = true)) {
            status = status.copy(overflows = status.overflows + 1)
            return
        }
        val frame = CanMonitorLines.parse(text) ?: return
        lastFrameNanos = nowNanos
        frameTimes.addLast(nowNanos)
        while (frameTimes.isNotEmpty() && nowNanos - frameTimes.first() > RATE_WINDOW_NANOS) frameTimes.removeFirst()
        if (pending == Survey) surveyCounts[frame.id] = (surveyCounts[frame.id] ?: 0) + 1
        seenIds += frame.id
        val signals = byId[frame.id] ?: return
        var values = status.values
        for (s in signals) {
            val v = s.decode(frame.data) ?: continue
            samples += CanSample(s, v, nowNanos)
            values = values + (s.name to v)
        }
        status = status.copy(values = values, missingIds = profile.ids.toSet() - seenIds, message = null)
    }

    private fun onPrompt() {
        val step = pending
        val lines = reply.toList()
        reply.clear()
        timeouts = 0
        when (step) {
            is Command -> {
                pending = null
                val answer = lines.filter { it.replace(" ", "") != step.text.replace(" ", "") }
                when (step.text) {
                    "STI" -> {
                        isStn = answer.any { it.startsWith("STN", ignoreCase = true) }
                        answer.firstOrNull { it.startsWith("STN", ignoreCase = true) }?.let { status = status.copy(adapter = it) }
                    }
                    "ATI" -> answer.firstOrNull { it.contains("ELM", ignoreCase = true) }?.let {
                        if (status.adapter == null) status = status.copy(adapter = it)
                    }
                }
                nextCommand()
            }
            Stop -> {
                monitoring = false
                pending = null
                afterMonitorStopped()
            }
            Survey, Monitor -> {
                // The adapter left monitoring by itself: a reset, or an overflow on a plain ELM327.
                monitoring = false
                pending = null
                if (++restarts > 5) {
                    status = status.copy(message = "The adapter keeps stopping. Restarting it.")
                    start(nowNanos)
                } else {
                    startMonitor()
                }
            }
            null -> {}
        }
    }

    private fun nextCommand() {
        val cmd = queue.removeFirstOrNull()
        if (cmd != null) {
            request(Command(cmd), cmd)
            return
        }
        when (status.phase) {
            Phase.STARTING -> {
                status = status.copy(phase = Phase.SURVEY, message = "Listening to all CAN traffic for a few seconds.")
                surveyCounts.clear()
                surveyEndNanos = nowNanos + surveyNanos
                monitoring = true
                request(Survey, if (isStn) "STMA" else "ATMA")
            }
            Phase.FILTERING -> startMonitor()
            else -> {}
        }
    }

    private fun stopMonitoring() {
        // Any character ends monitoring; the adapter then shows its prompt.
        request(Stop, "")
    }

    private fun afterMonitorStopped() {
        status = status.copy(
            surveyCounts = surveyCounts.toMap(),
            phase = Phase.FILTERING,
            message = if (surveyCounts.isEmpty()) "No CAN traffic at all on this connection." else null,
        )
        if (isStn) {
            queue.add("STFAC")
            profile.ids.forEach { queue.add("STFAP %03X,7FF".format(it)) }
        }
        nextCommand()
    }

    private fun startMonitor() {
        status = status.copy(phase = Phase.MONITORING)
        monitoring = true
        lastFrameNanos = 0L
        request(Monitor, if (isStn) "STM" else "ATMA")
    }

    private fun request(step: Step, text: String) {
        pending = step
        sentAtNanos = nowNanos
        send(text + "\r")
    }

    private fun rate(): Double? {
        if (frameTimes.size < 2) return null
        val span = frameTimes.last() - frameTimes.first()
        return if (span > 0) (frameTimes.size - 1) * 1e9 / span else null
    }

    private companion object {
        val SETUP = listOf(
            "ATZ", // reset
            "ATE0", // no echo
            "ATL0", // no line feeds
            "ATS0", // no spaces
            "ATH1", // headers on: each message line starts with its identifier
            "ATCAF0", // CAN formatting off: all eight data bytes, raw
            "ATSP6", // ISO 15765-4 CAN, 11-bit identifiers, 500 kbit/s
            "ATCSM1", // silent monitoring: never acknowledge or send on the car's bus
            "ATI",
            "STI", // an OBDLink answers with its STN chip; a plain ELM327 with "?"
        )

        /** No messages for this long while monitoring means something is wrong. */
        const val QUIET_NANOS = 3_000_000_000L
        const val RATE_WINDOW_NANOS = 2_000_000_000L
    }
}
