package laptimer.app

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.edit
import laptimer.core.CanMeaning
import laptimer.core.CanMonitor
import laptimer.core.CanProfile
import laptimer.core.CanProfiles
import laptimer.core.ObdChannel
import laptimer.core.ObdPoller
import java.util.UUID

/** Remembers the chosen OBD adapter between sessions. */
object ObdPrefs {
    private fun prefs(c: Context) = c.getSharedPreferences("obd", Context.MODE_PRIVATE)

    fun load(c: Context): ObdTarget? {
        val p = prefs(c)
        val address = p.getString("address", null) ?: return null
        val transport = runCatching { ObdTransport.valueOf(p.getString("transport", "") ?: "") }.getOrNull() ?: return null
        return ObdTarget(address, p.getString("name", null) ?: address, transport)
    }

    fun save(c: Context, t: ObdTarget?) {
        prefs(c).edit {
            if (t == null) {
                remove("address").remove("name").remove("transport")
            } else {
                putString("address", t.address).putString("name", t.name).putString("transport", t.transport.name)
            }
        }
    }

    /** The CAN profile to read with, or null for standard OBD-II. */
    fun loadCanProfile(c: Context): CanProfile? = prefs(c).getString("canProfile", null)?.let { id -> CanProfiles.ALL.find { it.id == id } }

    fun saveCanProfile(c: Context, p: CanProfile?) {
        prefs(c).edit { if (p == null) remove("canProfile") else putString("canProfile", p.id) }
    }
}

/** Names OBD adapters commonly advertise. */
private val OBD_NAMES = listOf("obd", "elm", "vlink", "vgate", "icar", "veepeak", "kiwi", "carista", "v-link")

private fun looksLikeObd(name: String?) = name != null && OBD_NAMES.any { name.contains(it, ignoreCase = true) }

/** Services BLE adapters advertise; FFF0 is also what the RaceBox emulator offers. */
private val OBD_SERVICES = listOf("FFF0", "FFE0", "18F0").map {
    ParcelUuid(UUID.fromString("0000$it-0000-1000-8000-00805F9B34FB"))
}

/**
 * Scans for BLE OBD adapters for 10 seconds. Callbacks on the main thread.
 * The caller must hold BLUETOOTH_SCAN.
 */
@SuppressLint("MissingPermission")
private class ObdScanner(context: Context, private val onFound: (ObdTarget) -> Unit, private val onStopped: () -> Unit) {
    private val scanner = context.getSystemService(BluetoothManager::class.java).adapter?.bluetoothLeScanner
    private val main = Handler(Looper.getMainLooper())
    private var scanning = false

    private val callback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val record = result.scanRecord
            val name = record?.deviceName ?: result.device.name
            val hasService = record?.serviceUuids?.any { it in OBD_SERVICES } == true
            if (!hasService && !looksLikeObd(name)) return
            onFound(ObdTarget(result.device.address, name ?: "OBD adapter (no name)", ObdTransport.BLE))
        }

        override fun onScanFailed(errorCode: Int) = stop()
    }

    fun start() {
        if (scanning || scanner == null) return
        scanning = true
        scanner.startScan(null, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), callback)
        main.postDelayed({ stop() }, 10_000L)
    }

    fun stop() {
        if (!scanning) return
        scanning = false
        main.removeCallbacksAndMessages(null)
        scanner?.stopScan(callback)
        onStopped()
    }
}

/**
 * Picks the OBD adapter for the next session: none, a paired Classic
 * adapter (OBDLink MX+ or LX), or a BLE adapter found by scanning
 * (OBDLink CX). The choice is remembered.
 */
/**
 * What to read through the adapter: standard OBD-II readings, or a car's
 * own CAN messages with a profile. CAN needs an OBDLink (STN chip) and,
 * on most recent cars, a tap behind the gateway.
 */
