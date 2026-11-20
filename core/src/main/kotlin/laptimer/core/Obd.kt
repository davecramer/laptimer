package laptimer.core

/**
 * Engine readings from the car's OBD-II port, through an ELM327-compatible
 * adapter such as an OBDLink.
 *
 * Each reading is a mode 01 PID: the request is "01" plus the PID in hex,
 * and the reply is "41", the PID, then data bytes A, B... decoded as the
 * SAE J1979 standard gives.
 */
enum class ObdChannel(
    val pid: Int,
    val label: String,
    val unit: String,
    /** Data bytes in the reply. */
    val bytes: Int,
    /** Read every polling round; the rest about once a second. */
    val fast: Boolean,
    private val decode: (IntArray) -> Double,
) {
    RPM(0x0C, "RPM", "rpm", 2, true, { (it[0] * 256 + it[1]) / 4.0 }),
    THROTTLE(0x11, "Throttle", "%", 1, true, { it[0] * 100.0 / 255.0 }),
    SPEED(0x0D, "Speed (OBD)", "km/h", 1, true, { it[0].toDouble() }),

    /** Absolute pressure; at most 255 kPa, so very high boost reads as 255. */
    MANIFOLD_PRESSURE(0x0B, "Manifold pressure", "kPa", 1, true, { it[0].toDouble() }),
    MASS_AIRFLOW(0x10, "Airflow", "g/s", 2, true, { (it[0] * 256 + it[1]) / 100.0 }),
    COOLANT_TEMP(0x05, "Coolant", "°C", 1, false, { it[0] - 40.0 }),
    INTAKE_TEMP(0x0F, "Intake air", "°C", 1, false, { it[0] - 40.0 }),

    /** Many cars don't report this. */
    OIL_TEMP(0x5C, "Oil", "°C", 1, false, { it[0] - 40.0 }),
    BAROMETRIC_PRESSURE(0x33, "Barometric pressure", "kPa", 1, false, { it[0].toDouble() }),
    ;

    fun decode(data: IntArray): Double = decode.invoke(data)

    /** The request, without the response-count suffix. */
    val request: String get() = "01%02X".format(pid)

    companion object {
        fun forPid(pid: Int): ObdChannel? = entries.firstOrNull { it.pid == pid }
    }
}

/** One reading. [timeNanos] is the phone's clock when the reply arrived. */
data class ObdSample(val channel: ObdChannel, val value: Double, val timeNanos: Long)

/** The latest reading of each channel. */
data class ObdValues(val latest: Map<ObdChannel, ObdSample> = emptyMap()) {
    operator fun get(c: ObdChannel): Double? = latest[c]?.value

    fun with(s: ObdSample) = ObdValues(latest + (s.channel to s))

    /**
     * Boost above the outside air pressure in kPa, from manifold and
     * barometric pressure. Negative is vacuum. Null until both are read.
     */
    val boostKpa: Double? get() {
        val map = get(ObdChannel.MANIFOLD_PRESSURE) ?: return null
        val baro = get(ObdChannel.BAROMETRIC_PRESSURE) ?: return null
        return map - baro
    }
}

/**
 * Splits an ELM327 byte stream into replies. The adapter ends every reply
 * with the '>' prompt, and replies may arrive split across Bluetooth
 * packets, or joined.
 */
class ElmReplySplitter {
    private val buffer = StringBuilder()

    /** Feeds bytes; returns the replies they completed, as lines without the prompt. */
    fun feed(bytes: ByteArray): List<List<String>> {
        val replies = mutableListOf<List<String>>()
        for (b in bytes) {
            val c = (b.toInt() and 0xFF).toChar()
            when (c) {
                '>' -> {
                    replies += lines(buffer.toString())
                    buffer.setLength(0)
                }
                // Adapters pad with NUL bytes after a reset.
                '\u0000' -> {}
                else -> buffer.append(c)
            }
        }
        // A reply that never ends would grow without limit.
        if (buffer.length > 4096) buffer.setLength(0)
        return replies
    }

    private fun lines(text: String) = text.split('\r', '\n').map { it.trim() }.filter { it.isNotEmpty() }
}

