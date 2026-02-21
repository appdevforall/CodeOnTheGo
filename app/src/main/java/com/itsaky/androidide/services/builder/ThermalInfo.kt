package com.itsaky.androidide.services.builder

import android.content.Context
import android.os.Build
import android.os.PowerManager
import androidx.annotation.RequiresApi
import org.slf4j.LoggerFactory
import java.io.File

/** @author Akash Yadav */
object ThermalInfo {
	private val logger = LoggerFactory.getLogger(ThermalInfo::class.java)

	/**
	 * Check if the device is thermal throttled.
	 *
	 * Strategy (in priority order):
	 * 1. [PowerManager.getCurrentThermalStatus] – available from API 29 and
	 *    the most reliable indicator. Maps NONE/LIGHT/MODERATE to
	 *    [ThermalState.NotThrottled], SEVERE/CRITICAL/EMERGENCY/SHUTDOWN to
	 *    [ThermalState.Throttled].
	 * 2. Read `/sys/class/thermal/thermal_zone* /temp` against
	 *    `trip_point_*_temp` type "passive" to detect passive (throttling)
	 *    cooling events – available on virtually all Linux-kernel-based
	 *    Android devices down to API 21.
	 * 3. [ThermalState.Unknown] if neither source is available or readable.
	 */
	fun getThermalState(context: Context): ThermalState {
		// --- Strategy 1: PowerManager.getCurrentThermalStatus (API 29+) ---
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
			runCatching {
				return getThermalStateFromPowerManager(context)
			}.onFailure { err ->
				logger.warn(
					"Unable to read thermal state from PowerManager. " +
						"Falling back to sysfs thermal zones: {}",
					err.message,
				)
			}
		}

		// --- Strategy 2: sysfs thermal zones ---
		runCatching {
			return getThermalStateFromSysFs()
		}.onFailure { err ->
			logger.warn("Unable to read thermal state from sysfs thermal zones: {}", err.message)
		}

		// --- Strategy 3: unknown ---
		return ThermalState.Unknown
	}

	private fun getThermalStateFromSysFs(): ThermalState {
		// TODO(itsaky): This may not be the most reliable approach
		//               We currently check for *all* thermal zones, irrespective
		//               of the devices they belong to. For example, thermal_zone1
		//               might belong to the device's GPU instead of the CPU, while
		//               thermal_zone2 might belong to the device's battery. A GPU
		//               being throttled does not necessarily mean that the CPU is
		//               throttled as well.

		val thermalRoot = File("/sys/class/thermal")
		if (thermalRoot.isDirectory) {
			val zones =
				thermalRoot.listFiles { f ->
					f.isDirectory && f.name.startsWith("thermal_zone")
				} ?: emptyArray()

			for (zone in zones) {
				val currentTemp =
					File(zone, "temp")
						.takeIf { it.exists() }
						?.readText()
						?.trim()
						?.toLongOrNull()
						?: continue

				// Find all passive trip points for this zone.
				// Trip point files come in pairs: trip_point_N_temp / trip_point_N_type.
				val tripFiles =
					zone.listFiles { f ->
						f.name.matches(Regex("trip_point_\\d+_type"))
					} ?: continue

				for (typeFile in tripFiles) {
					val tripType = typeFile.readText().trim()
					if (tripType != "passive") continue

					// Extract index N from "trip_point_N_type"
					val index =
						typeFile.name
							.removePrefix("trip_point_")
							.removeSuffix("_type")

					val tripTemp =
						File(zone, "trip_point_${index}_temp")
							.takeIf { it.exists() }
							?.readText()
							?.trim()
							?.toLongOrNull()
							?: continue

					if (currentTemp >= tripTemp) {
						logger.info(
							"Thermal zone {} is throttled at {}°C (trip point: {}°C)",
							zone.name,
							currentTemp / 1000.0,
							tripTemp / 1000.0,
						)
						return ThermalState.Throttled
					}
				}
			}
		}

		// All zones checked without hitting a passive trip point.
		return ThermalState.NotThrottled
	}

	@RequiresApi(Build.VERSION_CODES.Q)
	private fun getThermalStateFromPowerManager(context: Context): ThermalState {
		val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
		return when (pm.currentThermalStatus) {
			PowerManager.THERMAL_STATUS_NONE,
			PowerManager.THERMAL_STATUS_LIGHT,
			PowerManager.THERMAL_STATUS_MODERATE,
			-> ThermalState.NotThrottled

			PowerManager.THERMAL_STATUS_SEVERE,
			PowerManager.THERMAL_STATUS_CRITICAL,
			PowerManager.THERMAL_STATUS_EMERGENCY,
			PowerManager.THERMAL_STATUS_SHUTDOWN,
			-> ThermalState.Throttled

			else -> ThermalState.Unknown
		}
	}
}
