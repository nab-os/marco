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

    private val r = FloatArray(9)

    fun start() {
        sensor?.let { manager?.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
    }

    fun stop() {
        manager?.unregisterListener(this)
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
        _heading.value = DirectionSweep.normalize(Math.toDegrees(atan2(east, north).toDouble()))
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit
}
