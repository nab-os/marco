package dev.nabos.marco.acoustic

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToLong
import kotlin.math.sqrt

/**
 * Where the chirping phone is, from when its chirps arrive along the finder's
 * tracked path. The chirps leave every [periodS] on that phone's clock, so
 * arrival `i` is `t0 + k_i·T·(1 + drift) + distance_i / c`: four unknowns (the
 * position, when the train started, and how fast the other clock runs) that a
 * walk of a metre or two pins down, the way GPS would with one satellite if the
 * receiver's path were known. Solved by Gauss–Newton with a Huber weight, so
 * an echo picked instead of the direct path now and then doesn't drag it.
 */
class AcousticRanging(private val periodS: Double = Chirps.RANGING_PERIOD.toDouble() / Chirps.SAMPLE_RATE) {

    /** One chirp heard: when (finder clock, ns) and where the finder was. */
    data class Observation(val tNs: Long, val x: Double, val y: Double, val z: Double)

    data class Fix(
        val x: Double,
        val y: Double,
        /** Standard error of the position, metres. */
        val spreadM: Double,
        /** Root-mean-square of the range residuals, metres: how well the chirps agree. */
        val residualM: Double,
        val chirps: Int,
        val driftPpm: Double,
    )

    /**
     * [initX], [initY]: where to start from (the signal map's spot): with a straight
     * path, the mirror image fits as well, and the start picks the side.
     * [belowM]: how far below the finder's hand the phone is taken to lie.
     */
    fun solve(obs: List<Observation>, initX: Double, initY: Double, belowM: Double = 0.8): Fix? {
        if (obs.size < MIN_CHIRPS) return null
        val sorted = obs.sortedBy { it.tNs }
        val t = DoubleArray(sorted.size) { (sorted[it].tNs - sorted[0].tNs) / 1e9 }
        val k = DoubleArray(sorted.size) { (t[it] / periodS).roundToLong().toDouble() }
        val zs = sorted.map { it.z }.average() - belowM
        val spanX = sorted.maxOf { it.x } - sorted.minOf { it.x }
        val spanY = sorted.maxOf { it.y } - sorted.minOf { it.y }
        if (max(spanX, spanY) < MIN_PATH_M) return null

        var sx = initX
        var sy = initY
        var drift = 0.0
        val range = DoubleArray(sorted.size)
        fun ranges() {
            for (i in sorted.indices) range[i] = sqrt((sorted[i].x - sx) * (sorted[i].x - sx) + (sorted[i].y - sy) * (sorted[i].y - sy) + (sorted[i].z - zs) * (sorted[i].z - zs))
        }
        ranges()
        // t0 from the median, robust to the odd echo.
        var t0 = sorted.indices.map { t[it] - k[it] * periodS - range[it] / SPEED_OF_SOUND }.sorted().let { it[it.size / 2] }

        val w = DoubleArray(sorted.size) { 1.0 }
        var residuals = DoubleArray(sorted.size)
        val n = 4
        val ata = Array(n) { DoubleArray(n) }
        val atb = DoubleArray(n)
        repeat(ITERATIONS) {
            ranges()
            for (i in sorted.indices) {
                val predicted = t0 + k[i] * periodS * (1 + drift) + range[i] / SPEED_OF_SOUND
                residuals[i] = (t[i] - predicted) * SPEED_OF_SOUND // metres
                // Huber: full weight within HUBER_M, less beyond.
                val a = abs(residuals[i])
                w[i] = if (a <= HUBER_M) 1.0 else HUBER_M / a
            }
            // Jacobian of the residual (metres) in (sx, sy, t0·c, drift·c·T): d range / d s = -(x_i - sx) / range.
            for (r in 0 until n) {
                atb[r] = 0.0
                for (c in 0 until n) ata[r][c] = 0.0
            }
            for (i in sorted.indices) {
                val j = doubleArrayOf(
                    (sorted[i].x - sx) / range[i],
                    (sorted[i].y - sy) / range[i],
                    -1.0,
                    -k[i] * periodS * SPEED_OF_SOUND,
                )
                for (r in 0 until n) {
                    atb[r] += w[i] * j[r] * residuals[i]
                    for (c in 0 until n) ata[r][c] += w[i] * j[r] * j[c]
                }
            }
            // A weak prior on the drift: on a short walk it looks like the phone a little further
            // along the path; phones' clocks differ by tens of ppm at most. The residual
            // (0 − drift) / σ, weighted like a range residual of NOISE_M.
            val priorWeight = (NOISE_M / DRIFT_PRIOR) * (NOISE_M / DRIFT_PRIOR)
            ata[3][3] += priorWeight
            atb[3] += priorWeight * drift
            // The Jacobian is the residual's (observed minus predicted), so the step is subtracted.
            val step = solve(ata, atb) ?: return null
            sx -= step[0]
            sy -= step[1]
            t0 -= step[2] / SPEED_OF_SOUND
            drift -= step[3]
        }
        ranges()
        var sum = 0.0
        var count = 0
        for (i in sorted.indices) {
            val predicted = t0 + k[i] * periodS * (1 + drift) + range[i] / SPEED_OF_SOUND
            residuals[i] = (t[i] - predicted) * SPEED_OF_SOUND
            if (abs(residuals[i]) <= 3 * HUBER_M) {
                sum += residuals[i] * residuals[i]
                count++
            }
        }
        if (count < MIN_CHIRPS) return null
        val rms = sqrt(sum / count)
        // The position's standard error from the normal matrix, scaled by the residuals.
        val cov = invert(ata) ?: return null
        val sigma = max(rms, MIN_RESIDUAL_M)
        val spread = sigma * sqrt(max(0.0, cov[0][0] + cov[1][1]))
        return Fix(sx, sy, max(spread, MIN_SPREAD_M), rms, count, drift * 1e6)
    }

