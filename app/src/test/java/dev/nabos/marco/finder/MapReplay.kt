package dev.nabos.marco.finder

import org.junit.Test
import java.io.File
import kotlin.math.hypot

/**
 * Replays saved map logs (MARCO_MAP, one or more paths separated by ':') through
 * SignalMap once a second as the app does, printing how far the shown spot moves.
 */
class MapReplay {
    @Test fun replay() {
        val paths = System.getenv("MARCO_MAP") ?: return
        for (path in paths.split(':')) {
            val rows = File(path).readLines().drop(1).map { it.split(',') }.filter { it.size == 8 }
            val map = SignalMap()
            val steady = SignalMap.Steady()
            val t0 = rows.first()[0].toLong()
            var next = 1_000L
            var moved = 0.0
            var bigJumps = 0
            var last: Pair<Double, Double>? = null
            val trace = StringBuilder()
            for (r in rows) {
                map.add(SignalMap.Reading(r[1].toDouble(), r[2].toDouble(), r[4].toDouble(), r[5].toDouble(), r[6].toInt(), r[7] == "1"))
                val t = r[0].toLong() - t0
                if (t < next && r !== rows.last()) continue
                next += 1_000
                val e = map.estimate() ?: continue
                val shown = steady.update(e)
                last?.let {
                    val d = hypot(shown.first - it.first, shown.second - it.second)
                    moved += d
                    if (d > 0.5) bigJumps++
                }
                last = shown
                if ((t / 1000) % 5 == 0L || r === rows.last()) {
                    trace.append("REPLAY %5.1f s: shown (%.2f, %.2f), best (%.2f, %.2f) within %.1f m\n".format(t / 1000.0, shown.first, shown.second, e.x, e.y, e.spreadM))
                }
            }
            print(trace)
            println("REPLAY ${File(path).name}: shown spot moved %.1f m in all, %d jumps over 0.5 m".format(moved, bigJumps))
        }
    }
}
