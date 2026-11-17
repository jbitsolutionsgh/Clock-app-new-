package com.example.core

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import java.util.Calendar

/** Runtime hardware & permission availability status for Diagnostics. */
enum class SensorAvailability(val label: String) {
  AVAILABLE("Available"),
  PERMISSION_NEEDED("Permission needed"),
  NO_HARDWARE("No hardware"),
}

/** Granular sensor streams so a watch face activates only the sensors it actually references. */
enum class FaceSensorType {
  STEP_COUNTER,
  HEART_RATE,
  LIGHT,
  PRESSURE,
  ACCELEROMETER,
}

/** Snapshot of phone sensor readiness displayed in Diagnostics. */
data class FaceSensorDiagnostics(
  val steps: SensorAvailability = SensorAvailability.NO_HARDWARE,
  val heartRate: SensorAvailability = SensorAvailability.NO_HARDWARE,
  val light: SensorAvailability = SensorAvailability.NO_HARDWARE,
  val pressure: SensorAvailability = SensorAvailability.NO_HARDWARE,
  val accelerometer: SensorAvailability = SensorAvailability.NO_HARDWARE,
  val stepPermissionGranted: Boolean = false,
  val heartRatePermissionGranted: Boolean = false,
  val activeListenersCount: Int = 0,
)

/** Live sensor readings consumed by Watch Face Format expressions and complications. */
data class FaceSensorData(
  val stepCount: Int = 0,
  val stepGoal: Int = 10_000,
  val hasStepPermission: Boolean = false,
  val stepHardwarePresent: Boolean = false,
  val heartRate: Int = 0,
  val heartRateAvailable: Boolean = false,
  val hasHeartRatePermission: Boolean = false,
  val heartRateHardwarePresent: Boolean = false,
  val lightLux: Float = 0f,
  val lightAvailable: Boolean = false,
  val pressureHpa: Float = 0f,
  val pressureAvailable: Boolean = false,
  val accelX: Float = 0f,
  val accelY: Float = 0f,
  val accelZ: Float = 0f,
  val accelAvailable: Boolean = false,
)

/** Public contract in core managing on-demand offline sensor streams for visible watch faces. */
interface FaceSensorSource {
  val diagnostics: StateFlow<FaceSensorDiagnostics>
  fun observeSensors(requested: Set<FaceSensorType>): Flow<FaceSensorData>
  fun hasStepPermission(): Boolean
  fun hasStepHardware(): Boolean
  fun hasHeartRatePermission(): Boolean
  fun hasHeartRateHardware(): Boolean
  fun heartRatePermission(): String
  fun refreshDiagnostics()
}

/**
 * On-demand phone sensor provider backed by Android [SensorManager].
 * Registers listeners strictly for the [FaceSensorType]s needed by a visible face and unregisters
 * them immediately when the collector cancels (screen off or navigation away).
 */
