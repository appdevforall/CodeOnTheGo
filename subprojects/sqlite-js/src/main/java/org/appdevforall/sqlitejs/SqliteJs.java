package org.appdevforall.sqlitejs;

import android.content.Context;

/**
 * sqlite-js for Android apps: JavaScript functions in SQLite. The library is an SQLite extension, loaded by an SQLite that can load extensions, such as requery's sqlite-android:
 *
 * <pre>
 * new SQLiteCustomExtension(SqliteJs.libraryPath(context), SqliteJs.ENTRY_POINT)
 * </pre>
 *
 * The app must let the installer extract native libraries ({@code useLegacyPackaging = true}), since SQLite loads the extension from a file.
 */
public final class SqliteJs {

	/** The extension's file name in the app's native library directory. */
	public static final String LIBRARY = "libsqlitejs.so";

	/**
	 * Loads sqlite-js and the functions stored in the database's js_functions table (see js_init_table), on every connection: each connection has its own JavaScript runtime. A connection that opened before a function was stored does not have it, so store the functions before opening the database for use, with {@link #ENTRY_POINT_FOR_DEFINING}.
	 */
	public static final String ENTRY_POINT = "sqlite3_sqlitejs_init";

	/**
	 * Loads sqlite-js without the stored functions, for a connection that defines and stores them: a function that a connection already has cannot be redefined in it.
	 */
	public static final String ENTRY_POINT_FOR_DEFINING = "sqlite3_js_init";

	static {
		System.loadLibrary("sqlitejs");
	}

	/**
	 * Stops all JavaScript that is running now, in every connection: each call fails with an "interrupted" error. JavaScript that begins afterwards runs normally. Safe from any thread. Android's own cancelling (a CancellationSignal) cannot stop a JavaScript function.
	 */
	public static native void cancelAll();

	/** The path to give SQLite to load the extension. */
	public static String libraryPath(Context context) {
		return context.getApplicationInfo().nativeLibraryDir + "/" + LIBRARY;
	}

	private SqliteJs() {
		throw new UnsupportedOperationException();
	}
}
