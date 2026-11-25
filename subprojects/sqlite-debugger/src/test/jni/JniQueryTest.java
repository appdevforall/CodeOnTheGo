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

	/** "code message" of the refusal, or null when the query ran. */
	static String refusal(long h, String sql) {
		try {
			q(h, sql);
			return null;
		} catch (DebugEngineException e) {
			return e.sqliteCode + " " + e.getMessage();
		}
	}

	static Set<Long> set(Long... v) {
		return new HashSet<>(Arrays.asList(v));
	}

	static long exec(long h, String sql, Object... args) throws DebugEngineException {
		long[] r = NativeBridge.execute(h, sql, args, FAR);
		eq(r.length, 2, "execute returns {changes, last_insert_rowid}");
		return r[0];
	}

	/** The rowid execute reports, or null (0 on the wire) when the statement inserted no row. */
	static Long rowid(long h, String sql, Object... args) throws DebugEngineException {
		long r = NativeBridge.execute(h, sql, args, FAR)[1];
		return r == 0 ? null : r;
	}

	static int execCode(long h, String sql, Object[] args, long deadline) {
		try {
			NativeBridge.execute(h, sql, args, deadline);
			return 0;
		} catch (DebugEngineException e) {
			return e.sqliteCode;
		}
	}

	/** "code message" of the refusal, or null when the statement ran. */
	static String execRefusal(long h, String sql) {
		try {
			NativeBridge.execute(h, sql, new Object[0], FAR);
			return null;
		} catch (DebugEngineException e) {
			return e.sqliteCode + " " + e.getMessage();
		}
	}

	/** ADFA-7298: execute on a writable server connection. */
	static void execute(Path dir, String path, long app) throws Exception {
		long w = NativeBridge.openServer(path.getBytes(StandardCharsets.UTF_8), true);
		long r = NativeBridge.openServer(path.getBytes(StandardCharsets.UTF_8), false);

		// a write reports its own change count, and the app sees it
		eq(exec(w, "INSERT INTO t(id, s) VALUES(?, ?), (?, ?)", 6001L, "a", 6002L, "b"), 2L, "insert 2: changes 2");
		eq(exec(w, "UPDATE t SET s = ? WHERE id >= ?", "z", 6001L), 2L, "update 2: changes 2");
		eq(exec(w, "DELETE FROM t WHERE id = :id", 6001L), 1L, "delete 1, named parameter: changes 1");
		eq(exec(w, "DELETE FROM t WHERE id = -1"), 0L, "delete none: changes 0");
		// a statement that changes no row reports 0, not the previous write's count (sqlite3_changes64 keeps it)
		eq(exec(w, "UPDATE t SET s = 'y' WHERE id = 6002"), 1L, "update 1");
		eq(exec(w, "CREATE TABLE ex(a)"), 0L, "CREATE TABLE: changes 0, not the update's 1");
		eq(exec(w, "PRAGMA user_version = 7"), 0L, "a PRAGMA that stores a value runs: changes 0");
		eq(q(r, "PRAGMA user_version").rows.get(0)[0], 7L, "and is stored");
		eq(exec(w, "PRAGMA user_version = 0"), 0L, "user_version back");
		eq(exec(w, "DROP TABLE ex"), 0L, "DROP TABLE: changes 0");
		eq(exec(w, "INSERT INTO t(id) VALUES(6003) RETURNING id"), 1L, "RETURNING rows are read and dropped: changes 1");
		eq(q(r, "SELECT count(*) FROM t WHERE id IN (6002, 6003)").rows.get(0)[0], 2L, "the writes are visible to a reader");
		eq(Helper.exec(app, "DELETE FROM t WHERE id IN (6002, 6003)"), 0, "the app's own connection writes after them");
		eq(Helper.autocommit(app), 1, "and the app's connection is in autocommit");

		// last_insert_rowid: the writable connection is cached, so SQLite's value outlives each statement
		eq(exec(w, "CREATE TABLE lr(a UNIQUE)"), 0L, "create lr");
		eq(exec(w, "CREATE TABLE lrw(a PRIMARY KEY) WITHOUT ROWID"), 0L, "create lrw");
		eq(rowid(w, "INSERT INTO lr VALUES(1)"), 1L, "INSERT reports its rowid");
		eq(rowid(w, "INSERT INTO lr VALUES(2)"), 2L, "the next INSERT reports the next rowid");
		eq(rowid(w, "UPDATE lr SET a = a + 10"), null, "an UPDATE after INSERTs reports null, not their rowid");
		eq(rowid(w, "INSERT INTO lr VALUES(3)"), 3L, "an INSERT again");
		eq(rowid(w, "INSERT INTO lrw VALUES(1)"), null, "INSERT into a WITHOUT ROWID table: null, not the previous rowid");
		eq(rowid(w, "INSERT INTO lr VALUES(4)"), 4L, "an INSERT again");
		eq(rowid(w, "INSERT OR IGNORE INTO lr VALUES(4)"), null, "INSERT OR IGNORE that inserts nothing: null");
		eq(rowid(w, "INSERT INTO lr VALUES(5), (6), (7)"), 7L, "a multi-row INSERT reports the last row's rowid");
		eq(rowid(w, "DELETE FROM lr"), null, "a DELETE reports null");
		// SQLite restores the value when a trigger ends, so inserts made only by a trigger report null
		eq(exec(w, "CREATE TABLE lt(a)"), 0L, "create lt");
		eq(exec(w, "CREATE TRIGGER lt_up AFTER UPDATE ON lt BEGIN INSERT INTO lr VALUES(100); END"), 0L, "create trigger");
		eq(rowid(w, "INSERT INTO lt VALUES(1)"), 1L, "an INSERT with no trigger on it reports its rowid");
		eq(rowid(w, "UPDATE lt SET a = 2"), null, "an UPDATE whose trigger inserts a row reports null");
		eq(q(r, "SELECT count(*) FROM lr").rows.get(0)[0], 1L, "the trigger did insert");
		eq(exec(w, "DROP TABLE lt"), 0L, "drop lt");
		eq(exec(w, "DROP TABLE lr"), 0L, "drop lr");
		eq(exec(w, "DROP TABLE lrw"), 0L, "drop lrw");

		// a statement that does not write belongs to query
		eq(execRefusal(w, "SELECT 1"), "1 the statement does not write; run it with query", "a SELECT is refused");
		eq(execRefusal(w, "PRAGMA user_version"), "1 the statement does not write; run it with query", "a PRAGMA read is refused");
		// one statement, the same argument rules as query
		eq(execRefusal(w, "DELETE FROM t WHERE id = -1; DELETE FROM t"),
				"1 the SQL holds more than one statement; run one statement per call", "a second statement");
		eq(q(r, "SELECT count(*) FROM t WHERE id = 1").rows.get(0)[0], 1L, "nothing deleted by the refused second statement");
		eq(execCode(w, "DELETE FROM t WHERE id = ?", new Object[0], FAR), 25, "a missing argument: SQLITE_RANGE");
		eq(execCode(w, "DELETE FROM t WHERE id = ?", new Object[] {1L, 2L}, FAR), 25, "an extra argument: SQLITE_RANGE");
		eq(execCode(w, "INSERT INTO nope VALUES(1)", new Object[0], FAR), 1, "an SQLite error: SQLITE_ERROR");
		eq(execCode(w, "COMMIT", new Object[0], FAR), 23, "COMMIT refused");
		eq(execCode(w, "ROLLBACK", new Object[0], FAR), 23, "ROLLBACK refused");
		eq(execCode(w, "PRAGMA journal_mode = DELETE", new Object[0], FAR), 23, "journal_mode change refused");
		eq(execCode(w, "PRAGMA busy_timeout = 60000", new Object[0], FAR), 23, "busy_timeout refused");
		eq(execCode(w, "DELETE FROM t WHERE id = -1", new Object[0], System.currentTimeMillis() - 1), 9, "a deadline already past");
		// a read-only connection still refuses a write through execute
		eq(execCode(r, "INSERT INTO t(id) VALUES(6004)", new Object[0], FAR), 23, "read-only connection: SQLITE_AUTH");
		eq(q(r, "SELECT count(*) FROM t WHERE id = 6004").rows.get(0)[0], 0L, "nothing written through a read-only connection");
		NativeBridge.close(r);
		NativeBridge.close(w);

		// a long write is stopped after the 1.5 s cap, in WAL mode too, and rolled back, so the app's write never fails
		for (String mode : new String[] {"DELETE", "WAL"}) {
			String p = dir.resolve("cap-" + mode + ".db").toString();
			long a = Helper.open(p);
			eq(Helper.busyTimeout(a, 2500), 0, mode + ": the app's busy timeout");
			eq(Helper.exec(a, "PRAGMA journal_mode=" + mode), 0, mode + ": mode");
			eq(Helper.exec(a, "CREATE TABLE t(id INTEGER)"), 0, mode + ": create");
			eq(Helper.exec(a, "WITH RECURSIVE c(x) AS (SELECT 1 UNION ALL SELECT x+1 FROM c WHERE x<300) INSERT INTO t SELECT x FROM c"), 0,
					mode + ": insert");
			long sw = NativeBridge.openServer(p.getBytes(StandardCharsets.UTF_8), true);
			Ran ran = new Ran();
			Thread th = new Thread(() -> {
				long t = System.currentTimeMillis();
				try {
					// far more work than 1.5 s: a cross join of t four times over, inserted
					NativeBridge.execute(sw, "INSERT INTO t SELECT a.id FROM t a, t b, t c, t d WHERE a.id + b.id + c.id + d.id < 0 "
							+ "UNION ALL SELECT -1", new Object[0], System.currentTimeMillis() + 8000);
				} catch (DebugEngineException e) {
					ran.code = e.sqliteCode;
					ran.message = e.getMessage();
				}
				ran.ms = System.currentTimeMillis() - t;
			});
			th.start();
			Thread.sleep(300);
			long t0 = System.currentTimeMillis();
			eq(Helper.exec(a, "INSERT INTO t(id) VALUES(7001)"), 0, mode + ": the app commits during a long server write");
			long commitMs = System.currentTimeMillis() - t0;
			th.join();
			check(commitMs < 2500, mode + ": the commit waited " + commitMs + " ms, under the busy timeout");
			eq(ran.code, 9, mode + ": the long write was interrupted: SQLITE_INTERRUPT");
			check(ran.message != null && ran.message.contains("rolled back") && ran.message.contains("fewer rows"),
					mode + ": the message says why and what to do: " + ran.message);
			check(ran.ms >= 1400 && ran.ms < 2500, mode + ": the write stopped after " + ran.ms + " ms, near the 1500 ms cap");
			long rr = NativeBridge.openServer(p.getBytes(StandardCharsets.UTF_8), false);
			eq(q(rr, "SELECT count(*) FROM t WHERE id = -1").rows.get(0)[0], 0L, mode + ": the stopped write left nothing");
			eq(q(rr, "SELECT count(*) FROM t WHERE id = 7001").rows.get(0)[0], 1L, mode + ": the app's write is there");
			NativeBridge.close(rr);
			NativeBridge.close(sw);
			Helper.close(a);
		}
	}

	/** Reads t four times over: far longer than the cap, and it holds the lock from its first step. */
	static final String LONG_READ = "SELECT count(*) FROM t a, t b, t c, t d WHERE a.id + b.id + c.id + d.id < 0";

	/** The outcome of a long server read run on its own thread. */
	static class Ran {
		volatile String message;
		volatile int code;
		volatile long ms;
	}

	static Thread longRead(long h, long deadline, Ran out) {
		Thread th = new Thread(() -> {
			long t = System.currentTimeMillis();
			try {
				query(h, LONG_READ, new Object[0], 0, 100, 4096, 1024, MAX, deadline);
			} catch (DebugEngineException e) {
				out.code = e.sqliteCode;
				out.message = e.getMessage();
			}
			out.ms = System.currentTimeMillis() - t;
		});
		th.start();
		return th;
	}

	/**
	 * ADFA-7136 acceptance criterion 5: on a database not in WAL mode, a long read does not hold up the
	 * app's commit past requery's 2500 ms busy timeout. And a WAL database keeps the call's deadline.
	 */
	static void nonWalCap(Path dir, String delPath) throws Exception {
		// DELETE mode: the app writes while the server read runs, with requery's busy timeout
		long app = Helper.open(delPath);
		eq(Helper.busyTimeout(app, 2500), 0, "the app's busy timeout");
		long srv = NativeBridge.openServer(delPath.getBytes(StandardCharsets.UTF_8), false);
		Ran del = new Ran();
		// 8 s stands in for the 50 s call deadline: without the cap the app's commit fails at 2.5 s, and this ends
		Thread th = longRead(srv, System.currentTimeMillis() + 8000, del);
		Thread.sleep(300);
		long t0 = System.currentTimeMillis();
		eq(Helper.exec(app, "INSERT INTO t(id) VALUES(5001)"), 0, "the app commits during a long read of a non-WAL database");
		long commitMs = System.currentTimeMillis() - t0;
		th.join();
		check(commitMs < 2500, "the commit waited " + commitMs + " ms, under the busy timeout");
		eq(del.code, 9, "the long read was interrupted: SQLITE_INTERRUPT");
		check(del.message != null && del.message.contains("not in WAL mode") && del.message.contains("LIMIT"),
				"the message says why and what to do: " + del.message);
		check(del.ms >= 1400 && del.ms < 2500, "the read stopped after " + del.ms + " ms, near the 1500 ms cap");
		eq(q(srv, "SELECT count(*) FROM t WHERE id = 5001").rows.get(0)[0], 1L, "the commit is visible, the connection usable");
		Helper.exec(app, "DELETE FROM t WHERE id = 5001");
		// a call whose own deadline is shorter than the cap keeps its own message
		Ran shorter = new Ran();
		longRead(srv, System.currentTimeMillis() + 400, shorter).join();
		eq(shorter.code, 9, "a 400 ms deadline: SQLITE_INTERRUPT");
		check(shorter.message != null && shorter.message.contains("time limit") && !shorter.message.contains("WAL"),
				"the call's own deadline is reported as such: " + shorter.message);
		NativeBridge.close(srv);
		Helper.close(app);

		// WAL mode: no cap, the call's deadline (3.5 s here) is what stops the read, and the app is never held up
		String walPath = dir.resolve("wal.db").toString();
		long walApp = Helper.open(walPath);
		eq(Helper.exec(walApp, "PRAGMA journal_mode=WAL"), 0, "WAL on");
		eq(Helper.exec(walApp, "CREATE TABLE t(id INTEGER)"), 0, "create in WAL");
		eq(Helper.exec(walApp, "WITH RECURSIVE c(x) AS (SELECT 1 UNION ALL SELECT x+1 FROM c WHERE x<300) INSERT INTO t SELECT x FROM c"), 0,
				"insert in WAL");
		long walSrv = NativeBridge.openServer(walPath.getBytes(StandardCharsets.UTF_8), false);
		Ran wal = new Ran();
		Thread wt = longRead(walSrv, System.currentTimeMillis() + 3500, wal);
		Thread.sleep(300);
		long w0 = System.currentTimeMillis();
		eq(Helper.exec(walApp, "INSERT INTO t(id) VALUES(5001)"), 0, "the app commits during a long read of a WAL database");
		check(System.currentTimeMillis() - w0 < 500, "the WAL commit did not wait");
		wt.join();
		eq(wal.code, 9, "the WAL read ended at its deadline: SQLITE_INTERRUPT");
		check(wal.message != null && wal.message.contains("time limit") && !wal.message.contains("WAL"), "the WAL message: " + wal.message);
		check(wal.ms >= 3000 && wal.ms < 5000, "the WAL read ran " + wal.ms + " ms, past the non-WAL cap");
		NativeBridge.close(walSrv);
		Helper.close(walApp);
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
		eq(codeOf(srv, "SELECT ?", new Object[0], FAR), 25, "too few args: SQLITE_RANGE, not a NULL parameter");
		eq(codeOf(srv, "SELECT ?, ?", new Object[] {1L}, FAR), 25, "one arg of two: SQLITE_RANGE");
		eq(codeOf(srv, "SELECT 1", new Object[] {1L}, FAR), 25, "an arg for no parameter: SQLITE_RANGE");
		try {
			query(srv, "SELECT ?, ?", new Object[] {1L}, 0, 100, 4096, 1024, MAX, FAR);
			check(false, "a statement with two parameters and one argument ran");
		} catch (DebugEngineException e) {
			check(e.getMessage().contains("2 parameter") && e.getMessage().contains("1 argument"), "the message names both counts: " + e.getMessage());
		}
		eq(codeOf(srv, "SELECT ?", new Object[] {1L, 2L}, FAR), 25, "too many args: SQLITE_RANGE");
		eq(codeOf(srv, "SELECT ?", new Object[] {new Object()}, FAR), 21, "unsupported arg type: SQLITE_MISUSE");
		eq(codeOf(srv, "SELEKT 1", new Object[0], FAR), 1, "syntax error: SQLITE_ERROR");

		// one statement per call: SQLite compiles only the first, so a second is refused, not dropped
		String more = "1 the SQL holds more than one statement; run one statement per call";
		eq(refusal(srv, "SELECT 1; SELECT 2"), more, "two statements");
		eq(refusal(srv, "SELECT 1;\n-- why\nSELECT id FROM t"), more, "a statement after a comment");
		eq(refusal(srv, "SELECT 1; DELETE FROM t"), more, "a write the authorizer refuses at prepare time is a second statement, not SQLITE_AUTH");
		eq(refusal(srv, "SELECT 1; nonsense"), more, "a second statement that fails to prepare");
		eq(refusal(srv, "EXPLAIN QUERY PLAN SELECT id FROM t; SELECT 2"), more, "what explain_query_plan sends for two statements");
		eq(refusal(srv, "-- only a comment"), "1 the SQL holds no statement, only whitespace or comments", "no statement");
		eq(refusal(srv, " ;\n"), "1 the SQL holds no statement, only whitespace or comments", "only a semicolon");
		eq(refusal(srv, "SELECT 1\0; DELETE FROM t"), "1 the SQL holds a NUL character, and SQLite reads no further than it", "a NUL");
		for (String sql : new String[] {"SELECT 41 + 1;", "SELECT 41 + 1 ;  \n\t", "SELECT 41 + 1; -- done", "SELECT 41 + 1 /* c */;;",
				"SELECT 41 + 1; /* open", "-- why\n;SELECT 41 + 1"}) {
			eq(refusal(srv, sql), null, "accepted: " + sql);
			eq(q(srv, sql).rows.get(0)[0], 42L, "and run: " + sql);
		}
		eq(q(srv, "SELECT count(*) FROM t WHERE id = 1").rows.get(0)[0], 1L, "nothing deleted");

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

		// a writable connection refuses transactions too (ADFA-7298), so the app is never locked out
		long w = NativeBridge.openServer(bytes, true);
		for (String sql : new String[] {"BEGIN", "BEGIN IMMEDIATE", "SAVEPOINT s"}) {
			eq(codeOf(w, sql, new Object[0], FAR), 23, "writable: " + sql + " refused: SQLITE_AUTH");
			eq(execCode(w, sql, new Object[0], FAR), 23, "writable execute: " + sql + " refused: SQLITE_AUTH");
			eq(Helper.exec(app, "INSERT INTO t(id) VALUES(4244)"), 0, "the app can write after a writable " + sql);
			Helper.exec(app, "DELETE FROM t WHERE id = 4244");
		}
		q(w, "INSERT INTO t(id) VALUES(4245)");
		eq(q(w, "SELECT count(*) FROM t WHERE id = 4245").rows.get(0)[0], 1L, "an autocommit insert on the writable connection stays");
		// a rollback the backstop cannot make is the query's error, not a lock held in silence. The
		// transactions below are opened past the authorizer, removed here, as a hole in it would leave one.
		eq(Helper.denyTransactions(w, false), 0, "the server's authorizer removed");
		eq(Helper.exec(w, "BEGIN IMMEDIATE"), 0, "a transaction opened outside the query path");
		eq(Helper.denyTransactions(w, true), 0, "an authorizer that refuses ROLLBACK");
		eq(refusal(w, "SELECT 1"), "23 a transaction was left open and rolling it back failed: not authorized",
				"the failed rollback is reported");
		eq(Helper.exec(app, "INSERT INTO t(id) VALUES(4246)"), 5, "the app is still locked out: SQLITE_BUSY");
		eq(Helper.denyTransactions(w, false), 0, "the authorizer cleared");
		eq(refusal(w, "SELECT 1"), null, "the next query rolls it back");
		eq(Helper.exec(app, "INSERT INTO t(id) VALUES(4246)"), 0, "the app can write again");
		Helper.exec(app, "DELETE FROM t WHERE id = 4246");

		// the backstop also runs when the query fails before it starts: a transaction opened outside the
		// query path (sqlite3_exec on the handle, as above) is rolled back by each early return
		String[][] early = {{"SELEKT 1", "1"}, {"SELECT 1; SELECT 2", "1"}, {"SELECT 1\0", "1"}, {"SELECT * FROM no_such_table", "1"}};
		for (String[] c : early) {
			eq(Helper.exec(w, "BEGIN IMMEDIATE"), 0, "a transaction opened outside the query path before " + c[0]);
			eq(Helper.autocommit(w), 0, "it is open");
			eq(codeOf(w, c[0], new Object[0], FAR), Integer.parseInt(c[1]), "the original error is kept: " + c[0]);
			eq(Helper.autocommit(w), 1, "rolled back after a failed prepare: " + c[0]);
		}
		eq(Helper.exec(w, "BEGIN IMMEDIATE"), 0, "a transaction opened outside the query path before a past deadline");
		eq(codeOf(w, "SELECT 1", new Object[0], System.currentTimeMillis() - 1), 9, "a deadline already past: SQLITE_INTERRUPT");
		eq(Helper.autocommit(w), 1, "rolled back after a deadline already past");
		// a rollback that fails does not replace the original error
		eq(Helper.exec(w, "BEGIN IMMEDIATE"), 0, "a transaction opened outside the query path, rollback to be refused");
		eq(Helper.denyTransactions(w, true), 0, "an authorizer that refuses ROLLBACK, again");
		eq(refusal(w, "SELEKT 1").startsWith("1 "), true, "the syntax error, not the rollback failure, is reported");
		eq(Helper.autocommit(w), 0, "the refused rollback left it open");
		eq(Helper.denyTransactions(w, false), 0, "the authorizer cleared, again");
		eq(refusal(w, "SELECT 1"), null, "the next query rolls it back, again");
		eq(Helper.autocommit(w), 1, "closed");
		// the backstop runs after execute too, and keeps execute's own error
		eq(Helper.exec(w, "BEGIN IMMEDIATE"), 0, "a transaction opened outside the execute path");
		eq(execCode(w, "UPDATE t SET id = id WHERE id = -1; SELECT 1", new Object[0], FAR), 1, "execute's own error is kept");
		eq(Helper.autocommit(w), 1, "rolled back after a failed execute");
		eq(Helper.exec(w, "BEGIN IMMEDIATE"), 0, "a transaction opened outside the execute path, rollback to be refused");
		eq(Helper.denyTransactions(w, true), 0, "an authorizer that refuses ROLLBACK, for execute");
		eq(execRefusal(w, "DELETE FROM t WHERE id = -1"), "23 a transaction was left open and rolling it back failed: not authorized",
				"execute reports the failed rollback");
		eq(Helper.denyTransactions(w, false), 0, "the authorizer cleared, for execute");
		eq(execRefusal(w, "DELETE FROM t WHERE id = -1"), null, "the next execute rolls it back");
		eq(Helper.autocommit(w), 1, "closed, after execute");
		NativeBridge.close(w);
		Helper.exec(app, "DELETE FROM t WHERE id = 4245");

		execute(dir, path, app);

		nonWalCap(dir, path);

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
