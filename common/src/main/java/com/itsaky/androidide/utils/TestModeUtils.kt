package com.itsaky.androidide.utils

fun isTestMode(): Boolean {
    // Check system property (for unit tests)
    if (System.getProperty("androidide.test.mode") == "true") {
        return true
    }

	return VMUtils.isInstrumentedTest
}