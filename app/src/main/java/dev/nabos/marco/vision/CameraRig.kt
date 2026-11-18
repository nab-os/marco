package dev.nabos.marco.vision

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size
import java.util.concurrent.Executor
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Motion sensor period, µs: 200 Hz, the fastest Android 12+ allows without the
 * HIGH_SAMPLING_RATE_SENSORS permission (SENSOR_DELAY_FASTEST throws without it).
 */
const val IMU_PERIOD_US = 5_000

/** One grey frame, rows packed (`width` bytes each), [timestampNs] on the elapsedRealtime clock. */
class LumaFrame(val luma: ByteArray, val width: Int, val height: Int, val timestampNs: Long)

/** Per-frame capture settings, matched to frames by [timestampNs]. */
data class FrameInfo(
    val timestampNs: Long,
    val exposureNs: Long?,
    val rollingShutterSkewNs: Long?,
    val frameDurationNs: Long?,
    val focusDiopters: Float?,
)

/**
 * A back camera set up for measuring rather than for pretty pictures: no
 * optical or digital stabilisation, no distortion correction, no sharpening,
 * focus fixed. Those all move or warp the image behind a calibration's back.
 *
 * Prefers the widest camera without OIS (usually the ultrawide, which is also
 * fixed-focus): a wide view keeps more of the room in sight while tracking.
 */
@SuppressLint("MissingPermission") // Callers check CAMERA first.
class CameraRig(context: Context) {

    private val manager = context.getSystemService(CameraManager::class.java)

    private val choice: Choice? = chooseCamera()

    /** The camera to open: a logical multi-camera when [physicalId] picks one of its lenses. */
    val cameraId: String? = choice?.openId

    /** The lens streamed from inside [cameraId] (Pixels hide the ultrawide there), or null for [cameraId] itself. */
    val physicalId: String? = choice?.physicalId

    /** Of the lens actually streamed. */
    val characteristics: CameraCharacteristics? = choice?.characteristics

    /** Clockwise turn that shows the sensor's frames upright on a portrait screen (90 on most phones). */
    val sensorOrientation: Int = characteristics?.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0

    /** `0`, or `0/2` for lens 2 of logical camera 0. */
    val label: String get() = listOfNotNull(cameraId, physicalId).joinToString("/")

