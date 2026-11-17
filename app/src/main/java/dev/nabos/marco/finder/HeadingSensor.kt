package dev.nabos.marco.finder

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * Which way the phone (and so the person holding it in front of them) faces,
 * in degrees clockwise seen from above.
 *
 * Prefers the game rotation vector: gyro + accelerometer only, so steel and
 * speakers indoors don't bend it. Its zero is arbitrary, which is fine because
 * a sweep only compares headings with each other.
 */
class HeadingSensor(context: Context) : SensorEventListener {

    private val manager = context.getSystemService(SensorManager::class.java)
    private val sensor: Sensor? = manager?.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
        ?: manager?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

    val available: Boolean get() = sensor != null

    private val _heading = MutableStateFlow<Double?>(null)
    /** Null until the first reading. */
    val heading: StateFlow<Double?> = _heading.asStateFlow()

    private val history = HeadingHistory()
    private val r = FloatArray(9)

    /** Where the phone faced at [atMs] (elapsedRealtime), if that's within the last few seconds. */
    fun headingAt(atMs: Long): Double? = history.at(atMs)

    fun start() {
        sensor?.let { manager?.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
    }

    fun stop() {
        manager?.unregisterListener(this)
        history.clear()
        _heading.value = null
    }

    override fun onSensorChanged(event: SensorEvent) {
        SensorManager.getRotationMatrixFromVector(r, event.values)
        // Columns of r are the device axes in world coordinates (x east, y north).
        // Facing direction = top edge (Y) when held flat, back (-Z) when held
        // upright; summing their horizontal parts blends smoothly in between.
        val east = r[1] - r[2]
        val north = r[4] - r[5]
        if (hypot(east, north) < 0.3f) return // pointing at the floor or ceiling: keep the last heading
        val h = DirectionSweep.normalize(Math.toDegrees(atan2(east, north).toDouble()))
        history.add(event.timestamp / 1_000_000, h) // sensor timestamps share the elapsedRealtime clock
        _heading.value = h
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit
}

/**
 * Recent headings, so an RSSI reading can be paired with where you faced when it
 * was measured rather than when it arrived. While turning, that difference
 * shifts every reading the same way and would skew the result.
 */
class HeadingHistory(private val keepMs: Long = 3_000) {
    private val times = ArrayDeque<Long>()
    private val values = ArrayDeque<Double>()

    fun add(atMs: Long, deg: Double) {
        if (times.isNotEmpty() && atMs <= times.last()) return
        times.addLast(atMs)
        values.addLast(deg)
        while (times.first() < atMs - keepMs) {
            times.removeFirst()
            values.removeFirst()
        }
    }

    fun clear() {
        times.clear()
        values.clear()
    }

    /** Interpolated heading at [atMs]; the newest one if later, null if older than the history. */
    fun at(atMs: Long): Double? {
        if (times.isEmpty() || atMs < times.first()) return null
        if (atMs >= times.last()) return values.last()
        var i = times.size - 1
        while (times[i - 1] > atMs) i--
        val t0 = times[i - 1]
        val f = (atMs - t0).toDouble() / (times[i] - t0)
        val h0 = values[i - 1]
        return DirectionSweep.normalize(h0 + f * DirectionSweep.delta(h0, values[i]))
    }
}
