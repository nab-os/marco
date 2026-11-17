package dev.nabos.marco.finder

import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * The continuous signal map: RSSI readings taken where the camera tracking put
 * you, and which way you faced, fitted to where the other phone would explain
 * them best.
 *
 * Each candidate spot on a grid around your path is scored with a log-distance
 * path loss model, `rssi = A − 10·n·log10(d) − B·behind`: `A` the level at 1 m
 * (one per source, advert and connection readings differ), `n` how fast it
 * fades, and `B` what your body takes off when the spot is behind you (`behind`
 * goes from 0 facing it to 1 with your back to it).
 * `A` is solved per spot, `n` and `B` picked from a few values; the score is how
 * well the best of them fits, turned into a probability over the grid.
 *
 * A gradient in signal strength tells the direction much better than the
 * distance, so two weak priors keep the far end of the grid from adding up:
 * `A` near what phones read at 1 m, and the phone more likely near where you
 * search than far beyond it.
 *
 * Readings are averaged per half-metre cell and heading sector first: fading
 * changes within centimetres, and many readings from one spot are not many
 * independent ones.
 *
 * Coordinates are metres in the tracking's world (z up, so x/y are the floor).
 * Readings may be added while an estimate is being made on another thread.
 */
class SignalMap(private val tuning: Tuning = Tuning()) {

    /** The model's knobs, with the values the app uses; the replay test tries others. */
    data class Tuning(
        /** The level at 1 m, give or take: phones read about −45 dBm right next to each other. */
        val aPriorDbm: Double = -58.0,
        val aPriorSdDb: Double = 8.0,
        /** Fading exponents tried, and the prior among them: indoors at 2.4 GHz about 2–3. */
        val exponents: DoubleArray = doubleArrayOf(1.6, 1.9, 2.2, 2.5, 2.8, 3.1, 3.4),
        val nPrior: Double = 2.4,
        val nPriorSd: Double = 0.4,
        /**
         * Body losses tried, and the prior: a body takes off about 5–10 dB. Left free,
         * the body loss fits each stretch walked in one direction its own offset, and
         * spots far off fit about as well.
         */
        val bodyDb: DoubleArray = doubleArrayOf(0.0, 3.0, 6.0, 9.0, 12.0, 15.0),
        val bodyPriorDb: Double = 7.0,
        val bodyPriorSdDb: Double = 3.0,
        /**
         * The other phone is likely lower (floor, sofa, bag) than the one held in front of
         * you, chest high. Also the closest the model gets, which sets how steeply it
         * expects the level to rise up close: steeper than real rooms (0.8 m) put the phone
         * too far out, away from the strongest readings, in a small flat.
         */
        val heightM: Double = 1.2,
        /**
         * Within this of your path any spot is as likely; beyond, less so over [farScaleM].
         * Firm on purpose: walls, furniture and a body shadow the signal far more than
         * distance fades it in a flat, which the model reads as "further away", and
         * looser (1 and 1.5 m) it put the phone outside a 3 by 4 m flat. The spot then
         * sits at the edge of the walked area on the phone's side, and moves on as you do.
         * Against four searches ending over the phone: 0.5 m off at the end, 1.5 m before
         * the walk up to it (0.7 and 1.6 m at 0.5 and 0.5 m; 1.9 and 2.6 m at 2 and 3 m).
         */
        val nearPathM: Double = 0.3,
        val farScaleM: Double = 0.3,
    ) {
        /** The level prior as a weight in readings: (σ / sd)². */
        val aPriorWeight: Double get() = (SIGMA_DB / aPriorSdDb) * (SIGMA_DB / aPriorSdDb)
    }

    /** One reading where it was taken: position, the horizontal way the camera faced (unit or zero). */
    data class Reading(val x: Double, val y: Double, val headingX: Double, val headingY: Double, val rssi: Int, val fromConnection: Boolean)

