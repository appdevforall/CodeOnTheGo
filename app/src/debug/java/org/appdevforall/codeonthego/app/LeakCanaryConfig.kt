package org.appdevforall.codeonthego.app

import leakcanary.LeakCanary
import org.appdevforall.codeonthego.utils.FeatureFlags

internal object LeakCanaryConfig {
	fun applyFromFeatureFlags() {
		if (FeatureFlags.isLeakCanaryDumpInhibited) {
			LeakCanary.config = LeakCanary.config.copy(dumpHeap = false)
		}
	}
}
