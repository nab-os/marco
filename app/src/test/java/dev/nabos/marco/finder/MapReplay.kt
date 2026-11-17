package dev.nabos.marco.finder

import org.junit.Test
import java.io.File
import kotlin.math.hypot

/**
 * Replays saved map logs (MARCO_MAP, one or more paths separated by ':') through
 * SignalMap once a second as the app does, printing how far the shown spot moves
 * and how far it sits from the heat map's hottest cell.
 */
class MapReplay {
    @Test fun replay() {
        val paths = System.getenv("MARCO_MAP") ?: return
        // Variants of the tuning to compare, "name:key=value,key=value;name2:..."; the keys are Tuning's.
        val variants = (System.getenv("MARCO_VARIANTS") ?: "base:").split(';').map { v ->
            val name = v.substringBefore(':')
            var t = SignalMap.Tuning()
            v.substringAfter(':').split(',').filter { it.contains('=') }.forEach { kv ->
                val (k, value) = kv.split('=')
                t = when (k) {
                    "aPriorDbm" -> t.copy(aPriorDbm = value.toDouble())
                    "aPriorSdDb" -> t.copy(aPriorSdDb = value.toDouble())
                    "exponents" -> t.copy(exponents = value.split('/').map(String::toDouble).toDoubleArray())
                    "nPrior" -> t.copy(nPrior = value.toDouble())
                    "nPriorSd" -> t.copy(nPriorSd = value.toDouble())
                    "bodyDb" -> t.copy(bodyDb = value.split('/').map(String::toDouble).toDoubleArray())
                    "bodyPriorDb" -> t.copy(bodyPriorDb = value.toDouble())
                    "bodyPriorSdDb" -> t.copy(bodyPriorSdDb = value.toDouble())
                    "heightM" -> t.copy(heightM = value.toDouble())
                    "nearPathM" -> t.copy(nearPathM = value.toDouble())
                    "farScaleM" -> t.copy(farScaleM = value.toDouble())
                    else -> error("unknown tuning key $k")
                }
            }
            name to t
        }
        for ((variant, tuning) in variants) {
        val misses = mutableListOf<Double>()
        for (path in paths.split(':')) {
            val rows = File(path).readLines().drop(1).map { it.split(',') }.filter { it.size == 8 }
            if (rows.size < 20) continue
            val map = SignalMap(tuning)
            val t0 = rows.first()[0].toLong()
            var next = 1_000L
            var moved = 0.0
            var bigJumps = 0
            var last: Pair<Double, Double>? = null
            val offPeak = mutableListOf<Double>()
            val centreOffPeak = mutableListOf<Double>()
            val trace = StringBuilder()
            for (r in rows) {
                map.add(SignalMap.Reading(r[1].toDouble(), r[2].toDouble(), r[4].toDouble(), r[5].toDouble(), r[6].toInt(), r[7] == "1"))
                val t = r[0].toLong() - t0
                if (t < next && r !== rows.last()) continue
                next += 1_000
                val e = map.estimate() ?: continue
                val shown = e.x to e.y
                val top = e.probability.indices.maxBy { e.probability[it] }
                val peakX = e.originX + (top % e.cols) * e.cellM
                val peakY = e.originY + (top / e.cols) * e.cellM
                offPeak += hypot(shown.first - peakX, shown.second - peakY)
                // The middle of the hot core, cells at least half as likely as the hottest: what the eye reads as the peak.
                var cx = 0.0; var cy = 0.0; var cw = 0.0
                val half = e.probability[top] / 2
                for (i in e.probability.indices) {
                    val w = e.probability[i].toDouble()
                    if (w < half) continue
                    cx += w * (e.originX + (i % e.cols) * e.cellM); cy += w * (e.originY + (i / e.cols) * e.cellM); cw += w
                }
                centreOffPeak += hypot(shown.first - cx / cw, shown.second - cy / cw)
                // Only while the map is sure enough to show the spot (3 m, as the view does).
                if (e.spreadM <= 3.0) {
                    last?.let {
                        val d = hypot(shown.first - it.first, shown.second - it.second)
                        moved += d
                        if (d > 0.5) bigJumps++
                    }
                    last = shown
                } else {
                    last = null
                }
                System.getenv("MARCO_DUMP")?.let { dir ->
                    if ((t / 1000) % 5 == 0L || r === rows.last()) File(dir, "${File(path).nameWithoutExtension}-%03d.csv".format(t / 1000)).bufferedWriter().use { w ->
                        w.write("# origin ${e.originX} ${e.originY} cell ${e.cellM} cols ${e.cols} rows ${e.rows} spot ${e.x} ${e.y} spread ${e.spreadM} here ${r[1]} ${r[2]} heading ${r[4]} ${r[5]}\n")
                        w.write(e.probability.joinToString(",") + "\n")
                    }
                }
                if ((t / 1000) % 5 == 0L || r === rows.last()) {
                    trace.append(
                        "REPLAY %5.1f s: shown (%.2f, %.2f), centre (%.2f, %.2f), peak (%.2f, %.2f) within %.1f m · shown off peak %.2f m\n"
                            .format(t / 1000.0, shown.first, shown.second, e.x, e.y, peakX, peakY, e.spreadM, offPeak.last()),
                    )
                }
            }
            if (variant == variants.first().first) print(trace)
            // The strongest tenth of the readings' centre: in a small flat, close to the truth.
            val smooth = rows.indices.map { i -> rows.subList(maxOf(0, i - 4), minOf(rows.size, i + 5)).map { it[6].toDouble() }.average() }
            val strongest = smooth.indices.sortedByDescending { smooth[it] }.take(maxOf(5, rows.size / 10))
            val tx = strongest.map { rows[it][1].toDouble() }.average()
            val ty = strongest.map { rows[it][2].toDouble() }.average()
            val e = map.estimate()!!
            misses += hypot(e.x - tx, e.y - ty)
            println("REPLAY-END $variant ${File(path).name}: dot to strongest %.1f m, within %.1f m, n %.1f, body %.0f dB".format(misses.last(), e.spreadM, e.exponent, e.bodyDb))
            println(
                "REPLAY ${File(path).name}: shown spot moved %.1f m while shown, %d jumps over 0.5 m; off the hottest cell %.2f m on average, %.2f m at worst; off the hot core's middle %.2f m on average, %.2f m at worst"
                    .format(moved, bigJumps, offPeak.average(), offPeak.max(), centreOffPeak.average(), centreOffPeak.max()),
            )
        }
        println("REPLAY-VARIANT $variant: dot to strongest %.2f m on average, %.1f m at worst".format(misses.average(), misses.max()))
        }
    }
}
