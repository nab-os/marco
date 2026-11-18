package dev.nabos.marco.acoustic

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * The acoustic test track: once a second, one short chirp in each of three
 * bands, so a recording tells how well each band gets from one phone's speaker
 * to the other's microphone (held, in a drawer, under a cushion). Later the
 * same chirps become the ranging signal.
 *
 * Everything is in samples at [SAMPLE_RATE], so both phones agree exactly.
 */
object Chirps {
    const val SAMPLE_RATE = 48_000
    /** One period of the track, samples: a chirp per band at [Band.offset]. */
    const val PERIOD = SAMPLE_RATE
    /** A chirp's length, samples (60 ms). */
    const val LENGTH = SAMPLE_RATE * 60 / 1000

    class Band(val name: String, val fromHz: Double, val toHz: Double, val offset: Int)

    val bands = listOf(
        Band("low", 2_000.0, 5_000.0, 0),
        Band("mid", 8_000.0, 12_000.0, PERIOD * 3 / 10),
        Band("high", 16_500.0, 20_000.0, PERIOD * 6 / 10),
    )

    /** A linear chirp through the band, tapered at both ends, full scale. */
    fun chirp(band: Band): FloatArray {
        val out = FloatArray(LENGTH)
        val seconds = LENGTH.toDouble() / SAMPLE_RATE
        for (i in 0 until LENGTH) {
            val t = i.toDouble() / SAMPLE_RATE
            val phase = 2 * PI * (band.fromHz * t + (band.toHz - band.fromHz) / (2 * seconds) * t * t)
            val taper = 0.5 - 0.5 * cos(2 * PI * i / LENGTH) // Hann
            out[i] = (sin(phase) * taper).toFloat()
        }
        return out
    }

    /** One period of the track as 16-bit PCM, to loop. */
    fun period(): ShortArray {
        val out = ShortArray(PERIOD)
        for (band in bands) {
            val c = chirp(band)
            for (i in c.indices) out[band.offset + i] = (c[i] * Short.MAX_VALUE).toInt().toShort()
        }
        return out
    }
}
