package dev.nabos.marco.finder

import android.content.Context
import android.util.Log
import dev.nabos.marco.vision.TrackPoint
import dev.nabos.marco.vision.TrackState
import dev.nabos.marco.vision.Tracking
import dev.nabos.marco.vision.Vernier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.Writer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class MapState(
    val running: Boolean = false,
    val track: TrackState = TrackState(),
    /** Where you are now and which way you face. */
    val here: TrackPoint? = null,
    val estimate: SignalMap.Estimate? = null,
    /** Readings placed on the map so far. */
    val readings: Int = 0,
)

/**
 * The live signal map on top of a [FinderEngine] search: tracks where you walk
 * with the cameras ([Tracking]), places each RSSI reading where you were when
 * it was measured, and keeps a [SignalMap] estimate of where the other phone is.
 *
 * Readings are also written to `files/maps/` (time, position, heading, RSSI),
 * to tune the map offline against real searches.
 */
class SignalMapSession(private val context: Context, private val scope: CoroutineScope, private val engine: FinderEngine) {

    private val map = SignalMap()
    private var tracking: Tracking? = null
    private val jobs = mutableListOf<Job>()
    /** Readings waiting for the tracking to reach their time (poses come a fraction of a second late). */
    private val pending = ArrayDeque<RssiSample>()
    private var path: List<TrackPoint> = emptyList()
    private var log: Writer? = null

    private val _state = MutableStateFlow(MapState())
    val state: StateFlow<MapState> = _state.asStateFlow()

    /** This build can track with the cameras (it still needs a calibrated camera). */
    val available: Boolean get() = Vernier.available

    fun start() {
        stop()
        map.clear()
        pending.clear()
        path = emptyList()
        val t = Tracking(context.applicationContext).also { tracking = it }
        t.start(scope)
        engine.fastPoll = true
        log = openLog()
        _state.value = MapState(running = true, track = t.state.value)
        jobs += scope.launch { t.state.collect(::onTrack) }
        jobs += scope.launch {
            engine.samples.collect {
                pending.addLast(it)
                place()
            }
        }
        jobs += scope.launch {
            while (isActive) {
                delay(ESTIMATE_EVERY_MS)
                val estimate = withContext(Dispatchers.Default) { map.estimate() } ?: continue
                _state.update { it.copy(estimate = estimate) }
            }
        }
    }

    fun stop() {
        jobs.forEach { it.cancel() }
        jobs.clear()
        tracking?.close()
        tracking = null
        if (_state.value.running) engine.fastPoll = false
        runCatching { log?.close() }
        log = null
        _state.update { it.copy(running = false) }
    }

    private fun onTrack(t: TrackState) {
        path = t.path
        _state.update { it.copy(track = t, here = t.path.lastOrNull()) }
        place()
    }

    /** Places the readings the tracking has caught up with. */
    private fun place() {
        val last = path.lastOrNull() ?: return
        while (pending.isNotEmpty()) {
            val s = pending.first()
            val tNs = s.atMs * 1_000_000
            if (tNs > last.tNs) {
                // Not tracked that far yet: wait, unless the tracking has stalled.
                if (last.tNs < tNs - MAX_WAIT_NS) pending.removeFirst() else return
                continue
            }
            pending.removeFirst()
            val at = poseAt(tNs) ?: continue
            map.add(SignalMap.Reading(at.x.toDouble(), at.y.toDouble(), at.headingX.toDouble(), at.headingY.toDouble(), s.rssi, s.fromConnection))
            log?.let { w ->
                runCatching {
                    w.write("${s.atMs},${at.x},${at.y},${at.z},${at.headingX},${at.headingY},${s.rssi},${if (s.fromConnection) 1 else 0}\n")
                }
            }
        }
        _state.update { it.copy(readings = map.readings) }
    }

    /** The tracked pose at [tNs], interpolated; null if the tracking has no pose near it. */
    private fun poseAt(tNs: Long): TrackPoint? {
        val p = path
        if (p.isEmpty() || tNs < p.first().tNs) return null
        var lo = 0
        var hi = p.size - 1
        while (hi - lo > 1) {
            val mid = (lo + hi) / 2
            if (p[mid].tNs <= tNs) lo = mid else hi = mid
        }
        val a = p[lo]
        val b = p[hi]
        if (b.tNs - a.tNs > MAX_GAP_NS) return null
        val f = if (b.tNs > a.tNs) ((tNs - a.tNs).toDouble() / (b.tNs - a.tNs)).toFloat().coerceIn(0f, 1f) else 0f
        val near = if (f < 0.5f) a else b
        return TrackPoint(tNs, a.x + (b.x - a.x) * f, a.y + (b.y - a.y) * f, a.z + (b.z - a.z) * f, near.headingX, near.headingY)
    }

    private fun openLog(): Writer? = runCatching {
        val dir = File(context.getExternalFilesDir(null), "maps").apply { mkdirs() }
        val name = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date())
        File(dir, "$name.csv").bufferedWriter().apply { write("t_ms,x,y,z,heading_x,heading_y,rssi,connection\n") }
    }.onFailure { Log.w(TAG, "no map log", it) }.getOrNull()

    companion object {
        private const val TAG = "Marco.Map"
        private const val ESTIMATE_EVERY_MS = 1_000L
        private const val MAX_WAIT_NS = 3_000_000_000L
        /** Poses further apart than this (tracking lost for a while) don't place a reading between them. */
        private const val MAX_GAP_NS = 500_000_000L
    }
}
