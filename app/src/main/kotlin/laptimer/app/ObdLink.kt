package laptimer.app

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.SystemClock
import laptimer.core.RaceBox
import java.io.IOException
import java.util.UUID

/** How an OBD adapter connects. OBDLink MX+ and LX use Classic; OBDLink CX uses BLE. */
enum class ObdTransport { CLASSIC, BLE }

/** The adapter the user picked. */
data class ObdTarget(val address: String, val name: String, val transport: ObdTransport)

/**
 * A byte pipe to an OBD adapter. Callbacks and calls all happen on the
 * owner's [Handler] thread. After [Listener.onDisconnected], make a new
 * link to try again.
 */
interface ObdLink {
    interface Listener {
        fun onObdConnected()

        /** [timeNanos] is SystemClock.elapsedRealtimeNanos() on arrival. */
        fun onObdBytes(timeNanos: Long, data: ByteArray)

        /** Not called after [close]. */
        fun onObdDisconnected(reason: String)
    }

    fun connect()
    fun write(text: String)
    fun close()

    companion object {
        fun create(context: Context, target: ObdTarget, handler: Handler, listener: Listener): ObdLink {
            val device = context.getSystemService(BluetoothManager::class.java).adapter.getRemoteDevice(target.address)
            return when (target.transport) {
                ObdTransport.CLASSIC -> ClassicObdLink(device, handler, listener)
                ObdTransport.BLE -> BleObdLink(context, device, handler, listener)
            }
        }
    }
}

/**
 * Bluetooth Classic serial (SPP), as on the OBDLink MX+ and LX and most
 * ELM327 clones. The adapter must be paired in Android's Bluetooth
 * settings first. Reads block, so they run on their own thread and are
 * handed to [handler].
 */
@SuppressLint("MissingPermission")
class ClassicObdLink(
    private val device: BluetoothDevice,
    private val handler: Handler,
    private val listener: ObdLink.Listener,
) : ObdLink {
    @Volatile private var closed = false
    private var socket: BluetoothSocket? = null

    override fun connect() {
        Thread({
            val s = try {
                device.createRfcommSocketToServiceRecord(SPP).also { it.connect() }
            } catch (e: IOException) {
                handler.post { if (!closed) listener.onObdDisconnected("could not connect: ${e.message}") }
                return@Thread
            }
            handler.post {
                if (closed) {
                    s.close()
                } else {
                    socket = s
                    listener.onObdConnected()
                }
            }
            val buffer = ByteArray(1024)
            try {
                val input = s.inputStream
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    val now = SystemClock.elapsedRealtimeNanos()
                    val data = buffer.copyOf(n)
                    handler.post { if (!closed) listener.onObdBytes(now, data) }
                }
                handler.post { if (!closed) listener.onObdDisconnected("the adapter closed the connection") }
            } catch (e: IOException) {
                handler.post { if (!closed) listener.onObdDisconnected("connection lost: ${e.message}") }
            }
        }, "obd-classic").start()
    }

    override fun write(text: String) {
        val s = socket ?: return
        try {
            s.outputStream.write(text.toByteArray(Charsets.US_ASCII))
        } catch (e: IOException) {
            close()
            listener.onObdDisconnected("write failed: ${e.message}")
        }
    }

    override fun close() {
        closed = true
        try {
            socket?.close()
        } catch (_: IOException) {
        }
        socket = null
    }

    private companion object {
        /** The standard Serial Port Profile UUID. */
        val SPP: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    }
}

/**
 * Bluetooth LE, as on the OBDLink CX and Vgate iCar Pro BLE. Adapters
 * expose a serial link as one characteristic to write commands to and one
 * that notifies replies, under a service that varies by maker.
 */