    data class Estimate(
        /** The most likely spot: the middle of the (time-smoothed) probability's hottest area. */
        val x: Double,
        val y: Double,
        /** The radius around that spot holding two thirds of the probability, metres: how sure it is. */
        val spreadM: Double,
        /** The grid: cell (col, row) is centred at `originX + col·cellM, originY + row·cellM`. */
        val originX: Double,
        val originY: Double,
        val cellM: Double,
        val cols: Int,
        val rows: Int,
        /** Probability per cell, row-major, summing to 1. */
        val probability: FloatArray,
        /** Fitted fading exponent and body loss at the most likely spot. */
        val exponent: Double,
        val bodyDb: Double,
        val readings: Int,
    )

    private class Bin(val fromConnection: Boolean) {
        var n = 0
        var x = 0.0
        var y = 0.0
        var hx = 0.0
        var hy = 0.0
        var rssi = 0.0

        fun add(r: Reading) {
            n++
            x += (r.x - x) / n
            y += (r.y - y) / n
            hx += (r.headingX - hx) / n
            hy += (r.headingY - hy) / n
            rssi += (r.rssi - rssi) / n
        }

        fun snapshot() = Bin(fromConnection).also {
            it.n = n
            it.x = x
            it.y = y
            it.hx = hx
            it.hy = hy
            it.rssi = rssi
        }
    }

    private val bins = HashMap<Long, Bin>()
    @Volatile var readings = 0
        private set
    /** The last probability per cell (keyed by cell column and row), for smoothing over time; estimate() only. */
    private val smoothed = HashMap<Long, Double>()
    private var smoothedCellM = 0.0

    @Synchronized fun add(r: Reading) {
        val ix = kotlin.math.floor(r.x / BIN_M).toLong()
        val iy = kotlin.math.floor(r.y / BIN_M).toLong()
        val angle = kotlin.math.atan2(r.headingY, r.headingX)
        val sector = (((angle / (2 * Math.PI) + 1.0) * SECTORS).toInt() % SECTORS).toLong()
        val key = (((ix and 0xFFFFF) shl 24) or ((iy and 0xFFFFF) shl 4) or sector) * 2 + if (r.fromConnection) 1 else 0
        bins.getOrPut(key) { Bin(r.fromConnection) }.add(r)
        readings++
    }

    @Synchronized fun clear() {
        bins.clear()
        readings = 0
        smoothed.clear()
    }

