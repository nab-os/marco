package dev.nabos.marco.findable

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import androidx.core.content.ContextCompat
import dev.nabos.marco.MainActivity
import dev.nabos.marco.R
import dev.nabos.marco.core.Peer
import dev.nabos.marco.core.PeerStore
import dev.nabos.marco.core.Perms
import dev.nabos.marco.core.Protocol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

data class FindableStatus(
    val running: Boolean = false,
    val advertising: Boolean = false,
    val ringing: Boolean = false,
    val connectedFinders: Int = 0,
    val error: String? = null,
)

/**
 * Keeps this phone findable by its paired phones:
 *  - advertises one rotating ID per paired phone (low duty cycle, ~1 advert/s each),
 *    since a legacy advert only has room for one ID,
 *  - runs a GATT server that accepts RING_START / RING_STOP authenticated with any
 *    paired key,
 *  - rings through [Ringer].
 */
@SuppressLint("MissingPermission") // Checked in start(); the service stops itself without them.
class FindableService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val main = Handler(Looper.getMainLooper())
    private lateinit var store: PeerStore
    private lateinit var ringer: Ringer
    private lateinit var notifications: NotificationManager
    private val btManager by lazy { getSystemService(BluetoothManager::class.java)!! }

    @Volatile private var peers: List<Peer> = emptyList() // also read on the GATT binder thread
    private var advertiser: BluetoothLeAdvertiser? = null
    /** One advertisement per paired phone, keyed by [Peer.id]. */
    private val adverts = mutableMapOf<String, AdvertiseCallback>()
    private val advertsStarted = mutableSetOf<String>()
    private val advertFailures = mutableMapOf<String, String>()
    private var gattServer: BluetoothGattServer? = null
    private val nonces = ConcurrentHashMap<String, ByteArray>()
    private val connected = ConcurrentHashMap.newKeySet<String>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        store = PeerStore.get(this)
        notifications = getSystemService(NotificationManager::class.java)!!
        createChannels()
        ringer = Ringer(this).apply {
            onStateChanged = { ringing ->
                _status.update { it.copy(ringing = ringing) }
                if (ringing) showRingingNotification() else notifications.cancel(NOTIF_RINGING)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP_RING -> {
                ringer.stop()
                return START_STICKY
            }
            ACTION_TEST_RING -> {
                ringingFor = null
                ringer.ring(5_000)
                return START_STICKY
            }
        }

        try {
            startForeground(NOTIF_ONGOING, ongoingNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } catch (e: Exception) {
            // Missing BLE permission, or started from the background where not allowed.
            Log.e(TAG, "startForeground failed", e)
            _status.value = FindableStatus(error = "Couldn't start: ${e.javaClass.simpleName}")
            stopSelf()
            return START_NOT_STICKY
        }

        if (!started) {
            started = true
            _status.update { it.copy(running = true) }
            ContextCompat.registerReceiver(
                this, btStateReceiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            scope.launch {
                store.peers.collect { list ->
                    peers = list
                    nonces.clear()
                    if (list.isEmpty()) stopSelf() else restartRadio()
                }
            }
        }
        return START_STICKY
    }

    private var started = false

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        ringer.stop()
        stopRadio()
        if (started) runCatching { unregisterReceiver(btStateReceiver) }
        scope.cancel()
        _status.value = FindableStatus()
        super.onDestroy()
    }

    // --- radio ---------------------------------------------------------------

    private fun restartRadio() {
        stopRadio()
        if (peers.isEmpty()) return
        if (!Perms.hasBle(this)) {
            _status.update { it.copy(advertising = false, error = "Bluetooth permissions missing") }
            return
        }
        val adapter = btManager.adapter
        if (adapter == null || !adapter.isEnabled) {
            _status.update { it.copy(advertising = false, error = "Bluetooth is off") }
            return
        }
        startGattServer()
        startAdvertising()
    }

    private fun stopRadio() {
        main.removeCallbacks(rotateId)
        stopAdvertising()
        advertiser = null
        gattServer?.let { server ->
            runCatching {
                for (addr in connected) {
                    btManager.adapter?.getRemoteDevice(addr)?.let { server.cancelConnection(it) }
                }
                server.close()
            }
        }
        gattServer = null
        connected.clear()
        nonces.clear()
        _status.update { it.copy(advertising = false, connectedFinders = 0) }
    }

    private fun startAdvertising() {
        val adv = btManager.adapter?.bluetoothLeAdvertiser
        if (adv == null) {
            _status.update { it.copy(error = "This phone can't advertise over BLE") }
            return
        }
        advertiser = adv
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_POWER) // ~1 Hz, cheap
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)  // stable RSSI, better range
            .setConnectable(true)
            .setTimeout(0)
            .build()
        val window = Protocol.window()
        for (p in peers) {
            val data = AdvertiseData.Builder()
                .setIncludeDeviceName(false)
                .setIncludeTxPowerLevel(false)
                .addServiceData(ParcelUuid(Protocol.SERVICE_UUID), Protocol.advId(p.key, window))
                .build()
            val callback = advertiseCallback(p)
            adverts[p.id] = callback
            adv.startAdvertising(settings, data, callback)
        }
        // Re-advertise with the new IDs when the window rolls over (+1 s margin).
        main.postDelayed(rotateId, Protocol.msUntilNextWindow() + 1_000)
    }

    private fun stopAdvertising() {
        for (callback in adverts.values) runCatching { advertiser?.stopAdvertising(callback) }
        adverts.clear()
        advertsStarted.clear()
        advertFailures.clear()
    }

    private val rotateId: Runnable = Runnable {
        if (peers.isEmpty()) return@Runnable
        stopAdvertising()
        startAdvertising()
    }

    private fun advertiseCallback(p: Peer) = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            if (adverts[p.id] !== this) return
            advertsStarted += p.id
            advertFailures -= p.id
            updateAdvertisingStatus()
        }

        override fun onStartFailure(errorCode: Int) {
            if (errorCode == AdvertiseCallback.ADVERTISE_FAILED_ALREADY_STARTED) return
            if (adverts[p.id] !== this) return
            adverts -= p.id
            val why = when (errorCode) {
                AdvertiseCallback.ADVERTISE_FAILED_DATA_TOO_LARGE -> "advert too large"
                AdvertiseCallback.ADVERTISE_FAILED_TOO_MANY_ADVERTISERS -> "too many paired phones for this Bluetooth chip"
                AdvertiseCallback.ADVERTISE_FAILED_FEATURE_UNSUPPORTED -> "not supported"
                else -> "error $errorCode"
            }
            Log.w(TAG, "Advertising for ${p.name} failed: $why")
            advertFailures[p.id] = why
            updateAdvertisingStatus()
            // Transient failures happen (e.g. right after Bluetooth turns on): retry.
            // Running out of advertisers won't fix itself, so don't churn on it.
            if (errorCode != AdvertiseCallback.ADVERTISE_FAILED_TOO_MANY_ADVERTISERS) {
                main.removeCallbacks(rotateId)
                main.postDelayed(rotateId, 10_000)
            }
        }
    }

    private fun updateAdvertisingStatus() {
        val failed = peers.filter { it.id in advertFailures }
        val error = when {
            failed.isEmpty() -> null
            failed.size == peers.size -> "Advertising failed (${advertFailures[failed[0].id]})"
            else -> "Not findable by ${failed.joinToString { it.name }} (${advertFailures[failed[0].id]})"
        }
        _status.update { it.copy(advertising = advertsStarted.isNotEmpty(), error = error) }
    }

    private fun startGattServer() {
        val server = btManager.openGattServer(this, gattCallback)
        if (server == null) {
            _status.update { it.copy(error = "Couldn't open GATT server") }
            return
        }
        val service = BluetoothGattService(Protocol.SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        service.addCharacteristic(
            BluetoothGattCharacteristic(
                Protocol.NONCE_CHAR_UUID,
                BluetoothGattCharacteristic.PROPERTY_READ,
                BluetoothGattCharacteristic.PERMISSION_READ,
            )
        )
        service.addCharacteristic(
            BluetoothGattCharacteristic(
                Protocol.CMD_CHAR_UUID,
                BluetoothGattCharacteristic.PROPERTY_WRITE,
                BluetoothGattCharacteristic.PERMISSION_WRITE,
            )
        )
        server.addService(service)
        gattServer = server
    }

    private val gattCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) connected.add(device.address)
            else {
                connected.remove(device.address)
                nonces.remove(device.address)
            }
            _status.update { it.copy(connectedFinders = connected.size) }
        }

        override fun onCharacteristicReadRequest(
            device: BluetoothDevice, requestId: Int, offset: Int, characteristic: BluetoothGattCharacteristic,
        ) {
            val server = gattServer ?: return
            if (characteristic.uuid != Protocol.NONCE_CHAR_UUID) {
                server.sendResponse(device, requestId, BluetoothGatt.GATT_READ_NOT_PERMITTED, offset, null)
                return
            }
            // A read at offset 0 issues a fresh single-use nonce; a continuation read
            // (offset > 0, only with tiny MTUs) returns the rest of the same one.
            val nonce = if (offset == 0) Protocol.randomBytes(Protocol.NONCE_LEN).also { nonces[device.address] = it }
            else nonces[device.address]
            if (nonce == null || offset > nonce.size) {
                server.sendResponse(device, requestId, BluetoothGatt.GATT_INVALID_OFFSET, offset, null)
                return
            }
            server.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, nonce.copyOfRange(offset, nonce.size))
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice, requestId: Int, characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray?,
        ) {
            val server = gattServer ?: return
            val ok = characteristic.uuid == Protocol.CMD_CHAR_UUID && !preparedWrite && offset == 0 && value != null
            val nonce = if (ok) nonces.remove(device.address) else null // single use, even on failure
            // The finder doesn't say who it is; whichever paired key verifies the MAC does.
            val (sender, opcode) = nonce?.let { n ->
                peers.firstNotNullOfOrNull { p -> Protocol.verifyCommand(p.key, n, value!!)?.let { p to it } }
            } ?: (null to null)

            if (responseNeeded) {
                val status = if (opcode != null) BluetoothGatt.GATT_SUCCESS else BluetoothGatt.GATT_WRITE_NOT_PERMITTED
                server.sendResponse(device, requestId, status, 0, null)
            }
            if (opcode == null) {
                Log.w(TAG, "Rejected command from ${device.address}")
                return
            }
            main.post {
                when (opcode) {
                    Protocol.OP_RING_START -> {
                        ringingFor = sender?.name
                        ringer.ring(Protocol.RING_DURATION_MS)
                    }
                    Protocol.OP_RING_STOP -> ringer.stop()
                }
            }
        }
    }

    private val btStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)) {
                BluetoothAdapter.STATE_ON -> restartRadio()
                BluetoothAdapter.STATE_TURNING_OFF, BluetoothAdapter.STATE_OFF -> {
                    stopRadio()
                    _status.update { it.copy(error = "Bluetooth is off") }
                }
            }
        }
    }

    // --- notifications -------------------------------------------------------

    private fun createChannels() {
        notifications.createNotificationChannel(
            NotificationChannel(CH_ONGOING, "Findable", NotificationManager.IMPORTANCE_MIN).apply {
                description = "Shown while this phone can be found by your paired phones"
                setShowBadge(false)
            }
        )
        notifications.createNotificationChannel(
            NotificationChannel(CH_RINGING, "Ringing", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Shown while a paired phone is making this one ring"
                setSound(null, null) // the Ringer makes the noise
            }
        )
    }

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE,
    )

    private fun ongoingNotification(): Notification =
        Notification.Builder(this, CH_ONGOING)
            .setSmallIcon(R.drawable.ic_marco)
            .setContentTitle("Findable")
            .setContentText("Your paired phones can ring this one")
            .setContentIntent(openAppIntent())
            .setOngoing(true)
            .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_DEFERRED)
            .build()

    /** Name of the paired phone that started the current ring, for the notification. */
    private var ringingFor: String? = null

    private fun showRingingNotification() {
        val stop = PendingIntent.getService(
            this, 1, Intent(this, FindableService::class.java).setAction(ACTION_STOP_RING),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val n = Notification.Builder(this, CH_RINGING)
            .setSmallIcon(R.drawable.ic_marco)
            .setContentTitle("Found me!")
            .setContentText(ringingFor?.let { "$it is looking for this phone. Tap Stop to silence." } ?: "Tap Stop to silence")
            .setCategory(Notification.CATEGORY_ALARM)
            .setContentIntent(openAppIntent())
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .setDeleteIntent(stop)
            .setOngoing(true)
            .build()
        runCatching { notifications.notify(NOTIF_RINGING, n) } // no-op if notifications denied
    }

    companion object {
        private const val TAG = "Marco.Findable"
        private const val CH_ONGOING = "findable"
        private const val CH_RINGING = "ringing"
        private const val NOTIF_ONGOING = 1
        private const val NOTIF_RINGING = 2
        const val ACTION_STOP_RING = "dev.nabos.marco.STOP_RING"
        const val ACTION_TEST_RING = "dev.nabos.marco.TEST_RING"

        private val _status = MutableStateFlow(FindableStatus())
        val status: StateFlow<FindableStatus> = _status.asStateFlow()

        /** Start if paired, wanted, and permitted. Safe to call repeatedly. */
        fun sync(context: Context) {
            val store = PeerStore.get(context)
            val want = store.peers.value.isNotEmpty() && store.findable.value && Perms.hasBle(context)
            if (want) {
                runCatching {
                    ContextCompat.startForegroundService(context, Intent(context, FindableService::class.java))
                }.onFailure { Log.e(TAG, "Couldn't start service", it) }
            } else {
                context.stopService(Intent(context, FindableService::class.java))
            }
        }

        fun stopRinging(context: Context) {
            if (!_status.value.running) return
            context.startService(Intent(context, FindableService::class.java).setAction(ACTION_STOP_RING))
        }

        /** Ring this phone for 5 s so the user can check it gets through silent mode. */
        fun testRing(context: Context) {
            if (!_status.value.running) return
            context.startService(Intent(context, FindableService::class.java).setAction(ACTION_TEST_RING))
        }
    }
}
