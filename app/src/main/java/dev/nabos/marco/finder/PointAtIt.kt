package dev.nabos.marco.finder

import android.content.Context
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.math.abs

enum class PointPhase { OFF, SWEEPING, DONE }

data class PointState(
    val phase: PointPhase = PointPhase.OFF,
    /** Where the phone faces now, null until the sensor reports. */
    val heading: Double? = null,
    val coverage: Float = 0f,
    /** Smoothed signal per heading bin, for drawing. */
    val profile: List<Double?> = emptyList(),
    val result: DirectionSweep.Result? = null,
    /** Turning faster than samples arrive. */
    val tooFast: Boolean = false,
    /** No rotation sensor on this phone. */
    val unsupported: Boolean = false,
)

/**
 * Runs a "point at it" sweep on top of a live [FinderEngine] search: you turn
 * slowly on the spot with the phone in front of you, and it finds the heading
 * where the signal is strongest (your body blocks it from behind).
 */
class PointAtIt(context: Context, private val scope: CoroutineScope, private val engine: FinderEngine) {

    private val sensor = HeadingSensor(context)
    private val sweep = DirectionSweep()
    private val _state = MutableStateFlow(PointState(unsupported = !sensor.available))
    val state: StateFlow<PointState> = _state.asStateFlow()

    private val jobs = mutableListOf<Job>()
    private var fromConnection: Boolean? = null
    private var lastHeading: Double? = null
    private var lastHeadingMs = 0L
    private var turnRate = 0.0 // °/s, smoothed

    fun start() {
        if (!sensor.available) return
        stop()
        sensor.start()
        engine.fastPoll = true
        restartSweep()
        jobs += scope.launch { sensor.heading.collect(::onHeading) }
        jobs += scope.launch { engine.samples.collect(::onSample) }
    }

    /** Throw away what was measured and turn again. */
    fun restartSweep() {
        sweep.reset()
        fromConnection = null
        _state.update { it.copy(phase = PointPhase.SWEEPING, coverage = 0f, profile = emptyList(), result = null, tooFast = false) }
    }

    fun stop() {
        jobs.forEach { it.cancel() }
        jobs.clear()
        sensor.stop()
        engine.fastPoll = false
        lastHeading = null
        turnRate = 0.0
        _state.value = PointState(unsupported = !sensor.available)
    }

    private fun onHeading(h: Double?) {
        if (h == null) return
        val t = SystemClock.elapsedRealtime()
        lastHeading?.let { prev ->
            val dt = (t - lastHeadingMs) / 1000.0
            if (dt > 0) turnRate += (abs(DirectionSweep.delta(prev, h)) / dt - turnRate) * (dt / (dt + RATE_SMOOTHING_S))
        }
        lastHeading = h
        lastHeadingMs = t
        _state.update { it.copy(heading = h, tooFast = it.phase == PointPhase.SWEEPING && turnRate > MAX_TURN_DEG_PER_S) }
    }

    private fun onSample(s: RssiSample) {
        if (_state.value.phase != PointPhase.SWEEPING) return
        val h = sensor.headingAt(s.atMs) ?: return
        // Advert and connection RSSI differ in level: start over if the source switches.
        if (fromConnection != null && fromConnection != s.fromConnection) restartSweep()
        fromConnection = s.fromConnection
        if (turnRate > MAX_TURN_DEG_PER_S) return // smeared across too many degrees to place
        sweep.add(h, s.rssi)
        val done = sweep.coverage >= 1f && sweep.samples >= DONE_SAMPLES
        _state.update {
            it.copy(
                phase = if (done) PointPhase.DONE else PointPhase.SWEEPING,
                coverage = sweep.coverage,
                profile = sweep.profile().toList(),
                result = sweep.result(),
            )
        }
    }

    companion object {
        /** At ~10 readings/s this still lands several readings in every 30° sector. */
        const val MAX_TURN_DEG_PER_S = 90.0
        private const val RATE_SMOOTHING_S = 0.3
        private const val DONE_SAMPLES = 40
    }
}
