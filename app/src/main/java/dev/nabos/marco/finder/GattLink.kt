package dev.nabos.marco.finder

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.os.Build
import android.util.Log
import dev.nabos.marco.core.Protocol
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * One GATT connection to the lost phone. Android allows only one outstanding GATT
 * operation per connection, so every operation goes through [op], which holds a
 * mutex and waits for the matching callback (or a timeout).
 *
 * [onReady] / [onClosed] are invoked on a Binder thread.
 */
@SuppressLint("MissingPermission") // Finder only runs once BLE permissions are granted.
class GattLink(
    private val context: Context,
    val device: BluetoothDevice,
    private val onReady: (GattLink) -> Unit,
    private val onClosed: (GattLink, status: Int) -> Unit,
) {
    private var gatt: BluetoothGatt? = null
    private var nonceChar: BluetoothGattCharacteristic? = null
    private var cmdChar: BluetoothGattCharacteristic? = null
    private val mutex = Mutex()
    @Volatile private var pending: CompletableDeferred<Any?>? = null
    @Volatile private var closed = false
    @Volatile var ready = false
        private set

    fun connect() {
        gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
        if (gatt == null) close(STATUS_LOCAL_FAILURE)
    }

    /** Tear down without waiting for the stack. Idempotent. */
    fun close(status: Int = 0) {
        if (closed) return
        closed = true
        ready = false
        pending?.complete(null)
        gatt?.let { g ->
            runCatching { g.disconnect() }
            runCatching { g.close() }
        }
        gatt = null
        onClosed(this, status)
    }

    suspend fun readRssi(): Int? = op { it.readRemoteRssi() } as Int?

    /** Authenticated command: fetch a fresh nonce, then write opcode + MAC. */
    suspend fun sendCommand(key: ByteArray, opcode: Byte): Boolean {
        val nc = nonceChar ?: return false
        val cc = cmdChar ?: return false
        val nonce = op { it.readCharacteristic(nc) } as ByteArray? ?: return false
        if (nonce.size != Protocol.NONCE_LEN) return false
        val cmd = Protocol.buildCommand(key, nonce, opcode)
        return op { g -> write(g, cc, cmd) } as Boolean? ?: false
    }

    private suspend fun op(start: (BluetoothGatt) -> Boolean): Any? = mutex.withLock {
        val g = gatt
        if (g == null || closed) return@withLock null
        val d = CompletableDeferred<Any?>()
        pending = d
        try {
            if (!start(g)) return@withLock null
            withTimeoutOrNull(OP_TIMEOUT_MS) { d.await() }
        } finally {
            pending = null
        }
    }

    @Suppress("DEPRECATION")
    private fun write(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeCharacteristic(c, value, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothStatusCodes.SUCCESS
        } else {
            c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            c.value = value
            g.writeCharacteristic(c)
        }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (closed) return
            if (status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED) {
                if (!g.discoverServices()) close(STATUS_LOCAL_FAILURE)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED || status != BluetoothGatt.GATT_SUCCESS) {
                // 133 (GATT_ERROR) is the classic "connect didn't work, try again".
                Log.d(TAG, "Disconnected, status=$status")
                close(status)
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (closed) return
            val svc = if (status == BluetoothGatt.GATT_SUCCESS) g.getService(Protocol.SERVICE_UUID) else null
            nonceChar = svc?.getCharacteristic(Protocol.NONCE_CHAR_UUID)
            cmdChar = svc?.getCharacteristic(Protocol.CMD_CHAR_UUID)
            if (nonceChar == null || cmdChar == null) {
                Log.w(TAG, "Marco service not found (status=$status)")
                close(STATUS_NO_SERVICE)
                return
            }
            // Shorter connection interval => fresher RSSI readings.
            g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
            ready = true
            onReady(this@GattLink)
        }

        override fun onReadRemoteRssi(g: BluetoothGatt, rssi: Int, status: Int) {
            pending?.complete(if (status == BluetoothGatt.GATT_SUCCESS) rssi else null)
        }

        // Android 13+ delivers the value here; we don't call super, so the
        // deprecated overload below only runs on Android 12.
        override fun onCharacteristicRead(
            g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray, status: Int,
        ) {
            pending?.complete(if (status == BluetoothGatt.GATT_SUCCESS) value else null)
        }

        @Deprecated("Android 12 path")
        @Suppress("DEPRECATION")
        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return
            pending?.complete(if (status == BluetoothGatt.GATT_SUCCESS) c.value else null)
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            pending?.complete(status == BluetoothGatt.GATT_SUCCESS)
        }
    }

    companion object {
        private const val TAG = "Marco.GattLink"
        private const val OP_TIMEOUT_MS = 3_000L
        const val STATUS_LOCAL_FAILURE = -1
        const val STATUS_NO_SERVICE = -2
    }
}
