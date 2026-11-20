package laptimer.raceboxemulator

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import laptimer.core.Bounds
import laptimer.core.LatLon
import laptimer.core.OsmClient
import laptimer.core.OsmTrackCandidate
import laptimer.core.Overpass
import laptimer.core.Place
import laptimer.core.TrackLoop
import laptimer.core.UrlConnectionTransport
import java.io.IOException

/**
 * Process-wide state, so rotating the screen or leaving the app does not
 * drop the Bluetooth connection.
 */
object Emulator {
    val drive = DriveController()

    // Holds only the application context, which lives as long as the process.
    @SuppressLint("StaticFieldLeak")
    private var peripheral: RaceBoxPeripheral? = null

    fun peripheral(context: Context): RaceBoxPeripheral =
        peripheral ?: RaceBoxPeripheral(context.applicationContext, drive::fixAt, drive::engine) { drive.ignitionOn }
            .also { peripheral = it }

    /** A generic name: OSM services ask clients to identify the software, not the person. */
    val osm by lazy { OsmClient(UrlConnectionTransport("laptimer-racebox-emulator/0.1")) }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // A phone used as the emulator should keep running with nobody touching it.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) { EmulatorScreen() }
            }
        }
    }
}

private val PERMISSIONS = arrayOf(Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_CONNECT)

/** Laguna Seca from OpenStreetMap, bundled so the emulator works offline. */
private const val BUILT_IN_ASSET = "overpass-laguna-seca.json"

/** Where the test oval goes until a place is picked. */
private val DEFAULT_OVAL_CENTER = LatLon(28.0, -81.0)

