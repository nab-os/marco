package dev.nabos.marco.finder

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.ParcelUuid
import android.os.SystemClock
import android.util.Log
import dev.nabos.marco.core.Peer
import dev.nabos.marco.core.PeerStore
import dev.nabos.marco.core.Perms
import dev.nabos.marco.core.Protocol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.pow

enum class LinkPhase { SEARCHING, SEEN, CONNECTING, CONNECTED }

data class FinderState(
    val active: Boolean = false,
    val phase: LinkPhase = LinkPhase.SEARCHING,
    /** Filtered RSSI in dBm, null until the first sample. */
    val rssi: Double? = null,
    val rawRssi: Int? = null,
    /** 0 = far/none, 1 = right next to it. */
    val level: Float = 0f,
    /** +1 warmer, -1 colder, 0 steady. */
    val trend: Int = 0,
    /** No sample for a few seconds. */
    val stale: Boolean = true,
    val ringing: Boolean = false,
    val ringQueued: Boolean = false,
    val sound: Boolean = true,
    val autoRing: Boolean = false,
    val message: String? = null,
)

/**
 * Finder side: scans for one paired phone's rotating ID, connects over GATT to
 * poll RSSI ~4×/s, filters it, beeps faster as you get closer, and sends
 * authenticated ring commands (manually or automatically when close).
 *
 * All state lives on the main thread: Bluetooth callbacks hop onto [scope].
 */
@SuppressLint("MissingPermission")
class FinderEngine(private val context: Context, private val scope: CoroutineScope) {

    private val store = PeerStore.get(context)
    private val _state = MutableStateFlow(FinderState(sound = store.proximitySound, autoRing = store.autoRing))
    val state: StateFlow<FinderState> = _state.asStateFlow()

    private enum class Source { ADVERT, CONNECTION }

    /** The phone being searched for, while active. */
    private var target: Peer? = null

    private val filter = RssiFilter()
    private var source: Source? = null
    private val history = ArrayDeque<Pair<Long, Double>>()
    private var lastSampleMs = 0L

    private var link: GattLink? = null
    private var lastDevice: BluetoothDevice? = null
    private var nextConnectAt = 0L
    private var backoffMs = INITIAL_BACKOFF_MS

    private var acceptedIds: List<ByteArray> = emptyList()
    private var acceptedIdsWindow = Long.MIN_VALUE

    private var ringingUntil = 0L
    private var queuedOp: Byte? = null
    private var sending = false
    /** Auto-ring fires once per approach; re-armed when you move away again. */
    private var autoArmed = true
    private var lastAutoAttempt = 0L

    private var tone: ToneGenerator? = null
    private val jobs = mutableListOf<Job>()
    private var pollJob: Job? = null
    private var scanning = false

    private val serviceUuid = ParcelUuid(Protocol.SERVICE_UUID)

    private fun now() = SystemClock.elapsedRealtime()

    // --- lifecycle ----------------------------------------------------------------

    fun start(peer: Peer) {
        if (_state.value.active) {
            if (target?.id == peer.id) return
            stop()
        }
        if (!Perms.hasBle(context)) return setMessage("Bluetooth permissions missing")
        val scanner = context.getSystemService(BluetoothManager::class.java)?.adapter
            ?.takeIf { it.isEnabled }?.bluetoothLeScanner
            ?: return setMessage("Turn Bluetooth on")

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setReportDelay(0)
            .build()
        // Unfiltered on purpose: some chipsets mishandle hardware filters on
        // 128-bit service data, and we only scan while the screen is on.
        target = peer
        acceptedIdsWindow = Long.MIN_VALUE
        scanner.startScan(null, settings, scanCallback)
        scanning = true

        nextConnectAt = 0
        backoffMs = INITIAL_BACKOFF_MS
        autoArmed = true
        tone = runCatching { ToneGenerator(AudioManager.STREAM_MUSIC, 85) }.getOrNull()
        _state.update { it.copy(active = true, phase = LinkPhase.SEARCHING, message = null) }
        jobs += scope.launch { ticker() }
        jobs += scope.launch { beeper() }
    }

    fun stop() {
        if (scanning) {
            runCatching {
                context.getSystemService(BluetoothManager::class.java)?.adapter?.bluetoothLeScanner?.stopScan(scanCallback)
            }
            scanning = false
        }
        jobs.forEach { it.cancel() }
        jobs.clear()
        pollJob?.cancel()
        link?.close()
        link = null
        target = null
        lastDevice = null
        ringingUntil = 0
        sending = false
        tone?.release()
        tone = null
        filter.reset()
        history.clear()
        source = null
        queuedOp = null
        _state.update { FinderState(sound = it.sound, autoRing = it.autoRing) }
    }

