package laptimer.core

import kotlin.math.roundToInt

/** Engine readings for an emulated OBD adapter, from simulated driving. */
data class EngineState(
    val rpm: Double,
    val throttlePercent: Double,
    val speedKmh: Double,
    val manifoldKpa: Double,
    val airflowGs: Double,
    val coolantC: Double,
    val intakeC: Double,
    val oilC: Double,
    val barometricKpa: Double = 101.0,
)

/**
 * A plausible small turbocharged engine with a six-speed gearbox, driven
 * hard: it holds the lowest gear that keeps the revs under the shift
 * point. Good enough to see sensible numbers move with the driving; not a
 * physical model.
 */
object EngineModel {
    /** km/h per 1000 rpm in each gear. */
    private val GEARS = doubleArrayOf(8.0, 13.0, 18.0, 23.0, 28.0, 33.0)
    private const val IDLE_RPM = 850.0
    private const val SHIFT_RPM = 6500.0

    fun state(drive: DriveState, secondsSinceStart: Double): EngineState {
        val kmh = drive.speedMps * 3.6
        val gear = GEARS.firstOrNull { kmh / it * 1000 <= SHIFT_RPM } ?: GEARS.last()
        val rpm = maxOf(IDLE_RPM, kmh / gear * 1000)
        val throttle = when {
            kmh < 1 -> 0.0
            drive.longitudinalG > 0.03 -> (drive.longitudinalG / 0.4 * 100).coerceIn(20.0, 100.0)
            drive.longitudinalG < -0.05 -> 0.0
            // Holding speed.
            else -> 35.0
        }
        // Vacuum with the throttle shut; boost builds above 3000 rpm.
        val spool = ((rpm - 2000) / 1000).coerceIn(0.0, 1.0)
        val manifold = 30.0 + throttle / 100 * (70.0 + 80.0 * spool)
        // Air through a 2-litre engine: revs times pressure, at 90% efficiency.
        val airflow = rpm / 60 / 2 * 2.0 * (manifold / 101.0) * 1.2 * 0.9
        val warm = (secondsSinceStart / 600).coerceIn(0.0, 1.0)
        return EngineState(
            rpm = rpm,
            throttlePercent = throttle,
            speedKmh = kmh,
            manifoldKpa = manifold,
            airflowGs = airflow,
            coolantC = 70 + 20 * warm,
            intakeC = 25 + (manifold - 101).coerceAtLeast(0.0) * 0.15,
            oilC = 80 + 25 * warm,
        )
    }
}

/**
 * Answers ELM327 commands the way an ELM327 v1.5 adapter does, for the
 * RaceBox emulator app and for tests. It starts with echo and spaces on,
 * as the real chip does after a reset.
 */
class Elm327Emulator(
    private val engine: () -> EngineState?,
    /** The readings the emulated car supports. */
    private val supported: Set<ObdChannel> = ObdChannel.entries.toSet(),
) {
    private var echo = true
    private var spaces = true
    private var headers = false
    private var searched = false

    /** Set false to act as a car with the ignition off. */
    var carOn = true

    /** The reply to one command (without its "\r"), ending with the prompt. */
    fun reply(commandText: String): String {
        val cmd = commandText.replace(" ", "").trim().uppercase()
        // The chip echoes a command as it arrives, before acting on it, so "ATE0" itself is echoed.
        val echoed = echo
        val lines = respond(cmd)
        val body = lines.joinToString("\r") + "\r\r>"
        return if (echoed) "$commandText\r$body" else body
    }

    private fun respond(cmd: String): List<String> {
        if (cmd.isEmpty()) return emptyList()
        if (cmd.startsWith("AT")) return at(cmd.substring(2))
        // OBDLink's own commands; a plain ELM327 doesn't know them.
        if (cmd.startsWith("ST")) return listOf("?")
        if (cmd.length < 4 || !cmd.all { it.isDigit() || it in 'A'..'F' }) return listOf("?")
        if (!cmd.startsWith("01")) return listOf("NO DATA")
        val pid = cmd.substring(2, 4).toInt(16)
        // A trailing digit is the number of replies to wait for; accepted and ignored.
        val prefix = if (!searched) listOf("SEARCHING...") else emptyList()
        searched = true
        if (!carOn) {
            searched = false
            return prefix + "UNABLE TO CONNECT"
        }
        val data = mode01(pid) ?: return prefix + "NO DATA"
        val bytes = listOf(0x41, pid) + data
        return prefix + bytes.joinToString(if (spaces) " " else "") { "%02X".format(it) }
    }

    private fun at(cmd: String): List<String> = when {
        cmd == "Z" -> {
            echo = true
            spaces = true
            headers = false
            searched = false
            listOf("", "ELM327 v1.5")
        }
        cmd == "I" -> listOf("ELM327 v1.5")
        cmd == "E0" -> ok { echo = false }
        cmd == "E1" -> ok { echo = true }
        cmd == "S0" -> ok { spaces = false }
        cmd == "S1" -> ok { spaces = true }
        cmd == "H0" -> ok { headers = false }
        cmd == "H1" -> ok { headers = true }
        cmd == "L0" || cmd == "L1" -> ok {}
        cmd.startsWith("AT") && cmd.length == 3 -> ok {}
        cmd.startsWith("SP") -> ok { searched = false }
        cmd == "DPN" -> listOf(if (searched) "A6" else "0")
        else -> listOf("?")
    }

    private inline fun ok(change: () -> Unit): List<String> {
        change()
        return listOf("OK")
    }

    private fun mode01(pid: Int): List<Int>? {
        when (pid) {
            0x00, 0x20, 0x40 -> return supportMask(pid)
        }
        val channel = ObdChannel.forPid(pid)?.takeIf { it in supported } ?: return null
        val e = engine() ?: return null
        fun byte(v: Double) = v.roundToInt().coerceIn(0, 255)
        fun word(v: Double) = v.roundToInt().coerceIn(0, 65535).let { listOf(it shr 8, it and 0xFF) }
        return when (channel) {
            ObdChannel.RPM -> word(e.rpm * 4)
            ObdChannel.THROTTLE -> listOf(byte(e.throttlePercent * 255 / 100))
            ObdChannel.SPEED -> listOf(byte(e.speedKmh))
            ObdChannel.MANIFOLD_PRESSURE -> listOf(byte(e.manifoldKpa))
            ObdChannel.MASS_AIRFLOW -> word(e.airflowGs * 100)
            ObdChannel.COOLANT_TEMP -> listOf(byte(e.coolantC + 40))
            ObdChannel.INTAKE_TEMP -> listOf(byte(e.intakeC + 40))
            ObdChannel.OIL_TEMP -> listOf(byte(e.oilC + 40))
            ObdChannel.BAROMETRIC_PRESSURE -> listOf(byte(e.barometricKpa))
        }
    }

    /** The supported-PIDs mask for PIDs page+1 to page+32; the last bit means the next page exists. */
    private fun supportMask(page: Int): List<Int> {
        val pids = supported.map { it.pid }
        var mask = 0L
        for (p in pids) if (p in page + 1..page + 32) mask = mask or (1L shl (32 - (p - page)))
        if (pids.any { it > page + 32 }) mask = mask or 1L
        return listOf((mask shr 24).toInt() and 0xFF, (mask shr 16).toInt() and 0xFF, (mask shr 8).toInt() and 0xFF, mask.toInt() and 0xFF)
    }
}
