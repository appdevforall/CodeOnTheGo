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

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import androidx.annotation.VisibleForTesting
import androidx.core.content.getSystemService
import com.itsaky.androidide.services.builder.ThermalInfo
import com.itsaky.androidide.services.builder.ThermalState
import com.itsaky.androidide.utils.PowerUsageWatcher.BatteryState
import com.itsaky.androidide.utils.PowerUsageWatcher.PowerReading
import org.slf4j.LoggerFactory
import kotlin.math.abs

/**
 * Reads temperature and power from the battery, which is all a normally-installed app can see
 * (ADFA-5499).
 *
 * `ACTION_BATTERY_CHANGED` is a broadcast, and this registers one receiver for it and keeps the
 * last Intent. Re-fetching the sticky Intent per sample with `registerReceiver(null, ...)` is a
 * synchronous binder round trip to the system server, and at the fastest offered rate that was ten
 * of them a second, for the life of the process, to re-read values that move on the order of
 * seconds. Registering costs one call and the broadcast then pushes every change (ADFA-5172 is the
 * repo's precedent: eliminate the operation rather than make it cheaper).
 *
 * Not read here, deliberately: the per-zone CPU, GPU and skin temperatures from
 * `HardwarePropertiesManager`. Those need `android.permission.DEVICE_POWER`, which is signature
 * level and cannot be granted to an installed app, so there is nothing to ask for and no fallback
 * worth attempting. A privileged build would supply a different `PowerSource`.
 */
class DevicePowerSource(
	private val context: Context,
) : PowerUsageWatcher.PowerSource,
	AutoCloseable {
	private val batteryManager = context.getSystemService<BatteryManager>()
	private val powerManager = context.getSystemService<PowerManager>()

	@Volatile
	private var lastBattery: Intent? = null

	private val batteryReceiver =
		object : BroadcastReceiver() {
			override fun onReceive(
				context: Context?,
				intent: Intent?,
			) {
				lastBattery = intent
			}
		}

	init {
		// The registration returns the sticky Intent, so the first sample has a value without
		// waiting for a change. Registered on the main looper: the receiver only stores a
		// reference, and the field it stores into is volatile for the sampling thread.
		lastBattery = context.registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
	}

	/** Stops listening. The source is unusable afterwards; [read] would go on reporting the last Intent. */
	override fun close() {
		runCatching { context.unregisterReceiver(batteryReceiver) }
			.onFailure { log.warn("Could not unregister the battery receiver", it) }
	}

	override fun read(): PowerReading {
		val battery = lastBattery

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

		return microWattsOrUnavailable(microAmps, milliVolts)
	}

	/**
	 * Turns a current and a voltage into microwatts, or [PowerUsageWatcher.UNAVAILABLE].
	 *
	 * Separated so the envelope can be asserted: [readPower] needs a BatteryManager and a sticky
	 * intent, and the part worth testing is arithmetic.
	 */
	@VisibleForTesting
	internal fun microWattsOrUnavailable(
		microAmps: Int,
		milliVolts: Int,
	): Long {
		val microWatts = microAmps.toLong() * milliVolts.toLong() / NANOWATTS_PER_MICROWATT

		// The sign of CURRENT_NOW is documented and not always honoured; the unit is the same
		// story. Several OEM kernels report milliamps, which divides the reading by a thousand:
		// a five-watt build then reads as five milliwatts, with no error path at all.
		//
		// A single sample cannot tell that apart from a genuinely tiny draw -- both are 5,000
		// microwatts -- so this is a plausibility floor, not a detector. It is set to reject the
		// range a misreported build actually lands in: a real draw of 0.01W to 100W misreported as
		// milliwatts gives 10 to 100,000 microwatts, and a phone running a Gradle build draws
		// watts, not milliwatts. The residual gaps are stated rather than papered over: a real
		// draw below MIN_PLAUSIBLE_MICROWATTS is rejected as implausible, and a misreport of a
		// draw above 10W would pass -- neither happens on a phone.
		//
		// The earlier comment here claimed the envelope caught the milliamp case at a 1,000
		// microwatt floor. It did not: five watts misreported is 5,000, comfortably inside it.
		val magnitude = abs(microWatts)
		return if (magnitude == 0L || magnitude in MIN_PLAUSIBLE_MICROWATTS..MAX_PLAUSIBLE_MICROWATTS) {
			microWatts
		} else {
			PowerUsageWatcher.UNAVAILABLE
		}
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
			// LIGHT, not SEVERE. The fallback knows only throttled or not, and its own
			// PowerManager mapping counts LIGHT and MODERATE as not throttled -- so one mild trip
			// point was painted with the middle hue of a six-level severity scale. Claim the least
			// the reading could mean.
			ThermalState.Throttled -> PowerManager.THERMAL_STATUS_LIGHT

			ThermalState.NotThrottled -> PowerManager.THERMAL_STATUS_NONE

			else -> PowerUsageWatcher.THERMAL_UNKNOWN
		}
	}

	private fun readBatteryState(battery: Intent?): BatteryState {
		battery ?: return BatteryState.UNKNOWN

		val level = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
		val scale = battery.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
		// EXTRA_PLUGGED rather than EXTRA_STATUS. A device held at a charge cap -- Adaptive
		// Charging, or any battery-protection limit -- reports NOT_CHARGING while plugged in, so
		// testing the status showed the battery readout for a device on mains power with its
		// current still reversed. Plugged is the question the readout actually asks.
		val plugged = battery.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)

		val percent =
			if (level < 0 || scale <= 0) {
				-1
			} else {
				level * 100 / scale
			}

		return BatteryState(
			levelPercent = percent,
			isCharging = plugged != 0,
		)
	}

	private companion object {
		private val log = LoggerFactory.getLogger(DevicePowerSource::class.java)

		/** Microamps times millivolts gives nanowatts; this scales the product to microwatts. */
		const val NANOWATTS_PER_MICROWATT = 1_000L

		/**
		 * Ten milliwatts.
		 *
		 * Below this, a non-zero reading is likelier a milliamp-for-microamp kernel than a real
		 * draw: it is a thousandth of the 10W ceiling a phone can actually reach, so any build
		 * misreported this way lands under it. A device deep in doze can draw single-digit
		 * milliwatts, which this would reject -- acceptable, because the chart exists to show what
		 * a build costs and a dozing device is not running one.
		 */
		const val MIN_PLAUSIBLE_MICROWATTS = 10_000L

		/** A hundred watts: no phone draws this, so that is a unit mismatch the other way. */
		const val MAX_PLAUSIBLE_MICROWATTS = 100_000_000L
	}
}
