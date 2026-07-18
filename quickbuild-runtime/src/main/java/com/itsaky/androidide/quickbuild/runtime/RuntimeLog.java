package com.itsaky.androidide.quickbuild.runtime;

import android.util.Log;

/**
 * Single logging front for the runtime. One tag so a device walk can follow the whole reload flow with a single logcat filter; android.util.Log directly because this AAR ships into arbitrary user apps and must not carry a logging dependency.
 */
final class RuntimeLog {

	static final String TAG = "QuickBuildRuntime";

	static void d(String message) {
		Log.d(TAG, message);
	}

	static void e(String message, Throwable error) {
		Log.e(TAG, message, error);
	}

	static void i(String message) {
		Log.i(TAG, message);
	}

	static void w(String message) {
		Log.w(TAG, message);
	}

	static void w(String message, Throwable error) {
		Log.w(TAG, message, error);
	}

	private RuntimeLog() {}
}
