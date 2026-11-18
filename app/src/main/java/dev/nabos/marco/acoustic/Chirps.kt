package dev.nabos.marco.acoustic

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * The chirps the phones exchange, in samples at [SAMPLE_RATE] so both agree exactly.
 *
 * The ranging train is what the lost phone rings with: a 40 ms chirp through
 * 2–5 kHz five times a second, one continuous loop so it runs on that phone's
 * audio clock. The finder times each chirp's arrival along its tracked path.
 * That band gets through a cushion with 26 dB to spare; above 8 kHz it doesn't.
 *
 * The test track plays one chirp a second in each of three bands, to measure
 * what gets through from one phone to the other.
 */
object Chirps {
    const val SAMPLE_RATE = 48_000

    // --- the ranging train --------------------------------------------------------------

    /** Between chirps, samples (200 ms). */
    const val RANGING_PERIOD = SAMPLE_RATE / 5
    /** A ranging chirp's length, samples (40 ms). */
    const val RANGING_LENGTH = SAMPLE_RATE * 40 / 1000
    const val RANGING_FROM_HZ = 2_000.0
    const val RANGING_TO_HZ = 5_000.0

    /** The ranging chirp, as the finder looks for it. */
    fun rangingChirp(): FloatArray = chirp(RANGING_FROM_HZ, RANGING_TO_HZ, RANGING_LENGTH)

    /** The same a quarter cycle on: correlating with both gives the envelope, free of the carrier's ripple. */
    fun rangingChirpQuadrature(): FloatArray = chirp(RANGING_FROM_HZ, RANGING_TO_HZ, RANGING_LENGTH, quadrature = true)

    /** One period of the ranging train as 16-bit PCM, to loop: the ring's sound. */
    fun rangingPeriod(): ShortArray {
        val out = ShortArray(RANGING_PERIOD)
        val c = rangingChirp()
        for (i in c.indices) out[i] = (c[i] * RING_LEVEL * Short.MAX_VALUE).toInt().toShort()
        return out
    }

    private const val RING_LEVEL = 0.9f

    // --- the test track ------------------------------------------------------------------

    /** One period of the test track, samples: a chirp per band at [Band.offset]. */
    const val PERIOD = SAMPLE_RATE
    /** A test chirp's length, samples (60 ms). */
    const val LENGTH = SAMPLE_RATE * 60 / 1000

    class Band(val name: String, val fromHz: Double, val toHz: Double, val offset: Int)

    val bands = listOf(
        Band("low", 2_000.0, 5_000.0, 0),
        Band("mid", 8_000.0, 12_000.0, PERIOD * 3 / 10),
        Band("high", 16_500.0, 20_000.0, PERIOD * 6 / 10),
    )

    fun chirp(band: Band): FloatArray = chirp(band.fromHz, band.toHz, LENGTH)

    /** One period of the test track as 16-bit PCM, to loop. */
    fun period(): ShortArray {
        val out = ShortArray(PERIOD)
        for (band in bands) {
            val c = chirp(band)
            for (i in c.indices) out[band.offset + i] = (c[i] * Short.MAX_VALUE).toInt().toShort()
        }
        return out
    }

    /** A linear chirp from [fromHz] to [toHz] over [length] samples, tapered at both ends, full scale. */
    fun chirp(fromHz: Double, toHz: Double, length: Int, quadrature: Boolean = false): FloatArray {
        val out = FloatArray(length)
        val seconds = length.toDouble() / SAMPLE_RATE
        for (i in 0 until length) {
            val t = i.toDouble() / SAMPLE_RATE
            val phase = 2 * PI * (fromHz * t + (toHz - fromHz) / (2 * seconds) * t * t)
            val taper = 0.5 - 0.5 * cos(2 * PI * i / length) // Hann
            out[i] = ((if (quadrature) cos(phase) else sin(phase)) * taper).toFloat()
        }
        return out
    }
}
