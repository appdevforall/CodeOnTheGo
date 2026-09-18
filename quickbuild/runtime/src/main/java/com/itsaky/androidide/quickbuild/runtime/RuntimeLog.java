package com.itsaky.androidide.quickbuild.runtime;

import android.util.Log;

/**
 * The runtime's only logging entry point, under one tag so a device walk can follow a whole reload with a single logcat filter. The tag carries the same QB- prefix every Quick Build tag does, so one grep spans this process and CoGo's.
 *
 * Calls android.util.Log directly, because this AAR ships into arbitrary user apps and must not carry a logging dependency. Every call is guarded because android.util.Log is an unmocked stub in JVM unit tests and throws there; on device it never throws, so the guard costs nothing.
 */
final class RuntimeLog {

	/** The single logcat tag every runtime message carries. */
	static final String TAG = "QB-Runtime";

	/**
	 * Logs at debug level, for the step-by-step detail of a reload.
	 *
	 * @param message
	 *            the line to log; passed through unformatted
	 */
	static void d(String message) {
		try {
			Log.d(TAG, message);
		} catch (Throwable ignored) {
			// Logging must never alter behavior.
		}
	}

	/**
	 * Logs at debug level with the swallowed cause attached, mirroring {@link #e} and {@link #w}.
	 *
	 * Prefer this over concatenating the exception into the message: {@code toString()} names the exception and nothing else, while the attached trace names where it came from - which is the whole question on the skipped-step paths that log at this level.
	 *
	 * @param message
	 *            the line to log; passed through unformatted
	 * @param error
	 *            the cause to attach, printed with its stack trace; may be null
	 */
	static void d(String message, Throwable error) {
		try {
			Log.d(TAG, message, error);
		} catch (Throwable ignored) {
			// Logging must never alter behavior.
		}
	}

	/**
	 * Logs at error level, for a failure that cost the user a reload.
	 *
	 * @param message
	 *            the line to log; passed through unformatted
	 * @param error
	 *            the cause to attach, printed with its stack trace; may be null
	 */
	static void e(String message, Throwable error) {
		try {
			Log.e(TAG, message, error);
		} catch (Throwable ignored) {
			// Logging must never alter behavior.
		}
	}

	/**
	 * Logs at info level, for the milestones of a reload a device walk follows.
	 *
	 * @param message
	 *            the line to log; passed through unformatted
	 */
	static void i(String message) {
		try {
			Log.i(TAG, message);
		} catch (Throwable ignored) {
			// Logging must never alter behavior.
		}
	}

	/**
	 * Logs at warning level, for a degraded path the runtime recovered from.
	 *
	 * @param message
	 *            the line to log; passed through unformatted
	 */
	static void w(String message) {
		try {
			Log.w(TAG, message);
		} catch (Throwable ignored) {
			// Logging must never alter behavior.
		}
	}

	/**
	 * Logs at warning level with the swallowed cause attached.
	 *
	 * @param message
	 *            the line to log; passed through unformatted
	 * @param error
	 *            the cause to attach, printed with its stack trace; may be null
	 */
	static void w(String message, Throwable error) {
		try {
			Log.w(TAG, message, error);
		} catch (Throwable ignored) {
			// Logging must never alter behavior.
		}
	}

	private RuntimeLog() {}
}
