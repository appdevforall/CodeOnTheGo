package com.itsaky.androidide.quickbuild.runtime;

import android.util.Log;

/**
 * The runtime's only logging entry point, under one tag so a device walk can follow a whole reload with a single logcat filter.
 *
 * Calls android.util.Log directly, because this AAR ships into arbitrary user apps and must not carry a logging dependency. Every call is guarded because android.util.Log is an unmocked stub in JVM unit tests and throws there; on device it never throws, so the guard costs nothing.
 */
final class RuntimeLog {

	static final String TAG = "QuickBuildRuntime";

	static void d(String message) {
		try {
			Log.d(TAG, message);
		} catch (Throwable ignored) {
			// Logging must never alter behavior.
		}
	}

	static void e(String message, Throwable error) {
		try {
			Log.e(TAG, message, error);
		} catch (Throwable ignored) {
			// Logging must never alter behavior.
		}
	}

	static void i(String message) {
		try {
			Log.i(TAG, message);
		} catch (Throwable ignored) {
			// Logging must never alter behavior.
		}
	}

	static void w(String message) {
		try {
			Log.w(TAG, message);
		} catch (Throwable ignored) {
			// Logging must never alter behavior.
		}
	}

	static void w(String message, Throwable error) {
		try {
			Log.w(TAG, message, error);
		} catch (Throwable ignored) {
			// Logging must never alter behavior.
		}
	}

	private RuntimeLog() {}
}
