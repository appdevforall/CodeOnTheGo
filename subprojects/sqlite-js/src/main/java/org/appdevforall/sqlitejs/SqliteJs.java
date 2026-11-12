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
	 * Loads sqlite-js and nothing stored in the database: no code stored in the file runs, so it is safe for any database, including one the app did not make. Use it also to define functions and store them (see js_init_table) before opening a database with {@link #ENTRY_POINT_FOR_TRUSTED_DATABASE}: a connection cannot redefine a function it already has.
	 */
	public static final String ENTRY_POINT = "sqlite3_sqlitejs_init";

	/**
	 * Loads sqlite-js and then the functions stored in the database's js_functions table (see js_init_table), on every connection: each connection has its own JavaScript runtime. Loading a stored function runs its code, which can run SQL and read the app's files, so use this only for a database the app made and keeps. Open a downloaded or shared file, or a restored backup, with {@link #ENTRY_POINT}. A connection that opened before a function was stored does not have it, so store the functions before opening the database for use. A stored function that fails to load is logged and skipped.
	 */
	public static final String ENTRY_POINT_FOR_TRUSTED_DATABASE = "sqlite3_sqlitejs_trusted_init";

	static {
		System.loadLibrary("sqlitejs");
	}

	/**
	 * Stops the JavaScript that is running now, in every query on every connection: each running call fails with an "interrupted" error, including calls in queries the app did not mean to stop, which then need to be retried. So call it only when a runaway function is known to be running, for example from a Cancel button, not whenever a coroutine is cancelled. It stops only JavaScript that has started: a query that has not reached its JavaScript yet runs it normally, so call it again until the query being stopped ends. Safe from any thread. Android's own cancelling (a CancellationSignal) cannot stop a JavaScript function.
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
