package com.itsaky.androidide.services.builder

/**
 * Information about a device.
 *
 * @property mem The memory information of the device.
 * @property cpu The CPU topology of the device.
 * @property thermal The thermal state of the device.
 * @property storageFreeMb The amount of free storage space on the device.
 * @author Akash Yadav
 */
data class DeviceProfile(
	val mem: MemInfo,
	val cpu: CpuTopology,
	val thermal: ThermalState,
	val storageFreeMb: Long,
) {

	/**
	 * Whether the device is thermal throttled.
	 */
	val isThermalThrottled: Boolean
		get() = thermal == ThermalState.Throttled
}

/**
 * The thermal state of the device.
 */
enum class ThermalState {

	/**
	 * The device is not thermal throttled.
	 */
	NotThrottled,

	/**
	 * The device is thermal throttled.
	 */
	Throttled,

	/**
	 * The thermal status is unknown. Assume [NotThrottled].
	 */
	Unknown,
}

/**
 * Information about the RAM.
 *
 * @property totalMemMb The total amount of RAM in MB.
 * @property availRamMb The amount of available RAM in MB.
 * @property isLowMemDevice Whether the device is a low memory device.
 * @author Akash Yadav
 */
data class MemInfo(
	val totalMemMb: Long,
	val availRamMb: Long,
	val isLowMemDevice: Boolean,
)

/**
 * Topological information about the CPU.
 *
 * @property primeCores The number of prime cores. This is available only if the
 *                      device cores are categorized as prime, big and small.
 *                      Otherwise, only [bigCores] and [smallCores] are available.
 * @property bigCores The number of big cores.
 * @property smallCores The number of small cores.
 * @property totalCores The total number of cores.
 * @author Akash Yadav
 */
data class CpuTopology(
	val primeCores: Int?,
	val bigCores: Int,
	val smallCores: Int,
	val totalCores: Int,
)
