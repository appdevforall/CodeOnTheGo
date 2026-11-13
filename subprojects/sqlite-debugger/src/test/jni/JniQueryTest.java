import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.appdevforall.sqlitedebugger.engine.DebugEngineException;
import org.appdevforall.sqlitedebugger.engine.NativeBridge;

/**
 * The real JNI query path (sqlitedbg_jni.c over sqlitedbg.c) on a host JVM, against a SQLite built
 * like requery's. test.sh builds libsqlite3x.so, libsqlitedbg.so (no SQLITE_CORE) and libhosthelper.so
 * and runs this with them. It calls NativeBridge directly; the Kotlin above it is covered by the
 * JVM unit tests with a fake engine.
 */
public class JniQueryTest {
	static int failures, checks;

	static void check(boolean ok, String what) {
		checks++;
		if (!ok) {
			failures++;
			System.out.println("FAIL " + what);
		}
	}

	static void eq(Object a, Object b, String what) {
		check(Objects.equals(a, b), what + ": " + show(a) + " vs " + show(b));
	}

	static String show(Object o) {
		return o instanceof byte[] ? Arrays.toString((byte[]) o) : String.valueOf(o);
	}

	static final long FAR = Long.MAX_VALUE / 2;
	static final int MAX = 1 << 30;

	/** What NativeBridge.query returns, unpacked. */
	static class Result {
		String[] columns;
		List<Object[]> rows;
		long truncated;
		boolean hasMore;
		Set<Long> cut = new HashSet<>();
	}

	@SuppressWarnings("unchecked")
	static Result query(long h, String sql, Object[] args, long offset, int maxRows, int maxText, int maxBlob, int maxTotal,
			long deadline) throws DebugEngineException {
		Object[] r = NativeBridge.query(h, sql, args, offset, maxRows, maxText, maxBlob, maxTotal, deadline);
		Result out = new Result();
		out.columns = (String[]) r[0];
		out.rows = (List<Object[]>) r[1];
		long[] counts = (long[]) r[2];
		out.truncated = counts[0];
		out.hasMore = counts[1] != 0;
		eq(counts.length, 2 + (int) counts[0], "one index per cut cell");
		for (int i = 2; i < counts.length; i++) out.cut.add(counts[i]);
		return out;
	}

	static Result q(long h, String sql) throws DebugEngineException {
		return query(h, sql, new Object[0], 0, 1000, 4096, 1024, MAX, FAR);
	}

	static int codeOf(long h, String sql, Object[] args, long deadline) {
		try {
			query(h, sql, args, 0, 100, 4096, 1024, MAX, deadline);
			return 0;
		} catch (DebugEngineException e) {
			return e.sqliteCode;
		}
	}

	static Set<Long> set(Long... v) {
		return new HashSet<>(Arrays.asList(v));
	}

