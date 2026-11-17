package dev.nabos.marco.lab

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.Executor
import kotlin.math.abs

data class StereoState(
    val lenses: String = "",
    val supported: String = "",
    /** Frames received per lens. */
    val counts: List<Int> = listOf(0, 0),
    val fps: List<Double> = listOf(0.0, 0.0),
    /** Median time from each frame of the first lens to the nearest frame of the second, ms. */
    val medianGapMs: Double? = null,
    val maxGapMs: Double? = null,
    val previews: List<ImageBitmap?> = listOf(null, null),
    val error: String? = null,
)

/**
 * Debug: can this phone stream two of its back lenses at once (for stereo
 * tracking with Basalt), and how close in time are their frames? Opens the
 * logical multi-camera with one stream from each of its two widest lenses.
 */
@SuppressLint("MissingPermission")
class StereoProbe(context: Context) {
    private val manager = context.getSystemService(CameraManager::class.java)
    private var thread: HandlerThread? = null
    private var device: CameraDevice? = null
    private val readers = mutableListOf<ImageReader>()
    private val stamps = listOf(mutableListOf<Long>(), mutableListOf<Long>())

    private val _state = MutableStateFlow(StereoState())
    val state: StateFlow<StereoState> = _state.asStateFlow()

    fun start() {
        val (logical, lenses) = pickLenses() ?: return fail("No back logical multi-camera with two YUV lenses.")
        val size = Size(1280, 960)
        _state.update { it.copy(lenses = "camera $logical: lenses ${lenses.joinToString(" + ")} at ${size.width}×${size.height}") }
        val t = HandlerThread("marco-stereo").also { it.start() }
        thread = t
        val handler = Handler(t.looper)
        val executor = Executor { handler.post(it) }
        lenses.forEachIndexed { index, _ ->
            val reader = ImageReader.newInstance(size.width, size.height, ImageFormat.YUV_420_888, 4)
            reader.setOnImageAvailableListener({ r ->
                val image = r.acquireLatestImage() ?: return@setOnImageAvailableListener
                try {
                    onFrame(index, image.timestamp, image.planes[0].buffer.let { b -> ByteArray(b.remaining()).also { b.get(it) } }, image.planes[0].rowStride, image.width, image.height)
                } finally {
                    image.close()
                }
            }, handler)
            readers += reader
        }
        manager.openCamera(logical, executor, object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                device = camera
                val outputs = readers.mapIndexed { i, r -> OutputConfiguration(r.surface).apply { setPhysicalCameraId(lenses[i]) } }
                val config = SessionConfiguration(SessionConfiguration.SESSION_REGULAR, outputs, executor, object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD, lenses.toSet()).apply {
                            readers.forEach { addTarget(it.surface) }
                        }
                        session.setRepeatingRequest(request.build(), null, handler)
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) = fail("The phone refused to stream both lenses at once.")
                })
                val supported = runCatching { @Suppress("DEPRECATION") camera.isSessionConfigurationSupported(config) }
                _state.update { it.copy(supported = supported.fold({ s -> if (s) "supported" else "reported unsupported" }, { e -> "can't tell (${e.message})" })) }
                camera.createCaptureSession(config)
            }

            override fun onDisconnected(camera: CameraDevice) = camera.close()
            override fun onError(camera: CameraDevice, error: Int) {
                camera.close()
                fail("Camera error $error")
            }
        })
    }

    fun stop() {
        runCatching { device?.close() }
        readers.forEach { runCatching { it.close() } }
        readers.clear()
        thread?.let { it.quitSafely(); it.join(1_000) }
        thread = null
    }

    private fun onFrame(lens: Int, timestamp: Long, luma: ByteArray, stride: Int, width: Int, height: Int) {
        stamps[lens] += timestamp
        val counts = stamps.map { it.size }
        val fps = stamps.map { s -> if (s.size > 1) (s.size - 1) / ((s.last() - s.first()) / 1e9) else 0.0 }
        // For each frame of lens 0, the nearest frame of lens 1.
        val recent = stamps[1].takeLast(100)
        val gaps = stamps[0].takeLast(100).mapNotNull { t -> recent.minByOrNull { abs(it - t) }?.let { abs(it - t) / 1e6 } }.sorted()
        val preview = if (counts[lens] % 10 == 1) preview(luma, stride, width, height) else null
        _state.update {
            it.copy(
                counts = counts, fps = fps,
                medianGapMs = gaps.getOrNull(gaps.size / 2), maxGapMs = gaps.lastOrNull(),
                previews = if (preview != null) it.previews.toMutableList().also { p -> p[lens] = preview } else it.previews,
            )
        }
    }

    /** The logical back camera and its two widest lenses that stream YUV. */
    private fun pickLenses(): Pair<String, List<String>>? {
        for (id in manager.cameraIdList) {
            val c = manager.getCameraCharacteristics(id)
            if (c.get(CameraCharacteristics.LENS_FACING) != CameraCharacteristics.LENS_FACING_BACK) continue
            val caps = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: continue
            if (CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA !in caps) continue
            val lenses = c.physicalCameraIds
                .map { it to manager.getCameraCharacteristics(it) }
                .filter { (_, lc) -> !lc.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)?.getOutputSizes(ImageFormat.YUV_420_888).isNullOrEmpty() }
                .sortedBy { (_, lc) -> lc.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.minOrNull() ?: Float.MAX_VALUE }
                .distinctBy { (_, lc) -> lc.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.minOrNull() }
                .take(2)
                .map { it.first }
            if (lenses.size == 2) return id to lenses
        }
        return null
    }

    private fun preview(luma: ByteArray, stride: Int, width: Int, height: Int): ImageBitmap {
        val step = 8
        val w = width / step
        val h = height / step
        val pixels = IntArray(w * h) { i ->
            val g = luma[(i / w) * step * stride + (i % w) * step].toInt() and 0xFF
            (0xFF shl 24) or (g shl 16) or (g shl 8) or g
        }
        return Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888).asImageBitmap()
    }

    private fun fail(message: String) = _state.update { it.copy(error = message) }
}
