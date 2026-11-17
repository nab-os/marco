package dev.nabos.marco.vision

import android.content.Context
import android.graphics.Bitmap
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.camera2.CameraCharacteristics
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.min

/** Which parts of the frame the board has visited: a grid over the frame less [inset] on each side. */
data class Coverage(val columns: Int, val rows: Int, val inset: Float, val cells: List<Boolean>)

data class CalibrationState(
    val running: Boolean = false,
    val cameraLabel: String = "",
    /** Board points in the last frame measured, -1 if its code wasn't read. */
    val points: Int = -1,
    val views: Int = 0,
    val poses: Int = 0,
    /** Fraction of [coverage]'s cells visited. */
    val covered: Float = 0f,
    val coverage: Coverage? = null,
    /** Enough of everything: finishing on its own. */
    val ready: Boolean = false,
    val preview: ImageBitmap? = null,
    val finishing: Boolean = false,
    val result: CalibrationResult? = null,
    val error: String? = null,
) {
    /** 0..1, the least advanced of views, coverage and poses. */
    val progress: Float
        get() = minOf(
            views.toFloat() / Calibrator.READY_VIEWS,
            covered / Calibrator.READY_COVERAGE,
            poses.toFloat() / Calibrator.READY_POSES,
        ).coerceIn(0f, 1f)
}

data class CalibrationResult(
    val fx: Double, val fy: Double, val cx: Double, val cy: Double,
    val distortion: List<Double>,
    val model: String,
    val rms: Double,
    /** Every lens model tried, with its rms; the lowest wins. */
    val rmsByModel: Map<String, Double>,
    val views: Int,
    val factory: FactoryIntrinsics?,
    /** Null if the alignment failed; see [alignmentError]. */
    val alignment: AlignmentResult?,
    val alignmentError: String?,
    val savedTo: File,
)

data class AlignmentResult(
    /** Gyroscope (phone) axes to camera axes, `x, y, z, w`. */
    val imuToCamera: List<Double>,
    /** `t_gyro = t_camera + timeOffsetMs`. */
    val timeOffsetMs: Double,
    val gyroBias: List<Double>,
    /** How far off square the camera sits in the phone, degrees. */
    val offAxisDeg: Double,
    /** Angle to Android's factory camera pose, degrees; null if the phone reports none. */
    val vsFactoryDeg: Double?,
    val rmsDeg: Double,
    val pairs: Int,
    val poses: Int,
    /** Bootstrap spreads: how far a repeat run would likely land. */
    val timeOffsetSdMs: Double,
    val rotationSdDeg: Double,
    /** 0..1; low means one axis was barely turned about. */
    val axisCoverage: Double,
)

/**
 * One pass in front of the board calibrates this phone's [CameraRig] camera
 * and aligns it with the gyroscope: frames and gyro readings are recorded
 * together, and once there are enough views, enough of the frame covered and
 * enough poses, it finishes on its own (or on [finish]).
 *
 * Frames are measured on one worker thread as fast as it manages, dropping
 * the rest; every one measured is a pose for the alignment, and at most one
 * every [KEEP_EVERY_NS] is kept for the lens, if it shows the board in a new way.
 */
class Calibrator(private val context: Context) {

    private val rig = CameraRig(context)
    private val sensors = context.getSystemService(SensorManager::class.java)
    private val worker = Executors.newSingleThreadExecutor()
    private val busy = AtomicBoolean(false)
    private var handle = 0L // only touched on [worker]
    private var lastKeepNs = 0L
    private var size: Size? = null
    private var measured = 0

    /** Exposure and rolling shutter of recent frames, by sensor timestamp. */
    private val frameInfo = ConcurrentHashMap<Long, FrameInfo>()
    private var gyroThread: HandlerThread? = null
    private var gyroTimes = LongArray(GYRO_BATCH)
    private var gyroRates = FloatArray(GYRO_BATCH * 3)
    private var gyroCount = 0 // only touched on the gyro thread

    private val _state = MutableStateFlow(CalibrationState())
    val state: StateFlow<CalibrationState> = _state.asStateFlow()

    /** The size frames are measured at, after binning; calibrations are for this size. */
    private val measuredSize: Size get() = size!!.let { Size(it.width / BIN, it.height / BIN) }
    private val calibrationName: String
        get() = measuredSize.let { "camera-${rig.label.replace('/', '-')}-${it.width}x${it.height}.json" }

