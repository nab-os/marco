package dev.nabos.marco.acoustic

import kotlin.math.max

/**
 * Finds the ranging chirps in a microphone stream: a matched filter (with the
 * chirp and its quadrature twin, for the envelope), then the first arrival of
 * each burst, which is the direct path (echoes come after it, through longer
 * ways round).
 *
 * Feed it samples as they come, in any block size; it reports each arrival as
 * a position in the stream, in samples from the first one fed, to a fraction
 * of a sample (one sample is 7 mm of sound).
 */
class ChirpDetector(
    private val template: FloatArray = Chirps.rangingChirp(),
    private val quadrature: FloatArray = Chirps.rangingChirpQuadrature(),
    private val sampleRate: Int = Chirps.SAMPLE_RATE,
) {

    data class Arrival(
        /** Position in the stream, samples. */
        val sample: Double,
        /** How far the burst stood above the background, dB. */
        val marginDb: Double,
        /** The matched filter's peak height, for what it's worth. */
        val strength: Double,
    )

    private val length = template.size
    /** The samples not yet correlated: the tail of the last block and the new one. */
    private var pending = FloatArray(0)
    /** Stream position of pending[0]. */
    private var pendingStart = 0L
    /** The matched filter's output since the last arrival was settled, with the stream position of its first value. */
    private var out = FloatArray(0)
    private var outStart = 0L
    private var produced = 0L
    /** Background level: a decimated history of the output over about a second. */
    private val history = FloatArray(sampleRate / DECIMATE)
    private var historyFilled = 0
    private var historyAt = 0
    private var lastArrival = Long.MIN_VALUE

    /** New samples, as they come. Returns the arrivals settled by them. */
    fun feed(samples: ShortArray, count: Int): List<Arrival> {
        val joined = FloatArray(pending.size + count)
        pending.copyInto(joined)
        for (i in 0 until count) joined[pending.size + i] = samples[i] / 32768f
        // Correlate every position whose whole window is in.
        val n = joined.size - length + 1
        if (n <= 0) {
            pending = joined
            return emptyList()
        }
        val corr = FloatArray(n)
        for (i in 0 until n) {
            var accI = 0f
            var accQ = 0f
            for (k in 0 until length) {
                val x = joined[i + k]
                accI += x * template[k]
                accQ += x * quadrature[k]
            }
            corr[i] = kotlin.math.sqrt(accI * accI + accQ * accQ)
        }
        // Keep the last length-1 samples for the next block.
        val keep = length - 1
        pending = joined.copyOfRange(joined.size - keep, joined.size)
        val corrStart = pendingStart
        pendingStart += n
        for (i in corr.indices step DECIMATE) {
            history[historyAt] = corr[i]
            historyAt = (historyAt + 1) % history.size
            if (historyFilled < history.size) historyFilled++
        }
        produced += n
        // Append to the output under consideration.
        val merged = FloatArray(out.size + n)
        out.copyInto(merged)
        corr.copyInto(merged, out.size)
        if (out.isEmpty()) outStart = corrStart
        out = merged
        return settle()
    }

    /** Arrivals whose burst window has fully arrived. */
    private fun settle(): List<Arrival> {
        if (historyFilled < history.size / 2) {
            trim()
            return emptyList()
        }
        val background = percentile(BACKGROUND_PERCENTILE)
        val threshold = max(background * THRESHOLD_RATIO, MIN_LEVEL)
        val arrivals = ArrayList<Arrival>()
        var i = 0
        val burst = sampleRate * BURST_MS / 1000
        while (i < out.size) {
            if (out[i] < threshold) {
                i++
                continue
            }
            // A burst starts: it needs the whole window to pick the first arrival from.
            val end = i + burst
            if (end > out.size) break
            var peak = i
            for (j in i until end) if (out[j] > out[peak]) peak = j
            // The first local maximum within FIRST_DB of the strongest: the direct path.
            val floor = out[peak] / FIRST_RATIO
            var first = peak
            for (j in i until peak) {
                if (out[j] >= floor && out[j] >= out[max(0, j - 1)] && out[j] >= out[j + 1]) {
                    first = j
                    break
                }
            }
            val at = outStart + first + refine(first)
            if (lastArrival == Long.MIN_VALUE || outStart + first - lastArrival >= sampleRate * MIN_GAP_MS / 1000) {
                arrivals += Arrival(at, 20 * kotlin.math.log10((out[peak] / max(background, 1e-9f)).toDouble()), out[peak].toDouble())
                lastArrival = outStart + first
            }
            i = end
        }
        // Drop what's been looked at, keeping a burst window's worth for a burst in progress.
        val dropTo = max(0, i - burst)
        if (dropTo > 0) {
            out = out.copyOfRange(dropTo, out.size)
            outStart += dropTo
        }
        trim()
        return arrivals
    }

    /** Keep the unsettled output bounded (silence never settles anything). */
    private fun trim() {
        val maxOut = sampleRate * 2
        if (out.size > maxOut) {
            val drop = out.size - maxOut
            out = out.copyOfRange(drop, out.size)
            outStart += drop
        }
    }

    /** Sub-sample peak position by a parabola through the neighbours. */
    private fun refine(i: Int): Double {
        if (i <= 0 || i >= out.size - 1) return 0.0
        val a = out[i - 1]
        val b = out[i]
        val c = out[i + 1]
        val d = a - 2 * b + c
        return if (d < 0) ((a - c) / (2 * d)).toDouble().coerceIn(-0.5, 0.5) else 0.0
    }

    private fun percentile(p: Double): Float {
        val sorted = history.copyOf(historyFilled)
        sorted.sort()
        return sorted[((sorted.size - 1) * p).toInt()]
    }

    companion object {
        private const val DECIMATE = 64
        /** The background is the loud end of the filter's output away from bursts. */
        private const val BACKGROUND_PERCENTILE = 0.9
        /** A burst must stand this far over the background (12 dB). */
        private const val THRESHOLD_RATIO = 4f
        private const val MIN_LEVEL = 1e-4f
        /** The first arrival is the first local peak within 6 dB of the burst's strongest. */
        private const val FIRST_RATIO = 2f
        /** A burst's window, ms: the direct path and the echoes behind it. */
        private const val BURST_MS = 30
        /** Chirps come every 200 ms: nothing closer counts. */
        private const val MIN_GAP_MS = 120
    }
}
