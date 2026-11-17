package dev.nabos.marco.vision

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.camera2.CameraCharacteristics
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Size
import dev.nabos.marco.finder.RssiSample
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.PrintWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

data class RecorderStats(
    val recording: Boolean = false,
    val frames: Int = 0,
    /** Captures dropped because writing fell behind. */
    val dropped: Int = 0,
    val imu: Int = 0,
    val rssi: Int = 0,
    val dir: File? = null,
    val error: String? = null,
)

/**
 * Debug recorder for tuning the signal map offline: camera frames, motion
 * sensors and RSSI, all stamped on the elapsedRealtime clock, into
 * `Android/data/dev.nabos.marco/files/recordings/<date>/`:
 *
 * - `frames.y8`: raw 8-bit luma, frames back to back, size in `meta.json`: captured at
 *   1280×960 and averaged over 2×2 blocks, the geometry [Calibrator] calibrates.
 * - `frames.csv`: `index,timestamp_ns` per frame in `frames.y8`.
 * - `capture.csv`: `timestamp_ns,exposure_ns,rolling_shutter_skew_ns,frame_duration_ns,focus_diopters`.
 * - `imu.csv`: `timestamp_ns,sensor,x,y,z` with sensor `g` (gyro, rad/s) or `a` (accelerometer, m/s²).
 * - `rssi.csv`: `timestamp_ns,rssi_dbm,connection` (1: GATT connection, 0: advert).
 * - `meta.json`: device, camera factory calibration and pose, sensors.
 * - `calibration.json`: this camera's calibration and gyroscope alignment, if one was made.
 * - `frames1.y8`, `frames1.csv`, `capture1.csv`: the same for the camera's second lens, when it
 *   has one (captured at 640×480, binned to 320×240): with lens 0, a stereo pair for Basalt.
 */
class Recorder(private val context: Context) {

    private val rig = CameraRig(context)
    private val sensors = context.getSystemService(SensorManager::class.java)
    private var sensorThread: HandlerThread? = null
    /** Per lens. */
    private val frameOut = arrayOfNulls<BufferedOutputStream>(2)
    private val framesCsv = arrayOfNulls<PrintWriter>(2)
    private val captureCsv = arrayOfNulls<PrintWriter>(2)
    private val lensFrames = listOf(AtomicInteger(), AtomicInteger())
    /**
     * Bins and writes frames off the camera thread: binning a 1280×960 frame in Kotlin took
     * ~300 ms in a debug build, and on the camera thread that dropped 9 frames in 10.
     */
    private var writer: ExecutorService? = null
    private val pending = AtomicInteger()
    private val dropped = AtomicInteger()
    private var imuCsv: PrintWriter? = null
    private var rssiCsv: PrintWriter? = null
    private val frames = AtomicInteger()
    private val imu = AtomicInteger()
    private val rssi = AtomicInteger()
    private val jobs = mutableListOf<Job>()

    private val _stats = MutableStateFlow(RecorderStats())
    val stats: StateFlow<RecorderStats> = _stats.asStateFlow()

