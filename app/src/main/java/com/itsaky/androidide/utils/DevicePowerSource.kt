/*
 *  This file is part of AndroidIDE.
 *
 *  AndroidIDE is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  AndroidIDE is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *   along with AndroidIDE.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.itsaky.androidide.utils

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import androidx.core.content.getSystemService
import com.itsaky.androidide.services.builder.ThermalInfo
import com.itsaky.androidide.services.builder.ThermalState
import com.itsaky.androidide.utils.PowerUsageWatcher.BatteryState
import com.itsaky.androidide.utils.PowerUsageWatcher.PowerReading

/**
 * Reads temperature and power from the battery, which is all a normally-installed app can see
 * (ADFA-5499).
 *
 * `ACTION_BATTERY_CHANGED` is a sticky broadcast, so the current values can be read on demand with a
 * null receiver rather than by registering one and waiting -- which suits being polled on the
 * sampling tick.
 *
 * Not read here, deliberately: the per-zone CPU, GPU and skin temperatures from
 * `HardwarePropertiesManager`. Those need `android.permission.DEVICE_POWER`, which is signature
 * level and cannot be granted to an installed app, so there is nothing to ask for and no fallback
 * worth attempting. A privileged build would supply a different `PowerSource`.
 */
class DevicePowerSource(
	private val context: Context,
) : PowerUsageWatcher.PowerSource {
	private val batteryManager = context.getSystemService<BatteryManager>()
	private val powerManager = context.getSystemService<PowerManager>()

	override fun read(): PowerReading {
		val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))

		return PowerReading(
			temperatureMilliCelsius = readTemperature(battery),
			powerMicroWatts = readPower(battery),
			thermalStatus = readThermalStatus(),
			battery = readBatteryState(battery),
		)
	}

	/**
	 * Battery temperature. The broadcast reports tenths of a degree, which is coarser than the
	 * millidegrees stored, but storing the finer unit keeps the arithmetic honest if a privileged
	 * source ever supplies something better.
	 */
	private fun readTemperature(battery: Intent?): Long {
		val tenthsCelsius = battery?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
		if (tenthsCelsius == null || tenthsCelsius == Int.MIN_VALUE) {
			return PowerUsageWatcher.UNAVAILABLE
		}
		return tenthsCelsius.toLong() * 100L
	}

	/**
	 * Instantaneous draw, from current and voltage.
	 *
	 * Microamps times millivolts is nanowatts, so the product is scaled down to microwatts.
	 *
	 * The sign is the platform's, passed through unchanged: `BATTERY_PROPERTY_CURRENT_NOW` is
	 * positive for current entering the battery -- charging -- and negative for current leaving it.
	 * Not every OEM honours that, which is one reason the chart plots the magnitude rather than the
	 * signed value; the other is that a line dipping below zero reads as negative power spent.
	 */
	private fun readPower(battery: Intent?): Long {
		val microAmps = batteryManager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
		val milliVolts = battery?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, Int.MIN_VALUE)

		if (microAmps == null || microAmps == Int.MIN_VALUE ||
			milliVolts == null || milliVolts <= 0
		) {
			return PowerUsageWatcher.UNAVAILABLE
		}

		return microAmps.toLong() * milliVolts.toLong() / NANOWATTS_PER_MICROWATT
	}

	/**
	 * The platform's throttling level, which is what the chart shades by.
	 *
	 * Only API 29 and above report a graded level. Below that [ThermalInfo] can still say whether
	 * the device is throttled at all, which gives one shade instead of several.
	 */
	private fun readThermalStatus(): Int {
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
			val status = runCatching { powerManager?.currentThermalStatus }.getOrNull()
			if (status != null) {
				return status
			}
		}

		return when (ThermalInfo.getThermalState(context)) {
			ThermalState.Throttled -> PowerManager.THERMAL_STATUS_SEVERE
			ThermalState.NotThrottled -> PowerManager.THERMAL_STATUS_NONE
			else -> PowerUsageWatcher.THERMAL_UNKNOWN
		}
	}

	private fun readBatteryState(battery: Intent?): BatteryState {
		battery ?: return BatteryState.UNKNOWN

		val level = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
		val scale = battery.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
		val status = battery.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN)

		val percent =
			if (level < 0 || scale <= 0) {
				-1
			} else {
				level * 100 / scale
			}

		return BatteryState(
			levelPercent = percent,
			isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL,
		)
	}

	private companion object {
		/** Microamps times millivolts gives nanowatts; this scales the product to microwatts. */
		const val NANOWATTS_PER_MICROWATT = 1_000L
	}
}