    /** Solves A x = b for a small symmetric A by Gaussian elimination; null if singular. */
    private fun solve(a: Array<DoubleArray>, b: DoubleArray): DoubleArray? {
        val n = b.size
        val m = Array(n) { r -> DoubleArray(n + 1) { c -> if (c < n) a[r][c] else b[r] } }
        for (col in 0 until n) {
            var pivot = col
            for (r in col + 1 until n) if (abs(m[r][col]) > abs(m[pivot][col])) pivot = r
            if (abs(m[pivot][col]) < 1e-12) return null
            val tmp = m[col]; m[col] = m[pivot]; m[pivot] = tmp
            for (r in 0 until n) {
                if (r == col) continue
                val f = m[r][col] / m[col][col]
                for (c in col..n) m[r][c] -= f * m[col][c]
            }
        }
        return DoubleArray(n) { m[it][n] / m[it][it] }
    }

    private fun invert(a: Array<DoubleArray>): Array<DoubleArray>? {
        val n = a.size
        val cols = Array(n) { c -> solve(a, DoubleArray(n) { if (it == c) 1.0 else 0.0 }) ?: return null }
        return Array(n) { r -> DoubleArray(n) { c -> cols[c][r] } }
    }

    companion object {
        const val SPEED_OF_SOUND = 343.0
        const val MIN_CHIRPS = 10
        /** The path must span this much: standing still, every spot at the right range fits. */
        private const val MIN_PATH_M = 0.5
        private const val ITERATIONS = 12
        /** Residuals beyond this are down-weighted: an echo picked, a dropped sample. */
        private const val HUBER_M = 0.2
        private const val MIN_RESIDUAL_M = 0.03
        /** Clock drift between two phones, as a fraction: 30 ppm. */
        private const val DRIFT_PRIOR = 30e-6
        /** A chirp's timing noise as a range, metres: what one residual is worth. */
        private const val NOISE_M = 0.1
        private const val MIN_SPREAD_M = 0.1
    }
}