    fun start(scope: CoroutineScope, samples: Flow<RssiSample>? = null, captureWidth: Int = 1280, bin: Int = 2) {
        if (_stats.value.recording) return
        val capture = rig.outputSize(captureWidth) ?: return fail("No 4:3 camera stream up to $captureWidth px wide.")
        val captures = listOfNotNull(capture, rig.secondOutputSize(Calibrator.SECOND_CAPTURE_WIDTH))
        val size = Size(capture.width / bin, capture.height / bin)
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val dir = File(context.getExternalFilesDir("recordings"), stamp).apply { mkdirs() }
        frames.set(0); imu.set(0); rssi.set(0); dropped.set(0); pending.set(0)
        lensFrames.forEach { it.set(0) }
        val frameWriter = Executors.newSingleThreadExecutor().also { writer = it }

        File(dir, "meta.json").writeText(meta(size, capture, bin, captures.getOrNull(1)).toString(2))
        // The calibration for exactly these frames, if this phone has one: a recording then stands alone.
        File(context.filesDir, "vision/camera-${rig.label.replace('/', '-')}-${size.width}x${size.height}.json")
            .takeIf { it.exists() }?.copyTo(File(dir, "calibration.json"), overwrite = true)
        captures.indices.forEach { lens ->
            val suffix = if (lens == 0) "" else "$lens"
            frameOut[lens] = BufferedOutputStream(FileOutputStream(File(dir, "frames$suffix.y8")), 1 shl 20)
            framesCsv[lens] = writer(dir, "frames$suffix.csv", "index,timestamp_ns")
            captureCsv[lens] = writer(dir, "capture$suffix.csv", "timestamp_ns,exposure_ns,rolling_shutter_skew_ns,frame_duration_ns,focus_diopters")
        }
        imuCsv = writer(dir, "imu.csv", "timestamp_ns,sensor,x,y,z")

        // Camera callbacks all run on the rig's one thread, so these writers aren't shared.
        rig.startLenses(
            captures,
            onFrame = { lens, f ->
                // When writing falls behind, drop rather than stall the camera (the converter keeps only pairs both lenses wrote).
                if (pending.get() >= MAX_PENDING) {
                    if (lens == 0) dropped.incrementAndGet()
                    return@startLenses
                }
                pending.incrementAndGet()
                frameWriter.execute {
                    val binned = if (Vernier.available) Vernier.binLuma(f.luma, f.width, f.height, bin) else binDown(f, bin)
                    if (binned != null) {
                        frameOut[lens]?.write(binned)
                        framesCsv[lens]?.println("${lensFrames[lens].getAndIncrement()},${f.timestampNs}")
                        if (lens == 0) frames.incrementAndGet()
                    }
                    pending.decrementAndGet()
                }
            },
            onInfo = { lens, i ->
                captureCsv[lens]?.println("${i.timestampNs},${i.exposureNs ?: ""},${i.rollingShutterSkewNs ?: ""},${i.frameDurationNs ?: ""},${i.focusDiopters ?: ""}")
            },
            onError = ::fail,
        )

        val t = HandlerThread("marco-imu").also { it.start() }
        sensorThread = t
        val handler = Handler(t.looper)
        listOfNotNull(sensors.getDefaultSensor(Sensor.TYPE_GYROSCOPE), sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER))
            .forEach { sensors.registerListener(imuListener, it, IMU_PERIOD_US, handler) }