@SuppressLint("MissingPermission")
@Composable
private fun EmulatorScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val peripheral = remember { Emulator.peripheral(context) }
    val ble by peripheral.status.collectAsStateWithLifecycle()
    val drive by Emulator.drive.status.collectAsStateWithLifecycle()

    fun granted() = PERMISSIONS.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }
    var hasPermissions by remember { mutableStateOf(granted()) }
    val askPermissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        hasPermissions = granted()
    }
    val adapter = remember { context.getSystemService(BluetoothManager::class.java).adapter }
    var bluetoothOn by remember { mutableStateOf(adapter?.isEnabled == true) }
    val askBluetooth = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        bluetoothOn = adapter?.isEnabled == true
    }

    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var search by remember { mutableStateOf("") }
    var places by remember { mutableStateOf<List<Place>>(emptyList()) }
    var candidates by remember { mutableStateOf<List<OsmTrackCandidate>>(emptyList()) }
    var lastPlace by remember { mutableStateOf<Place?>(null) }
    var pace by remember { mutableStateOf(Emulator.drive.currentPace) }

    fun useCandidate(c: OsmTrackCandidate, name: String) {
        // Try the OSM direction first; fall back to any loop if the ways aren't all one-way.
        val loop = TrackLoop.find(c.polylines, directed = c.drivingDirectionKnown)
            ?: TrackLoop.find(c.polylines, directed = false)
        if (loop == null) {
            message = "$name doesn't form a closed loop in OpenStreetMap. Try another, or the test oval."
            return
        }
        Emulator.drive.setTrack(name, loop)
        candidates = emptyList()
        message = if (c.drivingDirectionKnown) {
            "Driving direction from OpenStreetMap."
        } else {
            "OpenStreetMap doesn't give a driving direction here, so the car may lap the wrong way round."
        }
    }

    suspend fun request(block: suspend () -> Unit) {
        busy = true
        message = null
        try {
            block()
        } catch (e: IOException) {
            message = e.message ?: "Network error."
        } catch (e: IllegalArgumentException) {
            message = "Could not read the answer: ${e.message}"
        } finally {
            busy = false
        }
    }

    Column(
        Modifier
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("RaceBox Emulator", style = MaterialTheme.typography.headlineMedium)

        // Bluetooth.
        when {
            adapter == null -> Text("This device has no Bluetooth.")
            !hasPermissions -> Button(onClick = { askPermissions.launch(PERMISSIONS) }) { Text("Allow Bluetooth") }
            !bluetoothOn -> Button(onClick = { askBluetooth.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) }) {
                Text("Turn on Bluetooth")
            }
            else -> {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!ble.enabled) {
                        Button(onClick = { peripheral.start() }) { Text("Start advertising") }
                    } else {
                        OutlinedButton(onClick = { peripheral.stop() }) { Text("Stop") }
                    }
                }
                Text(
                    when {
                        !ble.enabled -> "Not advertising."
                        !ble.running -> "Waiting for Bluetooth."
                        !ble.advertising -> "Starting..."
                        ble.clients.isEmpty() -> "Advertising as ${adapter.name ?: "this device"}. Scan from the lap timer."
                        else -> "Connected."
                    },
                )
                ble.clients.forEach { c ->
                    Text(
                        "${c.address}: MTU ${c.mtu}, " +
                            (if (c.subscribed) "streaming, ${c.framesSent} frames sent" else "waiting for notifications") +
                            if (c.framesDropped > 0) ", ${c.framesDropped} backlogs dropped" else "",
                    )
                    if (c.obdSubscribed) Text("   OBD adapter in use: ${c.obdCommands} commands answered")
                }
                var ignition by remember { mutableStateOf(Emulator.drive.ignitionOn) }
                TextButton(onClick = {
                    ignition = !ignition
                    Emulator.drive.ignitionOn = ignition
                }) { Text(if (ignition) "Ignition on (tap to turn off)" else "Ignition off (tap to turn on)") }
                if (ble.commandsReceived > 0) Text("Commands received and ignored: ${ble.commandsReceived}")
                if (adapter.name?.startsWith("RaceBox") != true) {
                    TextButton(onClick = { adapter.setName("RaceBox Emulator") }) {
                        Text("Rename this device's Bluetooth to \"RaceBox Emulator\"")
                    }
                }
            }
        }
        ble.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }

        HorizontalDivider()

        // Driving.
        Text(drive.trackName?.let { "Track: $it, %.2f km".format(drive.trackLengthM / 1000) } ?: "Pick a track below.",
            style = MaterialTheme.typography.titleMedium)
        if (drive.trackName != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (drive.driving) {
                    OutlinedButton(onClick = { Emulator.drive.park() }) { Text("Park") }
                } else {
                    Button(onClick = { Emulator.drive.drive() }) { Text("Drive") }
                }
                OutlinedButton(onClick = { Emulator.drive.loseFix(5) }) { Text("Lose GPS for 5 s") }
            }
            Text(
                if (drive.driving) "Lap ${drive.lap + 1}, %.0f km/h".format(drive.speedKmh) else "Parked at the start of the loop.",
                style = MaterialTheme.typography.titleLarge,
            )
            drive.lastLapS?.let {
                Text("Last lap: %d:%06.3f (exact, measured where the loop starts)".format((it / 60).toInt(), it % 60))
            }
            if (drive.fixLost) Text("GPS fix lost", color = MaterialTheme.colorScheme.error)
        }
        PACES.forEach { p ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable {
                        pace = p
                        Emulator.drive.setPace(p)
                    },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = pace == p, onClick = null)
                Text(p.label, Modifier.padding(start = 8.dp))
            }
        }

        HorizontalDivider()

        // Tracks.
        message?.let { Text(it) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(enabled = !busy, onClick = {
                scope.launch {
                    request {
                        val text = withContext(Dispatchers.IO) { context.assets.open(BUILT_IN_ASSET).use { it.readBytes().decodeToString() } }
                        val c = Overpass.parse(text).first()
                        useCandidate(c, c.name ?: "Laguna Seca")
                    }
                }
            }) { Text("Laguna Seca") }
            OutlinedButton(onClick = {
                val center = lastPlace?.center ?: DEFAULT_OVAL_CENTER
                Emulator.drive.setTrack("Test oval", TrackLoop.testOval(center))
                message = "A 1.2 km oval centered on %.5f, %.5f.".format(center.lat, center.lon)
            }) { Text("Test oval") }
        }
        Text("Or find a track in OpenStreetMap:")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = search,
                onValueChange = { search = it },
                label = { Text("Place or track name") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            OutlinedButton(enabled = !busy && search.isNotBlank(), onClick = {
                scope.launch {
                    request {
                        places = withContext(Dispatchers.IO) { Emulator.osm.searchPlaces(search.trim()) }
                        candidates = emptyList()
                        if (places.isEmpty()) message = "No places found."
                    }
                }
            }) { Text("Go") }
        }
        if (busy) Text("Searching...")
        places.forEach { p ->
            Text(
                p.name,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        lastPlace = p
                        places = emptyList()
                        scope.launch {
                            request {
                                candidates = withContext(Dispatchers.IO) { Emulator.osm.findTracks(searchArea(p)) }
                                if (candidates.isEmpty()) message = "No tracks are mapped there. The test oval will be centered on it."
                            }
                        }
                    }
                    .padding(vertical = 8.dp),
            )
        }
        candidates.forEach { c ->
            val name = c.name ?: "Unnamed track"
            Text(
                "$name: ${c.kind}, %.1f km".format(c.lengthM / 1000),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { useCandidate(c, name) }
                    .padding(vertical = 8.dp),
            )
        }
        Text(
            "Track data © OpenStreetMap contributors, ODbL.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

/** The place's own bounds if small enough to search, otherwise about 4 km around its center. */
private fun searchArea(p: Place): Bounds {
    val b = p.bounds
    if (b != null && b.diagonalM <= OsmClient.MAX_SEARCH_DIAGONAL_M) return b
    val dLat = 0.018
    val dLon = dLat / kotlin.math.cos(Math.toRadians(p.center.lat))
    return Bounds(p.center.lat - dLat, p.center.lon - dLon, p.center.lat + dLat, p.center.lon + dLon)
}
