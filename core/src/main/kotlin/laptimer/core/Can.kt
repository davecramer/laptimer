package laptimer.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** How a signal's bits are laid out, as in a DBC file. */
@Serializable
enum class BitOrder {
    /**
     * Intel, DBC "@1". Bits are numbered from bit 0 of byte 0 upward
     * (byte * 8 + bit), and the start bit is the least significant bit.
     * RaceChrono's bitsToUintLe(raw, start, length) is the same.
     */
    LITTLE_ENDIAN,

    /**
     * Motorola, DBC "@0". Same bit numbering, but the start bit is the
     * most significant bit and the value runs on into the next byte's
     * top bit.
     */
    BIG_ENDIAN,
}

/**
 * What a signal measures, so the live screen and the emulator know what
 * to do with it. Each meaning has one unit, given here; a signal with a
 * meaning must be scaled to that unit.
 */
@Serializable
enum class CanMeaning(val unit: String) {
    RPM("rpm"),
    THROTTLE("%"),
    SPEED("km/h"),
    WHEEL_SPEED_FL("km/h"),
    WHEEL_SPEED_FR("km/h"),
    WHEEL_SPEED_RL("km/h"),
    WHEEL_SPEED_RR("km/h"),
    STEERING_ANGLE("deg"),
    STEERING_SPEED("deg/s"),
    YAW_RATE("deg/s"),
    LATERAL_G("g"),
    LONGITUDINAL_ACCEL("m/s²"),
    BRAKE_PRESSURE("bar"),
    BRAKE_LIGHT(""),
    CLUTCH("%"),
    GEAR(""),
    OIL_PRESSURE("bar"),
    MANIFOLD_PRESSURE("bar"),
    COOLANT_TEMP("°C"),
    OIL_TEMP("°C"),
    INTAKE_TEMP("°C"),
    FUEL_LEVEL("L"),
    OUTSIDE_TEMP("°C"),
}

/** One value packed into a CAN message, as a DBC file describes it. */
@Serializable
data class CanSignal(
    val name: String,
    /** The message's 11-bit identifier. */
    val id: Int,
    val startBit: Int,
    val length: Int,
    val order: BitOrder = BitOrder.LITTLE_ENDIAN,
    val signed: Boolean = false,
    val factor: Double = 1.0,
    val offset: Double = 0.0,
    val unit: String = "",
    val meaning: CanMeaning? = null,
    /** Where the definition came from, or what is doubtful about it. */
    val note: String? = null,
) {
    /** The physical value in [data], or null if the message is too short to hold it. */
    fun decode(data: ByteArray): Double? {
        val raw = rawValue(data) ?: return null
        val v = if (signed && length < 64 && raw and (1L shl (length - 1)) != 0L) raw - (1L shl length) else raw
        return v * factor + offset
    }

    /** Writes [value] into [data], for the emulator. Values out of range are clamped. */
    fun encode(value: Double, data: ByteArray) {
        val max = if (signed) (1L shl (length - 1)) - 1 else (1L shl length) - 1
        val min = if (signed) -(1L shl (length - 1)) else 0L
        val raw = Math.round((value - offset) / factor).coerceIn(min, max).let { if (it < 0) it + (1L shl length) else it }
        for ((i, pos) in bitPositions().withIndex()) {
            // Little-endian positions run from the least significant bit; big-endian from the most.
            val bitOfValue = if (order == BitOrder.LITTLE_ENDIAN) i else length - 1 - i
            val byte = pos / 8
            if (byte >= data.size) continue
            val mask = 1 shl (pos % 8)
            data[byte] = if (raw and (1L shl bitOfValue) != 0L) {
                (data[byte].toInt() or mask).toByte()
            } else {
                (data[byte].toInt() and mask.inv()).toByte()
            }
        }
    }

    private fun rawValue(data: ByteArray): Long? {
        var v = 0L
        for ((i, pos) in bitPositions().withIndex()) {
            val byte = pos / 8
            if (byte >= data.size) return null
            val bit = (data[byte].toInt() shr (pos % 8)) and 1
            v = if (order == BitOrder.LITTLE_ENDIAN) v or (bit.toLong() shl i) else (v shl 1) or bit.toLong()
        }
        return v
    }

    /**
     * The bit positions in order: least significant first for little
     * endian, most significant first for big endian, which steps down
     * within a byte and then to the top bit of the next byte.
     */
    private fun bitPositions(): IntArray {
        if (order == BitOrder.LITTLE_ENDIAN) return IntArray(length) { startBit + it }
        var pos = startBit
        return IntArray(length) {
            val p = pos
            pos = if (pos % 8 == 0) pos + 15 else pos - 1
            p
        }
    }
}

