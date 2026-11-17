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

enum class CalibrationPhase {
    /** Collecting views of the board for the lens calibration. */
    VIEWS,

    /** Collecting poses and gyro readings for the camera-to-gyroscope alignment. */
    ALIGNING,
}

data class CalibrationState(
    val running: Boolean = false,
    val phase: CalibrationPhase = CalibrationPhase.VIEWS,
    val cameraLabel: String = "",
    /** Board points in the last frame measured, -1 if its code wasn't read. */
    val points: Int = -1,
    val kept: Int = 0,
    /** Poses recorded for the alignment. */
    val poses: Int = 0,
    val preview: ImageBitmap? = null,
    val calibrating: Boolean = false,
    val result: CalibrationResult? = null,
    val aligning: Boolean = false,
    val alignment: AlignmentResult? = null,
    val error: String? = null,
)

data class CalibrationResult(
    val fx: Double, val fy: Double, val cx: Double, val cy: Double,
    val distortion: List<Double>,
    val model: String,
    val rms: Double,
    /** Every lens model tried, with its rms; the lowest wins. */
    val rmsByModel: Map<String, Double>,
    val views: Int,
    val factory: FactoryIntrinsics?,
    /** Loaded from an earlier session rather than calibrated now. */
    val saved: Boolean,
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
    /** 0..1; low means one axis was barely turned about. */
    val axisCoverage: Double,
    val savedTo: File,
)

