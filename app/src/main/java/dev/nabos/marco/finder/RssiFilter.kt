package dev.nabos.marco.finder

/**
 * 1-D Kalman filter on RSSI (dBm) with a random-walk model. Process noise scales
 * with the time between samples, so it behaves the same whether samples arrive
 * at 4 Hz (connection RSSI) or 1 Hz (advertisements).
 *
 * Indoors, raw RSSI jumps by ±5–10 dB from multipath and body shadowing; with
 * these defaults the estimate settles in about 1–2 s after you move.
 */
class RssiFilter(
    private val processNoisePerSec: Double = 6.0, // dB² per second the true value may drift
    private val measurementNoise: Double = 25.0,  // dB², i.e. σ ≈ 5 dB per sample
) {
    private var x = 0.0   // estimate
    private var p = 0.0   // estimate variance
    private var lastMs = 0L
    var initialized = false
        private set

    fun reset() {
        initialized = false
    }

    fun update(rssi: Int, nowMs: Long): Double {
        if (!initialized) {
            x = rssi.toDouble()
            p = measurementNoise
            lastMs = nowMs
            initialized = true
            return x
        }
        val dt = ((nowMs - lastMs).coerceIn(10, 5_000)) / 1000.0
        lastMs = nowMs
        p += processNoisePerSec * dt
        val k = p / (p + measurementNoise)
        x += k * (rssi - x)
        p *= (1 - k)
        return x
    }

    val value: Double get() = x
}