/** Reading mode 01 replies. */
object ObdReplies {
    /**
     * Data bytes of the reply to mode 01 [pid], or null if [lines] don't
     * hold one. Works with spaces on or off. Expects headers off (ATH0).
     * When several control units answer, the first is used.
     */
    fun mode01Data(lines: List<String>, pid: Int, bytes: Int): IntArray? {
        val prefix = "41%02X".format(pid)
        for (line in lines) {
            val hex = line.replace(" ", "").uppercase()
            val at = hex.indexOf(prefix)
            if (at < 0) continue
            val data = hex.substring(at + prefix.length)
            if (data.length < bytes * 2) continue
            return try {
                IntArray(bytes) { data.substring(it * 2, it * 2 + 2).toInt(16) }
            } catch (e: NumberFormatException) {
                null
            }
        }
        return null
    }

    /** Every mode 01 reading in [lines], decoded. Used to read back a recorded session. */
    fun samples(lines: List<String>, timeNanos: Long): List<ObdSample> =
        ObdChannel.entries.mapNotNull { c -> mode01Data(lines, c.pid, c.bytes)?.let { ObdSample(c, c.decode(it), timeNanos) } }

    /**
     * PIDs reported as supported by a reply to 0100, 0120 or 0140: a
     * 32-bit mask where the top bit is the PID one above the request.
     * When several control units answer (engine and gearbox, say), their
     * lists are combined, since the first to answer may not be the engine.
     */
    fun supportedPids(lines: List<String>, page: Int): Set<Int>? {
        val masks = lines.mapNotNull { mode01Data(listOf(it), page, 4) }
        if (masks.isEmpty()) return null
        return masks.flatMap { data ->
            val mask = (data[0].toLong() shl 24) or (data[1].toLong() shl 16) or (data[2].toLong() shl 8) or data[3].toLong()
            (1..32).filter { mask and (1L shl (32 - it)) != 0L }.map { page + it }
        }.toSet()
    }

    /** The adapter's way of saying the car didn't answer. */
    fun isNoData(lines: List<String>) = lines.any { it.replace(" ", "").uppercase().let { l -> l == "NODATA" } }

    /** Replies that mean the adapter can't reach the car: ignition off, or no OBD bus. */
    fun isBusError(lines: List<String>) = lines.any { line ->
        val l = line.uppercase()
        "UNABLE TO CONNECT" in l || "CAN ERROR" in l || "BUS ERROR" in l || "BUS INIT" in l && "ERROR" in l ||
            "BUSINIT" in l && "ERROR" in l || "FB ERROR" in l || "DATA ERROR" in l
    }
}

/**
 * Talks to an ELM327-compatible adapter: sets it up, finds out which
 * readings the car supports, then requests them in turn. It knows nothing
 * about Bluetooth: the owner passes bytes in, sends what [send] gives it,
 * and calls [tick] a few times a second so stalled replies time out and
 * retries happen.
 *
 * Fast channels are requested in rotation, and one slow channel goes in
 * about once a second. Each request ends in "1", telling the adapter to
 * return after one reply instead of waiting to see whether more control
 * units answer: a large speed-up. If the adapter rejects that, requests
 * go without it.
 *
 * Not thread-safe; use it from one thread.
 */
