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

    /**
     * How the [second] session's lens sits relative to the [first]'s, from frames where both read
     * the board at the same timestamp; both must have been [finish]ed. JSON: `{second_from_first_xyzw,
     * translation_squares, pairs, rotation_sd_deg, translation_sd_squares}` or `{error}`.
     */
    @JvmStatic external fun stereo(first: Long, second: Long): String

    /** [luma] (`width × height`, rows packed) averaged over [bin]×[bin] blocks; null on a short buffer. */
    @JvmStatic external fun binLuma(luma: ByteArray, width: Int, height: Int, bin: Int): ByteArray?

    // Live tracking with Basalt, loaded at run time (libbasalt.so, from vision/basalt-android).

    /** Starts Basalt with a stereo calibration, writing its files into [dir]: `{handle}` or `{error}`. */
    @JvmStatic external fun trackerStart(calibrationJson: String, dir: String): String

    /** [count] IMU readings: [times] in ns, [values] as `ax ay az wx wy wz` each (m/s², rad/s). */
    @JvmStatic external fun trackerPushImu(handle: Long, times: LongArray, values: FloatArray, count: Int)

    /** A stereo pair from one capture, [timestampNs] its mid-exposure on the camera clock; false if dropped. */
    @JvmStatic external fun trackerPushFrames(
        handle: Long, timestampNs: Long,
        luma0: ByteArray, width0: Int, height0: Int,
        luma1: ByteArray, width1: Int, height1: Int,
    ): Boolean

    /** Poses since the last poll, `t_ns px py pz qx qy qz qw` each, metres in a gravity-aligned world. */
    @JvmStatic external fun trackerPoll(handle: Long): DoubleArray

    /** Stops Basalt and frees the tracker; [handle] is then invalid. */
    @JvmStatic external fun trackerFree(handle: Long)
}
