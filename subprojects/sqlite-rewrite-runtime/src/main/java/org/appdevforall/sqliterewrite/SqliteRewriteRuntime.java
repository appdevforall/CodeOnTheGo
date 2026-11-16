package org.appdevforall.sqliterewrite;

import android.content.Context;
import android.database.DatabaseErrorHandler;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteStatement;
import android.os.CancellationSignal;
import android.text.TextUtils;

import java.io.File;

/**
 * Targets of the calls that CoGo's Gradle plugin redirects when it rewrites an app from android.database.sqlite to requery (ADFA-7138).
 *
 * <p>
 * The methods are written against Android's classes on purpose: the plugin rewrites this library like every other class of the app, so {@code SQLiteDatabase} and {@code DatabaseErrorHandler} below become requery's in the final APK. The plugin leaves this package's own calls alone, otherwise a redirect would call itself.
 *
 * <p>
 * Flag mapping, Android -> requery 3.49.0 (read from its classes.jar):
 *
 * <pre>
 * OPEN_READWRITE (0)                           -> OPEN_READWRITE (2)
 * OPEN_READONLY (1)                            -> OPEN_READONLY (1)
 * CREATE_IF_NECESSARY (0x10000000)             -> OPEN_CREATE (4), so 6 with read-write; dropped with OPEN_READONLY
 * ENABLE_WRITE_AHEAD_LOGGING (0x20000000)      -> same value in requery, kept
 * NO_LOCALIZED_COLLATORS (16)                  -> dropped, requery has no collator support
 * any other bit                                -> dropped
 * </pre>
 *
 * Other bits are dropped because requery hands them to sqlite3_open_v2, where 16 and 8 mean SQLITE_OPEN_EXCLUSIVE and SQLITE_OPEN_DELETEONCLOSE: an Android flag must never turn into one of those.
 */
public final class SqliteRewriteRuntime {
	private static final int ANDROID_OPEN_READONLY = 1;
	private static final int ANDROID_CREATE_IF_NECESSARY = 0x10000000;
	private static final int ANDROID_ENABLE_WRITE_AHEAD_LOGGING = 0x20000000;

	private static final int REQUERY_OPEN_READONLY = 1;
	private static final int REQUERY_OPEN_READWRITE = 2;
	private static final int REQUERY_OPEN_CREATE = 4;
	private static final int REQUERY_ENABLE_WRITE_AHEAD_LOGGING = 0x20000000;

	private static final int CONTEXT_MODE_ENABLE_WRITE_AHEAD_LOGGING = 0x8;

	/**
	 * Same as Context.deleteDatabase, which also returns false on any exception. requery's deleteDatabase removes the -journal, -wal, -shm and -mj files next to the file, like Android's.
	 */
	public static boolean deleteDatabase(Context context, String name) {
		try {
			return SQLiteDatabase.deleteDatabase(context.getDatabasePath(name));
		} catch (Exception e) {
			return false;
		}
	}

	public static long longForQuery(SQLiteDatabase db, String query, String[] selectionArgs) {
		SQLiteStatement prog = db.compileStatement(query);
		try {
			return longForQuery(prog, selectionArgs);
		} finally {
			prog.close();
		}
	}

	public static long longForQuery(SQLiteStatement prog, String[] selectionArgs) {
		prog.bindAllArgsAsStrings(selectionArgs);
		return prog.simpleQueryForLong();
	}

	public static SQLiteDatabase openDatabase(String path, SQLiteDatabase.CursorFactory factory,
			int flags) {
		return SQLiteDatabase.openDatabase(path, factory, translateOpenFlags(flags));
	}

	public static SQLiteDatabase openDatabase(String path, SQLiteDatabase.CursorFactory factory,
			int flags, DatabaseErrorHandler errorHandler) {
		return SQLiteDatabase.openDatabase(path, factory, translateOpenFlags(flags),
				errorHandler);
	}