    /**
     * Another lens of the same logical camera that can stream alongside [physicalId], for stereo:
     * the widest of the others. Null if the camera has no other lens.
     */
    val secondId: String? = choice?.takeIf { it.physicalId != null }?.let { chosen ->
        val logical = manager.getCameraCharacteristics(chosen.openId)
        logical.physicalCameraIds
            .filter { it != chosen.physicalId }
            .map { it to manager.getCameraCharacteristics(it) }
            .filter { (_, c) -> !c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)?.getOutputSizes(ImageFormat.YUV_420_888).isNullOrEmpty() }
            .minByOrNull { (_, c) -> c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.minOrNull() ?: Float.MAX_VALUE }
            ?.first
    }
    val secondCharacteristics: CameraCharacteristics? = secondId?.let { manager.getCameraCharacteristics(it) }
    val secondLabel: String? get() = secondId?.let { "$cameraId/$it" }

    private var thread: HandlerThread? = null
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private val readers = mutableListOf<ImageReader>()

    /** Picks the size to capture at: the largest of [aspect] no wider than [maxWidth]. */
    fun outputSize(maxWidth: Int, aspect: Double = 4.0 / 3.0): Size? = sizeOf(characteristics, maxWidth, aspect)

    /** [outputSize] for the [secondId] lens. */
    fun secondOutputSize(maxWidth: Int, aspect: Double = 4.0 / 3.0): Size? = sizeOf(secondCharacteristics, maxWidth, aspect)

    private fun sizeOf(chars: CameraCharacteristics?, maxWidth: Int, aspect: Double): Size? {
        val sizes = chars?.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?.getOutputSizes(ImageFormat.YUV_420_888) ?: return null
        return sizes.filter { it.width <= maxWidth && abs(it.width.toDouble() / it.height - aspect) < 0.01 }
            .maxByOrNull { it.width }
    }

    /** Horizontal field of view in degrees, from the factory focal length; null if unknown. */
    fun horizontalFovDeg(): Double? {
        val k = characteristics?.get(CameraCharacteristics.LENS_INTRINSIC_CALIBRATION) ?: return null
        val pre = characteristics.get(CameraCharacteristics.SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE) ?: return null
        if (k[0] <= 0f) return null
        return Math.toDegrees(2 * atan(pre.width() / 2.0 / k[0]))
    }

    /**
     * Starts streaming [size] frames. [onFrame] and [onInfo] run on the camera's
     * own thread; [onError] too, with a message.
     */
    fun start(
        size: Size,
        onFrame: (LumaFrame) -> Unit,
        onInfo: (FrameInfo) -> Unit = {},
        onError: (String) -> Unit,
        exposureEv: Double = 0.0,
    ) = startLenses(listOf(size), { _, frame -> onFrame(frame) }, { _, info -> onInfo(info) }, onError, exposureEv)

    /**
     * Streams [physicalId] at `sizes[0]` and, if a second size is given, [secondId] at `sizes[1]`,
     * in one session: frames of both lenses from the same capture share a timestamp. Callbacks get
     * the lens index (0 or 1) and run on the camera's own thread.
     */
    fun startLenses(
        sizes: List<Size>,
        onFrame: (Int, LumaFrame) -> Unit,
        onInfo: (Int, FrameInfo) -> Unit = { _, _ -> },
        onError: (String) -> Unit,
        exposureEv: Double = 0.0,
    ) {
        val id = cameraId ?: return onError("No usable back camera")
        val lenses = listOf(physicalId, secondId).take(sizes.size)
        val chars = listOf(characteristics!!, secondCharacteristics).take(sizes.size)
        if (sizes.size > 1 && (lenses[1] == null || chars[1] == null)) return onError("This camera has no second lens")
        val t = HandlerThread("marco-camera").also { it.start() }
        thread = t
        val handler = Handler(t.looper)
        val executor = Executor { handler.post(it) }

        sizes.forEachIndexed { index, size ->
            val r = ImageReader.newInstance(size.width, size.height, ImageFormat.YUV_420_888, 3)
            readers += r
            r.setOnImageAvailableListener({ ir ->
                val image = ir.acquireLatestImage() ?: return@setOnImageAvailableListener
                try {
                    val plane = image.planes[0]
                    val w = image.width
                    val h = image.height
                    val luma = ByteArray(w * h)
                    val buffer = plane.buffer
                    val stride = plane.rowStride
                    for (row in 0 until h) {
                        buffer.position(row * stride)
                        buffer.get(luma, row * w, w)
                    }
                    onFrame(index, LumaFrame(luma, w, h, image.timestamp))
                } finally {
                    image.close()
                }
            }, handler)
        }

        // Throws, rather than calling back, when the camera is refused outright (from the background, say).
        runCatching { openLenses(id, lenses, chars, executor, handler, exposureEv, onInfo, onError) }
            .onFailure { onError("Camera unavailable: ${it.message}") }
    }

    private fun openLenses(
        id: String,
        lenses: List<String?>,
        chars: List<CameraCharacteristics?>,
        executor: Executor,
        handler: Handler,
        exposureEv: Double,
        onInfo: (Int, FrameInfo) -> Unit,
        onError: (String) -> Unit,
    ) {
        manager.openCamera(id, executor, object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                device = camera
                val physical = lenses.filterNotNull().toSet()
                val request = (
                    if (physical.isNotEmpty()) camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD, physical)
                    else camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
                    ).apply {
                    readers.forEach { addTarget(it.surface) }
                    lenses.forEachIndexed { i, lens -> configureForMeasuring(chars[i]!!, lens, exposureEv) }
                }
                val outputs = readers.mapIndexed { i, r -> OutputConfiguration(r.surface).apply { lenses[i]?.let { setPhysicalCameraId(it) } } }
                val config = SessionConfiguration(
                    SessionConfiguration.SESSION_REGULAR,
                    outputs,
                    executor,
                    object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(s: CameraCaptureSession) {
                            session = s
                            s.setRepeatingRequest(request.build(), object : CameraCaptureSession.CaptureCallback() {
                                override fun onCaptureCompleted(s: CameraCaptureSession, req: CaptureRequest, total: TotalCaptureResult) {
                                    lenses.forEachIndexed { i, lens ->
                                        val result = lens?.let { total.physicalCameraTotalResults[it] } ?: total
                                        onInfo(
                                            i,
                                            FrameInfo(
                                                timestampNs = result.get(CaptureResult.SENSOR_TIMESTAMP) ?: return@forEachIndexed,
                                                exposureNs = result.get(CaptureResult.SENSOR_EXPOSURE_TIME),
                                                rollingShutterSkewNs = result.get(CaptureResult.SENSOR_ROLLING_SHUTTER_SKEW),
                                                frameDurationNs = result.get(CaptureResult.SENSOR_FRAME_DURATION),
                                                focusDiopters = result.get(CaptureResult.LENS_FOCUS_DISTANCE),
                                            ),
                                        )
                                    }
                                }
                            }, handler)
                        }

                        override fun onConfigureFailed(s: CameraCaptureSession) = onError(
                            when {
                                lenses.size > 1 -> "This phone can't stream camera $label and its second lens together"
                                lenses[0] != null -> "Camera $label can't stream on its own on this phone"
                                else -> "Camera session failed"
                            },
                        )
                    },
                )
                camera.createCaptureSession(config)
            }

            override fun onDisconnected(camera: CameraDevice) {
                camera.close()
                onError("Camera disconnected")
            }

            override fun onError(camera: CameraDevice, error: Int) {
                camera.close()
                onError(
                    when (error) {
                        CameraDevice.StateCallback.ERROR_CAMERA_IN_USE, CameraDevice.StateCallback.ERROR_MAX_CAMERAS_IN_USE -> "Another app is using the camera."
                        CameraDevice.StateCallback.ERROR_CAMERA_DISABLED -> "The camera was taken away (the app went to the background, or a policy forbids it)."
                        else -> "Camera error $error"
                    },
                )
            }
        })
    }

    fun stop() {
        runCatching { session?.close() }
        runCatching { device?.close() }
        readers.forEach { runCatching { it.close() } }
        // Let callbacks already queued finish, so callers can close what they write to.
        thread?.let { it.quitSafely(); it.join(1_000) }
        session = null
        device = null
        readers.clear()
        thread = null
    }

    /** [chars] are the streamed lens's; with [lens], lens settings go to that physical camera too. */
    private fun CaptureRequest.Builder.configureForMeasuring(chars: CameraCharacteristics, lens: String?, exposureEv: Double) {
        fun <T> put(key: CaptureRequest.Key<T>, value: T) {
            set(key, value)
            if (lens != null) setPhysicalCameraKey(key, value, lens)
        }
        set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
        set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF)
        if (CameraCharacteristics.LENS_OPTICAL_STABILIZATION_MODE_OFF in chars.ints(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)) {
            put(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF)
        }
        if (CameraCharacteristics.DISTORTION_CORRECTION_MODE_OFF in chars.ints(CameraCharacteristics.DISTORTION_CORRECTION_AVAILABLE_MODES)) {
            put(CaptureRequest.DISTORTION_CORRECTION_MODE, CaptureRequest.DISTORTION_CORRECTION_MODE_OFF)
        }
        if (CameraCharacteristics.EDGE_MODE_OFF in chars.ints(CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES)) {
            put(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_OFF)
        }
        // Focus moves the lens, which changes the focal length a little: fix it.
        val minFocus = chars.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
        if (minFocus > 0f) {
            put(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
            put(CaptureRequest.LENS_FOCUS_DISTANCE, chars.get(CameraCharacteristics.LENS_INFO_HYPERFOCAL_DISTANCE) ?: 0f)
        }
        // Steady 30 fps: short exposures blur less while waving the phone.
        chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
            ?.firstOrNull { it.lower == 30 && it.upper == 30 }
            ?.let { set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it) }
        // Auto-exposure meters the whole view; a lit screen in a dim room needs less.
        val step = chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)?.toDouble() ?: 0.0
        val range = chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)
        if (exposureEv != 0.0 && step > 0 && range != null) {
            set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, (exposureEv / step).roundToInt().coerceIn(range.lower, range.upper))
        }
    }

    private class Choice(
        val openId: String,
        val physicalId: String?,
        val characteristics: CameraCharacteristics,
        val ois: Boolean,
        val focalMm: Float,
    )

    private fun chooseCamera(): Choice? {
        fun choice(openId: String, physicalId: String?, c: CameraCharacteristics): Choice {
            val ois = c.ints(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)
                .any { it != CameraCharacteristics.LENS_OPTICAL_STABILIZATION_MODE_OFF }
            val focal = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.minOrNull() ?: Float.MAX_VALUE
            Log.i(TAG, "camera ${listOfNotNull(openId, physicalId).joinToString("/")}: focal $focal mm, OIS $ois")
            return Choice(openId, physicalId, c, ois, focal)
        }
        val candidates = mutableListOf<Choice>()
        for (id in manager.cameraIdList) {
            val c = manager.getCameraCharacteristics(id)
            if (c.get(CameraCharacteristics.LENS_FACING) != CameraCharacteristics.LENS_FACING_BACK) continue
            val caps = c.ints(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
            if (CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE !in caps) continue
            candidates += choice(id, null, c)
            if (CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA !in caps) continue
            for (lens in c.physicalCameraIds) {
                val lc = manager.getCameraCharacteristics(lens)
                val yuv = lc.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)?.getOutputSizes(ImageFormat.YUV_420_888)
                if (!yuv.isNullOrEmpty()) candidates += choice(id, lens, lc)
            }
        }
        // Widest first among those without OIS; failing that, the widest at all.
        return candidates.filter { !it.ois }.minByOrNull { it.focalMm } ?: candidates.minByOrNull { it.focalMm }
    }

    private fun CameraCharacteristics.ints(key: CameraCharacteristics.Key<IntArray>): IntArray = get(key) ?: IntArray(0)

    /** Factory intrinsics mapped onto a [size] stream, for comparing with a calibration. */
    fun factoryIntrinsics(size: Size, chars: CameraCharacteristics? = characteristics): FactoryIntrinsics? {
        chars ?: return null
        val k = chars.get(CameraCharacteristics.LENS_INTRINSIC_CALIBRATION) ?: return null
        val pre = chars.get(CameraCharacteristics.SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE) ?: return null
        if (k[0] <= 0f) return null
        // The stream is the pre-correction array scaled to cover the output, centred.
        val scale = max(size.width.toDouble() / pre.width(), size.height.toDouble() / pre.height())
        return FactoryIntrinsics(
            fx = k[0] * scale,
            fy = k[1] * scale,
            cx = (k[2] - pre.width() / 2.0) * scale + size.width / 2.0,
            cy = (k[3] - pre.height() / 2.0) * scale + size.height / 2.0,
            distortion = chars.get(CameraCharacteristics.LENS_DISTORTION)?.map { it.toDouble() } ?: emptyList(),
        )
    }

    companion object {
        private const val TAG = "Marco.Camera"
    }
}

/** Android's factory calibration; [distortion] is Android's `k1, k2, k3, p1, p2`. */
data class FactoryIntrinsics(val fx: Double, val fy: Double, val cx: Double, val cy: Double, val distortion: List<Double>)
