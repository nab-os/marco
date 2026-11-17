package dev.nabos.marco.finder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.random.Random

class DirectionSweepTest {

    /**
     * One slow turn at 4 readings/s: signal [shadowDb] dB stronger facing
     * [targetDeg] than facing away, plus ±5 dB indoor jitter.
     */
    private fun sweep(targetDeg: Double, shadowDb: Double, seed: Int, startDeg: Double = 0.0, degPerSample: Double = 5.0): DirectionSweep {
        val rnd = Random(seed)
        val s = DirectionSweep()
        var h = startDeg
        while (h < startDeg + 360.0) {
            val facing = cos(Math.toRadians(h - targetDeg))
            val rssi = -65.0 + shadowDb / 2 * facing + rnd.nextDouble(-5.0, 5.0)
            s.add(h, rssi.toInt())
            h += degPerSample
        }
        return s
    }

    @Test fun findsTheDirectionThroughNoise() {
        for (seed in 0 until 20) {
            val target = seed * 37.0 % 360
            val r = sweep(target, shadowDb = 10.0, seed = seed).result()!!
            assertTrue("seed $seed: ${r.headingDeg} vs $target", abs(DirectionSweep.delta(target, r.headingDeg)) <= 30.0)
            assertTrue(r.clear)
        }
    }

    @Test fun sharpShadowBehindStillPointsForward() {
        // Real bodies cast a deep, narrow-ish shadow rather than a smooth cosine.
        val rnd = Random(3)
        val s = DirectionSweep()
        for (k in 0 until 80) {
            val h = k * 4.5
            val behind = abs(DirectionSweep.delta(h, 200.0 + 180.0)) < 50.0
            s.add(h, (-62.0 - (if (behind) 12.0 else 0.0) + rnd.nextDouble(-5.0, 5.0)).toInt())
        }
        val r = s.result()!!
        assertTrue(r.headingDeg.toString(), abs(DirectionSweep.delta(200.0, r.headingDeg)) <= 25.0)
        assertTrue(r.clear)
    }

    @Test fun peakAcrossZeroWraps() {
        val r = sweep(355.0, shadowDb = 12.0, seed = 7, startDeg = 180.0).result()!!
        assertTrue(r.headingDeg.toString(), abs(DirectionSweep.delta(355.0, r.headingDeg)) <= 30.0)
    }

    @Test fun flatSignalIsNotClear() {
        // No body shadowing at all (e.g. phone right next to you): just jitter.
        var clear = 0
        for (seed in 0 until 20) if (sweep(0.0, shadowDb = 0.0, seed = seed).result()!!.clear) clear++
        assertTrue("$clear/20 false directions", clear <= 2)
    }

    @Test fun waitsForMostOfTheCircle() {
        val s = DirectionSweep()
        var h = 0.0
        repeat(90) { s.add(h, -60); h = (h + 2.0) % 180 } // only half a turn, plenty of samples
        assertEquals(0.5f, s.coverage, 0.05f)
        assertNull(s.result())
    }

    @Test fun shortestTurn() {
        assertEquals(-20.0, DirectionSweep.delta(10.0, 350.0), 1e-9)
        assertEquals(20.0, DirectionSweep.delta(350.0, 10.0), 1e-9)
        assertEquals(180.0, DirectionSweep.delta(0.0, 180.0), 1e-9)
        assertFalse(DirectionSweep.isFacing(0.0, 90.0))
    }
}