	public static SQLiteDatabase openOrCreateDatabase(Context context, String name, int mode,
			SQLiteDatabase.CursorFactory factory) {
		return openOrCreateDatabase(context, name, mode, factory, null);
	}

	/**
	 * Context.openOrCreateDatabase without the file permission bits of {@code mode}: Android applies them with a hidden API, and the modes other than private were deprecated in API 17.
	 */
	public static SQLiteDatabase openOrCreateDatabase(Context context, String name, int mode,
			SQLiteDatabase.CursorFactory factory, DatabaseErrorHandler errorHandler) {
		int flags = ANDROID_CREATE_IF_NECESSARY;
		if ((mode & CONTEXT_MODE_ENABLE_WRITE_AHEAD_LOGGING) != 0) {
			flags |= ANDROID_ENABLE_WRITE_AHEAD_LOGGING;
		}
		File file = context.getDatabasePath(name);
		File parent = file.getParentFile();
		if (parent != null && !parent.isDirectory()) {
			// getDatabasePath does this for the default directory only
			parent.mkdirs();
		}
		return openDatabase(file.getPath(), factory, flags, errorHandler);
	}

	/**
	 * The DatabaseUtils methods that take SQLiteDatabase or SQLiteStatement, as Android implements them (requery has no DatabaseUtils). They call only members requery has, so they stay on this class.
	 */
	public static long queryNumEntries(SQLiteDatabase db, String table) {
		return queryNumEntries(db, table, null, null);
	}

	public static long queryNumEntries(SQLiteDatabase db, String table, String selection) {
		return queryNumEntries(db, table, selection, null);
	}

	public static long queryNumEntries(SQLiteDatabase db, String table, String selection,
			String[] selectionArgs) {
		String s = (!TextUtils.isEmpty(selection)) ? " where " + selection : "";
		return longForQuery(db, "select count(*) from " + table + s, selectionArgs);
	}

	public static String stringForQuery(SQLiteDatabase db, String query, String[] selectionArgs) {
		SQLiteStatement prog = db.compileStatement(query);
		try {
			return stringForQuery(prog, selectionArgs);
		} finally {
			prog.close();
		}
	}

	public static String stringForQuery(SQLiteStatement prog, String[] selectionArgs) {
		prog.bindAllArgsAsStrings(selectionArgs);
		return prog.simpleQueryForString();
	}

	/**
	 * Android's signal for requery's. Android's signal holds one listener, so this replaces any listener the caller set on it (Android's setOnCancelListener documents that it replaces the previous one). It fires at once when the signal is cancelled already.
	 */
	public static androidx.core.os.CancellationSignal toCancellationSignal(
			CancellationSignal signal) {
		if (signal == null) {
			return null;
		}
		final androidx.core.os.CancellationSignal converted = new androidx.core.os.CancellationSignal();
		if (signal.isCanceled()) {
			converted.cancel();
			return converted;
		}
		signal.setOnCancelListener(new CancellationSignal.OnCancelListener() {
			@Override
			public void onCancel() {
				converted.cancel();
			}
		});
		return converted;
	}

	/** Converts Android's open flags to requery's. */
	public static int translateOpenFlags(int androidFlags) {
		boolean readOnly = (androidFlags & ANDROID_OPEN_READONLY) != 0;
		int flags = readOnly ? REQUERY_OPEN_READONLY : REQUERY_OPEN_READWRITE;
		// SQLite accepts READONLY, READWRITE and READWRITE|CREATE, and rejects READONLY|CREATE
		if (!readOnly && (androidFlags & ANDROID_CREATE_IF_NECESSARY) != 0) {
			flags |= REQUERY_OPEN_CREATE;
		}
		if ((androidFlags & ANDROID_ENABLE_WRITE_AHEAD_LOGGING) != 0) {
			flags |= REQUERY_ENABLE_WRITE_AHEAD_LOGGING;
		}
		return flags;
	}

	private SqliteRewriteRuntime() {}
}