/** The signals to read from one car. */
@Serializable
data class CanProfile(
    val id: String,
    val name: String,
    /** Where the definitions came from. */
    val source: String? = null,
    val signals: List<CanSignal>,
) {
    /** The message identifiers to ask the adapter for, in order. */
    val ids: List<Int> get() = signals.map { it.id }.distinct().sorted()

    fun signal(meaning: CanMeaning): CanSignal? = signals.firstOrNull { it.meaning == meaning }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }
        fun fromJson(text: String): CanProfile = json.decodeFromString(serializer(), text)
    }

    fun toJson(): String = json.encodeToString(serializer(), this)
}

/** One CAN message as the adapter reported it. */
class CanFrame(val id: Int, val data: ByteArray)

/** A decoded signal value. [timeNanos] is the phone's clock when the message arrived. */
data class CanSample(val signal: CanSignal, val value: Double, val timeNanos: Long)

/** Reads the signal definitions in a DBC file. */
object Dbc {
    class Result(val signals: List<CanSignal>, val skipped: List<String>)

    private val message = Regex("""^BO_\s+(\d+)\s+(\w+)\s*:""")
    private val signal = Regex(
        """^SG_\s+(\w+)\s*(\S*)\s*:\s*(\d+)\|(\d+)@([01])([+-])\s*\(\s*([-+0-9.eE]+)\s*,\s*([-+0-9.eE]+)\s*\)\s*\[[^\]]*]\s*"([^"]*)".*""",
    )

    /**
     * Reads every BO_ message's SG_ signals. Multiplexed signals and
     * 29-bit identifiers are skipped and listed in [Result.skipped].
     */
    fun parse(text: String): Result {
        val signals = mutableListOf<CanSignal>()
        val skipped = mutableListOf<String>()
        var id: Int? = null
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            message.find(line)?.let { m ->
                val value = m.groupValues[1].toLong()
                // DBC marks 29-bit identifiers by setting bit 31.
                id = if (value and 0x80000000L != 0L || value > 0x7FF) {
                    skipped += "message ${m.groupValues[2]}: 29-bit identifier"
                    null
                } else {
                    value.toInt()
                }
                return@let
            }
            val s = signal.find(line) ?: continue
            val messageId = id ?: continue
            val (name, mux) = s.destructured.let { it.component1() to it.component2() }
            if (mux.isNotEmpty()) {
                skipped += "$name: multiplexed"
                continue
            }
            signals += CanSignal(
                name = name,
                id = messageId,
                startBit = s.groupValues[3].toInt(),
                length = s.groupValues[4].toInt(),
                order = if (s.groupValues[5] == "1") BitOrder.LITTLE_ENDIAN else BitOrder.BIG_ENDIAN,
                signed = s.groupValues[6] == "-",
                factor = s.groupValues[7].toDouble(),
                offset = s.groupValues[8].toDouble(),
                unit = s.groupValues[9],
                meaning = guessMeaning(name),
            )
        }
        return Result(signals, skipped)
    }

    /** A meaning from the signal's name, for the live screen. Null when unsure. */
    fun guessMeaning(name: String): CanMeaning? {
        val n = name.lowercase()
        return when {
            "wheel" in n && "fl" in n -> CanMeaning.WHEEL_SPEED_FL
            "wheel" in n && "fr" in n -> CanMeaning.WHEEL_SPEED_FR
            "wheel" in n && "rl" in n -> CanMeaning.WHEEL_SPEED_RL
            "wheel" in n && "rr" in n -> CanMeaning.WHEEL_SPEED_RR
            "steering" in n && "angle" in n -> CanMeaning.STEERING_ANGLE
            "yaw" in n -> CanMeaning.YAW_RATE
            "lateral" in n -> CanMeaning.LATERAL_G
            "brake" in n && "pressure" in n -> CanMeaning.BRAKE_PRESSURE
            "oil" in n && "pressure" in n -> CanMeaning.OIL_PRESSURE
            "rpm" in n -> CanMeaning.RPM
            "throttle" in n -> CanMeaning.THROTTLE
            "coolant" in n -> CanMeaning.COOLANT_TEMP
            else -> null
        }
    }
}

/** Built-in signal definitions. */
object CanProfiles {
    private const val PLANETKRIS = "https://planetkris.com/porsche-718-cayman-can-bus-track-day-data/"

