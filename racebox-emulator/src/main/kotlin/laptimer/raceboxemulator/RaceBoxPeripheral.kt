package laptimer.raceboxemulator

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelUuid
import android.os.SystemClock
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import laptimer.core.Elm327Emulator
import laptimer.core.EngineState
import laptimer.core.GpsFix
import laptimer.core.RaceBox
import java.util.UUID

/** One connected phone, as the screen shows it. */
data class ClientInfo(
    val address: String,
    val mtu: Int,
    val subscribed: Boolean,
    val framesSent: Long,
    val framesDropped: Long,
    /** Subscribed to the OBD adapter's replies. */
    val obdSubscribed: Boolean = false,
    val obdCommands: Long = 0,
)

data class PeripheralStatus(
    /** The user started it and hasn't stopped it. It may still be waiting for Bluetooth. */
    val enabled: Boolean = false,
    val running: Boolean = false,
    val advertising: Boolean = false,
    val error: String? = null,
    val clients: List<ClientInfo> = emptyList(),
    /** Commands the lap timer wrote to the RX characteristic. The emulator accepts and ignores them. */
    val commandsReceived: Long = 0,
)

/**
 * Acts as a RaceBox over Bluetooth LE: the Nordic UART service, with data
 * frames sent as notifications on TX every 40 ms (25 Hz), as the real
 * device does.
 *
 * It also acts as a BLE OBD adapter (ELM327) on service FFF0, the layout
 * most BLE adapters use: commands written to FFF2, replies notified on
 * FFF1. Each phone gets its own emulated adapter, with engine readings
 * from [engineAt].
 *
 * All Bluetooth callbacks are moved onto one thread, so the state here
 * needs no locking. The caller must hold BLUETOOTH_ADVERTISE and
 * BLUETOOTH_CONNECT.
 */