/**
 * Calibrates this phone's [CameraRig] camera from frames of the coded
 * checkerboard shown on the other phone's screen, then aligns it with the
 * gyroscope: the rotation between them, the delay between their clocks, the
 * gyro's bias.
 *
 * Frames are measured on one worker thread as fast as it manages, dropping
 * the rest; at most one view is kept every [KEEP_EVERY_NS], and only if the
 * board sits differently from every view kept so far.
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

    private val calibrationName: String
        get() = size!!.let { "camera-${rig.label.replace('/', '-')}-${it.width}x${it.height}.json" }
    private val savedFile: File get() = File(context.filesDir, "vision").resolve(calibrationName)

    fun start() {
        if (_state.value.running) return
        if (!Vernier.available) return fail("This build has no Vernier (build with marco.vision=true).")
        val s = rig.outputSize(CAPTURE_WIDTH) ?: return fail("No 4:3 camera stream up to $CAPTURE_WIDTH px wide.")
        size = s
        val fov = rig.horizontalFovDeg()
        val saved = runCatching { JSONObject(savedFile.readText()) }.getOrNull()
        // Before the worker starts: it may report the saved calibration right away.
        _state.value = CalibrationState(
            running = true,
            cameraLabel = "camera ${rig.label} · ${s.width}×${s.height}" + (fov?.let { " · %.0f° wide".format(it) } ?: ""),
        )
        worker.execute {
            handle = Vernier.newSession(true)
            lastKeepNs = 0
            // A calibration from an earlier session: the alignment can start straight away.
            if (saved != null && Vernier.setCamera(handle, saved.getJSONObject("camera").toString())) {
                _state.update { it.copy(result = runCatching { result(saved, savedFile, saved = true) }.getOrNull()) }
            }
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

    fun stop() {
        stopGyro()
        rig.stop()
        worker.execute {
            if (handle != 0L) Vernier.freeSession(handle)
            handle = 0
        }
        _state.update { it.copy(running = false) }
    }

    /** Releases the worker for good. */
    fun close() {
        stop()
        worker.shutdown()
    }

    fun calibrate() {
        if (_state.value.calibrating) return
        _state.update { it.copy(calibrating = true, error = null) }
        worker.execute {
            val json = if (handle != 0L) JSONObject(Vernier.calibrate(handle)) else JSONObject().put("error", "not running")
            if (json.has("error")) {
                _state.update { it.copy(calibrating = false, error = json.getString("error")) }
                return@execute
            }
            val result = runCatching { saveCalibration(json) }
            _state.update {
                it.copy(calibrating = false, result = result.getOrNull(), alignment = null, error = result.exceptionOrNull()?.message)
            }
        }
    }

    /** Starts recording poses and gyro readings; needs a calibration (new or saved). */
    fun startAlignment() {
        if (_state.value.result == null || _state.value.phase == CalibrationPhase.ALIGNING) return
        val gyro = sensors.getDefaultSensor(Sensor.TYPE_GYROSCOPE) ?: return fail("This phone has no gyroscope.")
        worker.execute { if (handle != 0L) Vernier.resetAlignment(handle) }
        _state.update { it.copy(phase = CalibrationPhase.ALIGNING, poses = 0, alignment = null, error = null) }
        val thread = HandlerThread("marco-gyro").also { it.start() }
        gyroThread = thread
        gyroCount = 0
        sensors.registerListener(gyroListener, gyro, IMU_PERIOD_US, Handler(thread.looper))
    }

    fun align() {
        if (_state.value.aligning) return
        _state.update { it.copy(aligning = true, error = null) }
        stopGyro() // flushes the last readings onto the worker first
        worker.execute {
            val json = if (handle != 0L) JSONObject(Vernier.align(handle)) else JSONObject().put("error", "not running")
            if (json.has("error")) {
                _state.update { it.copy(aligning = false, phase = CalibrationPhase.VIEWS, error = json.getString("error")) }
                return@execute
            }
            val result = runCatching { saveAlignment(json) }
            _state.update {
                it.copy(
                    aligning = false, phase = CalibrationPhase.VIEWS,
                    alignment = result.getOrNull(), error = result.exceptionOrNull()?.message,
                )
            }
        }
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
            if (handle == 0L) return
            val started = System.nanoTime()
            if (_state.value.phase == CalibrationPhase.ALIGNING) {
                // Without its exposure and rolling shutter, a frame's moment is unknown: skip it.
                val info = frameInfo.remove(frame.timestampNs) ?: return
                val points = Vernier.track(
                    handle, frame.luma, frame.width, frame.height, frame.width,
                    frame.timestampNs, info.exposureNs ?: 0, info.rollingShutterSkewNs ?: 0,
                )
                val preview = preview(frame)
                _state.update { it.copy(points = points, poses = it.poses + if (points >= 0) 1 else 0, preview = preview) }
            } else {
                val points = Vernier.measure(handle, frame.luma, frame.width, frame.height, frame.width)
                var kept = _state.value.kept
                if (points >= 0 && kept < MAX_VIEWS && frame.timestampNs - lastKeepNs >= KEEP_EVERY_NS) {
                    val now = Vernier.keep(handle)
                    if (now > kept) {
                        kept = now
                        lastKeepNs = frame.timestampNs
                    }
                }
                val preview = preview(frame)
                _state.update { it.copy(points = points, kept = kept, preview = preview) }
            }
            if (++measured % 30 == 0) Log.d(TAG, "frame: ${(System.nanoTime() - started) / 1_000_000} ms")
        } finally {
            busy.set(false)
        }
    }

    private fun saveCalibration(json: JSONObject): CalibrationResult {
        val factory = rig.factoryIntrinsics(size!!)
        json.put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
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
        return result(json, write(json), saved = false)
    }

    private fun saveAlignment(json: JSONObject): AlignmentResult {
        val q = json.getJSONArray("imu_to_camera_xyzw").let { a -> List(4) { a.getDouble(it) } }
        // Android's pose: the camera's rotation from the phone's sensor axes, x, y, z, w.
        val factory = rig.characteristics?.get(CameraCharacteristics.LENS_POSE_ROTATION)
        val vsFactory = factory?.let { f ->
            val dot = abs(q.zip(f.map(Float::toDouble)).sumOf { (a, b) -> a * b })
            Math.toDegrees(2 * acos(min(1.0, dot)))
        }
        json.put("factory_pose_rotation_xyzw", factory?.let { JSONArray(it.map(Float::toDouble)) })
        val calibration = JSONObject(savedFile.readText()).put("imu", json)
        return AlignmentResult(
            imuToCamera = q,
            timeOffsetMs = json.getDouble("time_offset_ms"),
            gyroBias = json.getJSONArray("gyro_bias").let { a -> List(3) { a.getDouble(it) } },
            offAxisDeg = json.getDouble("off_axis_deg"),
            vsFactoryDeg = vsFactory,
            rmsDeg = json.getDouble("rms_deg"),
            pairs = json.getInt("pairs"),
            axisCoverage = json.getDouble("axis_coverage"),
            savedTo = write(calibration),
        )
    }

    /** Keeps [json] for the app, and a dated copy where `adb pull` can reach it; returns the copy. */
    private fun write(json: JSONObject): File {
        savedFile.apply { parentFile?.mkdirs() }.writeText(json.toString(2))
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        return File(context.getExternalFilesDir("vision"), "$stamp-$calibrationName").apply { writeText(json.toString(2)) }
    }

    private fun result(json: JSONObject, file: File, saved: Boolean): CalibrationResult {
        val camera = json.getJSONObject("camera")
        val distortion = camera.getJSONArray("distortion")
        val byModel = json.optJSONObject("rms_by_model")
        return CalibrationResult(
            fx = camera.getDouble("fx"), fy = camera.getDouble("fy"),
            cx = camera.getDouble("cx"), cy = camera.getDouble("cy"),
            distortion = List(distortion.length()) { distortion.getDouble(it) },
            model = camera.getString("model"),
            rms = json.getDouble("rms"),
            rmsByModel = byModel?.keys()?.asSequence()?.associateWith { byModel.getDouble(it) } ?: emptyMap(),
            views = json.getInt("views"),
            factory = rig.factoryIntrinsics(size!!),
            saved = saved,
            savedTo = file,
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
        const val MAX_VIEWS = 40
        const val MIN_VIEWS = 10
        /** Poses before the alignment is worth trying; more is better. */
        const val MIN_POSES = 25
        private const val KEEP_EVERY_NS = 700_000_000L
        private const val PREVIEW_STEP = 4
        private const val GYRO_BATCH = 100
        /** The board is a lit screen, brighter than the room around it. */
        private const val BOARD_EXPOSURE_EV = -1.0
    }
}
