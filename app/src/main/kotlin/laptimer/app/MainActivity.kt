package laptimer.app

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.io.File
import org.maplibre.android.MapLibre

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // MapLibre must be set up before the first MapView is created.
        MapLibre.getInstance(applicationContext)
        // At the track the phone is mounted and nobody touches it.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) { App() }
            }
        }
    }
}

private val REQUIRED_PERMISSIONS = buildList {
    add(Manifest.permission.BLUETOOTH_SCAN)
    add(Manifest.permission.BLUETOOTH_CONNECT)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
}.toTypedArray()

@Composable
private fun App() {
    val status by RecorderState.status.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    // While recording, only the recording screen is shown, so a stray
    // touch cannot open the editor mid-session.
    if (status.phase != Phase.IDLE) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) { RecordingScreen(status) }
        return
    }
    Column(Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = tab) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Record") })
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Sessions") })
            Tab(selected = tab == 2, onClick = { tab = 2 }, text = { Text("Tracks") })
        }
        when (tab) {
            0 -> Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) { DevicePicker(status) }
            1 -> SessionsTab()
            else -> TracksTab()
        }
    }
}

@Composable
private fun DevicePicker(status: RecorderStatus) {
    val context = LocalContext.current
    fun granted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }
    var hasPermissions by remember { mutableStateOf(granted()) }
    val askPermissions = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { hasPermissions = granted() }
    val adapter = remember { context.getSystemService(BluetoothManager::class.java).adapter }
    var bluetoothOn by remember { mutableStateOf(adapter?.isEnabled == true) }
    val askBluetooth = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { bluetoothOn = adapter?.isEnabled == true }

    val devices = remember { mutableStateListOf<FoundDevice>() }
    var scanning by remember { mutableStateOf(false) }
    val scanner = remember {
        RaceBoxScanner(
            context,
            onFound = { found ->
                val i = devices.indexOfFirst { it.address == found.address }
                if (i < 0) devices.add(found) else devices[i] = found
            },
            onStopped = { scanning = false },
        )
    }
    DisposableEffect(Unit) { onDispose { scanner.stop() } }

    // Tracks ready for timing. Picking none still records.
    val tracks = remember { trackStore(context).loadAll().tracks.filter { it.problems().isEmpty() } }
    // Finding the track by GPS is the default: it needs nothing picked at the track.
    var trackId by rememberSaveable { mutableStateOf<String?>(RecordingService.AUTO_TRACK) }
    var obdTarget by remember { mutableStateOf(ObdPrefs.load(context)) }
    var canProfile by remember { mutableStateOf(ObdPrefs.loadCanProfile(context)) }
    if (trackId != null && trackId != RecordingService.AUTO_TRACK && tracks.none { it.id == trackId }) trackId = RecordingService.AUTO_TRACK

    Text("Lap Timer", style = MaterialTheme.typography.headlineMedium)
    Spacer(Modifier.height(12.dp))
    status.error?.let { Text("Error: $it", color = MaterialTheme.colorScheme.error) }
    status.sessionPath?.let { path ->
        Text("Last session: ${File(path).name} (${File(path).length() / 1024} KB)")
        status.timing?.takeIf { it.laps.isNotEmpty() }?.let { timing ->
            Text("${status.trackName}: ${timing.laps.size} laps", style = MaterialTheme.typography.titleMedium)
            LapList(timing)
        }
        Spacer(Modifier.height(12.dp))
    }

    Text("Track", style = MaterialTheme.typography.titleMedium)
    if (tracks.isEmpty()) Text("No tracks are ready. Add one in the Tracks tab to time laps.")
    val options = listOf(RecordingService.AUTO_TRACK to "Automatic: the nearest saved track, found by GPS") +
        tracks.map { it.id to it.name } + (null to "No track: record only")
    for ((id, label) in options) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { trackId = id }
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = trackId == id, onClick = null)
            Text(label, Modifier.padding(start = 8.dp))
        }
    }
    Spacer(Modifier.height(12.dp))

    when {
        adapter == null -> Text("This phone has no Bluetooth.")
        !hasPermissions -> Button(onClick = { askPermissions.launch(REQUIRED_PERMISSIONS) }) {
            Text("Allow Bluetooth and notifications")
        }
        !bluetoothOn -> Button(onClick = {
            askBluetooth.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
        }) { Text("Turn on Bluetooth") }
        else -> {
            ObdPicker(obdTarget) { obdTarget = it }
            if (obdTarget != null) ReadModePicker(canProfile) { canProfile = it }
            Spacer(Modifier.height(16.dp))
            Text("RaceBox", style = MaterialTheme.typography.titleMedium)
            Button(
                enabled = !scanning,
                onClick = {
                    devices.clear()
                    scanning = true
                    scanner.start()
                },
            ) { Text(if (scanning) "Scanning..." else "Scan for RaceBox") }
            Spacer(Modifier.height(12.dp))
            // A plain column: the screen scrolls, and a lazy list can't sit inside a scrolling one.
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for (d in devices) {
                    Text(
                        "${d.name}   ${d.rssi} dBm",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                scanner.stop()
                                RecordingService.start(context, d.address, d.name, trackId, obdTarget, canProfile?.id)
                            }
                            .padding(vertical = 12.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun RecordingScreen(status: RecorderStatus) {
    val context = LocalContext.current
    val fix = status.lastFix
    val timing = status.timing
    if (timing != null && status.trackName != null) {
        val label = status.trackName + if (status.trackFoundByGps) " (found by GPS)" else ""
        LiveTiming(label, timing, status.timingAtNanos, streaming = status.phase == Phase.STREAMING)
        // Other layouts of the same circuit, or a neighbouring track, are a tap away.
        if (status.otherTracks.isNotEmpty()) {
            Text("Not this track? Switching restarts the lap count.", style = MaterialTheme.typography.bodySmall)
            for (t in status.otherTracks) {
                OutlinedButton(onClick = { RecordingService.setTrack(context, t.id) }) {
                    Text("${t.name} (%.1f km)".format(t.distanceM / 1000))
                }
            }
        }
        Spacer(Modifier.height(16.dp))
    }
    status.trackSearch?.let {
        Text(it, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(16.dp))
    }
    status.obd?.let {
        val profile = status.canProfile
        if (profile != null) CanPanel(it, profile) else EnginePanel(it)
        Spacer(Modifier.height(16.dp))
    }
    // With a track, the device details are secondary and shown smaller.
    val big = if (timing != null) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.headlineSmall
    Text(
        when (status.phase) {
            Phase.CONNECTING -> "Connecting to ${status.deviceName}"
            Phase.STREAMING -> "Recording from ${status.deviceName}"
            Phase.RECONNECTING -> "Connection lost, reconnecting"
            Phase.IDLE -> ""
        },
        style = MaterialTheme.typography.titleLarge,
    )
    Spacer(Modifier.height(12.dp))
    if (fix != null) {
        val fixName = when {
            !fix.fixOk -> "no fix"
            fix.fixType == 3 -> "3D fix"
            fix.fixType == 2 -> "2D fix"
            else -> "fix type ${fix.fixType}"
        }
        Text("$fixName, ${fix.satellites} satellites", style = big)
        Text("accuracy %.2f m".format(fix.horizontalAccuracyM), style = big)
        Text("speed %.1f km/h".format(fix.speedMps * 3.6), style = big)
        status.fixRateHz?.let { Text("rate %.1f Hz".format(it), style = big) }
        Text("%.7f, %.7f".format(fix.latitudeDeg, fix.longitudeDeg))
        Text("battery ${laptimer.core.RaceBox.miniBatteryPercent(fix.batteryRaw)}% (Mini/Mini S)")
    } else {
        Text("Waiting for data")
    }
    Spacer(Modifier.height(12.dp))
    Text("fixes ${status.fixes}, checksum errors ${status.checksumErrors}")
    Text("recorded ${status.bytesRecorded / 1024} KB")
    if (status.disconnects > 0) {
        Text("disconnects ${status.disconnects}: ${status.lastDisconnectReason}")
    }
    status.sessionPath?.let { Text(File(it).name) }
    Spacer(Modifier.height(24.dp))
    Button(onClick = { RecordingService.stop(context) }) { Text("Stop") }
}
