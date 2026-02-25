package com.itsaky.androidide.utils

import android.os.Environment
import android.os.StatFs

fun hasEnoughStorageAvailable(): Boolean {
	return try {
		val dataDir = Environment.getDataDirectory()
		val stat = StatFs(dataDir.path)
		val availableBytes = stat.availableBlocksLong * stat.blockSizeLong
		availableBytes > getMinimumStorageNeeded().gigabytesToBytes()
	} catch (_: Exception) { false }
}

fun getMinimumStorageNeeded(): Long {
	val minimumStorageStableGB = 4L
	val minimumStorageExperimentalGB = 6L

	if (FeatureFlags.isExperimentsEnabled) {
		return minimumStorageExperimentalGB;
	}
	return minimumStorageStableGB;
}
