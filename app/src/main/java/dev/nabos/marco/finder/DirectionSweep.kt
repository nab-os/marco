package dev.nabos.marco.finder

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * "Point at it": your body absorbs 2.4 GHz well, so holding the phone against
 * your chest and turning on the spot makes the signal dip when your back faces
 * the other phone and peak when you face it. This bins raw RSSI samples by the
 * heading you were facing and finds where around the circle it peaks.
 *
 * Raw samples are used rather than [RssiFilter] output: the filter lags 1–2 s,
 * which would drag the peak along in the direction you turn.
 *
 * Headings are in degrees, any range (normalised internally); 0 is just the
 * sensor's reference, so only differences between headings matter.
 */
class DirectionSweep(
    private val bins: Int = 36,
    /** Gaussian smoothing width around the circle. */
    private val smoothingDeg: Double = 25.0,
) {
    private val binDeg = 360.0 / bins
    private val sum = DoubleArray(bins)
    private val count = IntArray(bins)

    fun reset() {
        sum.fill(0.0)
        count.fill(0)
    }

    fun add(headingDeg: Double, rssi: Int) {
        val b = binOf(headingDeg)
        sum[b] += rssi
        count[b]++
    }

    /** Fraction of the circle that has at least one sample, 0..1. */
    val coverage: Float get() = count.count { it > 0 }.toFloat() / bins

    val samples: Int get() = count.sum()

    /**
     * Smoothed signal per bin (dBm), centred on bin i × [binDeg]; null where no
     * sampled bin is close enough to say anything.
     */
    fun profile(): Array<Double?> {
        val sigmaBins = smoothingDeg / binDeg
        val reach = (sigmaBins * 2).roundToInt().coerceAtLeast(1)
        return Array(bins) { i ->
            var wSum = 0.0
            var vSum = 0.0
            for (d in -reach..reach) {
                val j = Math.floorMod(i + d, bins)
                if (count[j] == 0) continue
                // Weight by sample count too, so one stray sample doesn't dominate.
                val w = exp(-0.5 * (d / sigmaBins) * (d / sigmaBins)) * count[j]
                wSum += w
                vSum += w * (sum[j] / count[j])
            }
            if (wSum > 0) vSum / wSum else null
        }
    }

    /**
     * Best direction so far, or null until there's enough of the circle to compare.
     *
     * Fits rssi ≈ a + b·cos h + c·sin h to the per-bin means by least squares:
     * the shadow of a body is broad, so a cosine uses every direction instead of
     * trusting whichever bin happened to be noisiest-high, and it copes with a
     * circle that isn't quite complete. Per-bin means keep slow spots in the
     * turn from outweighing fast ones.
     */
    fun result(): Result? {
        if (coverage < MIN_COVERAGE || samples < MIN_SAMPLES) return null
        // Normal equations for [a, b, c].
        val m = Array(3) { DoubleArray(3) }
        val y = DoubleArray(3)
        for (i in 0 until bins) {
            if (count[i] == 0) continue
            val a = Math.toRadians(i * binDeg)
            val f = doubleArrayOf(1.0, cos(a), sin(a))
            val v = sum[i] / count[i]
            for (r in 0..2) {
                y[r] += f[r] * v
                for (k in 0..2) m[r][k] += f[r] * f[k]
            }
        }
        val (_, b, c) = solve3(m, y) ?: return null
        return Result(
            headingDeg = normalize(Math.toDegrees(atan2(c, b))),
            contrastDb = 2 * hypot(b, c),
        )
    }

    data class Result(
        /** Heading (same reference as the input) where the signal is strongest. */
        val headingDeg: Double,
        /** Peak minus trough of the fitted cosine; body shadowing gives ~6–15 dB. */
        val contrastDb: Double,
    ) {
        /** Below this the dip from your body is lost in noise and reflections. */
        val clear: Boolean get() = contrastDb >= CLEAR_CONTRAST_DB
    }

    /** Cramer's rule; null if singular (all samples in a narrow arc). */
    private fun solve3(m: Array<DoubleArray>, y: DoubleArray): DoubleArray? {
        fun det(a: Array<DoubleArray>) =
            a[0][0] * (a[1][1] * a[2][2] - a[1][2] * a[2][1]) -
                a[0][1] * (a[1][0] * a[2][2] - a[1][2] * a[2][0]) +
                a[0][2] * (a[1][0] * a[2][1] - a[1][1] * a[2][0])
        val d = det(m)
        if (abs(d) < 1e-9) return null
        return DoubleArray(3) { col -> det(Array(3) { r -> DoubleArray(3) { k -> if (k == col) y[r] else m[r][k] } }) / d }
    }

    private fun binOf(headingDeg: Double): Int =
        ((normalize(headingDeg) + binDeg / 2) / binDeg).toInt() % bins

    companion object {
        const val MIN_COVERAGE = 0.8f
        const val MIN_SAMPLES = 30
        const val CLEAR_CONTRAST_DB = 3.0

        fun normalize(deg: Double): Double = ((deg % 360.0) + 360.0) % 360.0

        /** Signed shortest turn from [from] to [to], in (-180, 180]. Positive = clockwise. */
        fun delta(from: Double, to: Double): Double {
            val d = normalize(to - from)
            return if (d > 180.0) d - 360.0 else d
        }

        fun isFacing(currentDeg: Double, targetDeg: Double, toleranceDeg: Double = 20.0) =
            abs(delta(currentDeg, targetDeg)) <= toleranceDeg
    }
}