@Composable
fun ReadModePicker(selected: CanProfile?, onSelect: (CanProfile?) -> Unit) {
    val context = LocalContext.current
    Text("Read", style = MaterialTheme.typography.titleSmall)
    for (option in listOf<CanProfile?>(null) + CanProfiles.ALL) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable {
                    onSelect(option)
                    ObdPrefs.saveCanProfile(context, option)
                }
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = selected?.id == option?.id, onClick = null)
            Text(option?.let { "CAN bus: ${it.name}" } ?: "Standard OBD-II readings", Modifier.padding(start = 8.dp))
        }
    }
    if (selected != null) {
        Text(
            "Listens silently to the car's CAN bus for ${selected.signals.size} signals. Needs an OBDLink, and usually a tap " +
                "behind the car's gateway: the factory OBD port may not carry these messages. Source: ${selected.source}",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@SuppressLint("MissingPermission") // DevicePicker only shows this with the Bluetooth permissions granted.
@Composable
fun ObdPicker(selected: ObdTarget?, onSelect: (ObdTarget?) -> Unit) {
    val context = LocalContext.current
    val found = remember { mutableStateListOf<ObdTarget>() }
    var scanning by remember { mutableStateOf(false) }
    var searched by remember { mutableStateOf(false) }
    val scanner = remember {
        ObdScanner(
            context,
            onFound = { t -> if (found.none { it.address == t.address }) found.add(t) },
            onStopped = { scanning = false },
        )
    }
    DisposableEffect(Unit) { onDispose { scanner.stop() } }

    fun paired(): List<ObdTarget> {
        val adapter = context.getSystemService(BluetoothManager::class.java).adapter ?: return emptyList()
        return adapter.bondedDevices.orEmpty()
            .filter { it.type != BluetoothDevice.DEVICE_TYPE_LE && looksLikeObd(it.name) }
            .map { ObdTarget(it.address, it.name ?: it.address, ObdTransport.CLASSIC) }
    }

    Text("OBD adapter", style = MaterialTheme.typography.titleMedium)
    val options = buildList<ObdTarget?> {
        add(null)
        selected?.let { add(it) }
        (paired() + found).filter { t -> t.address != selected?.address }.forEach { add(it) }
    }.distinctBy { it?.address }
    for (option in options) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable {
                    onSelect(option)
                    ObdPrefs.save(context, option)
                }
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = selected?.address == option?.address, onClick = null)
            Text(
                option?.let { "${it.name} (${if (it.transport == ObdTransport.BLE) "BLE" else "Bluetooth"})" } ?: "No OBD adapter",
                Modifier.padding(start = 8.dp),
            )
        }
    }
    OutlinedButton(enabled = !scanning, onClick = {
        found.clear()
        searched = true
        scanning = true
        scanner.start()
    }) { Text(if (scanning) "Looking for BLE adapters..." else "Find BLE adapters") }
    Text(
        "OBDLink MX+ and LX: pair them in Android's Bluetooth settings first (press the adapter's button), " +
            "then they appear here. OBDLink CX and other BLE adapters: tap Find. The car's ignition must be on." +
            if (searched && !scanning && found.isEmpty()) " No BLE adapters found." else "",
        style = MaterialTheme.typography.bodySmall,
    )
}

