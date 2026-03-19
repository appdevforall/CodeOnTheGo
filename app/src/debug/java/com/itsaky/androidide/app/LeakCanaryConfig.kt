package com.itsaky.androidide.app

import com.itsaky.androidide.utils.FeatureFlags
import leakcanary.LeakCanary

internal object LeakCanaryConfig {
	fun applyFromFeatureFlags() {
		if (FeatureFlags.isLeakCanaryDumpInhibited) {
			LeakCanary.config = LeakCanary.config.copy(dumpHeap = false)
		}
	}
}
