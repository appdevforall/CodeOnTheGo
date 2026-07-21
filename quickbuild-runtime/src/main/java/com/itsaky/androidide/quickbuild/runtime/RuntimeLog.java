package com.itsaky.androidide.quickbuild.runtime;

import android.util.Log;

/**
 * Single logging front for the runtime. One tag so a device walk can follow the whole reload flow with a single logcat filter; android.util.Log directly because this AAR ships into arbitrary user apps and must not carry a logging dependency.
 *
 * Every call is guarded: in JVM unit tests android.util.Log is the unmocked stub and throws, and logging must never change behavior - on device Log never throws, so the guard is free.
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
