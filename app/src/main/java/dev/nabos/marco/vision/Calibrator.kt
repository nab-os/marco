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
import kotlin.math.sqrt

/** Which parts of the frame the board has visited: a grid over the frame less [inset] on each side. */
data class Coverage(val columns: Int, val rows: Int, val inset: Float, val cells: List<Boolean>) {
    /** The grid as seen after turning the frame [degrees] clockwise, like the preview. */
    fun rotated(degrees: Int): Coverage {
        val (w, h) = if (degrees % 180 == 0) columns to rows else rows to columns
        return Coverage(w, h, inset, List(w * h) { i ->
            val (x, y) = upright(i % w, i / w, columns, rows, degrees)
            cells[y * columns + x]
        })
    }
}

/**
 * The pixel of a `width × height` image that lands at ([x], [y]) once the image
 * is turned [degrees] clockwise (0, 90, 180 or 270).
 */
internal fun upright(x: Int, y: Int, width: Int, height: Int, degrees: Int): Pair<Int, Int> = when ((degrees % 360 + 360) % 360) {
    90 -> y to height - 1 - x
    180 -> width - 1 - x to height - 1 - y
    270 -> width - 1 - y to x
    else -> x to y
}

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
    /** How big the board's squares look to the camera, measured (binned) pixels; 0 before the first read. */
    val squarePx: Float = 0f,
    /** With a second lens: its views kept, and frames where both lenses read the board. */
    val stereo: Boolean = false,
    val secondViews: Int = 0,
    val bothRead: Int = 0,
    val preview: ImageBitmap? = null,
    val finishing: Boolean = false,
    val result: CalibrationResult? = null,
    val error: String? = null,
) {
    /** 0..1, the least advanced of views, coverage and poses (and, with a second lens, its own). */
    val progress: Float
        get() = listOfNotNull(
            views.toFloat() / Calibrator.READY_VIEWS,
            covered / Calibrator.READY_COVERAGE,
            poses.toFloat() / Calibrator.READY_POSES,
            if (stereo) secondViews.toFloat() / Calibrator.READY_VIEWS else null,
            if (stereo) bothRead.toFloat() / Calibrator.READY_BOTH else null,
        ).min().coerceIn(0f, 1f)
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
    /** The second lens and how it sits, when calibrated as a stereo pair; see [stereoError]. */
    val second: SecondLens?,
    val stereoError: String?,
    val savedTo: File,
)