    /** Null until there are enough readings from enough places to say anything. */
    fun estimate(): Estimate? {
        val (list, readings) = synchronized(this) {
            bins.values.map(Bin::snapshot) to readings
        }
        if (readings < MIN_READINGS || list.size < MIN_BINS) return null
        val spanM = max(list.maxOf { it.x } - list.minOf { it.x }, list.maxOf { it.y } - list.minOf { it.y }) + 2 * MARGIN_M
        // Cells on fixed multiples of their size: otherwise each spot shifts as the path grows.
        val cell = MIN_CELL_M * kotlin.math.ceil(spanM / MAX_CELLS / MIN_CELL_M)
        val minX = kotlin.math.floor((list.minOf { it.x } - MARGIN_M) / cell) * cell
        val minY = kotlin.math.floor((list.minOf { it.y } - MARGIN_M) / cell) * cell
        val cols = ((list.maxOf { it.x } + MARGIN_M - minX) / cell).toInt() + 1
        val rows = ((list.maxOf { it.y } + MARGIN_M - minY) / cell).toInt() + 1

        val count = list.size
        val bx = DoubleArray(count) { list[it].x }
        val by = DoubleArray(count) { list[it].y }
        val bhx = DoubleArray(count) { list[it].hx }
        val bhy = DoubleArray(count) { list[it].hy }
        val br = DoubleArray(count) { list[it].rssi }
        val bw = DoubleArray(count) { min(list[it].n, WEIGHT_CAP).toDouble() }
        val bs = IntArray(count) { if (list[it].fromConnection) 1 else 0 }

        // Per source, the sums that don't depend on the spot.
        val w = DoubleArray(2)
        val wr = DoubleArray(2)
        val wrr = DoubleArray(2)
        for (i in 0 until count) {
            w[bs[i]] += bw[i]
            wr[bs[i]] += bw[i] * br[i]
            wrr[bs[i]] += bw[i] * br[i] * br[i]
        }

        val logLikelihood = DoubleArray(cols * rows)
        val bestN = DoubleArray(cols * rows)
        val bestB = DoubleArray(cols * rows)
        val wl = DoubleArray(2)
        val wll = DoubleArray(2)
        val wrl = DoubleArray(2)
        val wb = DoubleArray(2)
        val wbb = DoubleArray(2)
        val wrb = DoubleArray(2)
        val wlb = DoubleArray(2)
        for (row in 0 until rows) for (col in 0 until cols) {
            val cx = minX + col * cell
            val cy = minY + row * cell
            wl.fill(0.0); wll.fill(0.0); wrl.fill(0.0); wb.fill(0.0); wbb.fill(0.0); wrb.fill(0.0); wlb.fill(0.0)
            for (i in 0 until count) {
                val dx = cx - bx[i]
                val dy = cy - by[i]
                val flat = hypot(dx, dy)
                val l = 10 * log10(sqrt(flat * flat + tuning.heightM * tuning.heightM))
                // 0 facing the spot, 1 with your back to it; ½ when the heading is unknown or the spot is right here.
                val facing = if (flat > 1e-6) (bhx[i] * dx + bhy[i] * dy) / flat else 0.0
                val b = (1 - facing) / 2
                val s = bs[i]
                val wi = bw[i]
                wl[s] += wi * l
                wll[s] += wi * l * l
                wrl[s] += wi * br[i] * l
                wb[s] += wi * b
                wbb[s] += wi * b * b
                wrb[s] += wi * br[i] * b
                wlb[s] += wi * l * b
            }
            // rssi + n·l + B·b = A + error: for each n and B, A has a closed form (with its prior),
            // and the squared error follows from the sums.
            // In units of 2σ², so that the priors on n and B can join it.
            var best = Double.MAX_VALUE
            for (n in tuning.exponents) for (bodyDb in tuning.bodyDb) {
                val nOff = (n - tuning.nPrior) / tuning.nPriorSd
                val bOff = (bodyDb - tuning.bodyPriorDb) / tuning.bodyPriorSdDb
                var sse = (nOff * nOff + bOff * bOff) * SIGMA_DB * SIGMA_DB
                for (s in 0..1) {
                    if (w[s] == 0.0) continue
                    val z1 = wr[s] + n * wl[s] + bodyDb * wb[s]
                    val z2 = wrr[s] + n * n * wll[s] + bodyDb * bodyDb * wbb[s] +
                        2 * n * wrl[s] + 2 * bodyDb * wrb[s] + 2 * n * bodyDb * wlb[s]
                    val a = (z1 + tuning.aPriorWeight * tuning.aPriorDbm) / (w[s] + tuning.aPriorWeight)
                    sse += z2 - 2 * a * z1 + a * a * w[s] + tuning.aPriorWeight * (a - tuning.aPriorDbm) * (a - tuning.aPriorDbm)
                }
                if (sse < best) {
                    best = sse
                    bestN[row * cols + col] = n
                    bestB[row * cols + col] = bodyDb
                }
            }
            var nearest = Double.MAX_VALUE
            for (i in 0 until count) nearest = min(nearest, hypot(cx - bx[i], cy - by[i]))
            val beyond = max(0.0, nearest - tuning.nearPathM) / tuning.farScaleM
            logLikelihood[row * cols + col] = -best / (2 * SIGMA_DB * SIGMA_DB) - beyond * beyond / 2
        }

        val peakLogLikelihood = logLikelihood.max()
        val p = DoubleArray(logLikelihood.size) { exp(logLikelihood[it] - peakLogLikelihood) }
        normalise(p)
        // Smoothed over time, so the map and its peak don't hop as readings come in: the
        // cells sit on fixed multiples of their size, so they match from one call to the next.
        if (smoothedCellM != cell) {
            smoothed.clear()
            smoothedCellM = cell
        }
        val baseCol = Math.round(minX / cell)
        val baseRow = Math.round(minY / cell)
        for (i in p.indices) {
            val key = (baseCol + i % cols) * 1_000_000L + (baseRow + i / cols)
            smoothed[key]?.let { prev -> p[i] = prev + (p[i] - prev) * SMOOTHING }
        }
        normalise(p)
        smoothed.clear()
        for (i in p.indices) smoothed[(baseCol + i % cols) * 1_000_000L + (baseRow + i / cols)] = p[i]

        // Shown and read blurred a little: a spot right under a reading predicts an extreme
        // level there, so single cells along the path spike; the eye reads the middle of the
        // hot area, and of two blobs the bigger one.
        val blurred = blur(p, cols, rows, PEAK_BLUR_M / cell)
        normalise(blurred)
        val top = blurred.indices.maxBy { blurred[it] }
        val topX = minX + (top % cols) * cell
        val topY = minY + (top / cols) * cell
        val byDistance = p.indices.sortedBy { hypot(minX + (it % cols) * cell - topX, minY + (it / cols) * cell - topY) }
        var mass = 0.0
        var spread = 0.0
        for (i in byDistance) {
            mass += blurred[i]
            if (mass >= SPREAD_MASS) {
                spread = hypot(minX + (i % cols) * cell - topX, minY + (i / cols) * cell - topY)
                break
            }
        }
        // Real fading is rougher than the model: never surer than this.
        spread = max(spread, MIN_SPREAD_M)
        // The peak, placed between cells by its nearest neighbours: never out of the hot area.
        var sx = 0.0
        var sy = 0.0
        var sp = 0.0
        for (i in byDistance) {
            val x = minX + (i % cols) * cell
            val y = minY + (i / cols) * cell
            if (hypot(x - topX, y - topY) > max(PEAK_AROUND_M, cell)) break
            sx += blurred[i] * x
            sy += blurred[i] * y
            sp += blurred[i]
        }
        return Estimate(
            x = sx / sp,
            y = sy / sp,
            spreadM = spread,
            originX = minX,
            originY = minY,
            cellM = cell,
            cols = cols,
            rows = rows,
            probability = FloatArray(blurred.size) { blurred[it].toFloat() },
            exponent = bestN[top],
            bodyDb = bestB[top],
            readings = readings,
        )
    }

