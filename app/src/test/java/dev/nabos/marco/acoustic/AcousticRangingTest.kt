package dev.nabos.marco.acoustic

import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot
import kotlin.math.sqrt
import kotlin.random.Random

class AcousticRangingTest {

    /**
     * A walk through [waypoints] at 0.7 m/s at hand height 1.2 m, hearing a
     * phone at ([px], [py]) on the floor-ish (0.4 m) chirping every 200 ms on a
     * clock running [driftPpm] fast, with timing noise of [noiseS] and an echo
     * (0.6 m longer) picked instead one time in [echoEvery].
     */
    private fun walk(px: Double, py: Double, waypoints: List<Pair<Double, Double>>, seed: Int, driftPpm: Double = 30.0, noiseS: Double = 0.0003, echoEvery: Int = 15): List<AcousticRanging.Observation> {
        val rnd = Random(seed)
        val period = 0.2 * (1 + driftPpm * 1e-6)
        val obs = ArrayList<AcousticRanging.Observation>()
        var time = 0.0
        var k = 0
        for ((a, b) in waypoints.zipWithNext()) {
            val length = hypot(b.first - a.first, b.second - a.second)
            val duration = length / 0.7
            while (k * period < time + duration) {
                val f = (k * period - time) / duration
                val x = a.first + (b.first - a.first) * f
                val y = a.second + (b.second - a.second) * f
                val z = 1.2
                var range = sqrt((x - px) * (x - px) + (y - py) * (y - py) + (z - 0.4) * (z - 0.4))
                if (k % echoEvery == echoEvery - 1) range += 0.6
                val gauss = sqrt(-2 * kotlin.math.ln(rnd.nextDouble(1e-9, 1.0))) * kotlin.math.cos(2 * Math.PI * rnd.nextDouble())
                val t = 0.123 + k * period + range / AcousticRanging.SPEED_OF_SOUND + noiseS * gauss
                obs += AcousticRanging.Observation((t * 1e9).toLong(), x, y, z)
                k++
            }
            time += duration
        }
        return obs
    }

    private val room = listOf(0.0 to 0.0, 2.5 to 0.0, 2.5 to 2.0)

    @Test fun pinsThePhoneToCentimetres() {
        val misses = (0 until 10).map { seed ->
            val px = 0.5 + seed % 3
            val py = 0.8 + seed % 4 * 0.5
            val fix = AcousticRanging().solve(walk(px, py, room, seed), initX = px + 0.8, initY = py - 0.6)!!
            hypot(fix.x - px, fix.y - py)
        }
        // Timing noise of 0.3 ms is 10 cm a chirp, and one in fifteen is an echo.
        assertTrue("misses $misses", misses.sorted()[4] < 0.15 && misses.sorted()[8] < 0.25)
    }

    @Test fun recoversTheClockDriftOnALongerWalk() {
        // Round the room and back: 13 s, enough to tell a drift from the phone sitting further along.
        val longer = room + room.reversed().drop(1) + room.drop(1)
        val fix = AcousticRanging().solve(walk(1.5, 1.0, longer, seed = 4, driftPpm = 60.0), initX = 1.0, initY = 1.0)!!
        assertTrue("drift ${fix.driftPpm} ppm", kotlin.math.abs(fix.driftPpm - 60) < 25)
        assertTrue("at ${fix.x}, ${fix.y}", hypot(fix.x - 1.5, fix.y - 1.0) < 0.15)
        assertTrue("spread ${fix.spreadM}", fix.spreadM < 0.3)
    }

    @Test fun aShortWalkStillPlacesThePhoneDespiteDrift() {
        val fix = AcousticRanging().solve(walk(1.5, 1.0, room, seed = 4, driftPpm = 60.0), initX = 1.0, initY = 1.0)!!
        assertTrue("at ${fix.x}, ${fix.y}", hypot(fix.x - 1.5, fix.y - 1.0) < 0.3)
    }

    @Test fun copesWithTheWrongHeight() {
        // The phone on a table (0.9 m) while 0.8 m below the hand is assumed.
        val obs = walk(1.5, 1.0, room, seed = 5).map { it.copy(z = it.z) }
        val fix = AcousticRanging().solve(obs, initX = 1.0, initY = 1.0, belowM = 0.3)!!
        assertTrue("at ${fix.x}, ${fix.y}", hypot(fix.x - 1.5, fix.y - 1.0) < 0.4)
    }

    @Test fun needsAWalk() {
        val still = walk(1.5, 1.0, listOf(0.0 to 0.0, 0.2 to 0.0), seed = 6)
        assertNull(AcousticRanging().solve(still, 1.0, 1.0))
    }
}
