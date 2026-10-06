package org.appdevforall.codeonthego.app

import org.appdevforall.codeonthego.utils.FeatureFlags
import leakcanary.LeakCanary

internal object LeakCanaryConfig {
	fun applyFromFeatureFlags() {
		if (FeatureFlags.isLeakCanaryDumpInhibited) {
			LeakCanary.config = LeakCanary.config.copy(dumpHeap = false)
		}
	}
}