@SuppressLint("MissingPermission")
class BleObdLink(
    private val context: Context,
    private val device: BluetoothDevice,
    private val handler: Handler,
    private val listener: ObdLink.Listener,
) : ObdLink {
    private var gatt: BluetoothGatt? = null
    private var writeChar: BluetoothGattCharacteristic? = null
    private var closed = false
    private var failure: String? = null

    override fun connect() {
        if (closed || gatt != null) return
        @Suppress("DEPRECATION") // See RaceBoxConnection: the replacement needs API 37.
        gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE, BluetoothDevice.PHY_LE_1M_MASK, handler)
    }

    @Suppress("DEPRECATION")
    override fun write(text: String) {
        val g = gatt ?: return
        val c = writeChar ?: return
        val bytes = text.toByteArray(Charsets.US_ASCII)
        val type = if (c.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) {
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        } else {
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeCharacteristic(c, bytes, type)
        } else {
            c.writeType = type
            c.value = bytes
            g.writeCharacteristic(c)
        }
    }

    override fun close() {
        closed = true
        gatt?.disconnect()
        gatt?.close()
        gatt = null
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                g.discoverServices()
                return
            }
            g.close()
            if (gatt === g) gatt = null
            if (!closed) listener.onObdDisconnected(failure ?: "connection state $newState, status $status")
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val pair = if (status == BluetoothGatt.GATT_SUCCESS) findSerial(g) else null
            if (pair == null) {
                failure = "no OBD serial service found on this device"
                g.disconnect()
                return
            }
            val (notify, write) = pair
            writeChar = write
            g.setCharacteristicNotification(notify, true)
            val cccd = notify.getDescriptor(CLIENT_CONFIG)
            if (cccd == null) {
                // Some adapters notify without the descriptor.
                listener.onObdConnected()
                return
            }
            val value = if (notify.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) {
                BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            } else {
                BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
            }
            @Suppress("DEPRECATION")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                g.writeDescriptor(cccd, value)
            } else {
                cccd.value = value
                g.writeDescriptor(cccd)
            }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                listener.onObdConnected()
            } else {
                failure = "enabling notifications failed (status $status)"
                g.disconnect()
            }
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            listener.onObdBytes(SystemClock.elapsedRealtimeNanos(), value)
        }

        @Deprecated("Replaced in API 33 by the overload that passes the value")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return
            @Suppress("DEPRECATION")
            val value = c.value ?: return
            listener.onObdBytes(SystemClock.elapsedRealtimeNanos(), value.copyOf())
        }
    }

    /**
     * The characteristic that notifies replies, and the one to write
     * commands to. Known layouts first, then any custom service that has
     * both kinds.
     */
    private fun findSerial(g: BluetoothGatt): Pair<BluetoothGattCharacteristic, BluetoothGattCharacteristic>? {
        for ((service, notify, write) in KNOWN) {
            val s = g.getService(service) ?: continue
            val n = s.getCharacteristic(notify) ?: continue
            val w = s.getCharacteristic(write) ?: continue
            return n to w
        }
        for (s in g.services) {
            if (s.uuid in IGNORED_SERVICES || isStandard(s.uuid)) continue
            val n = s.characteristics.firstOrNull { it.properties and (NOTIFY or INDICATE) != 0 } ?: continue
            val w = s.characteristics.firstOrNull { it.properties and (WRITE or WRITE_NO_RESPONSE) != 0 } ?: continue
            return n to w
        }
        return null
    }

    private companion object {
        const val NOTIFY = BluetoothGattCharacteristic.PROPERTY_NOTIFY
        const val INDICATE = BluetoothGattCharacteristic.PROPERTY_INDICATE
        const val WRITE = BluetoothGattCharacteristic.PROPERTY_WRITE
        const val WRITE_NO_RESPONSE = BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE

        fun short(id: String): UUID = UUID.fromString("0000$id-0000-1000-8000-00805F9B34FB")

        /** Service, notify, write. FFF0 is the most common, and is what the RaceBox emulator offers. */
        val KNOWN = listOf(
            Triple(short("FFF0"), short("FFF1"), short("FFF2")),
            Triple(short("FFE0"), short("FFE1"), short("FFE1")),
            Triple(short("18F0"), short("2AF0"), short("2AF1")),
        )

        /** The RaceBox's own serial service: the emulator app offers it next to the OBD one. */
        val IGNORED_SERVICES = setOf(UUID.fromString(RaceBox.SERVICE_UUID))

        /** Bluetooth SIG services such as Generic Access (1800) and Device Information (180A). */
        fun isStandard(u: UUID): Boolean {
            val s = u.toString().uppercase()
            return s.endsWith("-0000-1000-8000-00805F9B34FB") && s.substring(4, 6) == "18"
        }

        val CLIENT_CONFIG: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
}
