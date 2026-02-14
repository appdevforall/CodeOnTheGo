package com.itsaky.androidide.services.builder

/**
 * @author Akash Yadav
 */
data class DeviceProfile(
	val totalRamMb: Int,
	val availRamMb: Int,
	val lowRam: Boolean,
	val cpuCores: Int,
	val bigCores: Int?,
	val thermalThrottled: Boolean,
	val storageFreeMb: Int,
)