    // --- user actions ---------------------------------------------------------------

    fun ring() {
        autoArmed = true
        send(Protocol.OP_RING_START)
    }

    fun stopRinging() {
        autoArmed = false // don't immediately auto-ring again while still close
        queuedOp = null
        ringingUntil = 0
        _state.update { it.copy(ringing = false, ringQueued = false) }
        send(Protocol.OP_RING_STOP)
    }

    fun setSound(on: Boolean) {
        store.proximitySound = on
        _state.update { it.copy(sound = on) }
    }

    fun setAutoRing(on: Boolean) {
        store.autoRing = on
        autoArmed = true
        _state.update { it.copy(autoRing = on) }
    }

    // --- scanning -------------------------------------------------------------------

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) = handle(result)

        override fun onBatchScanResults(results: MutableList<ScanResult>) = results.forEach(::handle)

        override fun onScanFailed(errorCode: Int) {
            Log.w(TAG, "Scan failed: $errorCode")
            scope.launch {
                scanning = false
                setMessage(
                    if (errorCode == ScanCallback.SCAN_FAILED_APPLICATION_REGISTRATION_FAILED)
                        "Bluetooth scan was refused. Leave this screen and come back in 30 s."
                    else "Bluetooth scan failed ($errorCode)"
                )
            }
        }

        private fun handle(r: ScanResult) {
            val data = r.scanRecord?.getServiceData(serviceUuid) ?: return
            val device = r.device
            val rssi = r.rssi
            scope.launch { onAdvert(device, data, rssi) }
        }
    }

    private fun onAdvert(device: BluetoothDevice, data: ByteArray, rssi: Int) {
        if (!_state.value.active) return
        if (!matchesPeer(data)) return
        lastDevice = device // the advertiser's random address rotates; always use the latest
        if (link?.ready != true) feed(rssi, Source.ADVERT)
        if (_state.value.phase == LinkPhase.SEARCHING) _state.update { it.copy(phase = LinkPhase.SEEN) }
        maybeConnect()
    }

    private fun matchesPeer(data: ByteArray): Boolean {
        val key = target?.key ?: return false
        if (data.size < Protocol.ADV_ID_LEN) return false
        val w = Protocol.window()
        if (w != acceptedIdsWindow) {
            acceptedIds = Protocol.acceptedAdvIds(key)
            acceptedIdsWindow = w
        }
        val id = data.copyOf(Protocol.ADV_ID_LEN)
        return acceptedIds.any { it.contentEquals(id) }
    }

    // --- connection -----------------------------------------------------------------

    private fun maybeConnect() {
        val device = lastDevice ?: return
        if (link != null || now() < nextConnectAt) return
        val l = GattLink(
            context, device,
            onReady = { ready -> scope.launch { onLinkReady(ready) } },
            onClosed = { closed, status -> scope.launch { onLinkClosed(closed, status) } },
        )
        link = l
        _state.update { it.copy(phase = LinkPhase.CONNECTING) }
        l.connect()
        jobs.removeAll { it.isCompleted }
        jobs += scope.launch {
            delay(CONNECT_TIMEOUT_MS)
            if (link === l && !l.ready) l.close(STATUS_TIMEOUT)
        }
    }

    private fun onLinkReady(l: GattLink) {
        if (link !== l) return
        backoffMs = INITIAL_BACKOFF_MS
        _state.update { it.copy(phase = LinkPhase.CONNECTED, message = null) }
        pollJob?.cancel()
        pollJob = scope.launch {
            while (isActive && link === l && l.ready) {
                l.readRssi()?.let { feed(it, Source.CONNECTION) }
                delay(RSSI_POLL_MS)
            }
        }
        queuedOp?.let { op ->
            queuedOp = null
            send(op)
        }
    }

    private fun onLinkClosed(l: GattLink, status: Int) {
        if (link !== l) return
        link = null
        pollJob?.cancel()
        nextConnectAt = now() + backoffMs
        backoffMs = (backoffMs * 2).coerceAtMost(MAX_BACKOFF_MS)
        val recent = now() - lastSampleMs < STALE_MS
        _state.update {
            it.copy(
                phase = if (recent) LinkPhase.SEEN else LinkPhase.SEARCHING,
                message = if (status == GattLink.STATUS_NO_SERVICE)
                    "${target?.name ?: "That phone"} isn't findable. Open Marco on it and turn Findable on." else it.message,
            )
        }
    }

    // --- commands -------------------------------------------------------------------

    private fun send(op: Byte) {
        val peer = target ?: return
        val l = link
        if (l == null || !l.ready || sending) {
            if (op == Protocol.OP_RING_START) {
                queuedOp = op
                _state.update { it.copy(ringQueued = true) }
            }
            return
        }
        sending = true
        scope.launch {
            val ok = l.sendCommand(peer.key, op)
            if (target !== peer) return@launch // search stopped or switched meanwhile
            sending = false
            if (ok) {
                ringingUntil = if (op == Protocol.OP_RING_START) now() + Protocol.RING_DURATION_MS else 0
                _state.update { it.copy(ringing = ringingUntil > now(), ringQueued = false, message = null) }
            } else {
                if (op == Protocol.OP_RING_START) queuedOp = op // retry on next connection
                _state.update {
                    it.copy(
                        ringQueued = op == Protocol.OP_RING_START,
                        message = "Command didn't go through, retrying. If this keeps happening, pair again.",
                    )
                }
            }
        }
    }

    // --- signal processing ----------------------------------------------------------

    private fun feed(rssi: Int, src: Source) {
        // Advert and connection RSSI can use different TX power; don't blend them.
        if (src != source) {
            filter.reset()
            history.clear()
            source = src
        }
        val t = now()
        val v = filter.update(rssi, t)
        lastSampleMs = t
        history.addLast(t to v)
        while (history.size > 2 && t - history.first().first > TREND_WINDOW_MS) history.removeFirst()
        val (t0, v0) = history.first()
        val trend = when {
            t - t0 < TREND_WINDOW_MS / 2 -> 0
            v - v0 > TREND_DB -> 1
            v0 - v > TREND_DB -> -1
            else -> 0
        }
        val level = ((v - RSSI_FAR) / (RSSI_NEAR - RSSI_FAR)).toFloat().coerceIn(0f, 1f)
        _state.update { it.copy(rssi = v, rawRssi = rssi, level = level, trend = trend, stale = false) }
        checkAutoRing(v)
    }

    private fun checkAutoRing(v: Double) {
        if (!_state.value.autoRing) return
        if (v < AUTO_RING_RSSI - AUTO_RING_HYSTERESIS) autoArmed = true
        val renewDue = now() >= ringingUntil - RENEW_BEFORE_MS
        val t = now()
        if (autoArmed && v >= AUTO_RING_RSSI && renewDue && link?.ready == true && !sending &&
            t - lastAutoAttempt > AUTO_RETRY_MS
        ) {
            lastAutoAttempt = t
            send(Protocol.OP_RING_START)
        }
    }

    private suspend fun ticker() {
        while (true) {
            val t = now()
            val stale = t - lastSampleMs > STALE_MS
            val ringing = t < ringingUntil
            _state.update {
                it.copy(
                    stale = stale,
                    ringing = ringing,
                    phase = if (stale && it.phase == LinkPhase.SEEN) LinkPhase.SEARCHING else it.phase,
                )
            }
            delay(500)
        }
    }

    /** Geiger-counter beeps: faster as the filtered signal gets stronger. */
    private suspend fun beeper() {
        while (true) {
            val s = _state.value
            // Stay quiet while the other phone rings so you can hear it.
            if (s.sound && !s.stale && !s.ringing) {
                tone?.startTone(ToneGenerator.TONE_PROP_BEEP, 40)
                val closeness = s.level.toDouble()
                val interval = BEEP_MIN_MS + (BEEP_MAX_MS - BEEP_MIN_MS) * (1 - closeness).pow(2)
                delay(interval.toLong())
            } else {
                delay(250)
            }
        }
    }

    private fun setMessage(m: String) = _state.update { it.copy(message = m) }

    companion object {
        private const val TAG = "Marco.Finder"

        private const val RSSI_POLL_MS = 250L
        private const val STALE_MS = 4_000L
        private const val CONNECT_TIMEOUT_MS = 10_000L
        private const val INITIAL_BACKOFF_MS = 1_000L
        private const val MAX_BACKOFF_MS = 8_000L
        private const val STATUS_TIMEOUT = -3

        /** Mapping of filtered RSSI to the 0..1 "heat" level. */
        const val RSSI_FAR = -95.0
        const val RSSI_NEAR = -45.0

        private const val TREND_WINDOW_MS = 2_000L
        private const val TREND_DB = 1.5

        /** Roughly "same room, a few metres away" for two phones; tune to taste. */
        const val AUTO_RING_RSSI = -70.0
        private const val AUTO_RING_HYSTERESIS = 8.0
        private const val RENEW_BEFORE_MS = 5_000L
        private const val AUTO_RETRY_MS = 3_000L

        private const val BEEP_MIN_MS = 90.0
        private const val BEEP_MAX_MS = 1_500.0
    }
}