data class SecondLens(
    val label: String,
    val model: String,
    val fx: Double,
    val rms: Double,
    val views: Int,
    /** Angle between the two lenses' axes, degrees. */
    val angleDeg: Double,
    /** Baseline in metres: Android's factory length along the measured direction; null if unknown. */
    val baselineM: Double?,
    val pairs: Int,
    val rotationSdDeg: Double,
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
 * When the camera has a second lens, both stream together and the pass also
 * calibrates that lens and how it sits relative to the first: a stereo pair
 * for tracking with Basalt.
 *
 * Frames are measured on one worker thread as fast as it manages, dropping
 * the rest; every one measured is a pose for the alignment, and at most one
 * every [KEEP_EVERY_NS] is kept for each lens, if it shows the board in a new way.
 */
class Calibrator(private val context: Context) {

    private val rig = CameraRig(context)
    private val sensors = context.getSystemService(SensorManager::class.java)
    private val worker = Executors.newSingleThreadExecutor()
    private val busy = AtomicBoolean(false)
    /** Vernier sessions, one per lens; only touched on [worker]. */
    private val handles = longArrayOf(0L, 0L)
    private val lastKeepNs = longArrayOf(0L, 0L)
    private var sizes: List<Size> = emptyList()
    private var measured = 0

    /** Exposure and rolling shutter of recent frames, per lens, by sensor timestamp. */
    private val frameInfo = listOf(ConcurrentHashMap<Long, FrameInfo>(), ConcurrentHashMap<Long, FrameInfo>())
    /** The latest frame of each lens, waiting for its partner (camera thread only). */
    private val waiting = arrayOfNulls<LumaFrame>(2)
    private var gyroThread: HandlerThread? = null
    private var gyroTimes = LongArray(GYRO_BATCH)
    private var gyroRates = FloatArray(GYRO_BATCH * 3)
    private var gyroCount = 0 // only touched on the gyro thread

    private val _state = MutableStateFlow(CalibrationState())
    val state: StateFlow<CalibrationState> = _state.asStateFlow()

    private fun measuredSize(lens: Int) = sizes[lens].let { Size(it.width / BIN, it.height / BIN) }
    private val calibrationName: String
        get() = measuredSize(0).let { "camera-${rig.label.replace('/', '-')}-${it.width}x${it.height}.json" }

    fun start() {
        if (_state.value.running) return
        if (!Vernier.available) return fail("This build has no Vernier (build with marco.vision=true).")
        val first = rig.outputSize(CAPTURE_WIDTH) ?: return fail("No 4:3 camera stream up to $CAPTURE_WIDTH px wide.")
        // The second lens at the size that gives it about the first's pixel scale (a main lens is
        // about twice as long as an ultrawide), so Basalt can match image patches between them.
        val second = rig.secondOutputSize(SECOND_CAPTURE_WIDTH)
        sizes = listOfNotNull(first, second)
        val stereo = sizes.size == 2
        val gyro = sensors.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        val fov = rig.horizontalFovDeg()
        _state.value = CalibrationState(
            running = true,
            stereo = stereo,
            cameraLabel = "camera ${rig.label} · ${first.width}×${first.height} → ${first.width / BIN}×${first.height / BIN}" +
                (fov?.let { " · %.0f° wide".format(it) } ?: "") +
                (second?.let { " + ${rig.secondLabel} → ${it.width / BIN}×${it.height / BIN}" } ?: ""),
            error = if (gyro == null) "No gyroscope: the lens will be calibrated, but not aligned." else null,
        )
        worker.execute {
            for (lens in sizes.indices) {
                handles[lens] = Vernier.newSession(true, BIN)
                lastKeepNs[lens] = 0
            }
        }
        gyro?.let {
            val thread = HandlerThread("marco-gyro").also { t -> t.start() }
            gyroThread = thread
            gyroCount = 0
            sensors.registerListener(gyroListener, it, IMU_PERIOD_US, Handler(thread.looper))
        }
        rig.startLenses(
            sizes,
            onFrame = { lens, frame -> onFrame(lens, frame) },
            onInfo = { lens, info ->
                val infos = frameInfo[lens]
                infos[info.timestampNs] = info
                if (infos.size > 64) infos.keys.filter { it < info.timestampNs - 2_000_000_000 }.forEach(infos::remove)
            },
            onError = ::fail,
            exposureEv = BOARD_EXPOSURE_EV,
        )
    }

    /** On the camera thread: hands a frame, or a pair of frames from the same capture, to the worker. */
    private fun onFrame(lens: Int, frame: LumaFrame) {
        if (sizes.size == 1) {
            if (busy.compareAndSet(false, true)) worker.execute { process(listOf(frame)) }
            return
        }
        waiting[lens] = frame
        val pair = waiting.filterNotNull()
        if (pair.size == 2 && pair[0].timestampNs == pair[1].timestampNs) {
            waiting.fill(null)
            if (busy.compareAndSet(false, true)) worker.execute { process(pair) }
        }
    }

    /** Stops recording and computes the calibration and alignment from what was recorded. */
    fun finish() {
        if (_state.value.finishing || !_state.value.running) return
        _state.update { it.copy(finishing = true, running = false) }
        rig.stop()
        stopGyro() // flushes the last readings onto the worker first
        worker.execute {
            val json = if (handles[0] != 0L) JSONObject(Vernier.finish(handles[0])) else JSONObject().put("error", "not running")
            if (json.has("error")) {
                _state.update { it.copy(finishing = false, error = "Couldn't calibrate: ${json.getString("error")}") }
                return@execute
            }
            if (sizes.size == 2 && handles[1] != 0L) {
                val second = JSONObject(Vernier.finish(handles[1]))
                if (second.has("error")) {
                    json.put("stereo_error", "second lens: ${second.getString("error")}")
                } else {
                    second.remove("imu_error")
                    json.put("second", second)
                    val stereo = JSONObject(Vernier.stereo(handles[0], handles[1]))
                    if (stereo.has("error")) json.put("stereo_error", stereo.getString("error")) else json.put("stereo", stereo)
                }
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

    /** Releases the camera, sensors and sessions. */
    fun close(shutdown: Boolean = true) {
        rig.stop()
        stopGyro()
        worker.execute {
            for (lens in handles.indices) {
                if (handles[lens] != 0L) Vernier.freeSession(handles[lens])
                handles[lens] = 0
            }
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
        if (count > 0) worker.execute { if (handles[0] != 0L) Vernier.addGyro(handles[0], times, rates, count) }
    }

    private fun stopGyro() {
        val thread = gyroThread ?: return
        sensors.unregisterListener(gyroListener)
        thread.quitSafely()
        thread.join(1_000)
        gyroThread = null
        flushGyro()
    }

    private fun process(frames: List<LumaFrame>) {
        try {
            if (handles[0] == 0L || !_state.value.running) return
            val started = System.nanoTime()
            val points = frames.mapIndexed { lens, frame -> measure(lens, frame) }
            if (++measured % 30 == 0) Log.d(TAG, "frame: ${(System.nanoTime() - started) / 1_000_000} ms")
            val s = Vernier.status(handles[0])
            val second = if (frames.size == 2) Vernier.status(handles[1]) else null
            val coverage = Coverage(columns = s[5], rows = s[6], inset = s[7] / 1000f, cells = s.drop(8).map { it != 0 })
                .rotated(rig.sensorOrientation)
            val preview = preview(frames[0])
            val bothNow = points.size == 2 && points.all { it >= 0 }
            _state.update {
                it.copy(
                    points = points[0], views = s[0], poses = s[1], covered = s[2] / 1000f, squarePx = s[4] / 10f,
                    secondViews = second?.get(0) ?: 0, bothRead = it.bothRead + if (bothNow) 1 else 0,
                    coverage = coverage, preview = preview,
                )
            }
            val st = _state.value
            val ready = s[3] != 0 && (!st.stereo || (st.secondViews >= READY_VIEWS && st.bothRead >= READY_BOTH))
            _state.update { it.copy(ready = ready) }
            if (ready) finish()
        } finally {
            busy.set(false)
        }
    }

    /** Measures one lens's frame; board points, or -1. Skips frames whose exposure is unknown. */
    private fun measure(lens: Int, frame: LumaFrame): Int {
        // Without its exposure and rolling shutter, a frame's moment is unknown.
        val info = frameInfo[lens].remove(frame.timestampNs) ?: return -1
        val points = Vernier.measure(
            handles[lens], frame.luma, frame.width, frame.height, frame.width,
            frame.timestampNs, info.exposureNs ?: 0, info.rollingShutterSkewNs ?: 0,
        )
        if (points >= 0 && frame.timestampNs - lastKeepNs[lens] >= KEEP_EVERY_NS) {
            val before = Vernier.status(handles[lens])[0]
            if (Vernier.keep(handles[lens]) > before) lastKeepNs[lens] = frame.timestampNs
        }
        return points
    }

    private fun save(json: JSONObject): CalibrationResult {
        val factory = rig.factoryIntrinsics(measuredSize(0))
        json.put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
            .put("capture", "${sizes[0].width}x${sizes[0].height}")
            .put("binning", BIN)
            .put("camera_id", rig.cameraId)
            .put("physical_camera_id", rig.physicalId)
            .put("board", JSONObject().put("code_size", 5).put("packing", "two-bits").put("unit", "square"))
        factory?.let { json.put("factory", factoryJson(it)) }
        // Android's pose: the camera's rotation from the phone's sensor axes, x, y, z, w.
        val factoryPose = rig.characteristics?.get(CameraCharacteristics.LENS_POSE_ROTATION)
        val imu = json.optJSONObject("imu")?.also { imu ->
            imu.put("factory_pose_rotation_xyzw", factoryPose?.let { JSONArray(it.map(Float::toDouble)) })
        }
        val second = json.optJSONObject("second")?.also { second ->
            second.put("capture", "${sizes[1].width}x${sizes[1].height}")
                .put("binning", BIN)
                .put("physical_camera_id", rig.secondId)
            rig.factoryIntrinsics(measuredSize(1), rig.secondCharacteristics)?.let { second.put("factory", factoryJson(it)) }
        }
        val stereo = json.optJSONObject("stereo")?.also { stereo -> addMetricBaseline(stereo) }

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
            second = if (second != null && stereo != null) secondLens(second, stereo) else null,
            stereoError = json.optString("stereo_error").takeIf { it.isNotEmpty() },
            savedTo = pullable,
        )
    }

    /**
     * Vernier gives the baseline in board squares, whose real size is unknown: scale it to the
     * distance between the two lenses Android reports, keeping the measured direction.
     */
    private fun addMetricBaseline(stereo: JSONObject) {
        val first = rig.characteristics?.get(CameraCharacteristics.LENS_POSE_TRANSLATION)
        val second = rig.secondCharacteristics?.get(CameraCharacteristics.LENS_POSE_TRANSLATION)
        if (first == null || second == null) return
        val length = sqrt((0..2).sumOf { ((second[it] - first[it]).toDouble()).let { d -> d * d } })
        val squares = stereo.getJSONArray("translation_squares").let { a -> List(3) { a.getDouble(it) } }
        val norm = sqrt(squares.sumOf { it * it })
        if (length <= 0 || norm <= 0) return
        stereo.put("baseline_m_factory", length)
            .put("translation_m", JSONArray(squares.map { it / norm * length }))
    }

    private fun factoryJson(f: FactoryIntrinsics) = JSONObject().put("fx", f.fx).put("fy", f.fy).put("cx", f.cx).put("cy", f.cy)
        .put("distortion_k1k2k3p1p2", JSONArray(f.distortion))

    private fun secondLens(second: JSONObject, stereo: JSONObject): SecondLens {
        val q = stereo.getJSONArray("second_from_first_xyzw")
        return SecondLens(
            label = rig.secondLabel ?: "",
            model = second.getJSONObject("camera").getString("model"),
            fx = second.getJSONObject("camera").getDouble("fx"),
            rms = second.getDouble("rms"),
            views = second.getInt("views"),
            angleDeg = Math.toDegrees(2 * acos(min(1.0, abs(q.getDouble(3))))),
            baselineM = stereo.optDouble("baseline_m_factory").takeIf { !it.isNaN() },
            pairs = stereo.getInt("pairs"),
            rotationSdDeg = stereo.getDouble("rotation_sd_deg"),
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

    /** A quarter-size grey preview of what the camera sees, turned upright for a portrait screen. */
    private fun preview(frame: LumaFrame): ImageBitmap {
        val w = frame.width / PREVIEW_STEP
        val h = frame.height / PREVIEW_STEP
        val degrees = rig.sensorOrientation
        val (outW, outH) = if (degrees % 180 == 0) w to h else h to w
        val pixels = IntArray(outW * outH) { i ->
            val (x, y) = upright(i % outW, i / outW, w, h, degrees)
            val g = frame.luma[y * PREVIEW_STEP * frame.width + x * PREVIEW_STEP].toInt() and 0xFF
            (0xFF shl 24) or (g shl 16) or (g shl 8) or g
        }
        return Bitmap.createBitmap(pixels, outW, outH, Bitmap.Config.ARGB_8888).asImageBitmap()
    }

    companion object {
        private const val TAG = "Marco.Calibrate"
        const val CAPTURE_WIDTH = 1280

        /** The second lens's capture: binned to 320×240, a main lens matches an ultrawide's pixel scale. */
        const val SECOND_CAPTURE_WIDTH = 640

        /** Measuring a full 1280×960 frame takes about a second on a phone; binned by 2, a third of that. */
        private const val BIN = 2

        /** Mirrors vision/src/lib.rs: when a pass finishes on its own. */
        const val READY_VIEWS = 12
        const val READY_COVERAGE = 0.8f
        const val READY_POSES = 30

        /** Frames where both lenses read the board, for the stereo pose. */
        const val READY_BOTH = 15

        /**
         * Squares smaller than this in the measured image make frames slow and fragile, larger than
         * this keep too few in view. Measured pixels, after binning.
         */
        const val SMALL_SQUARE_PX = 4f
        const val LARGE_SQUARE_PX = 16f

        /** The least [finish] can calibrate from (vision's MIN_VIEWS). */
        const val MIN_VIEWS = 6
        private const val KEEP_EVERY_NS = 500_000_000L
        private const val PREVIEW_STEP = 4
        private const val GYRO_BATCH = 100

        /** The board is a lit screen, brighter than the room around it. */
        private const val BOARD_EXPOSURE_EV = -1.0
    }
}
