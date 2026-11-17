package dev.nabos.marco.finder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.log10
import kotlin.math.sqrt
import kotlin.random.Random

class SignalMapTest {

    /**
     * Walks through [waypoints] at 1 m/s, facing the way it goes, with 8
     * readings/s from a phone at ([tx], [ty]): [atOneM] dBm at 1 m, fading with
     * [exponent], [bodyDb] lost with your back to it, and Gaussian noise of [noiseDb].
     */
    private fun walk(
        tx: Double, ty: Double, waypoints: List<Pair<Double, Double>>, seed: Int,
        bodyDb: Double = 8.0, noiseDb: Double = 5.0, atOneM: Double = -55.0, exponent: Double = 2.3,
    ): SignalMap {
        val rnd = Random(seed)
        val map = SignalMap()
        for ((a, b) in waypoints.zipWithNext()) {
            val length = hypot(b.first - a.first, b.second - a.second)
            val hx = (b.first - a.first) / length
            val hy = (b.second - a.second) / length
            val steps = (length * 8).toInt()
            for (i in 0 until steps) {
                val x = a.first + hx * i / 8
                val y = a.second + hy * i / 8
                val dx = tx - x
                val dy = ty - y
                val flat = hypot(dx, dy)
                val behind = (1 - (hx * dx + hy * dy) / flat) / 2
                val d = sqrt(flat * flat + 0.6 * 0.6)
                val gauss = sqrt(-2 * kotlin.math.ln(rnd.nextDouble(1e-9, 1.0))) * kotlin.math.cos(2 * Math.PI * rnd.nextDouble())
                val rssi = atOneM - 10 * exponent * log10(d) - bodyDb * behind + noiseDb * gauss
                map.add(SignalMap.Reading(x, y, hx, hy, rssi.toInt(), fromConnection = true))
            }
        }
        return map
    }

    /** Along a room's sides, round a corner and back across. */
    private val roomWalk = listOf(0.0 to 0.0, 5.0 to 0.0, 5.0 to 4.0, 1.0 to 4.0, 1.0 to 1.0)

    @Test fun findsThePhoneFromAWalkAroundTheRoom() {
        val misses = (0 until 10).map { seed ->
            val tx = 1.5 + seed % 3
            val ty = 0.8 + seed % 4 * 0.9
            val e = walk(tx, ty, roomWalk, seed).estimate()!!
            hypot(e.x - tx, e.y - ty)
        }
        assertTrue("misses $misses", misses.sorted()[7] < 1.5)
    }

    @Test fun copesWithAHarsherRoomThanItAssumes() {
        // A weaker phone, faster fading, more body loss and noisier readings than its priors.
        val misses = (0 until 10).map { seed ->
            val tx = 1.5 + seed % 3
            val ty = 0.8 + seed % 4 * 0.9
            val e = walk(tx, ty, roomWalk, seed, bodyDb = 13.0, noiseDb = 7.0, atOneM = -68.0, exponent = 3.0).estimate()!!
            hypot(e.x - tx, e.y - ty)
        }
        assertTrue("misses $misses", misses.sorted()[7] < 2.0)
    }

    @Test fun findsAPhoneOutsideThePath() {
        val e = walk(8.0, 2.0, roomWalk, seed = 3).estimate()!!
        assertTrue("at ${e.x}, ${e.y}", hypot(e.x - 8.0, e.y - 2.0) < 2.5)
    }

    @Test fun aStraightLineCannotTellTheSides() {
        // Facing along the line, the body says nothing about left or right either: the
        // map picks neither side and keeps to the walked line, where the phone most
        // likely is anyway, at the right place along it.
        val e = walk(3.0, 2.0, listOf(0.0 to 0.0, 6.0 to 0.0), seed = 1).estimate()!!
        assertTrue("at ${e.x}, ${e.y}", abs(e.y) < 0.5 && e.x in 1.5..4.5)
        val sure = walk(3.0, 2.0, roomWalk, seed = 1).estimate()!!
        assertTrue("spread ${sure.spreadM} vs ${e.spreadM}", sure.spreadM < e.spreadM)
    }

    @Test fun probabilitySumsToOne() {
        val e = walk(2.0, 2.0, roomWalk, seed = 2).estimate()!!
        assertEquals(1.0, e.probability.sum().toDouble(), 1e-3)
        assertEquals(e.cols * e.rows, e.probability.size)
    }

    @Test fun needsReadingsFromSeveralPlaces() {
        val map = SignalMap()
        repeat(100) { map.add(SignalMap.Reading(0.1, 0.1, 1.0, 0.0, -60, true)) }
        assertNull(map.estimate())
    }
}