    fun start() {
        if (_state.value.running) return
        if (!Vernier.available) return fail("This build has no Vernier (build with marco.vision=true).")
        val s = rig.outputSize(CAPTURE_WIDTH) ?: return fail("No 4:3 camera stream up to $CAPTURE_WIDTH px wide.")
        val gyro = sensors.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        size = s
        val fov = rig.horizontalFovDeg()
        _state.value = CalibrationState(
            running = true,
            cameraLabel = "camera ${rig.label} · ${s.width}×${s.height} → ${s.width / BIN}×${s.height / BIN}" +
                (fov?.let { " · %.0f° wide".format(it) } ?: ""),
            error = if (gyro == null) "No gyroscope: the lens will be calibrated, but not aligned." else null,
        )
        worker.execute {
            handle = Vernier.newSession(true, BIN)
            lastKeepNs = 0
        }
        gyro?.let {
            val thread = HandlerThread("marco-gyro").also { t -> t.start() }
            gyroThread = thread
            gyroCount = 0
            sensors.registerListener(gyroListener, it, IMU_PERIOD_US, Handler(thread.looper))
        }
        rig.start(
            s,
            onFrame = { frame ->
                if (busy.compareAndSet(false, true)) worker.execute { process(frame) }
            },
            onInfo = { info ->
                frameInfo[info.timestampNs] = info
                if (frameInfo.size > 64) frameInfo.keys.filter { it < info.timestampNs - 2_000_000_000 }.forEach(frameInfo::remove)
            },
            onError = ::fail,
            exposureEv = BOARD_EXPOSURE_EV,
        )
    }

    /** Stops recording and computes the calibration and alignment from what was recorded. */
    fun finish() {
        if (_state.value.finishing || !_state.value.running) return
        _state.update { it.copy(finishing = true, running = false) }
        rig.stop()
        stopGyro() // flushes the last readings onto the worker first
        worker.execute {
            val json = if (handle != 0L) JSONObject(Vernier.finish(handle)) else JSONObject().put("error", "not running")
            if (json.has("error")) {
                _state.update { it.copy(finishing = false, error = "Couldn't calibrate: ${json.getString("error")}") }
                return@execute
            }
            val result = runCatching { save(json) }
            _state.update { it.copy(finishing = false, result = result.getOrNull(), error = result.exceptionOrNull()?.message) }
        }
    }

    /** Throws the pass away and starts a new one. */
    fun restart() {
        close(shutdown = false)
        start()
    }

    /** Releases the camera, sensors and session. */
    fun close(shutdown: Boolean = true) {
        rig.stop()
        stopGyro()
        worker.execute {
            if (handle != 0L) Vernier.freeSession(handle)
            handle = 0
        }
        if (shutdown) worker.shutdown()
        _state.update { it.copy(running = false) }
    }

    private val gyroListener = object : SensorEventListener {
        override fun onSensorChanged(e: SensorEvent) {
            gyroTimes[gyroCount] = e.timestamp
            gyroRates[gyroCount * 3] = e.values[0]
            gyroRates[gyroCount * 3 + 1] = e.values[1]
            gyroRates[gyroCount * 3 + 2] = e.values[2]
            if (++gyroCount == GYRO_BATCH) flushGyro()
        }

        override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit
    }

    /** Hands the buffered readings to the worker; on the gyro thread, or once it has stopped. */
    private fun flushGyro() {
        val times = gyroTimes
        val rates = gyroRates
        val count = gyroCount
        gyroTimes = LongArray(GYRO_BATCH)
        gyroRates = FloatArray(GYRO_BATCH * 3)
        gyroCount = 0
        if (count > 0) worker.execute { if (handle != 0L) Vernier.addGyro(handle, times, rates, count) }
    }

    private fun stopGyro() {
        val thread = gyroThread ?: return
        sensors.unregisterListener(gyroListener)
        thread.quitSafely()
        thread.join(1_000)
        gyroThread = null
        flushGyro()
    }

    private fun process(frame: LumaFrame) {
        try {
            if (handle == 0L || !_state.value.running) return
            // Without its exposure and rolling shutter, a frame's moment is unknown: skip it.
            val info = frameInfo.remove(frame.timestampNs) ?: return
            val started = System.nanoTime()
            val points = Vernier.measure(
                handle, frame.luma, frame.width, frame.height, frame.width,
                frame.timestampNs, info.exposureNs ?: 0, info.rollingShutterSkewNs ?: 0,
            )
            if (points >= 0 && frame.timestampNs - lastKeepNs >= KEEP_EVERY_NS) {
                val before = _state.value.views
                if (Vernier.keep(handle) > before) lastKeepNs = frame.timestampNs
            }
            if (++measured % 30 == 0) Log.d(TAG, "frame: ${(System.nanoTime() - started) / 1_000_000} ms")
            val s = Vernier.status(handle)
            val coverage = Coverage(columns = s[4], rows = s[5], inset = s[6] / 1000f, cells = s.drop(7).map { it != 0 })
            val preview = preview(frame)
            _state.update {
                it.copy(
                    points = points, views = s[0], poses = s[1], covered = s[2] / 1000f, ready = s[3] != 0,
                    coverage = coverage, preview = preview,
                )
            }
            if (s[3] != 0) finish()
        } finally {
            busy.set(false)
        }
    }

