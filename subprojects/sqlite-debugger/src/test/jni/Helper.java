/** Opens and uses "app" connections with the same libsqlite3x.so the debugger runs in. */
public class Helper {
	static long open(String path) {
		long db = openBytes(path.getBytes(java.nio.charset.StandardCharsets.UTF_8));
		if (db == 0) throw new IllegalStateException("sqlite3_open failed for " + path);
		return db;
	}

	private static native long openBytes(byte[] utf8Path);

	static native int autocommit(long db);

	static native int exec(long db, String sql);

	static native int close(long db);

	static native int busyTimeout(long db, int ms);

	static native int denyTransactions(long db, boolean deny);
}