    /** A Gaussian blur of [sigma] cells, separable, cut at 2σ. */
    private fun blur(p: DoubleArray, cols: Int, rows: Int, sigma: Double): DoubleArray {
        val radius = kotlin.math.ceil(2 * sigma).toInt()
        val kernel = DoubleArray(2 * radius + 1) { exp(-((it - radius) * (it - radius)) / (2 * sigma * sigma)) }
        val pass = DoubleArray(p.size)
        for (row in 0 until rows) for (col in 0 until cols) {
            var sum = 0.0
            for (k in -radius..radius) {
                val c = col + k
                if (c in 0 until cols) sum += p[row * cols + c] * kernel[k + radius]
            }
            pass[row * cols + col] = sum
        }
        val out = DoubleArray(p.size)
        for (row in 0 until rows) for (col in 0 until cols) {
            var sum = 0.0
            for (k in -radius..radius) {
                val r = row + k
                if (r in 0 until rows) sum += pass[r * cols + col] * kernel[k + radius]
            }
            out[row * cols + col] = sum
        }
        return out
    }

    private fun normalise(p: DoubleArray) {
        val total = p.sum()
        for (i in p.indices) p[i] /= total
    }

    companion object {
        private const val BIN_M = 0.5
        private const val SECTORS = 8
        /** A cell's readings count as at most this many independent ones. */
        private const val WEIGHT_CAP = 4
        /** Spread of one reading around the model, dB: multipath fading indoors. */
        private const val SIGMA_DB = 6.0
        /** How far past your path it may be. */
        private const val MARGIN_M = 8.0
        private const val SPREAD_MASS = 2.0 / 3
        private const val MIN_SPREAD_M = 1.0
        /** Of the way from the last map to the new one, per estimate (once a second). */
        private const val SMOOTHING = 0.5
        private const val PEAK_AROUND_M = 0.5
        private const val PEAK_BLUR_M = 0.5
        private const val MIN_CELL_M = 0.25
        private const val MAX_CELLS = 80
        const val MIN_READINGS = 20
        private const val MIN_BINS = 4
    }
}