/** CAN signals on the recording screen, by what they measure. */
@Composable
fun CanPanel(obd: ObdStatus, profile: CanProfile) {
    val c = obd.can
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            "CAN: " + when {
                obd.connection == ObdConnection.CONNECTING -> "connecting to ${obd.target.name}"
                obd.connection == ObdConnection.RECONNECTING -> "connection lost, reconnecting"
                c == null || c.phase == CanMonitor.Phase.STARTING -> "setting up the adapter"
                c.phase == CanMonitor.Phase.SURVEY -> "surveying the bus"
                c.phase == CanMonitor.Phase.FILTERING -> "setting filters"
                else -> listOfNotNull(c.adapter, c.framesPerSecond?.let { "%.0f messages/s".format(it) }).joinToString(", ")
            },
            style = MaterialTheme.typography.titleSmall,
        )
        if (c == null) return@Column
        c.message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        if (c.surveyCounts.isNotEmpty() && c.missingIds.isNotEmpty()) {
            Text(
                "Not seen: " + c.missingIds.sorted().joinToString(" ") { "%03X".format(it) } +
                    ". The survey saw ${c.surveyCounts.size} other IDs.",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFFC62828),
            )
        }
        if (c.overflows > 0) Text("The adapter dropped messages ${c.overflows} times (buffer full).", style = MaterialTheme.typography.bodySmall)

        fun v(m: CanMeaning): Double? = profile.signal(m)?.let { c.values[it.name] }
        fun fmt(m: CanMeaning, pattern: String) = v(m)?.let { pattern.format(it) }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(fmt(CanMeaning.RPM, "%.0f") ?: "-", fontSize = 40.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
            Text("rpm")
            v(CanMeaning.GEAR)?.let { g ->
                // On the manual gearbox 13 is reverse and 14 not engaged; 0 means stopped.
                Text(
                    when (g.toInt()) {
                        13 -> "R"
                        0, 14 -> "N"
                        else -> g.toInt().toString()
                    },
                    fontSize = 40.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
        v(CanMeaning.RPM)?.let { Bar(it / RPM_FULL_SCALE, if (it > RPM_WARN) Color(0xFFC62828) else Color(0xFF1E88E5)) }
        v(CanMeaning.THROTTLE)?.let {
            Text("Throttle %.0f %%".format(it))
            Bar(it / 100, Color(0xFF2E7D32))
        }
        v(CanMeaning.BRAKE_PRESSURE)?.let {
            Text("Brake %.0f bar".format(it))
            Bar(it / BRAKE_FULL_SCALE_BAR, Color(0xFFC62828))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            Reading("Steering", fmt(CanMeaning.STEERING_ANGLE, "%.0f°"))
            Reading("Lateral", fmt(CanMeaning.LATERAL_G, "%+.2f g"))
            Reading("Yaw", fmt(CanMeaning.YAW_RATE, "%.1f °/s"))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            for ((m, label) in listOf(
                CanMeaning.WHEEL_SPEED_FL to "FL", CanMeaning.WHEEL_SPEED_FR to "FR",
                CanMeaning.WHEEL_SPEED_RL to "RL", CanMeaning.WHEEL_SPEED_RR to "RR",
            )) {
                Reading(label, fmt(m, "%.1f"))
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            Reading("Oil", fmt(CanMeaning.OIL_PRESSURE, "%.1f bar"))
            Reading("Manifold", fmt(CanMeaning.MANIFOLD_PRESSURE, "%.2f bar"))
            Reading("Fuel", fmt(CanMeaning.FUEL_LEVEL, "%.0f L"))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            Reading("Coolant", fmt(CanMeaning.COOLANT_TEMP, "%.0f °C"))
            Reading("Oil", fmt(CanMeaning.OIL_TEMP, "%.0f °C"))
            Reading("Intake", fmt(CanMeaning.INTAKE_TEMP, "%.0f °C"))
        }
    }
}

/** A brake bar's full scale: hard braking on a road car reaches 100 bar or more. */
private const val BRAKE_FULL_SCALE_BAR = 150.0

/** Engine readings on the recording screen. */
@Composable
fun EnginePanel(obd: ObdStatus) {
    val p = obd.poller
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            "Engine: " + when {
                obd.connection == ObdConnection.CONNECTING -> "connecting to ${obd.target.name}"
                obd.connection == ObdConnection.RECONNECTING -> "connection lost, reconnecting"
                p == null || p.phase == ObdPoller.Phase.STARTING -> "setting up the adapter"
                p.phase == ObdPoller.Phase.SEARCHING -> "connecting to the car"
                p.phase == ObdPoller.Phase.NO_CAR -> "the car isn't answering"
                else -> listOfNotNull(p.adapter, p.readingsPerSecond?.let { "%.0f readings/s".format(it) }).joinToString(", ")
            },
            style = MaterialTheme.typography.titleSmall,
        )
        p?.message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        if (p == null || p.phase != ObdPoller.Phase.POLLING) return@Column
        val v = p.values
        val supported = p.supported

        if (ObdChannel.RPM in supported) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(v[ObdChannel.RPM]?.let { "%.0f".format(it) } ?: "-", fontSize = 40.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                Text("rpm")
            }
            Bar((v[ObdChannel.RPM] ?: 0.0) / RPM_FULL_SCALE, if ((v[ObdChannel.RPM] ?: 0.0) > RPM_WARN) Color(0xFFC62828) else Color(0xFF1E88E5))
        }
        if (ObdChannel.THROTTLE in supported) {
            Text("Throttle " + (v[ObdChannel.THROTTLE]?.let { "%.0f %%".format(it) } ?: "-"))
            Bar((v[ObdChannel.THROTTLE] ?: 0.0) / 100.0, Color(0xFF2E7D32))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            v.boostKpa?.let { Reading("Boost", "%+.2f bar".format(it / 100)) }
            if (ObdChannel.SPEED in supported) Reading("Speed", v[ObdChannel.SPEED]?.let { "%.0f km/h".format(it) })
            if (ObdChannel.MASS_AIRFLOW in supported) Reading("Airflow", v[ObdChannel.MASS_AIRFLOW]?.let { "%.0f g/s".format(it) })
        }
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            for (c in listOf(ObdChannel.COOLANT_TEMP, ObdChannel.OIL_TEMP, ObdChannel.INTAKE_TEMP)) {
                if (c in supported) Reading(c.label, v[c]?.let { "%.0f °C".format(it) })
            }
        }
    }
}

/** The RPM bar's full scale, and where it turns red. */
private const val RPM_FULL_SCALE = 8000.0
private const val RPM_WARN = 6500.0

@Composable
private fun Reading(label: String, value: String?) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium)
        Text(value ?: "-", fontFamily = FontFamily.Monospace)
    }
}

/** A horizontal bar filled to [fraction] (0 to 1). */
@Composable
private fun Bar(fraction: Double, color: Color) {
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .height(10.dp)
            .background(Color(0x22000000)),
    ) {
        Box(Modifier.size(maxWidth * fraction.coerceIn(0.0, 1.0).toFloat(), 10.dp).background(color))
    }
}