	public static void main(String[] a) throws Exception {
		System.loadLibrary("sqlite3x");
		System.loadLibrary("sqlitedbg");
		System.loadLibrary("hosthelper");

		try {
			NativeBridge.openServer("/nonexistent/x.db".getBytes(StandardCharsets.UTF_8), false);
			check(false, "open before install");
		} catch (DebugEngineException e) {
			eq(e.sqliteCode, 21, "SQLITE_MISUSE before any app connection");
		}
		eq(NativeBridge.install(), 0, "install");
		eq(NativeBridge.install(), 0, "install twice");

		Path dir = Files.createTempDirectory("jniquery");
		String path = dir.resolve("app.db").toString();
		long app = Helper.open(path);
		eq(Helper.exec(app, "CREATE TABLE t(id INTEGER, r REAL, s TEXT, b BLOB)"), 0, "create");
		eq(Helper.exec(app, "INSERT INTO t VALUES(1, 1.5, 'one', x'0102'),(2, NULL, 'héllo €', x''),(3, 3e999, NULL, NULL)"), 0,
				"insert");
		eq(Helper.exec(app, "WITH RECURSIVE c(x) AS (SELECT 1 UNION ALL SELECT x+1 FROM c WHERE x<300) INSERT INTO t(id) SELECT x+10 FROM c"),
				0, "insert 300");

		Object[] conns = NativeBridge.connections();
		eq(((String[]) conns[1]).length, 1, "one app connection");
		eq(((String[]) conns[1])[0], path, "its path");

		byte[] bytes = path.getBytes(StandardCharsets.UTF_8);
		long srv = NativeBridge.openServer(bytes, false);
		check(srv != 0, "server handle");

		// value types
		Result r = q(srv, "SELECT id, r, s, b FROM t WHERE id < 10 ORDER BY id");
		eq(Arrays.asList(r.columns), Arrays.asList("id", "r", "s", "b"), "columns");
		eq(r.rows.size(), 3, "rows");
		eq(r.rows.get(0)[0], 1L, "long");
		eq(r.rows.get(0)[1], 1.5, "double");
		eq(r.rows.get(0)[2], "one", "text");
		check(Arrays.equals((byte[]) r.rows.get(0)[3], new byte[] {1, 2}), "blob");
		eq(r.rows.get(1)[1], null, "null");
		eq(r.rows.get(1)[2], "héllo €", "utf8 text");
		eq(((byte[]) r.rows.get(1)[3]).length, 0, "empty blob");
		eq(r.rows.get(2)[1], Double.POSITIVE_INFINITY, "infinity");
		eq(r.hasMore, false, "no more");
		eq(r.truncated, 0L, "nothing cut");

		// paging: 300 rows with id >= 10
		String all = "SELECT id FROM t WHERE id >= 10 ORDER BY id";
		r = query(srv, all, new Object[0], 0, 100, 4096, 1024, MAX, FAR);
		eq(r.rows.size(), 100, "page 1");
		eq(r.hasMore, true, "has more after a full page");
		r = query(srv, all, new Object[0], 100, 100, 4096, 1024, MAX, FAR);
		eq(r.rows.get(0)[0], 111L, "page 2 starts after the offset");
		r = query(srv, all, new Object[0], 200, 100, 4096, 1024, MAX, FAR);
		eq(r.rows.size(), 100, "page 3");
		eq(r.hasMore, false, "exactly the last row of the last page: no more");
		r = query(srv, all, new Object[0], 299, 100, 4096, 1024, MAX, FAR);
		eq(r.rows.size(), 1, "last page");
		eq(r.rows.get(0)[0], 310L, "last id");
		r = query(srv, all, new Object[0], 5000, 100, 4096, 1024, MAX, FAR);
		eq(r.rows.size(), 0, "past the end");
		eq(r.hasMore, false, "past the end has no more");

		// M4: the total-bytes budget. 8 bytes per number: 3 numbers = 24 a row, 4 rows fit in 100.
		String nums = "WITH RECURSIVE c(x) AS (SELECT 1 UNION ALL SELECT x+1 FROM c WHERE x<50) SELECT x, x, x FROM c";
		r = query(srv, nums, new Object[0], 0, 1000, 4096, 1024, 100, FAR);
		eq(r.rows.size(), 4, "budget 100 holds 4 rows of 24");
		eq(r.hasMore, true, "budget reached: has more");
		r = query(srv, nums, new Object[0], 4, 1000, 4096, 1024, 100, FAR);
		eq(r.rows.get(0)[0], 5L, "the next page starts where the budget stopped");
		r = query(srv, nums, new Object[0], 0, 1000, 4096, 1024, 24 * 50, FAR);
		eq(r.rows.size(), 50, "a budget of exactly the whole result holds it");
		eq(r.hasMore, false, "and has no more");
		// text and blob count the bytes kept, not the bytes stored
		String fat = "WITH RECURSIVE c(x) AS (SELECT 1 UNION ALL SELECT x+1 FROM c WHERE x<5) "
				+ "SELECT substr(hex(zeroblob(1000)), 1, 1000), zeroblob(500) FROM c";
		r = query(srv, fat, new Object[0], 0, 1000, 4096, 1024, 3200, FAR);
		eq(r.rows.size(), 2, "1500 bytes a row: 2 rows in 3200");
		eq(r.hasMore, true, "fat rows: has more");
		r = query(srv, fat, new Object[0], 0, 1000, 100, 100, 3200, FAR);
		eq(r.rows.size(), 5, "cells cut to 100 bytes cost 200 a row: all 5 fit");
		// the first row always goes in, so a page makes progress
		r = query(srv, fat, new Object[0], 0, 1000, 4096, 1024, 10, FAR);
		eq(r.rows.size(), 1, "one row over the budget is still returned");
		eq(r.hasMore, true, "and more follows");

		// L11: per-cell truncation, exactly at the limit is whole
		r = query(srv, "SELECT x'01020304', x'0102030405', 'abcd', 'abcde'", new Object[0], 0, 10, 4, 4, MAX, FAR);
		eq(((byte[]) r.rows.get(0)[0]).length, 4, "blob at the limit");
		eq(((byte[]) r.rows.get(0)[1]).length, 4, "blob over the limit is cut");
		eq(r.rows.get(0)[2], "abcd", "text at the limit");
		eq(r.rows.get(0)[3], "abcd", "text over the limit is cut");
		eq(r.truncated, 2L, "two cells cut");
		eq(r.cut, set(1L, 3L), "cut cells are the over-limit blob and text, not the ones at the limit");
		r = query(srv, "SELECT x'01020304', x'0102030405', 'abcd', 'abcde' UNION ALL SELECT x'0102030405', x'01', 'abcde', 'a'",
				new Object[0], 0, 10, 4, 4, MAX, FAR);
		eq(r.cut, set(1L, 3L, 4L, 6L), "indexes are row * columns + column");
		eq(r.truncated, 4L, "four cells cut");
		// a character is never split: 'a' + 3 emoji of 4 bytes, 6 bytes allowed
		r = query(srv, "SELECT 'a' || '😀😀😀', x'0102030405'", new Object[0], 0, 10, 6, 3, MAX, FAR);
		eq(r.rows.get(0)[0], "a😀", "text cut at a character boundary");
		eq(((byte[]) r.rows.get(0)[1]).length, 3, "blob cut");
		eq(r.cut, set(0L, 1L), "both cut");
		// a row left out by the budget takes its cut cells with it
		r = query(srv, "SELECT 'abcde' UNION ALL SELECT 'abcde'", new Object[0], 0, 10, 4, 4, 6, FAR);
		eq(r.rows.size(), 1, "the second row would pass the budget");
		eq(r.cut, set(0L), "only the kept row's cut cell is reported");
		eq(r.truncated, 1L, "and counted");

		// bound values of every type
		r = query(srv, "SELECT ?, ?, ?, ?, ?", new Object[] {7L, 2.25, "a😀b", new byte[] {9, 8, 7}, null}, 0, 10, 4096, 1024,
				MAX, FAR);
		Object[] row = r.rows.get(0);
		eq(row[0], 7L, "arg long");
		eq(row[1], 2.25, "arg double");
		eq(row[2], "a😀b", "arg text");
		check(Arrays.equals((byte[]) row[3], new byte[] {9, 8, 7}), "arg blob");
		eq(row[4], null, "arg null");
		eq(q(srv, "SELECT ?").rows.get(0)[0], null, "an unbound parameter is NULL");
		eq(codeOf(srv, "SELECT ?", new Object[] {1L, 2L}, FAR), 25, "too many args: SQLITE_RANGE");
		eq(codeOf(srv, "SELECT ?", new Object[] {new Object()}, FAR), 21, "unsupported arg type: SQLITE_MISUSE");
		eq(codeOf(srv, "SELEKT 1", new Object[0], FAR), 1, "syntax error: SQLITE_ERROR");

		// a refused write is SQLITE_AUTH, and the data is untouched
		eq(codeOf(srv, "INSERT INTO t(id) VALUES(5)", new Object[0], FAR), 23, "write refused: SQLITE_AUTH");
		eq(codeOf(srv, "DELETE FROM t", new Object[0], FAR), 23, "delete refused: SQLITE_AUTH");
		eq(q(srv, "SELECT count(*) FROM t WHERE id = 5").rows.get(0)[0], 0L, "nothing written");

		// H3: a transaction cannot be opened, so the app is never locked out
		for (String sql : new String[] {"BEGIN", "BEGIN IMMEDIATE", "SAVEPOINT s", "PRAGMA locking_mode=EXCLUSIVE", "PRAGMA busy_timeout=60000"}) {
			eq(codeOf(srv, sql, new Object[0], FAR), 23, sql + " refused: SQLITE_AUTH");
			q(srv, "SELECT count(*) FROM t");
			eq(Helper.exec(app, "INSERT INTO t(id) VALUES(4242)"), 0, "the app can write after " + sql);
			Helper.exec(app, "DELETE FROM t WHERE id = 4242");
		}

		// the deadline stops a statement that never ends, and the connection stays usable
		long t0 = System.currentTimeMillis();
		eq(codeOf(srv, "WITH RECURSIVE c(x) AS (SELECT 1 UNION ALL SELECT x+1 FROM c) SELECT count(*) FROM c", new Object[0], t0 + 300), 9,
				"deadline: SQLITE_INTERRUPT");
		long took = System.currentTimeMillis() - t0;
		check(took >= 250 && took < 3000, "the deadline stopped it in " + took + " ms");
		eq(codeOf(srv, "SELECT 1", new Object[0], t0 - 1), 9, "a deadline already past: SQLITE_INTERRUPT");
		eq(q(srv, "SELECT 41 + 1").rows.get(0)[0], 42L, "usable after the deadline");
		eq(Helper.exec(app, "INSERT INTO t(id) VALUES(4243)"), 0, "the app can write after an interrupted query");
		Helper.exec(app, "DELETE FROM t WHERE id = 4243");

		// the server connection is not listed
		eq(((String[]) NativeBridge.connections()[1]).length, 1, "server not listed");
		NativeBridge.close(srv);

		// the backstop: a writable connection that opens a transaction does not keep it
		long w = NativeBridge.openServer(bytes, true);
		q(w, "BEGIN IMMEDIATE");
		eq(Helper.exec(app, "INSERT INTO t(id) VALUES(4244)"), 0, "the app can write after a writable BEGIN IMMEDIATE");
		Helper.exec(app, "DELETE FROM t WHERE id = 4244");
		q(w, "BEGIN");
		q(w, "INSERT INTO t(id) VALUES(4245)");
		eq(q(w, "SELECT count(*) FROM t WHERE id = 4245").rows.get(0)[0], 1L, "an autocommit insert on the writable connection stays");
		NativeBridge.close(w);
		Helper.exec(app, "DELETE FROM t WHERE id = 4245");

		try {
			NativeBridge.openServer(dir.resolve("none.db").toString().getBytes(StandardCharsets.UTF_8), false);
			check(false, "missing file");
		} catch (DebugEngineException e) {
			eq(e.sqliteCode, 14, "SQLITE_CANTOPEN");
		}

		eq(Helper.close(app), 0, "close the app connection");
		eq(((String[]) NativeBridge.connections()[1]).length, 0, "all freed");
		System.out.println(checks + " checks, " + failures + " failures");
		System.exit(failures == 0 ? 0 : 1);
	}
}
