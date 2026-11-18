package dev.nabos.marco.vision

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.hypot
import kotlin.math.sqrt

/** One tracked position: metres in a gravity-aligned world (z up), and which way the camera looks. */
data class TrackPoint(val tNs: Long, val x: Float, val y: Float, val z: Float, val headingX: Float, val headingY: Float)

data class TrackState(
    val running: Boolean = false,
    val status: String = "",
    /** Every position so far, for drawing the path. */
    val path: List<TrackPoint> = emptyList(),
    val pathLengthM: Float = 0f,
    /** Poses per second over the last second. */
    val rate: Float = 0f,
    /** How long after its frame's moment a pose arrives, ms. */
    val latencyMs: Float = 0f,
    /** Stereo pairs the camera delivered, and how many were dropped because Basalt was still busy with earlier ones. */
    val pairs: Int = 0,
    val dropped: Int = 0,
    /** Mean time to bin and push a pair, ms. */
    val pushMs: Float = 0f,
    val error: String? = null,
)

/**
 * Tracks the phone's position live with Basalt (`vision/basalt-android`), from
 * its ultrawide and main lenses as a stereo pair plus the gyroscope and
 * accelerometer, using the stereo calibration the [Calibrator] saved.
 *
 * Frames are binned and pushed on one worker thread; if it or Basalt falls
 * behind, pairs are dropped rather than queued, so tracking stays current. IMU readings go
 * on another: Basalt's pushes block while it is behind, and one waiting on the
 * other would stall both.
 */
class Tracking(private val context: Context) {

    private val rig = CameraRig(context)
    private val sensors = context.getSystemService(SensorManager::class.java)
    private val worker = Executors.newSingleThreadExecutor()
    private val imuWorker = Executors.newSingleThreadExecutor()
    private val busy = AtomicBoolean(false)
    private val pairs = java.util.concurrent.atomic.AtomicInteger()
    private val dropped = java.util.concurrent.atomic.AtomicInteger()
    private val pushNs = java.util.concurrent.atomic.AtomicLong()
    private val pushed = java.util.concurrent.atomic.AtomicInteger()
    @Volatile private var handle = 0L
    private var sensorThread: HandlerThread? = null
    private var pollJob: Job? = null
    private val frameInfo = ConcurrentHashMap<Long, FrameInfo>()
    /**
     * Recent frames of each lens waiting for their partner, by timestamp (camera thread only).
     * The lenses' frames don't always arrive alternately, so keeping only the latest of
     * each lost about half the pairs.
     */
    private val waiting = listOf(HashMap<Long, LumaFrame>(), HashMap<Long, LumaFrame>())

    /** Camera axis (z) in the IMU frame, from the calibration: for the heading arrow. */
    private var cameraAxis = floatArrayOf(0f, 0f, 1f)

    // IMU batching, on the sensor thread.
    private var accel = FloatArray(3)
    private var imuTimes = LongArray(IMU_BATCH)
    private var imuValues = FloatArray(IMU_BATCH * 6)
    private var imuCount = 0

    private val _state = MutableStateFlow(TrackState())
    val state: StateFlow<TrackState> = _state.asStateFlow()

    /** What the main lens sees, small and upright, a few times a second: to show the cameras are on. */
    val preview = MutableStateFlow<Bitmap?>(null)
    private var lastPreviewNs = 0L
    /**
     * Tangent of half the preview's horizontal field of view: upright, the preview's
     * width is the sensor's height, so the main lens's `fy` over half that height.
     * 0 until the calibration is read.
     */
    @Volatile var previewHalfFovTan = 0f
        private set

    fun start(scope: CoroutineScope) {
        if (_state.value.running) return
        if (!Vernier.available) return fail("This build has no Vernier (build with marco.vision=true).")
        val first = rig.outputSize(Calibrator.CAPTURE_WIDTH) ?: return fail("No camera stream.")
        val second = rig.secondOutputSize(Calibrator.SECOND_CAPTURE_WIDTH) ?: return fail("This camera has no second lens: Basalt needs stereo.")
        val calibrationFile = File(context.filesDir, "vision/camera-${rig.label.replace('/', '-')}-${first.width / BIN}x${first.height / BIN}.json")
        val calibration = runCatching { JSONObject(calibrationFile.readText()) }.getOrNull()
            ?: return fail("Calibrate this camera first (Marco Lab → Calibrate this camera).")
        if (!calibration.has("stereo")) return fail("The calibration has no stereo pose: calibrate again with both lenses.")
        cameraAxis = cameraAxisInImu(calibration)
        previewHalfFovTan = calibration.optJSONObject("second")?.optJSONObject("camera")?.let { lens ->
            val fy = lens.optDouble("fy", 0.0)
            val height = lens.optDouble("height", (second.height / BIN).toDouble())
            if (fy > 0) (height / 2.0 / fy).toFloat() else 0f
        } ?: 0f

        val started = JSONObject(Vernier.trackerStart(calibration.toString(), File(context.filesDir, "basalt").path))
        if (started.has("error")) return fail(started.getString("error"))
        handle = started.getLong("handle")
        _state.value = TrackState(running = true, status = "Starting… move the phone a little")

        val thread = HandlerThread("marco-track-imu").also { it.start() }
        sensorThread = thread
        val handler = Handler(thread.looper)
        listOfNotNull(sensors.getDefaultSensor(Sensor.TYPE_GYROSCOPE), sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER))
            .forEach { sensors.registerListener(imuListener, it, IMU_PERIOD_US, handler) }