class ObdPoller(
    private val send: (String) -> Unit,
    private val slowIntervalNanos: Long = 1_000_000_000L,
) {
    enum class Phase {
        /** Resetting and configuring the adapter. */
        STARTING,

        /** Asking the car which readings it supports. The first request also finds the bus protocol. */
        SEARCHING,

        /** Reading. */
        POLLING,

        /** The car isn't answering, usually because the ignition is off. Retrying every few seconds. */
        NO_CAR,
    }

    data class Status(
        val phase: Phase = Phase.STARTING,
        /** The adapter's identification: "ELM327 v1.5", or an OBDLink's "STN2255 v5.10.3". */
        val adapter: String? = null,
        /** ELM327 protocol number; "A6" is CAN 11-bit 500 kbit/s found automatically, the most common. */
        val protocol: String? = null,
        val supported: Set<ObdChannel> = emptySet(),
        val values: ObdValues = ObdValues(),
        /** Readings per second, over the last 50. */
        val readingsPerSecond: Double? = null,
        val message: String? = null,
    )

    var status = Status()
        private set

    private sealed interface Step {
        val command: String
    }

    private data class Setup(override val command: String) : Step
    private data class Discover(val page: Int) : Step {
        override val command get() = "01%02X".format(page)
    }
    private data object Protocol : Step {
        override val command = "ATDPN"
    }
    private data class Poll(val channel: ObdChannel, val suffix: Boolean) : Step {
        override val command get() = channel.request + if (suffix) "1" else ""
    }

    private val splitter = ElmReplySplitter()
    private var pending: Step? = null
    private var sentAtNanos = 0L

    /** When set, nothing is outstanding and the next request goes at this time. */
    private var waitUntilNanos: Long? = null
    private var nowNanos = 0L
    private var timeouts = 0
    private var useCountSuffix = true
    private var setupStep = 0
    private val supportedPids = mutableSetOf<Int>()
    private var fastIndex = 0
    private var slowIndex = 0
    private var lastSlowNanos = Long.MIN_VALUE / 2
    private val readingTimes = ArrayDeque<Long>()
    private val noDataCounts = mutableMapOf<ObdChannel, Int>()

    /** Starts, or restarts, from the reset command. */
    fun start(nowNanos: Long) {
        this.nowNanos = nowNanos
        status = Status(adapter = status.adapter)
        setupStep = 0
        supportedPids.clear()
        timeouts = 0
        useCountSuffix = true
        noDataCounts.clear()
        waitUntilNanos = null
        request(Setup(SETUP[0]))
    }

    /** Bytes from the adapter. Returns the readings they completed. */
    fun onBytes(bytes: ByteArray, nowNanos: Long): List<ObdSample> {
        this.nowNanos = nowNanos
        return splitter.feed(bytes).flatMap { onReply(it) }
    }

    /** Call a few times a second. */
    fun tick(nowNanos: Long) {
        this.nowNanos = nowNanos
        val wait = waitUntilNanos
        if (wait != null) {
            if (nowNanos >= wait) {
                waitUntilNanos = null
                next()
            }
            return
        }
        val step = pending ?: return
        val limit = when {
            // A reset takes about a second; the first request searches every bus protocol.
            step is Setup && step.command == "ATZ" -> 3_000_000_000L
            step is Discover -> 15_000_000_000L
            else -> 1_000_000_000L
        }
        if (nowNanos - sentAtNanos < limit) return
        pending = null
        if (step is Discover) {
            noCar("The car isn't answering. Turn the ignition on.")
            return
        }
        if (++timeouts >= 3) {
            status = status.copy(message = "The adapter stopped answering. Restarting it.")
            start(nowNanos)
        } else {
            next()
        }
    }

    private fun onReply(lines: List<String>): List<ObdSample> {
        val step = pending ?: return emptyList()
        pending = null
        timeouts = 0
        // An echo, if the adapter still has it on (before ATE0 takes effect).
        val reply = lines.filter { it.replace(" ", "") != step.command }
        when (step) {
            is Setup -> onSetupReply(step, reply)
            is Discover -> onDiscoverReply(step, reply)
            Protocol -> {
                status = status.copy(protocol = reply.firstOrNull())
                startPolling()
            }
            is Poll -> return onPollReply(step, reply)
        }
        return emptyList()
    }

    private fun onSetupReply(step: Setup, reply: List<String>) {
        when (step.command) {
            "ATI" -> reply.firstOrNull { it.contains("ELM", ignoreCase = true) }?.let { status = status.copy(adapter = it) }
            "STI" -> reply.firstOrNull { it.startsWith("STN", ignoreCase = true) }?.let { status = status.copy(adapter = it) }
        }
        if (++setupStep < SETUP.size) {
            request(Setup(SETUP[setupStep]))
        } else {
            status = status.copy(phase = Phase.SEARCHING, message = "Connecting to the car. The ignition must be on.")
            request(Discover(0))
        }
    }

    private fun onDiscoverReply(step: Discover, reply: List<String>) {
        val pids = ObdReplies.supportedPids(reply, step.page)
        if (pids == null) {
            if (step.page == 0) {
                noCar(
                    if (ObdReplies.isBusError(reply) || ObdReplies.isNoData(reply) || reply.any { "SEARCHING" in it.uppercase() }) {
                        "The car isn't answering. Turn the ignition on."
                    } else {
                        "Unexpected reply from the adapter: ${reply.joinToString(" ")}"
                    },
                )
            } else {
                request(Protocol)
            }
            return
        }
        supportedPids += pids
        // The last PID of a page says whether the next page exists. Oil
        // temperature (0x5C) is on the third page, from 0x40.
        val nextPage = step.page + 0x20
        if (nextPage in pids && nextPage <= 0x40) request(Discover(nextPage)) else request(Protocol)
    }

    private fun startPolling() {
        val supported = ObdChannel.entries.filter { it.pid in supportedPids }.toSet()
        status = status.copy(
            phase = Phase.POLLING,
            supported = supported,
            message = if (supported.isEmpty()) "The car reports none of the readings this app uses." else null,
        )
        next()
    }

    private fun onPollReply(step: Poll, reply: List<String>): List<ObdSample> {
        val channel = step.channel
        val data = ObdReplies.mode01Data(reply, channel.pid, channel.bytes)
        val samples = mutableListOf<ObdSample>()
        when {
            data != null -> {
                samples += ObdSample(channel, channel.decode(data), nowNanos)
                noDataCounts.remove(channel)
                readingTimes.addLast(nowNanos)
                while (readingTimes.size > 50) readingTimes.removeFirst()
                status = status.copy(values = status.values.with(samples[0]), readingsPerSecond = rate())
            }
            step.suffix && reply.any { it == "?" } -> useCountSuffix = false
            ObdReplies.isNoData(reply) -> {
                // A PID the car lists but never answers: stop asking after a few tries.
                val n = (noDataCounts[channel] ?: 0) + 1
                noDataCounts[channel] = n
                if (n >= 5) status = status.copy(supported = status.supported - channel)
            }
            ObdReplies.isBusError(reply) -> {
                noCar("The car stopped answering. Is the ignition off?")
                return samples
            }
        }
        next()
        return samples
    }

    private fun noCar(message: String) {
        status = status.copy(phase = Phase.NO_CAR, message = message)
        supportedPids.clear()
        waitUntilNanos = nowNanos + RETRY_NANOS
    }

    private fun rate(): Double? {
        if (readingTimes.size < 2) return null
        val span = readingTimes.last() - readingTimes.first()
        return if (span > 0) (readingTimes.size - 1) * 1e9 / span else null
    }

    /** Sends whatever comes next. */
    private fun next() {
        when (status.phase) {
            Phase.STARTING -> request(Setup(SETUP[setupStep]))
            Phase.SEARCHING -> request(Discover(0))
            Phase.NO_CAR -> {
                status = status.copy(phase = Phase.SEARCHING)
                request(Discover(0))
            }
            Phase.POLLING -> {
                val supported = status.supported
                val fast = ObdChannel.entries.filter { it.fast && it in supported }
                val slow = ObdChannel.entries.filter { !it.fast && it in supported }
                val channel = when {
                    slow.isNotEmpty() && (fast.isEmpty() || nowNanos - lastSlowNanos >= slowIntervalNanos) -> {
                        lastSlowNanos = nowNanos
                        slow[slowIndex++ % slow.size]
                    }
                    fast.isNotEmpty() -> fast[fastIndex++ % fast.size]
                    else -> return
                }
                request(Poll(channel, useCountSuffix))
            }
        }
    }

    private fun request(step: Step) {
        pending = step
        sentAtNanos = nowNanos
        send(step.command + "\r")
    }

    private companion object {
        val SETUP = listOf(
            "ATZ", // reset
            "ATE0", // no echo of commands
            "ATL0", // no line feeds
            "ATS0", // no spaces between bytes: shorter replies
            "ATH0", // no headers
            "ATAT1", // adaptive timing: waits only as long as the car needs
            "ATSP0", // find the bus protocol automatically
            "ATI", // identify (ELM327)
            "STI", // identify (OBDLink's STN chip); a plain ELM327 answers "?"
        )

        /** How long to wait before asking a car that didn't answer again. */
        const val RETRY_NANOS = 3_000_000_000L
    }
}
