package com.dotforge.notify

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager

/**
 * Whether the phone lies face down, for the "Only when face down" filter. The accelerometer only runs while
 * that filter is on, batched (readings may arrive a couple of seconds late) to keep it cheap. Main thread.
 */
class FaceDownSensor(context: Context) : SensorEventListener {
    private val manager = context.getSystemService(SensorManager::class.java)
    private val sensor = manager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private var running = false

    @Volatile private var z: Float? = null

    /** True face down, false face up or upright, null when unknown (filter off, no sensor, no reading yet). */
    val faceDown: Boolean? get() = z?.let { it < FACE_DOWN_Z }

    fun setEnabled(on: Boolean) {
        if (on == running || manager == null || sensor == null) return
        running = on
        if (on) {
            manager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_NORMAL, BATCH_US)
        } else {
            manager.unregisterListener(this)
            z = null
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        z = event.values[2]
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private companion object {
        /** Gravity is about -9.8 m/s² on z when the screen faces the table. */
        const val FACE_DOWN_Z = -7f
        const val BATCH_US = 2_000_000
    }
}
