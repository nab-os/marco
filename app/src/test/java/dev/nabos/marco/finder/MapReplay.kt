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
        for (path in paths.split(':')) {
            val rows = File(path).readLines().drop(1).map { it.split(',') }.filter { it.size == 8 }
            if (rows.size < 20) continue
            val map = SignalMap()
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
                    if ((t / 1000) % 5 == 0L) File(dir, "${File(path).nameWithoutExtension}-%03d.csv".format(t / 1000)).bufferedWriter().use { w ->
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
            print(trace)
            println(
                "REPLAY ${File(path).name}: shown spot moved %.1f m while shown, %d jumps over 0.5 m; off the hottest cell %.2f m on average, %.2f m at worst; off the hot core's middle %.2f m on average, %.2f m at worst"
                    .format(moved, bigJumps, offPeak.average(), offPeak.max(), centreOffPeak.average(), centreOffPeak.max()),
            )
        }
    }
}