    private fun save(json: JSONObject): CalibrationResult {
        val factory = rig.factoryIntrinsics(measuredSize)
        json.put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
            .put("capture", "${size!!.width}x${size!!.height}")
            .put("binning", BIN)
            .put("camera_id", rig.cameraId)
            .put("physical_camera_id", rig.physicalId)
            .put("board", JSONObject().put("code_size", 5).put("packing", "two-bits").put("unit", "square"))
        factory?.let {
            json.put(
                "factory",
                JSONObject().put("fx", it.fx).put("fy", it.fy).put("cx", it.cx).put("cy", it.cy)
                    .put("distortion_k1k2k3p1p2", JSONArray(it.distortion)),
            )
        }
        // Android's pose: the camera's rotation from the phone's sensor axes, x, y, z, w.
        val factoryPose = rig.characteristics?.get(CameraCharacteristics.LENS_POSE_ROTATION)
        val imu = json.optJSONObject("imu")?.also { imu ->
            imu.put("factory_pose_rotation_xyzw", factoryPose?.let { JSONArray(it.map(Float::toDouble)) })
        }

        // Kept for the app, and a dated copy where `adb pull` can reach it.
        File(context.filesDir, "vision").apply { mkdirs() }.resolve(calibrationName).writeText(json.toString(2))
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val pullable = File(context.getExternalFilesDir("vision"), "$stamp-$calibrationName").apply { writeText(json.toString(2)) }

        val camera = json.getJSONObject("camera")
        val byModel = json.optJSONObject("rms_by_model")
        return CalibrationResult(
            fx = camera.getDouble("fx"), fy = camera.getDouble("fy"),
            cx = camera.getDouble("cx"), cy = camera.getDouble("cy"),
            distortion = camera.getJSONArray("distortion").let { a -> List(a.length()) { a.getDouble(it) } },
            model = camera.getString("model"),
            rms = json.getDouble("rms"),
            rmsByModel = byModel?.keys()?.asSequence()?.associateWith { byModel.getDouble(it) } ?: emptyMap(),
            views = json.getInt("views"),
            factory = factory,
            alignment = imu?.let { alignment(it, factoryPose) },
            alignmentError = json.optString("imu_error").takeIf { it.isNotEmpty() },
            savedTo = pullable,
        )
    }

    private fun alignment(imu: JSONObject, factoryPose: FloatArray?): AlignmentResult {
        val q = imu.getJSONArray("imu_to_camera_xyzw").let { a -> List(4) { a.getDouble(it) } }
        return AlignmentResult(
            imuToCamera = q,
            timeOffsetMs = imu.getDouble("time_offset_ms"),
            gyroBias = imu.getJSONArray("gyro_bias").let { a -> List(3) { a.getDouble(it) } },
            offAxisDeg = imu.getDouble("off_axis_deg"),
            vsFactoryDeg = factoryPose?.let { f ->
                val dot = abs(q.zip(f.map(Float::toDouble)).sumOf { (a, b) -> a * b })
                Math.toDegrees(2 * acos(min(1.0, dot)))
            },
            rmsDeg = imu.getDouble("rms_deg"),
            pairs = imu.getInt("pairs"),
            poses = imu.optInt("poses"),
            timeOffsetSdMs = imu.optDouble("time_offset_sd_ms"),
            rotationSdDeg = imu.optDouble("rotation_sd_deg"),
            axisCoverage = imu.getDouble("axis_coverage"),
        )
    }

    private fun fail(message: String) = _state.update { it.copy(error = message) }

    /** A quarter-size grey preview of what the camera sees. */
    private fun preview(frame: LumaFrame): ImageBitmap {
        val w = frame.width / PREVIEW_STEP
        val h = frame.height / PREVIEW_STEP
        val pixels = IntArray(w * h) { i ->
            val g = frame.luma[(i / w) * PREVIEW_STEP * frame.width + (i % w) * PREVIEW_STEP].toInt() and 0xFF
            (0xFF shl 24) or (g shl 16) or (g shl 8) or g
        }
        return Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888).asImageBitmap()
    }

    companion object {
        private const val TAG = "Marco.Calibrate"
        const val CAPTURE_WIDTH = 1280

        /** Measuring a full 1280×960 frame takes about a second on a phone; binned by 2, a third of that. */
        private const val BIN = 2

        /** Mirrors vision/src/lib.rs: when a pass finishes on its own. */
        const val READY_VIEWS = 12
        const val READY_COVERAGE = 0.8f
        const val READY_POSES = 30

        /** The least [finish] can calibrate from (vision's MIN_VIEWS). */
        const val MIN_VIEWS = 6
        private const val KEEP_EVERY_NS = 500_000_000L
        private const val PREVIEW_STEP = 4
        private const val GYRO_BATCH = 100

        /** The board is a lit screen, brighter than the room around it. */
        private const val BOARD_EXPOSURE_EV = -1.0
    }
}
