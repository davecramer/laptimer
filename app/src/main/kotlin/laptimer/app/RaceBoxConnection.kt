package laptimer.app

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.SystemClock
import laptimer.core.RaceBox
import java.util.UUID

/**
 * One Bluetooth LE connection to a RaceBox.
 *
 * Every GATT callback runs on [handler]'s thread, and so does every method
 * here, so the connection state needs no locking. After a disconnect the
 * owner may call [connect] again; each call opens a fresh GATT client.
 *
 * The caller must hold BLUETOOTH_CONNECT before creating this object.
 */
@SuppressLint("MissingPermission")
class RaceBoxConnection(
    private val context: Context,
    private val device: BluetoothDevice,
    private val handler: Handler,
    private val listener: Listener,
) {
    interface Listener {
        /** Notifications are enabled and data will start arriving. */
        fun onStreaming()

        /** One notification. [timeNanos] is SystemClock.elapsedRealtimeNanos(). */
        fun onBytes(timeNanos: Long, data: ByteArray)

        /** The link dropped or setup failed. Not called after [close]. */
        fun onDisconnected(reason: String)
    }

    private var gatt: BluetoothGatt? = null
    private var closed = false

    /** Reason for a disconnect this object started, reported in [Listener.onDisconnected]. */
    private var failure: String? = null

    fun connect() = handler.post {
        if (closed || gatt != null) return@post
        failure = null
        // Deprecated in API 37, which adds connectGatt(settings, executor,
        // callback). The app supports API 31 and up, where only this form
        // exists, and it still works on 37.
        @Suppress("DEPRECATION")
        gatt = device.connectGatt(
            context, false, callback,
            BluetoothDevice.TRANSPORT_LE, BluetoothDevice.PHY_LE_1M_MASK, handler,
        )
    }

    fun close() = handler.post {
        closed = true
        gatt?.disconnect()
        gatt?.close()
        gatt = null
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                // A shorter connection interval delivers 25 Hz data with less
                // bunching. A larger MTU lets one notification carry a whole
                // 88-byte frame instead of five 20-byte pieces.
                g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                if (!g.requestMtu(DESIRED_MTU)) g.discoverServices()
                return
            }
            g.close()
            if (gatt === g) gatt = null
            if (!closed) listener.onDisconnected(failure ?: "connection state $newState, status $status")
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            // A refused MTU still leaves a working link at the default size.
            g.discoverServices()
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val tx = g.getService(SERVICE)?.getCharacteristic(TX)
            val cccd = tx?.getDescriptor(CLIENT_CONFIG)
            if (status != BluetoothGatt.GATT_SUCCESS || tx == null || cccd == null) {
                failure = "RaceBox service not found (status $status)"
                g.disconnect()
                return
            }
            g.setCharacteristicNotification(tx, true)
            writeDescriptor(g, cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                listener.onStreaming()
            } else {
                failure = "enabling notifications failed (status $status)"
                g.disconnect()
            }
        }

        // Android 13 and later call this version.
        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            listener.onBytes(SystemClock.elapsedRealtimeNanos(), value)
        }

        // Android 12 calls this version. The array belongs to the
        // characteristic and is overwritten by the next notification, so it
        // is copied.
        @Deprecated("Replaced in API 33 by the overload that passes the value")
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return
            @Suppress("DEPRECATION")
            val value = characteristic.value ?: return
            listener.onBytes(SystemClock.elapsedRealtimeNanos(), value.copyOf())
        }
    }

    @Suppress("DEPRECATION")
    private fun writeDescriptor(g: BluetoothGatt, d: BluetoothGattDescriptor, value: ByteArray) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeDescriptor(d, value)
        } else {
            d.value = value
            g.writeDescriptor(d)
        }
    }

    companion object {
        private const val DESIRED_MTU = 247
        private val SERVICE: UUID = UUID.fromString(RaceBox.SERVICE_UUID)
        private val TX: UUID = UUID.fromString(RaceBox.TX_CHARACTERISTIC)

        /** Standard Client Characteristic Configuration descriptor. */
        private val CLIENT_CONFIG: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
}
