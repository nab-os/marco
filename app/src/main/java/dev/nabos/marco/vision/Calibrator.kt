package dev.nabos.marco.vision

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
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
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

data class CalibrationState(
    val running: Boolean = false,
    val cameraLabel: String = "",
    /** Board points in the last frame measured, -1 if its code wasn't read. */
    val points: Int = -1,
    val kept: Int = 0,
    val preview: ImageBitmap? = null,
    val calibrating: Boolean = false,
    val result: CalibrationResult? = null,
    val error: String? = null,
)

data class CalibrationResult(
    val fx: Double, val fy: Double, val cx: Double, val cy: Double,
    val distortion: List<Double>,
    val model: String,
    val rms: Double,
    val views: Int,
    val factory: FactoryIntrinsics?,
    val savedTo: File,
)

/**
 * Calibrates this phone's [CameraRig] camera from frames of the coded
 * checkerboard shown on the other phone's screen.
 *
 * Frames are measured on one worker thread as fast as it manages, dropping
 * the rest; at most one view is kept every [KEEP_EVERY_NS], so the kept views
 * come from different poses as you move.
 */
class Calibrator(private val context: Context) {

    private val rig = CameraRig(context)
    private val worker = Executors.newSingleThreadExecutor()
    private val busy = AtomicBoolean(false)
    private var handle = 0L // only touched on [worker]
    private var lastKeepNs = 0L
    private var size: Size? = null
    private var fisheye = false
    private var measured = 0

    private val _state = MutableStateFlow(CalibrationState())
    val state: StateFlow<CalibrationState> = _state.asStateFlow()

    fun start() {
        if (_state.value.running) return
        if (!Vernier.available) return fail("This build has no Vernier (build with marco.vision=true).")
        val s = rig.outputSize(CAPTURE_WIDTH) ?: return fail("No 4:3 camera stream up to $CAPTURE_WIDTH px wide.")
        size = s
        // Past ~90° across, a pinhole model can't follow the distortion.
        val fov = rig.horizontalFovDeg()
        fisheye = (fov ?: 0.0) > FISHEYE_ABOVE_DEG
        worker.execute {
            handle = Vernier.newSession(true)
            lastKeepNs = 0
        }
        _state.value = CalibrationState(
            running = true,
            cameraLabel = "camera ${rig.label} · ${s.width}×${s.height}" +
                (fov?.let { " · %.0f° wide".format(it) } ?: "") + if (fisheye) " · fisheye" else " · pinhole",
        )
        rig.start(
            s,
            onFrame = { frame ->
                if (busy.compareAndSet(false, true)) worker.execute { process(frame) }
            },
            onError = ::fail,
            exposureEv = BOARD_EXPOSURE_EV,
        )
    }

    fun stop() {
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
            val json = if (handle != 0L) JSONObject(Vernier.calibrate(handle, fisheye)) else JSONObject().put("error", "not running")
            if (json.has("error")) {
                _state.update { it.copy(calibrating = false, error = json.getString("error")) }
                return@execute
            }
            val result = runCatching { save(json) }
            _state.update {
                it.copy(calibrating = false, result = result.getOrNull(), error = result.exceptionOrNull()?.message)
            }
        }
    }

    private fun process(frame: LumaFrame) {
        try {
            if (handle == 0L) return
            val started = System.nanoTime()
            val points = Vernier.measure(handle, frame.luma, frame.width, frame.height, frame.width)
            if (++measured % 30 == 0) Log.d(TAG, "measure: ${(System.nanoTime() - started) / 1_000_000} ms, $points points")
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
        } finally {
            busy.set(false)
        }
    }

    private fun save(json: JSONObject): CalibrationResult {
        val camera = json.getJSONObject("camera")
        val s = size!!
        val factory = rig.factoryIntrinsics(s)
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
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val name = "camera-${rig.label.replace('/', '-')}-${s.width}x${s.height}.json"
        // Kept for the app, and a dated copy where `adb pull` can reach it.
        File(context.filesDir, "vision").apply { mkdirs() }.resolve(name).writeText(json.toString(2))
        val pullable = File(context.getExternalFilesDir("vision"), "$stamp-$name")
        pullable.writeText(json.toString(2))
        val distortion = camera.getJSONArray("distortion")
        return CalibrationResult(
            fx = camera.getDouble("fx"), fy = camera.getDouble("fy"),
            cx = camera.getDouble("cx"), cy = camera.getDouble("cy"),
            distortion = List(distortion.length()) { distortion.getDouble(it) },
            model = camera.getString("model"),
            rms = json.getDouble("rms"),
            views = json.getInt("views"),
            factory = factory,
            savedTo = pullable,
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
        private const val KEEP_EVERY_NS = 700_000_000L
        private const val FISHEYE_ABOVE_DEG = 90.0
        private const val PREVIEW_STEP = 4
        /** The board is a lit screen, brighter than the room around it. */
        private const val BOARD_EXPOSURE_EV = -1.0
    }
}