        rig.startLenses(
            listOf(first, second),
            onFrame = ::onFrame,
            onInfo = { lens, info ->
                if (lens != 0) return@startLenses
                frameInfo[info.timestampNs] = info
                if (frameInfo.size > 64) frameInfo.keys.filter { it < info.timestampNs - 2_000_000_000 }.forEach(frameInfo::remove)
            },
            onError = ::fail,
        )
        pollJob = scope.launch { poll() }
    }

    fun stop() {
        pollJob?.cancel()
        pollJob = null
        rig.stop()
        sensors.unregisterListener(imuListener)
        sensorThread?.let { it.quitSafely(); it.join(1_000) }
        sensorThread = null
        val h = handle
        handle = 0
        // After the camera and sensors, and the IMU readings already handed over:
        // nothing pushes any more when it's freed.
        worker.execute {
            if (h == 0L) return@execute
            imuWorker.submit {}.get()
            Vernier.trackerFree(h)
        }
        _state.update { it.copy(running = false, status = "Stopped") }
    }

    /** Releases the worker for good. */
    fun close() {
        stop()
        worker.execute { imuWorker.shutdown() }
        worker.shutdown()
    }

    /** On the camera thread: pairs the lenses' frames from one capture and hands them on. */
    private fun onFrame(lens: Int, frame: LumaFrame) {
        val partner = waiting[1 - lens].remove(frame.timestampNs)
        if (partner == null) {
            waiting[lens][frame.timestampNs] = frame
            // Forget frames whose partner never came (a dropped buffer).
            waiting[lens].keys.removeAll { it < frame.timestampNs - MAX_PAIR_WAIT_NS }
            return
        }
        val (a, b) = if (lens == 0) frame to partner else partner to frame
        pairs.incrementAndGet()
        if (!busy.compareAndSet(false, true)) { // still on the last pair: drop this one
            dropped.incrementAndGet()
            return
        }
        worker.execute {
            val started = System.nanoTime()
            try {
                val h = handle
                if (h == 0L) return@execute
                // Both lenses' frames carry the capture's timestamp; the ultrawide's exposure info
                // has its own sensor's, a fraction of a millisecond away.
                val info = frameInfo.keys.minByOrNull { kotlin.math.abs(it - a.timestampNs) }
                    ?.takeIf { kotlin.math.abs(it - a.timestampNs) < 15_000_000 }?.let { frameInfo[it] }
                val moment = a.timestampNs + (info?.exposureNs ?: 0) / 2 + (info?.rollingShutterSkewNs ?: 0) / 2
                val binned0 = Vernier.binLuma(a.luma, a.width, a.height, BIN) ?: return@execute
                val binned1 = Vernier.binLuma(b.luma, b.width, b.height, BIN) ?: return@execute
                if (started - lastPreviewNs > PREVIEW_EVERY_NS) {
                    lastPreviewNs = started
                    preview.value = thumbnail(binned1, b.width / BIN, b.height / BIN)
                }
                if (Vernier.trackerPushFrames(h, moment, binned0, a.width / BIN, a.height / BIN, binned1, b.width / BIN, b.height / BIN)) {
                    pushNs.addAndGet(System.nanoTime() - started)
                    pushed.incrementAndGet()
                } else {
                    dropped.incrementAndGet()
                }
            } finally {
                busy.set(false)
            }
        }
    }

    private val imuListener = object : SensorEventListener {
        override fun onSensorChanged(e: SensorEvent) {
            if (e.sensor.type == Sensor.TYPE_ACCELEROMETER) {
                e.values.copyInto(accel, endIndex = 3)
                return
            }
            // Each gyro reading with the latest accelerometer one (both at 200 Hz).
            imuTimes[imuCount] = e.timestamp
            accel.copyInto(imuValues, imuCount * 6)
            e.values.copyInto(imuValues, imuCount * 6 + 3, endIndex = 3)
            if (++imuCount == IMU_BATCH) {
                val times = imuTimes
                val values = imuValues
                imuTimes = LongArray(IMU_BATCH)
                imuValues = FloatArray(IMU_BATCH * 6)
                imuCount = 0
                imuWorker.execute { handle.takeIf { it != 0L }?.let { Vernier.trackerPushImu(it, times, values, IMU_BATCH) } }
            }
        }

        override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit
    }

    private suspend fun poll() {
        val arrivals = ArrayDeque<Long>()
        while (kotlinx.coroutines.currentCoroutineContext().isActive) {
            delay(POLL_MS)
            val h = handle.takeIf { it != 0L } ?: continue
            val values = Vernier.trackerPoll(h)
            val now = SystemClock.elapsedRealtimeNanos()
            val points = (0 until values.size / 8).map { i -> point(values, i * 8) }
            repeat(points.size) { arrivals.addLast(now) }
            while (arrivals.isNotEmpty() && arrivals.first() < now - 1_000_000_000) arrivals.removeFirst()
            val stats = { s: TrackState ->
                s.copy(
                    rate = arrivals.size.toFloat(),
                    pairs = pairs.get(),
                    dropped = dropped.get(),
                    pushMs = pushNs.get() / 1e6f / maxOf(1, pushed.get()),
                )
            }
            if (points.isEmpty()) {
                _state.update(stats)
                continue
            }
            _state.update { s ->
                val path = s.path + points
                val added = (maxOf(1, s.path.size) until path.size).sumOf { i ->
                    val p = path[i]
                    val q = path[i - 1]
                    sqrt((p.x - q.x) * (p.x - q.x) + (p.y - q.y) * (p.y - q.y) + (p.z - q.z) * (p.z - q.z)).toDouble()
                }
                stats(s).copy(
                    status = "Tracking",
                    path = path,
                    pathLengthM = s.pathLengthM + added.toFloat(),
                    latencyMs = (now - points.last().tNs) / 1e6f,
                )
            }
        }
    }

    /** A pose from the poll array: position, and the camera's axis turned into the world, flattened. */
    private fun point(v: DoubleArray, at: Int): TrackPoint {
        val (qx, qy, qz, qw) = listOf(v[at + 4], v[at + 5], v[at + 6], v[at + 7]).map { it.toFloat() }
        val axis = rotate(qx, qy, qz, qw, cameraAxis)
        val norm = hypot(axis[0], axis[1]).takeIf { it > 1e-3f } ?: 1f
        return TrackPoint(v[at].toLong(), v[at + 1].toFloat(), v[at + 2].toFloat(), v[at + 3].toFloat(), axis[0] / norm, axis[1] / norm)
    }

    private fun fail(message: String) = _state.update { it.copy(error = message) }

    /** A binned luma frame as a bitmap, turned upright for a portrait screen. */
    private fun thumbnail(luma: ByteArray, width: Int, height: Int): Bitmap {
        val pixels = IntArray(width * height)
        for (i in pixels.indices) {
            val g = luma[i].toInt() and 0xFF
            pixels[i] = (0xFF shl 24) or (g shl 16) or (g shl 8) or g
        }
        val flat = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
        val turn = Matrix().apply { postRotate(rig.sensorOrientation.toFloat()) }
        return Bitmap.createBitmap(flat, 0, 0, width, height, turn, false)
    }

    companion object {
        private const val BIN = 2
        /** 20 ms of readings: Basalt waits for those past a frame before releasing its pose. */
        private const val IMU_BATCH = 4
        private const val POLL_MS = 50L
        private const val PREVIEW_EVERY_NS = 250_000_000L
        private const val MAX_PAIR_WAIT_NS = 200_000_000L

        /** The camera's z axis in IMU axes: the inverse of the calibrated gyro-to-camera rotation applied to z. */
        private fun cameraAxisInImu(calibration: JSONObject): FloatArray {
            val q = calibration.getJSONObject("imu").getJSONArray("imu_to_camera_xyzw")
            return rotate(-q.getDouble(0).toFloat(), -q.getDouble(1).toFloat(), -q.getDouble(2).toFloat(), q.getDouble(3).toFloat(), floatArrayOf(0f, 0f, 1f))
        }

        /** [v] turned by the unit quaternion (x, y, z, w). */
        private fun rotate(x: Float, y: Float, z: Float, w: Float, v: FloatArray): FloatArray {
            // v + 2w (q × v) + 2 q × (q × v)
            val cx = y * v[2] - z * v[1]
            val cy = z * v[0] - x * v[2]
            val cz = x * v[1] - y * v[0]
            return floatArrayOf(
                v[0] + 2 * (w * cx + y * cz - z * cy),
                v[1] + 2 * (w * cy + z * cx - x * cz),
                v[2] + 2 * (w * cz + x * cy - y * cx),
            )
        }
    }
}

