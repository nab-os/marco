package dev.nabos.marco.vision

/**
 * The native Vernier bridge (`vision/` at the repo root). Only in builds made
 * with `marco.vision=true` in local.properties: check [available] before
 * calling anything else.
 *
 * A session is one pass over the board: frames go to [measure] (and [keep]),
 * gyroscope readings to [addGyro], and [finish] turns it all into a lens
 * calibration and a camera-to-gyroscope alignment. A session handle must be
 * used from one thread at a time and freed once.
 */
object Vernier {
    val available: Boolean = runCatching { System.loadLibrary("marco_vision") }.isSuccess

    /** The coded checkerboard as 8-bit sRGB grey pixels, row-major; null on bad sizes. */
    @JvmStatic external fun renderBoard(width: Int, height: Int, squarePx: Double): ByteArray?

    /**
     * [srgb]: frames are sRGB-encoded, as phone cameras' YUV luma is. Frames are averaged over
     * [bin]×[bin] blocks before measuring, and calibrations and poses are in those binned pixels.
     */
    @JvmStatic external fun newSession(srgb: Boolean, bin: Int): Long

    @JvmStatic external fun freeSession(handle: Long)

    /**
     * Measures a luma frame, stamped from its sensor timestamp, exposure and rolling-shutter skew.
     * Board points found, or -1 if the board's code wasn't read.
     */
    @JvmStatic external fun measure(
        handle: Long, luma: ByteArray, width: Int, height: Int, stride: Int,
        timestampNs: Long, exposureNs: Long, rollingShutterSkewNs: Long,
    ): Int

    /** Keeps the last measured frame for the lens if it shows the board in a new way; returns views kept. */
    @JvmStatic external fun keep(handle: Long): Int

    /**
     * `[views, poses, coverage ‰, ready, square px × 10, columns, rows, inset ‰, cell…]`; the square is
     * as big as the board's squares looked in the last frame, and cells are 1 where the board has been.
     */
    @JvmStatic external fun status(handle: Long): IntArray

    /** [count] gyro readings: [times] in ns, [rates] as x, y, z triples in rad/s. */
    @JvmStatic external fun addGyro(handle: Long, times: LongArray, rates: FloatArray, count: Int)

    /**
     * Calibrates the lens (the better of pinhole and fisheye), solves every frame's pose with it, and
     * aligns the poses with the gyroscope. JSON: `{camera, rms, views, points, rms_by_model, imu |
     * imu_error}` or `{error}` if the lens couldn't be calibrated.
     */
    @JvmStatic external fun finish(handle: Long): String
}