internal class CoreFaceSensorSource(
  context: Context,
  private val prefs: AuraPrefs,
) : FaceSensorSource {

  private val appContext = context.applicationContext
  private val sensorManager: SensorManager? =
    appContext.getSystemService(Context.SENSOR_SERVICE) as? SensorManager

  private val _diagnostics = MutableStateFlow(buildDiagnostics(0))
  override val diagnostics: StateFlow<FaceSensorDiagnostics> = _diagnostics.asStateFlow()

  private var activeCount = 0

  override fun hasStepPermission(): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return true
    return ContextCompat.checkSelfPermission(
      appContext,
      Manifest.permission.ACTIVITY_RECOGNITION,
    ) == PackageManager.PERMISSION_GRANTED
  }

  override fun hasStepHardware(): Boolean =
    sensorManager?.getDefaultSensor(Sensor.TYPE_STEP_COUNTER) != null

  override fun heartRatePermission(): String =
    if (Build.VERSION.SDK_INT >= 36) PERMISSION_READ_HEART_RATE else Manifest.permission.BODY_SENSORS

  override fun hasHeartRatePermission(): Boolean =
    ContextCompat.checkSelfPermission(appContext, heartRatePermission()) == PackageManager.PERMISSION_GRANTED

  override fun hasHeartRateHardware(): Boolean =
    sensorManager?.getDefaultSensor(Sensor.TYPE_HEART_RATE) != null

  override fun refreshDiagnostics() {
    _diagnostics.value = buildDiagnostics(activeCount)
  }

  override fun observeSensors(requested: Set<FaceSensorType>): Flow<FaceSensorData> = callbackFlow {
    val stepHw = hasStepHardware()
    val stepPerm = hasStepPermission()
    val hrHw = hasHeartRateHardware()
    val hrPerm = hasHeartRatePermission()
    val lightHw = sensorManager?.getDefaultSensor(Sensor.TYPE_LIGHT) != null
    val pressureHw = sensorManager?.getDefaultSensor(Sensor.TYPE_PRESSURE) != null
    val accelHw = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) != null

    var current = FaceSensorData(
      stepCount = prefs.lastDailySteps.value,
      stepGoal = prefs.stepGoal.value,
      hasStepPermission = stepPerm,
      stepHardwarePresent = stepHw,
      hasHeartRatePermission = hrPerm,
      heartRateHardwarePresent = hrHw,
      lightAvailable = false,
      pressureAvailable = false,
      accelAvailable = false,
    )
    trySend(current)

    val mgr = sensorManager
    if (mgr == null || requested.isEmpty()) {
      awaitClose { }
      return@callbackFlow
    }

    val listener = object : SensorEventListener {
      override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
          Sensor.TYPE_STEP_COUNTER -> {
            val cumulative = event.values.firstOrNull() ?: return
            val todayKey = currentDayKey()
            val (savedDay, savedBase) = prefs.readStepBaseline()
            val (dailySteps, newBase) = computeDailySteps(
              cumulativeSinceBoot = cumulative,
              todayKey = todayKey,
              savedDayKey = savedDay,
              savedBaseline = savedBase,
            )
            prefs.saveStepState(todayKey, newBase, dailySteps)
            current = current.copy(stepCount = dailySteps, stepGoal = prefs.stepGoal.value)
            trySend(current)
          }
          Sensor.TYPE_HEART_RATE -> {
            val bpm = event.values.firstOrNull()?.toInt() ?: 0
            current = current.copy(heartRate = bpm, heartRateAvailable = bpm > 0)
            trySend(current)
          }
          Sensor.TYPE_LIGHT -> {
            val lux = event.values.firstOrNull() ?: 0f
            current = current.copy(lightLux = lux, lightAvailable = true)
            trySend(current)
          }
          Sensor.TYPE_PRESSURE -> {
            val hpa = event.values.firstOrNull() ?: 0f
            current = current.copy(pressureHpa = hpa, pressureAvailable = true)
            trySend(current)
          }
          Sensor.TYPE_ACCELEROMETER -> {
            if (event.values.size >= 3) {
              current = current.copy(
                accelX = event.values[0],
                accelY = event.values[1],
                accelZ = event.values[2],
                accelAvailable = true,
              )
              trySend(current)
            }
          }
        }
      }

      override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    var registeredCount = 0
    fun tryRegister(type: FaceSensorType, androidSensorType: Int) {
      if (type !in requested) return
      if (type == FaceSensorType.STEP_COUNTER && !hasStepPermission()) return
      if (type == FaceSensorType.HEART_RATE && !hasHeartRatePermission()) return
      val sensor = mgr.getDefaultSensor(androidSensorType) ?: return
      val ok = runCatching {
        mgr.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_UI)
      }.getOrDefault(false)
      if (ok) registeredCount++
    }

    tryRegister(FaceSensorType.STEP_COUNTER, Sensor.TYPE_STEP_COUNTER)
    tryRegister(FaceSensorType.HEART_RATE, Sensor.TYPE_HEART_RATE)
    tryRegister(FaceSensorType.LIGHT, Sensor.TYPE_LIGHT)
    tryRegister(FaceSensorType.PRESSURE, Sensor.TYPE_PRESSURE)
    tryRegister(FaceSensorType.ACCELEROMETER, Sensor.TYPE_ACCELEROMETER)

    synchronized(this@CoreFaceSensorSource) {
      activeCount += registeredCount
      _diagnostics.value = buildDiagnostics(activeCount)
    }

    awaitClose {
      if (registeredCount > 0) {
        runCatching { mgr.unregisterListener(listener) }
        synchronized(this@CoreFaceSensorSource) {
          activeCount = (activeCount - registeredCount).coerceAtLeast(0)
          _diagnostics.value = buildDiagnostics(activeCount)
        }
      }
    }
  }

  private fun buildDiagnostics(active: Int): FaceSensorDiagnostics {
    val mgr = sensorManager
    val stepPerm = hasStepPermission()
    val stepSensor = mgr?.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
    val stepsStatus = when {
      stepSensor == null -> SensorAvailability.NO_HARDWARE
      !stepPerm -> SensorAvailability.PERMISSION_NEEDED
      else -> SensorAvailability.AVAILABLE
    }
    val hrPerm = hasHeartRatePermission()
    val hrSensor = mgr?.getDefaultSensor(Sensor.TYPE_HEART_RATE)
    val hrStatus = when {
      hrSensor == null -> SensorAvailability.NO_HARDWARE
      !hrPerm -> SensorAvailability.PERMISSION_NEEDED
      else -> SensorAvailability.AVAILABLE
    }
    val lightStatus = if (mgr?.getDefaultSensor(Sensor.TYPE_LIGHT) != null) {
      SensorAvailability.AVAILABLE
    } else {
      SensorAvailability.NO_HARDWARE
    }
    val pressureStatus = if (mgr?.getDefaultSensor(Sensor.TYPE_PRESSURE) != null) {
      SensorAvailability.AVAILABLE
    } else {
      SensorAvailability.NO_HARDWARE
    }
    val accelStatus = if (mgr?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) != null) {
      SensorAvailability.AVAILABLE
    } else {
      SensorAvailability.NO_HARDWARE
    }
    return FaceSensorDiagnostics(
      steps = stepsStatus,
      heartRate = hrStatus,
      light = lightStatus,
      pressure = pressureStatus,
      accelerometer = accelStatus,
      stepPermissionGranted = stepPerm,
      heartRatePermissionGranted = hrPerm,
      activeListenersCount = active,
    )
  }

  companion object {
    private const val PERMISSION_READ_HEART_RATE = "android.permission.health.READ_HEART_RATE"
    /** Returns an integer YYYYDDD day key for rollover comparison. */
    fun currentDayKey(cal: Calendar = Calendar.getInstance()): Int =
      cal.get(Calendar.YEAR) * 1000 + cal.get(Calendar.DAY_OF_YEAR)

    /**
     * Converts a boot-cumulative [Sensor.TYPE_STEP_COUNTER] reading into today's step count
     * and updated baseline, handling midnight day rollovers and device reboots cleanly.
     */
    fun computeDailySteps(
      cumulativeSinceBoot: Float,
      todayKey: Int,
      savedDayKey: Int,
      savedBaseline: Float,
    ): Pair<Int, Float> {
      val safeCum = cumulativeSinceBoot.coerceAtLeast(0f)
      val baseline = if (savedDayKey != todayKey || savedBaseline < 0f || safeCum < savedBaseline) {
        safeCum
      } else {
        savedBaseline
      }
      val daily = (safeCum - baseline).toInt().coerceAtLeast(0)
      return daily to baseline
    }
  }
}