        if (samples != null) {
            rssiCsv = writer(dir, "rssi.csv", "timestamp_ns,rssi_dbm,connection")
            jobs += scope.launch {
                samples.collect { s ->
                    rssiCsv?.println("${s.atMs * 1_000_000},${s.rssi},${if (s.fromConnection) 1 else 0}")
                    rssi.incrementAndGet()
                }
            }
        }
        jobs += scope.launch {
            while (true) {
                _stats.update { it.copy(frames = frames.get(), dropped = dropped.get(), imu = imu.get(), rssi = rssi.get()) }
                delay(500)
            }
        }
        _stats.value = RecorderStats(recording = true, dir = dir)
    }

    fun stop() {
        if (!_stats.value.recording) return
        rig.stop()
        writer?.let { it.shutdown(); it.awaitTermination(5, TimeUnit.SECONDS) }
        writer = null
        sensors.unregisterListener(imuListener)
        sensorThread?.let { it.quitSafely(); it.join(1_000) }
        sensorThread = null
        jobs.forEach { it.cancel() }
        jobs.clear()
        // The camera, writer and sensor threads have finished: nothing writes to these any more.
        (frameOut.toList() + framesCsv + captureCsv + listOf(imuCsv, rssiCsv)).forEach { runCatching { it?.close() } }
        frameOut.fill(null); framesCsv.fill(null); captureCsv.fill(null); imuCsv = null; rssiCsv = null
        _stats.update { it.copy(recording = false, frames = frames.get(), dropped = dropped.get(), imu = imu.get(), rssi = rssi.get()) }
    }

    private val imuListener = object : SensorEventListener {
        override fun onSensorChanged(e: SensorEvent) {
            val tag = if (e.sensor.type == Sensor.TYPE_GYROSCOPE) 'g' else 'a'
            imuCsv?.println("${e.timestamp},$tag,${e.values[0]},${e.values[1]},${e.values[2]}")
            imu.incrementAndGet()
        }

        override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit
    }

    /** Averages [bin]×[bin] blocks of [frame]: the fallback for builds without Vernier. */
    private fun binDown(frame: LumaFrame, bin: Int): ByteArray {
        val width = frame.width / bin
        val height = frame.height / bin
        val out = ByteArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                var sum = 0
                for (dy in 0 until bin) {
                    val row = (y * bin + dy) * frame.width + x * bin
                    for (dx in 0 until bin) sum += frame.luma[row + dx].toInt() and 0xFF
                }
                out[y * width + x] = (sum / (bin * bin)).toByte()
            }
        }
        return out
    }

    private fun writer(dir: File, name: String, header: String) =
        PrintWriter(File(dir, name).bufferedWriter()).apply { println(header) }

    private fun fail(message: String) = _stats.update { it.copy(error = message) }

    private fun meta(size: Size, capture: Size, bin: Int, secondCapture: Size?): JSONObject {
        val c = rig.characteristics
        fun floats(key: CameraCharacteristics.Key<FloatArray>) = c?.get(key)?.let { JSONArray(it.map(Float::toDouble)) }
        val camera = JSONObject()
            .put("id", rig.cameraId)
            .put("physical_id", rig.physicalId)
            .put("width", size.width)
            .put("height", size.height)
            .put("capture", "${capture.width}x${capture.height}")
            .put("binning", bin)
            .put("intrinsic_calibration_fx_fy_cx_cy_s", floats(CameraCharacteristics.LENS_INTRINSIC_CALIBRATION))
            .put("distortion_k1k2k3p1p2", floats(CameraCharacteristics.LENS_DISTORTION))
            .put("pose_rotation_xyzw", floats(CameraCharacteristics.LENS_POSE_ROTATION))
            .put("pose_translation_m", floats(CameraCharacteristics.LENS_POSE_TRANSLATION))
            .put("pose_reference", c?.get(CameraCharacteristics.LENS_POSE_REFERENCE))
            .put("focal_lengths_mm", floats(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS))
            .put("hyperfocal_diopters", c?.get(CameraCharacteristics.LENS_INFO_HYPERFOCAL_DISTANCE)?.toDouble())
            .put("sensor_orientation_deg", c?.get(CameraCharacteristics.SENSOR_ORIENTATION))
            .put("timestamp_source", c?.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE))
            .put("pre_correction_active_array", c?.get(CameraCharacteristics.SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE)?.flattenToString())
            .put("active_array", c?.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)?.flattenToString())
            .put("factory_at_stream_size", rig.factoryIntrinsics(size)?.let {
                JSONObject().put("fx", it.fx).put("fy", it.fy).put("cx", it.cx).put("cy", it.cy)
            })
        val sensorInfo = JSONArray()
        listOfNotNull(sensors.getDefaultSensor(Sensor.TYPE_GYROSCOPE), sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)).forEach {
            sensorInfo.put(
                JSONObject().put("type", it.stringType).put("name", it.name).put("vendor", it.vendor)
                    .put("resolution", it.resolution.toDouble()).put("min_delay_us", it.minDelay)
            )
        }
        val display = context.resources.displayMetrics
        return JSONObject()
            .put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
            .put("android", Build.VERSION.SDK_INT)
            .put("started_elapsed_ns", SystemClock.elapsedRealtimeNanos())
            .put("started_unix_ms", System.currentTimeMillis())
            .put("frames_format", "y8")
            .put("camera", camera)
            .put("imu", sensorInfo)
            .put("second", secondCapture?.let {
                JSONObject().put("id", rig.secondId).put("width", it.width / bin).put("height", it.height / bin)
                    .put("capture", "${it.width}x${it.height}").put("binning", bin)
            })
            .put("display", JSONObject().put("width_px", display.widthPixels).put("height_px", display.heightPixels)
                .put("xdpi", display.xdpi.toDouble()).put("ydpi", display.ydpi.toDouble()))
    }

    private companion object {
        /** Frames waiting for the writer before captures get dropped: about a third of a second. */
        const val MAX_PENDING = 20
    }
}