    /**
     * Porsche 718 (982) Cayman, GT4 and Clubsport, from planetkris.com:
     * reverse-engineered by the author and published as a DBC file and
     * RaceChrono equations. The bus was tapped behind the gateway; the
     * factory OBD port is not expected to carry these messages.
     */
    val PORSCHE_718 = CanProfile(
        id = "porsche-718",
        name = "Porsche 718 Cayman / GT4",
        source = PLANETKRIS,
        signals = listOf(
            CanSignal("Steering_angle", 0x086, 16, 13, factor = 0.1, unit = "deg", meaning = CanMeaning.STEERING_ANGLE,
                note = "Magnitude only: the source gives no left/right bit."),
            CanSignal("Steering_speed", 0x086, 31, 9, factor = 5.0, unit = "deg/s", meaning = CanMeaning.STEERING_SPEED),
            CanSignal("Yaw_rate", 0x101, 40, 14, factor = 0.01, unit = "deg/s", meaning = CanMeaning.YAW_RATE,
                note = "Magnitude only: the source gives no direction bit."),
            CanSignal("Lateral_accel", 0x101, 16, 8, factor = 0.01, offset = -1.27, unit = "g", meaning = CanMeaning.LATERAL_G),
            CanSignal("Longitudinal_accel", 0x101, 24, 10, factor = 0.03125, offset = -16.0, unit = "m/s²", meaning = CanMeaning.LONGITUDINAL_ACCEL),
            CanSignal("Wheel_speed_FL", 0x103, 16, 12, factor = 0.103, unit = "km/h", meaning = CanMeaning.WHEEL_SPEED_FL),
            CanSignal("Wheel_speed_FR", 0x103, 28, 12, factor = 0.103, unit = "km/h", meaning = CanMeaning.WHEEL_SPEED_FR),
            CanSignal("Wheel_speed_RL", 0x103, 40, 12, factor = 0.103, unit = "km/h", meaning = CanMeaning.WHEEL_SPEED_RL),
            CanSignal("Wheel_speed_RR", 0x103, 52, 12, factor = 0.103, unit = "km/h", meaning = CanMeaning.WHEEL_SPEED_RR),
            CanSignal("Clutch_pedal", 0x104, 32, 8, factor = 0.4, offset = -10.0, unit = "%", meaning = CanMeaning.CLUTCH),
            CanSignal("Throttle", 0x105, 48, 8, factor = 0.4, unit = "%", meaning = CanMeaning.THROTTLE),
            CanSignal("RPM", 0x105, 16, 16, factor = 0.25, unit = "rpm", meaning = CanMeaning.RPM),
            CanSignal("Brake_pressure", 0x106, 16, 10, factor = 0.3, offset = -30.0, unit = "bar", meaning = CanMeaning.BRAKE_PRESSURE),
            CanSignal("Brake_light", 0x106, 26, 1, unit = "", meaning = CanMeaning.BRAKE_LIGHT,
                note = "The source's DBC gives an offset of 1, which looks like a slip for a one-bit signal; 0 is used."),
            CanSignal("Oil_pressure", 0x107, 16, 8, factor = 0.04, unit = "bar", meaning = CanMeaning.OIL_PRESSURE),
            CanSignal("Manifold_pressure", 0x107, 39, 9, factor = 0.01, unit = "bar", meaning = CanMeaning.MANIFOLD_PRESSURE,
                note = "Called boost pressure in the DBC; RaceChrono's equation treats it as manifold pressure."),
            CanSignal("Displayed_speed", 0x30B, 48, 10, factor = 0.32, unit = "km/h", meaning = CanMeaning.SPEED),
            CanSignal("Oil_temp", 0x640, 16, 8, offset = -60.0, unit = "°C", meaning = CanMeaning.OIL_TEMP),
            CanSignal("Coolant_temp", 0x640, 24, 8, offset = -75.0, unit = "°C", meaning = CanMeaning.COOLANT_TEMP),
            CanSignal("Intake_temp", 0x640, 8, 8, factor = 0.75, offset = -48.0, unit = "°C", meaning = CanMeaning.INTAKE_TEMP),
            CanSignal("Fuel_level", 0x6B7, 40, 7, unit = "L", meaning = CanMeaning.FUEL_LEVEL),
            CanSignal("Outside_temp", 0x6B7, 56, 8, factor = 0.5, offset = -50.0, unit = "°C", meaning = CanMeaning.OUTSIDE_TEMP),
            CanSignal("Gear_manual", 0x081, 49, 4, unit = "", meaning = CanMeaning.GEAR,
                note = "Manual gearbox: 0 stopped, 1 to 6, 13 reverse, 14 not engaged."),
            CanSignal("Gear_PDK", 0x102, 27, 4, order = BitOrder.BIG_ENDIAN, unit = "",
                note = "PDK: 1 to 7, 8 reverse. From RaceChrono's bitsToUInt(raw, 28, 4), read as most significant bit " +
                    "first: the low four bits of byte 3. Unverified."),
        ),
    )

    val ALL = listOf(PORSCHE_718)
}

/**
 * Reads CAN messages from an adapter in monitor mode. With headers on
 * (ATH1), spaces off (ATS0) and CAN formatting off (ATCAF0), an 11-bit
 * message comes as one line: three hex digits of identifier, then the
 * data bytes, "1051A2B3C4D5E6F708".
 */
object CanMonitorLines {
    fun parse(line: String): CanFrame? {
        val hex = line.replace(" ", "").uppercase()
        if (hex.length < 3 || hex.length > 3 + 16 || (hex.length - 3) % 2 != 0) return null
        if (!hex.all { it.isDigit() || it in 'A'..'F' }) return null
        val id = hex.substring(0, 3).toInt(16)
        if (id > 0x7FF) return null
        val data = ByteArray((hex.length - 3) / 2) { hex.substring(3 + it * 2, 5 + it * 2).toInt(16).toByte() }
        return CanFrame(id, data)
    }
}