@SuppressLint("MissingPermission")
class RaceBoxPeripheral(
    private val context: Context,
    private val fixAt: (unixMs: Long) -> GpsFix,
    private val engineAt: () -> EngineState?,
    /** False makes the emulated car answer as with the ignition off. */
    private val ignitionOn: () -> Boolean,
) {
    private val thread = HandlerThread("racebox-peripheral").apply { start() }
    private val handler = Handler(thread.looper)
    private val manager = context.getSystemService(BluetoothManager::class.java)

    private val _status = MutableStateFlow(PeripheralStatus())
    val status: StateFlow<PeripheralStatus> = _status

    private var server: BluetoothGattServer? = null
    private var tx: BluetoothGattCharacteristic? = null
    private var obdService: BluetoothGattService? = null
    private var obdNotify: BluetoothGattCharacteristic? = null
    private var running = false

    /** True from start() until stop(), including while Bluetooth is off. */
    private var enabled = false
    private var advertising = false
    private var commands = 0L
    private var lastFrameMs = 0L

    private class Client(val device: BluetoothDevice) {
        /** 23 until the phone asks for more. Each notification carries MTU - 3 bytes. */
        var mtu = 23
        var subscribed = false
        var obdSubscribed = false
        var obdCommands = 0L
        val obdInput = StringBuilder()
        lateinit var elm: Elm327Emulator

        /** Pieces waiting to be notified, each with the characteristic it goes on. */
        val queue = ArrayDeque<Pair<BluetoothGattCharacteristic, ByteArray>>()
        var inFlight = false
        var inFlightSinceMs = 0L
        var framesSent = 0L
        var framesDropped = 0L
    }

    private val clients = LinkedHashMap<String, Client>()

    /**
     * Turning Bluetooth off closes the GATT server and stops advertising
     * without telling the app, which then looked as if it was still
     * advertising. Watch for it: shut down cleanly when Bluetooth goes off,
     * and start again when it comes back if the user hadn't stopped.
     */
    private val bluetoothState = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
            handler.post { onBluetoothState(state) }
        }
    }

    init {
        // System broadcasts still reach a receiver that isn't exported.
        ContextCompat.registerReceiver(
            context, bluetoothState, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    private fun onBluetoothState(state: Int) {
        when (state) {
            BluetoothAdapter.STATE_TURNING_OFF, BluetoothAdapter.STATE_OFF -> {
                if (running) stopNow()
                if (enabled) publish(error = "Bluetooth is off. Advertising starts again when it's back on.")
            }
            BluetoothAdapter.STATE_ON -> if (enabled && !running) startNow()
        }
    }

    fun start() = handler.post {
        enabled = true
        startNow()
    }

    private fun startNow() {
        if (running) return
        val adapter = manager.adapter
        if (adapter == null || !adapter.isEnabled) {
            publish(error = "Bluetooth is off. Advertising starts when it's on.")
            return
        }
        val s = manager.openGattServer(context, callback)
        if (s == null) {
            publish(error = "Could not open a Bluetooth GATT server.")
            return
        }
        val service = BluetoothGattService(SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        val txc = BluetoothGattCharacteristic(TX, BluetoothGattCharacteristic.PROPERTY_NOTIFY, 0)
        txc.addDescriptor(
            BluetoothGattDescriptor(
                CLIENT_CONFIG,
                BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE,
            ),
        )
        val rxc = BluetoothGattCharacteristic(
            RX,
            BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
            BluetoothGattCharacteristic.PERMISSION_WRITE,
        )
        service.addCharacteristic(txc)
        service.addCharacteristic(rxc)

        val obd = BluetoothGattService(OBD_SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        val obdNotifyChar = BluetoothGattCharacteristic(OBD_NOTIFY, BluetoothGattCharacteristic.PROPERTY_NOTIFY, 0)
        obdNotifyChar.addDescriptor(
            BluetoothGattDescriptor(CLIENT_CONFIG, BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE),
        )
        obd.addCharacteristic(obdNotifyChar)
        obd.addCharacteristic(
            BluetoothGattCharacteristic(
                OBD_WRITE,
                BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
                BluetoothGattCharacteristic.PERMISSION_WRITE,
            ),
        )
        obdService = obd
        obdNotify = obdNotifyChar
        server = s
        tx = txc
        running = true
        // The OBD service, then advertising, follow in onServiceAdded: the
        // server takes one service at a time.
        if (!s.addService(service)) {
            stopNow()
            publish(error = "Could not add the RaceBox service.")
            return
        }
        publish(error = null)
        scheduleTick()
    }

    fun stop() = handler.post {
        enabled = false
        stopNow()
        publish(error = null)
    }

    fun release() {
        context.unregisterReceiver(bluetoothState)
        handler.post {
            enabled = false
            stopNow()
            thread.quitSafely()
        }
    }

    private fun stopNow() {
        handler.removeCallbacks(tickRunnable)
        if (advertising) manager.adapter?.bluetoothLeAdvertiser?.stopAdvertising(advertiseCallback)
        advertising = false
        for (c in clients.values) server?.cancelConnection(c.device)
        clients.clear()
        server?.close()
        server = null
        tx = null
        obdService = null
        obdNotify = null
        running = false
        publish()
    }

    private fun startAdvertising(includeName: Boolean) {
        val advertiser = manager.adapter?.bluetoothLeAdvertiser
        if (advertiser == null) {
            publish(error = "This device can't advertise over Bluetooth LE.")
            return
        }
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(true)
            .build()
        // The 128-bit service UUID nearly fills the 31-byte advertisement,
        // so the name goes in the scan response.
        // Both services, so the lap timer finds this as a RaceBox and as an
        // OBD adapter. The 128-bit and 16-bit UUIDs together still fit.
        val data = AdvertiseData.Builder().addServiceUuid(ParcelUuid(SERVICE)).addServiceUuid(ParcelUuid(OBD_SERVICE)).build()
        val response = AdvertiseData.Builder().setIncludeDeviceName(includeName).build()
        nameIncluded = includeName
        advertiser.startAdvertising(settings, data, response, advertiseCallback)
    }

    private var nameIncluded = true

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            handler.post {
                advertising = true
                publish(error = null)
            }
        }

        override fun onStartFailure(errorCode: Int) {
            handler.post {
                if (errorCode == ADVERTISE_FAILED_DATA_TOO_LARGE && nameIncluded) {
                    // A long device name doesn't fit; the lap timer also finds the service without it.
                    startAdvertising(includeName = false)
                    return@post
                }
                advertising = false
                publish(error = "Advertising failed (code $errorCode).")
            }
        }
    }

    private val callback = object : BluetoothGattServerCallback() {
        override fun onServiceAdded(status: Int, service: BluetoothGattService) {
            handler.post {
                if (!running) return@post
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    publish(error = "Adding the ${service.uuid} service failed (status $status).")
                    return@post
                }
                val obd = obdService
                if (service.uuid == SERVICE && obd != null) {
                    if (server?.addService(obd) != true) publish(error = "Could not add the OBD service.")
                } else {
                    startAdvertising(includeName = true)
                }
            }
        }

        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            handler.post {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    clients[device.address] = Client(device).apply { elm = Elm327Emulator(engineAt) }
                } else {
                    clients.remove(device.address)
                }
                publish()
            }
        }

        override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
            handler.post {
                clients[device.address]?.mtu = mtu
                publish()
            }
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?,
        ) {
            // Copy before switching threads; the stack may reuse the array.
            val v = value?.copyOf()
            handler.post {
                val ok = descriptor.uuid == CLIENT_CONFIG
                val on = v != null && v.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                val c = clients[device.address]
                if (ok && c != null) {
                    if (descriptor.characteristic.uuid == OBD_NOTIFY) c.obdSubscribed = on else c.subscribed = on
                }
                if (responseNeeded) {
                    val status = if (ok) BluetoothGatt.GATT_SUCCESS else BluetoothGatt.GATT_FAILURE
                    server?.sendResponse(device, requestId, status, 0, null)
                }
                publish()
            }
        }

        override fun onDescriptorReadRequest(
            device: BluetoothDevice,
            requestId: Int,
            offset: Int,
            descriptor: BluetoothGattDescriptor,
        ) {
            handler.post {
                val c = clients[device.address]
                val on = if (descriptor.characteristic.uuid == OBD_NOTIFY) c?.obdSubscribed == true else c?.subscribed == true
                val value = if (on) BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE else BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
                server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, value)
            }
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?,
        ) {
            val v = value?.copyOf()
            handler.post {
                if (characteristic.uuid == RX) commands++
                if (responseNeeded) server?.sendResponse(device, requestId, GATT_SUCCESS, 0, null)
                if (characteristic.uuid == OBD_WRITE && v != null) clients[device.address]?.let { onObdBytes(it, v) }
                publish()
            }
        }

        override fun onNotificationSent(device: BluetoothDevice, status: Int) {
            handler.post {
                val c = clients[device.address] ?: return@post
                c.inFlight = false
                pump(c)
            }
        }
    }

    /** Commands end in a carriage return; each gets its reply as notifications. */
    private fun onObdBytes(c: Client, bytes: ByteArray) {
        for (b in bytes) {
            val ch = (b.toInt() and 0xFF).toChar()
            if (ch == '\r') {
                val command = c.obdInput.toString()
                c.obdInput.setLength(0)
                if (command.isBlank()) continue
                c.obdCommands++
                c.elm.carOn = ignitionOn()
                val characteristic = obdNotify ?: return
                enqueue(c, characteristic, c.elm.reply(command).toByteArray(Charsets.US_ASCII))
            } else if (c.obdInput.length < 64) {
                c.obdInput.append(ch)
            }
        }
    }

    private val tickRunnable = Runnable { tick() }

    /** Runs just after the next 40 ms boundary of the wall clock, as a GPS receiver's fixes are aligned. */
    private fun scheduleTick() {
        val now = System.currentTimeMillis()
        val delay = FRAME_MS - Math.floorMod(now, FRAME_MS) + 1
        handler.postAtTime(tickRunnable, SystemClock.uptimeMillis() + delay)
    }

    private var lastPublishMs = 0L

    private fun tick() {
        if (!running) return
        val now = System.currentTimeMillis()
        val frameMs = now - Math.floorMod(now, FRAME_MS)
        if (frameMs != lastFrameMs) {
            lastFrameMs = frameMs
            val subscribed = clients.values.filter { it.subscribed }
            val characteristic = tx
            if (subscribed.isNotEmpty() && characteristic != null) {
                val frame = RaceBox.encodeFrame(fixAt(frameMs))
                for (c in subscribed) {
                    enqueue(c, characteristic, frame)
                    c.framesSent++
                }
            }
        }
        // The screen doesn't need 25 updates a second.
        if (now - lastPublishMs >= 500) publish()
        scheduleTick()
    }

    private fun enqueue(c: Client, characteristic: BluetoothGattCharacteristic, frame: ByteArray) {
        // A phone that stops reading would make the queue grow without
        // limit. Drop what's waiting instead, as a real device's buffer would.
        if (c.queue.size > MAX_QUEUED_PIECES) {
            c.queue.clear()
            c.framesDropped++
        }
        val size = (c.mtu - 3).coerceAtLeast(20)
        var off = 0
        while (off < frame.size) {
            val end = minOf(frame.size, off + size)
            c.queue.addLast(characteristic to frame.copyOfRange(off, end))
            off = end
        }
        pump(c)
    }

    /** Sends the next piece unless one is still on its way. Android allows one outstanding notification per device. */
    @Suppress("DEPRECATION")
    private fun pump(c: Client) {
        val s = server ?: return
        // If the stack never confirms a notification, don't stall forever.
        if (c.inFlight && SystemClock.uptimeMillis() - c.inFlightSinceMs < STALL_MS) return
        val (characteristic, piece) = c.queue.firstOrNull() ?: return
        val sent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            s.notifyCharacteristicChanged(c.device, characteristic, false, piece) == BluetoothStatusCodes.SUCCESS
        } else {
            characteristic.value = piece
            s.notifyCharacteristicChanged(c.device, characteristic, false)
        }
        // If the stack is busy, the piece stays queued and the next tick tries again.
        if (sent) {
            c.queue.removeFirst()
            c.inFlight = true
            c.inFlightSinceMs = SystemClock.uptimeMillis()
        }
    }

    private fun publish(error: String? = _status.value.error) {
        lastPublishMs = System.currentTimeMillis()
        _status.value = PeripheralStatus(
            enabled = enabled,
            running = running,
            advertising = advertising,
            error = error,
            clients = clients.values.map {
                ClientInfo(it.device.address, it.mtu, it.subscribed, it.framesSent, it.framesDropped, it.obdSubscribed, it.obdCommands)
            },
            commandsReceived = commands,
        )
    }

    companion object {
        private const val FRAME_MS = 40L
        private const val STALL_MS = 1_000L

        /** About two seconds of frames at the default MTU. */
        private const val MAX_QUEUED_PIECES = 250

        private val SERVICE: UUID = UUID.fromString(RaceBox.SERVICE_UUID)
        private val TX: UUID = UUID.fromString(RaceBox.TX_CHARACTERISTIC)
        private val RX: UUID = UUID.fromString(RaceBox.RX_CHARACTERISTIC)
        private val CLIENT_CONFIG: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        private fun short(id: String): UUID = UUID.fromString("0000$id-0000-1000-8000-00805F9B34FB")
        private val OBD_SERVICE = short("FFF0")
        private val OBD_NOTIFY = short("FFF1")
        private val OBD_WRITE = short("FFF2")
        private const val GATT_SUCCESS = BluetoothGatt.GATT_SUCCESS
    }
}
