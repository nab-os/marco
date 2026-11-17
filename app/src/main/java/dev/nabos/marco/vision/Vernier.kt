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

    /** JSON: `{camera: {model, width, height, fx, fy, cx, cy, distortion}, rms, views, points}` or `{error}`. */
    @JvmStatic external fun calibrate(handle: Long, fisheye: Boolean): String
}
