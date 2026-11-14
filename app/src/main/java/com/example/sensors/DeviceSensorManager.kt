package com.example.sensors

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import com.example.domain.HardwareSensorMetadata
import com.example.domain.LiveSensorSample
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

class DeviceSensorManager(context: Context) {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager

    private val expectedCoreSensors = listOf(
        Sensor.TYPE_ACCELEROMETER to "Accelerometer",
        Sensor.TYPE_GYROSCOPE to "Gyroscope",
        Sensor.TYPE_PROXIMITY to "Proximity",
        Sensor.TYPE_LIGHT to "Light",
        Sensor.TYPE_MAGNETIC_FIELD to "Magnetic Field",
        Sensor.TYPE_ROTATION_VECTOR to "Rotation Vector / Orientation",
        Sensor.TYPE_PRESSURE to "Pressure (Barometer)",
        Sensor.TYPE_AMBIENT_TEMPERATURE to "Ambient Temperature"
    )

    fun getSensorInventory(): List<HardwareSensorMetadata> {
        val sm = sensorManager ?: return expectedCoreSensors.map { (type, label) ->
            HardwareSensorMetadata(
                name = "Unavailable",
                type = type,
                typeLabel = label,
                vendor = "N/A",
                version = 0,
                resolution = 0f,
                maxRange = 0f,
                powerMa = 0f,
                minDelayUs = 0,
                isAvailable = false
            )
        }

        val allSensors = sm.getSensorList(Sensor.TYPE_ALL)
        val presentTypes = allSensors.map { it.type }.toSet()

        val availableList = allSensors.map { sensor ->
            HardwareSensorMetadata(
                name = sensor.name,
                type = sensor.type,
                typeLabel = mapSensorTypeLabel(sensor.type),
                vendor = sensor.vendor,
                version = sensor.version,
                resolution = sensor.resolution,
                maxRange = sensor.maximumRange,
                powerMa = sensor.power,
                minDelayUs = sensor.minDelay,
                isAvailable = true
            )
        }

        val missingCoreList = expectedCoreSensors
            .filter { (type, _) -> type !in presentTypes }
            .map { (type, label) ->
                HardwareSensorMetadata(
                    name = "Not present on hardware",
                    type = type,
                    typeLabel = label,
                    vendor = "N/A",
                    version = 0,
                    resolution = 0f,
                    maxRange = 0f,
                    powerMa = 0f,
                    minDelayUs = 0,
                    isAvailable = false
                )
            }

        return availableList + missingCoreList
    }

    /**
     * Streams live values for key available sensors using battery-efficient SENSOR_DELAY_UI.
     * Listeners are automatically unregistered when the Flow collector stops.
     */
    fun observeLiveSensors(): Flow<Map<Int, LiveSensorSample>> = callbackFlow {
        val sm = sensorManager
        if (sm == null) {
            trySend(emptyMap())
            close()
            return@callbackFlow
        }

        val targetTypes = listOf(
            Sensor.TYPE_ACCELEROMETER,
            Sensor.TYPE_GYROSCOPE,
            Sensor.TYPE_PROXIMITY,
            Sensor.TYPE_LIGHT,
            Sensor.TYPE_MAGNETIC_FIELD,
            Sensor.TYPE_ROTATION_VECTOR,
            Sensor.TYPE_PRESSURE,
            Sensor.TYPE_AMBIENT_TEMPERATURE
        )

        val currentSamples = LinkedHashMap<Int, LiveSensorSample>()
        var lastEmitMs = 0L

        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                val sample = LiveSensorSample(
                    sensorType = event.sensor.type,
                    typeLabel = mapSensorTypeLabel(event.sensor.type),
                    sensorName = event.sensor.name,
                    values = event.values.toList(),
                    unit = mapSensorUnit(event.sensor.type),
                    accuracy = event.accuracy,
                    timestampNs = event.timestamp
                )
                synchronized(currentSamples) {
                    currentSamples[event.sensor.type] = sample
                    val now = android.os.SystemClock.elapsedRealtime()
                    if (now - lastEmitMs >= 180L) {
                        lastEmitMs = now
                        trySend(currentSamples.toMap())
                    }
                }
            }

            override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {
                // Handled on next event
            }
        }

        val registeredSensors = mutableListOf<Sensor>()
        for (type in targetTypes) {
            val sensor = sm.getDefaultSensor(type)
            if (sensor != null) {
                if (sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_UI)) {
                    registeredSensors.add(sensor)
                }
            }
        }

        trySend(emptyMap())

        awaitClose {
            if (registeredSensors.isNotEmpty()) {
                sm.unregisterListener(listener)
            }
        }
    }

    private fun mapSensorTypeLabel(type: Int): String = when (type) {
        Sensor.TYPE_ACCELEROMETER -> "Accelerometer"
        Sensor.TYPE_GYROSCOPE -> "Gyroscope"
        Sensor.TYPE_PROXIMITY -> "Proximity"
        Sensor.TYPE_LIGHT -> "Light"
        Sensor.TYPE_MAGNETIC_FIELD -> "Magnetic Field"
        Sensor.TYPE_ROTATION_VECTOR -> "Rotation Vector"
        Sensor.TYPE_GRAVITY -> "Gravity"
        Sensor.TYPE_LINEAR_ACCELERATION -> "Linear Acceleration"
        Sensor.TYPE_PRESSURE -> "Pressure"
        Sensor.TYPE_AMBIENT_TEMPERATURE -> "Ambient Temperature"
        Sensor.TYPE_STEP_COUNTER -> "Step Counter"
        Sensor.TYPE_STEP_DETECTOR -> "Step Detector"
        Sensor.TYPE_SIGNIFICANT_MOTION -> "Significant Motion"
        else -> "Sensor Type $type"
    }

    private fun mapSensorUnit(type: Int): String = when (type) {
        Sensor.TYPE_ACCELEROMETER, Sensor.TYPE_GRAVITY, Sensor.TYPE_LINEAR_ACCELERATION -> "m/s²"
        Sensor.TYPE_GYROSCOPE -> "rad/s"
        Sensor.TYPE_PROXIMITY -> "cm"
        Sensor.TYPE_LIGHT -> "lx"
        Sensor.TYPE_MAGNETIC_FIELD -> "µT"
        Sensor.TYPE_PRESSURE -> "hPa"
        Sensor.TYPE_AMBIENT_TEMPERATURE -> "°C"
        Sensor.TYPE_ROTATION_VECTOR -> "unitless"
        else -> "raw"
    }
}
