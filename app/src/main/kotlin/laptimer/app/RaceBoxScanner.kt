package laptimer.app

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import laptimer.core.RaceBox

data class FoundDevice(val name: String, val address: String, val rssi: Int)

/**
 * Scans for RaceBox devices for [SCAN_MILLIS], then stops on its own to
 * save battery. Callbacks run on the main thread.
 *
 * The caller must hold BLUETOOTH_SCAN.
 */
@SuppressLint("MissingPermission")
class RaceBoxScanner(
    context: Context,
    private val onFound: (FoundDevice) -> Unit,
    private val onStopped: () -> Unit,
) {
    private val scanner = context.getSystemService(BluetoothManager::class.java).adapter.bluetoothLeScanner
    private val main = Handler(Looper.getMainLooper())
    private var scanning = false

    private val callback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val record = result.scanRecord
            // Match on either the advertised service or the name, because not
            // every firmware puts the service UUID in its advertisement.
            val hasService = record?.serviceUuids?.contains(SERVICE) == true
            val name = record?.deviceName ?: result.device.name
            if (!hasService && name?.startsWith("RaceBox") != true) return
            // The name may not fit in the advertisement, as with the emulator.
            onFound(FoundDevice(name ?: "RaceBox (no name)", result.device.address, result.rssi))
        }

        override fun onScanFailed(errorCode: Int) {
            stop()
        }
    }

    fun start() {
        if (scanning || scanner == null) return
        scanning = true
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        scanner.startScan(null, settings, callback)
        main.postDelayed({ stop() }, SCAN_MILLIS)
    }

    fun stop() {
        if (!scanning) return
        scanning = false
        main.removeCallbacksAndMessages(null)
        scanner?.stopScan(callback)
        onStopped()
    }

    companion object {
        private const val SCAN_MILLIS = 15_000L
        private val SERVICE = ParcelUuid.fromString(RaceBox.SERVICE_UUID)
    }
}
