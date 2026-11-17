package dev.nabos.marco.vision

/**
 * The native Vernier bridge (`vision/` at the repo root). Only in builds made
 * with `marco.vision=true` in local.properties: check [available] before
 * calling anything else.
 *
 * A session handle must be used from one thread at a time and freed once.
 */
object Vernier {
    val available: Boolean = runCatching { System.loadLibrary("marco_vision") }.isSuccess

    /** The coded checkerboard as 8-bit sRGB grey pixels, row-major; null on bad sizes. */
    @JvmStatic external fun renderBoard(width: Int, height: Int, squarePx: Double): ByteArray?

    /** [srgb]: frames are sRGB-encoded, as phone cameras' YUV luma is. */
    @JvmStatic external fun newSession(srgb: Boolean): Long

    @JvmStatic external fun freeSession(handle: Long)

    /** Board points found in one luma frame, or -1 if the board's code wasn't read. */
    @JvmStatic external fun measure(handle: Long, luma: ByteArray, width: Int, height: Int, stride: Int): Int

    /** Keeps the last measured frame if it was good enough; returns views kept so far. */
    @JvmStatic external fun keep(handle: Long): Int

    /**
     * Fits every lens model to the kept views and keeps the best, which [track] then uses. JSON:
     * `{camera: {model, width, height, fx, fy, cx, cy, distortion}, rms, views, points, rms_by_model}` or `{error}`.
     */
    @JvmStatic external fun calibrate(handle: Long): String

    /** Uses a `camera` object saved from an earlier [calibrate] for [track]; false if it doesn't parse. */
    @JvmStatic external fun setCamera(handle: Long, cameraJson: String): Boolean

    /**
     * Measures a frame and records the camera's pose for the alignment, stamped from the frame's
     * sensor timestamp, exposure and rolling-shutter skew. Board points, or -1 if no pose came out.
     */
    @JvmStatic external fun track(
        handle: Long, luma: ByteArray, width: Int, height: Int, stride: Int,
        timestampNs: Long, exposureNs: Long, rollingShutterSkewNs: Long,
    ): Int

    /** [count] gyro readings: [times] in ns, [rates] as x, y, z triples in rad/s. Returns poses so far. */
    @JvmStatic external fun addGyro(handle: Long, times: LongArray, rates: FloatArray, count: Int): Int

    /** Forgets the poses and gyro readings recorded for the alignment. */
    @JvmStatic external fun resetAlignment(handle: Long)

    /**
     * Camera-to-gyroscope alignment. JSON: `{imu_to_camera_xyzw, time_offset_ms, gyro_bias,
     * off_axis_deg, rms_deg, pairs, axis_coverage}` or `{error}`.
     */
    @JvmStatic external fun align(handle: Long): String
}
