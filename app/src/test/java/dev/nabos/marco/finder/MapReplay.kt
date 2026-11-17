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
                centreOffPeak += hypot(e.x - peakX, e.y - peakY)
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
                if ((t / 1000) % 5 == 0L || r === rows.last()) {
                    trace.append(
                        "REPLAY %5.1f s: shown (%.2f, %.2f), centre (%.2f, %.2f), peak (%.2f, %.2f) within %.1f m · shown off peak %.2f m\n"
                            .format(t / 1000.0, shown.first, shown.second, e.x, e.y, peakX, peakY, e.spreadM, offPeak.last()),
                    )
                }
            }
            print(trace)
            println(
                "REPLAY ${File(path).name}: shown spot moved %.1f m while shown, %d jumps over 0.5 m; off the peak %.2f m on average, %.2f m at worst (centre: %.2f / %.2f)"
                    .format(moved, bigJumps, offPeak.average(), offPeak.max(), centreOffPeak.average(), centreOffPeak.max()),
            )
        }
    }
}
