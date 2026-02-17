package com.itsaky.androidide.services.builder

import android.app.ActivityManager
import android.content.Context
import android.os.StatFs
import com.itsaky.androidide.utils.Environment
import org.slf4j.LoggerFactory

/**
 * Utilities to get information about the device.
 *
 * @author Akash Yadav
 */
object DeviceInfo {
	private val logger = LoggerFactory.getLogger(DeviceInfo::class.java)

	/**
	 * Get the memory information of the device.
	 *
	 * @param context The context to use.
	 * @return The memory information.
	 */
	fun getMemInfo(context: Context): MemInfo {
		val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
		val memoryInfo = ActivityManager.MemoryInfo()
		activityManager.getMemoryInfo(memoryInfo)

		val totalMemMb = memoryInfo.totalMem / 1024 / 1024
		val availMemMb = memoryInfo.availMem / 1024 / 1024
		return MemInfo(
			totalMemMb = totalMemMb,
			availRamMb = availMemMb,
			isLowMemDevice = activityManager.isLowRamDevice,
		)
	}

	/**
	 * Create a device profile for the current device.
	 *
	 * @param context The context to use.
	 * @return The device profile.
	 */
	suspend fun buildDeviceProfile(context: Context): DeviceProfile {
		val memInfo = getMemInfo(context)
		val cpuTopology = CpuInfo.getCpuTopology()
		val thermalState = ThermalInfo.getThermalState(context)
		val availableStorageMb =
			runCatching {
				val stat = StatFs(Environment.DEFAULT_ROOT)
				stat.availableBytes / 1024 / 1024
			}.getOrDefault(0L)

		return DeviceProfile(
			mem = memInfo,
			cpu = cpuTopology,
			thermal = thermalState,
			storageFreeMb = availableStorageMb,
		)
	}
}
