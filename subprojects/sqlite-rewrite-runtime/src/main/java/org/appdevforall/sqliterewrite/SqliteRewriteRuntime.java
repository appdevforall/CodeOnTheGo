package org.appdevforall.sqliterewrite;

import android.content.Context;
import android.database.DatabaseErrorHandler;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteStatement;
import android.os.Build;
import android.os.CancellationSignal;
import android.text.TextUtils;

import java.io.File;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.WeakHashMap;

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

	private static final int CONTEXT_MODE_WORLD_READABLE = 0x1;
	private static final int CONTEXT_MODE_WORLD_WRITEABLE = 0x2;
	private static final int CONTEXT_MODE_ENABLE_WRITE_AHEAD_LOGGING = 0x8;
	private static final int ANDROID_N = 24;

	/**
	 * The listener each Android signal forwards cancellation through. Weak keys, so a signal the app dropped goes, and weak values: a WeakHashMap keeps the value of a cleared key until a later call on the map, so a strong value kept a dropped signal's forwarder alive until the next conversion. The signal's own listener field keeps its forwarder alive while the signal lives.
	 */
	private static final Map<CancellationSignal, WeakReference<CancelForwarder>> FORWARDERS = new WeakHashMap<>();

	/**
	 * Same as Context.deleteDatabase, which also returns false on any exception. requery's deleteDatabase removes the -journal, -wal, -shm and -mj files next to the file, like Android's. A null context still throws the NullPointerException the original call threw, so it is checked before the catch.
	 */
	public static boolean deleteDatabase(Context context, String name) {
		Objects.requireNonNull(context);
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
	 * Context.openOrCreateDatabase without the file permission bits of {@code mode}: Android applies them with a hidden API, and the modes other than private were deprecated in API 17. The world-access bits are checked first, as ContextImpl.checkMode does (AOSP android-15.0.0_r1): on Android 7 or later, an app that targets API 24 or later gets a SecurityException for MODE_WORLD_READABLE or MODE_WORLD_WRITEABLE, before the database path is resolved. An app that targets an older API gets no exception here, and Android's world-readable or world-writable file is not made either.
	 */
	public static SQLiteDatabase openOrCreateDatabase(Context context, String name, int mode,
			SQLiteDatabase.CursorFactory factory, DatabaseErrorHandler errorHandler) {
		checkMode(context, mode);
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
	 * Android's signal for requery's. Each call returns a new requery signal, and cancelling Android's cancels every one of them still in use: requery keeps a query's signal for the cursor's later reads, so two lazy queries that share an Android signal must both stop. Android's signal holds one listener, so this replaces any listener the caller set on it (Android's setOnCancelListener documents that it replaces the previous one). The forwarder lives only as long as it is that listener: if the caller sets its own listener after a query, the forwarder can be collected, the next call starts a new one, and queries converted before the caller's listener no longer stop with the signal.
	 */
	public static androidx.core.os.CancellationSignal toCancellationSignal(
			CancellationSignal signal) {
		if (signal == null) {
			return null;
		}
		final androidx.core.os.CancellationSignal converted = new androidx.core.os.CancellationSignal();
		CancelForwarder forwarder;
		synchronized (FORWARDERS) {
			WeakReference<CancelForwarder> ref = FORWARDERS.get(signal);
			forwarder = ref == null ? null : ref.get();
			if (forwarder == null) {
				forwarder = new CancelForwarder();
				FORWARDERS.put(signal, new WeakReference<>(forwarder));
			}
		}
		// the local keeps the new forwarder alive until setOnCancelListener stores it in the signal
		forwarder.add(converted);
		signal.setOnCancelListener(forwarder);
		// setOnCancelListener does not call a listener that is already set, so a signal cancelled before this call
		// has to be checked
		if (signal.isCanceled()) {
			converted.cancel();
		}
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

	private static void checkMode(Context context, int mode) {
		if (Build.VERSION.SDK_INT >= ANDROID_N
				&& context.getApplicationInfo().targetSdkVersion >= ANDROID_N) {
			if ((mode & CONTEXT_MODE_WORLD_READABLE) != 0) {
				throw new SecurityException("MODE_WORLD_READABLE no longer supported");
			}
			if ((mode & CONTEXT_MODE_WORLD_WRITEABLE) != 0) {
				throw new SecurityException("MODE_WORLD_WRITEABLE no longer supported");
			}
		}
	}

	private SqliteRewriteRuntime() {}

	/**
	 * Cancels every requery signal converted from one Android signal. It holds them weakly: a query's signal lives as long as its cursor, and a closed and dropped cursor must not be kept for a signal the app reuses.
	 */
	private static final class CancelForwarder implements CancellationSignal.OnCancelListener {
		private final List<WeakReference<androidx.core.os.CancellationSignal>> converted = new ArrayList<>();

		@Override
		public void onCancel() {
			List<androidx.core.os.CancellationSignal> live = new ArrayList<>();
			synchronized (this) {
				for (WeakReference<androidx.core.os.CancellationSignal> ref : converted) {
					androidx.core.os.CancellationSignal signal = ref.get();
					if (signal != null) {
						live.add(signal);
					}
				}
				converted.clear();
			}
			// outside the lock: requery's listener interrupts the native query
			for (androidx.core.os.CancellationSignal signal : live) {
				signal.cancel();
			}
		}

		synchronized void add(androidx.core.os.CancellationSignal signal) {
			for (int i = converted.size() - 1; i >= 0; i--) {
				if (converted.get(i).get() == null) {
					converted.remove(i);
				}
			}
			converted.add(new WeakReference<>(signal));
		}
	}
}
