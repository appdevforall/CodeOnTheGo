package com.itsaky.androidide.utils

import android.util.Log

fun isTestMode(): Boolean {
    // Check system property (for unit tests)
    if (System.getProperty("androidide.test.mode") == "true") {
        return true
    }
    // Check if we're running under test instrumentation
    try {
        val instrumentationClass =
            Class.forName("androidx.test.platform.app.InstrumentationRegistry")
        val getInstrumentationMethod = instrumentationClass.getMethod("getInstrumentation")
        val instrumentation = getInstrumentationMethod.invoke(null)
        if (instrumentation != null) {
            Log.i("TestModeUtils", "Running under test instrumentation")
            return true
        }
    } catch (e: Exception) {
        // Not running under instrumentation
    }
    return false
}