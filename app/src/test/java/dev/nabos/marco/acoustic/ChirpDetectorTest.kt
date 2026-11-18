package dev.nabos.marco.acoustic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

class ChirpDetectorTest {

    /**
     * Three seconds of the ranging train as heard: each chirp at [level] with
     * an echo [echoDelay] samples later at half the level, in white noise of
     * [noise], fed in blocks of 4800 like the listener does.
     */
    private fun hear(level: Float, noise: Float, echoDelay: Int, seed: Int, offset: Int = 777): List<ChirpDetector.Arrival> {
        val rnd = Random(seed)
        val chirp = Chirps.rangingChirp()
        val seconds = 3
        val stream = FloatArray(Chirps.SAMPLE_RATE * seconds) { (rnd.nextFloat() * 2 - 1) * noise }
        val expected = ArrayList<Int>()
        var at = offset
        while (at + chirp.size + echoDelay < stream.size) {
            for (i in chirp.indices) {
                stream[at + i] += chirp[i] * level
                stream[at + echoDelay + i] += chirp[i] * level / 2
            }
            expected += at
            at += Chirps.RANGING_PERIOD
        }
        val detector = ChirpDetector()
        val arrivals = ArrayList<ChirpDetector.Arrival>()
        val block = ShortArray(4800)
        var pos = 0
        while (pos < stream.size) {
            val n = minOf(block.size, stream.size - pos)
            for (i in 0 until n) block[i] = (stream[pos + i].coerceIn(-1f, 1f) * 32767).toInt().toShort()
            arrivals += detector.feed(block, n)
            pos += n
        }
        this.expected = expected
        return arrivals
    }

    private var expected = listOf<Int>()

    @Test fun findsEveryChirpToWithinASample() {
        val arrivals = hear(level = 0.3f, noise = 0.02f, echoDelay = 200, seed = 1)
        // The first second or so goes to learning the background.
        val settled = expected.filter { it > Chirps.SAMPLE_RATE }
        val found = settled.map { e -> arrivals.minByOrNull { abs(it.sample - e) } }
        assertTrue("found ${arrivals.size} of ${expected.size}", arrivals.size >= settled.size)
        for ((e, a) in settled.zip(found)) {
            assertTrue("chirp at $e found at ${a?.sample}", a != null && abs(a.sample - e) < 1.5)
        }
    }

    @Test fun picksTheDirectPathOverALouderEcho() {
        // An echo 4 ms later, as loud: the first arrival is still the one reported.
        val rnd = Random(3)
        val chirp = Chirps.rangingChirp()
        val stream = FloatArray(Chirps.SAMPLE_RATE * 3) { (rnd.nextFloat() * 2 - 1) * 0.01f }
        var at = 500
        val starts = ArrayList<Int>()
        while (at + chirp.size + 300 < stream.size) {
            for (i in chirp.indices) {
                stream[at + i] += chirp[i] * 0.2f
                stream[at + 192 + i] += chirp[i] * 0.25f
            }
            starts += at
            at += Chirps.RANGING_PERIOD
        }
        val detector = ChirpDetector()
        val arrivals = ArrayList<ChirpDetector.Arrival>()
        val block = ShortArray(4800)
        var pos = 0
        while (pos < stream.size) {
            val n = minOf(block.size, stream.size - pos)
            for (i in 0 until n) block[i] = (stream[pos + i].coerceIn(-1f, 1f) * 32767).toInt().toShort()
            arrivals += detector.feed(block, n)
            pos += n
        }
        val late = starts.filter { it > Chirps.SAMPLE_RATE }
        val direct = late.count { s -> arrivals.any { abs(it.sample - s) < 1.5 } }
        assertTrue("direct path picked for $direct of ${late.size}", direct >= late.size * 9 / 10)
    }

    @Test fun staysQuietInNoise() {
        val arrivals = hear(level = 0f, noise = 0.05f, echoDelay = 200, seed = 2)
        assertEquals(0, arrivals.size)
    }
}
