// Host tests of the SQLite debugger's extension (ADFA-7135), run by test.sh against SQLite built
// from the amalgamation as requery builds it (SQLITE_THREADSAFE=2). The extension keeps process
// state and installs once, so the tests share one install and run in order.
//
// Usage: sqlitedbg_test [--write-fixture BIN TSV | --check-fixture BIN TSV]
#define _GNU_SOURCE
#include <assert.h>
#include <pthread.h>
#include <stdatomic.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <unistd.h>
#include <sys/stat.h>
#include <sys/syscall.h>

#include "sqlite3.h"
#include "sqlitedbg.h"

static int g_failures;
static int g_checks;

#define CHECK(cond)                                                                      \
	do {                                                                                 \
		g_checks++;                                                                      \
		if (!(cond)) {                                                                   \
			g_failures++;                                                                \
			fprintf(stderr, "FAIL %s:%d: %s\n", __FILE__, __LINE__, #cond);              \
		}                                                                                \
	} while (0)

#define CHECK_EQ(a, b)                                                                   \
	do {                                                                                 \
		long long va_ = (long long)(a), vb_ = (long long)(b);                            \
		g_checks++;                                                                      \
		if (va_ != vb_) {                                                                \
			g_failures++;                                                                \
			fprintf(stderr, "FAIL %s:%d: %s == %s (%lld vs %lld)\n", __FILE__, __LINE__, \
			        #a, #b, va_, vb_);                                                   \
		}                                                                                \
	} while (0)

#define MAX_SQL 64
#define BUFFER_BYTES 8192

static char g_dir[256];
static char g_db_path[300];

static long long tid(void) { return (long long)syscall(SYS_gettid); }

static void exec_ok(sqlite3 *db, const char *sql) {
	char *err = NULL;
	int rc = sqlite3_exec(db, sql, NULL, NULL, &err);
	if (rc != SQLITE_OK) fprintf(stderr, "exec(%s): %s\n", sql, err ? err : "?");
	sqlite3_free(err);
	CHECK_EQ(rc, SQLITE_OK);
}

static sqlite3 *open_app(const char *path) {
	sqlite3 *db = NULL;
	int rc = sqlite3_open(path, &db);
	CHECK_EQ(rc, SQLITE_OK);
	return db;
}

// Events

typedef struct ev {
	uint32_t kind, flags;
	uint64_t seq, conn;
	int64_t tid, time_ns, duration_ns;
	char sql[MAX_SQL * 4 + 8];
	uint32_t sql_bytes;
} ev;

typedef struct batch {
	ev *events;
	size_t count;
	uint64_t next, dropped;
} batch;

static void batch_free(batch *b) { free(b->events); }

// Reads everything after `after`, checking the layout of each record on the way.
static void read_all(uint64_t after, batch *b) {
	size_t cap = 1 << 20;
	unsigned char *buf = malloc(cap);
	sqlitedbg_read_result r;
	sqlitedbg_read_events(after, buf, cap, &r);
	CHECK_EQ(r.bytes % 8, 0);
	b->events = calloc(r.count + 1, sizeof(ev));
	b->count = r.count;
	b->next = r.next;
	b->dropped = r.dropped;
	size_t off = 0;
	uint64_t prev = after;
	for (size_t i = 0; i < r.count; i++) {
		const sqlitedbg_event_record *rec = (const sqlitedbg_event_record *)(void *)(buf + off);
		CHECK(rec->record_bytes >= sizeof *rec);
		CHECK_EQ(rec->record_bytes % 8, 0);
		CHECK(rec->sql_bytes <= MAX_SQL);
		CHECK(rec->record_bytes >= sizeof *rec + rec->sql_bytes);
		CHECK(rec->seq > prev);
		prev = rec->seq;
		ev *e = &b->events[i];
		e->kind = rec->kind;
		e->flags = rec->flags;
		e->seq = rec->seq;
		e->conn = rec->connection_id;
		e->tid = rec->thread_id;
		e->time_ns = rec->time_ns;
		e->duration_ns = rec->duration_ns;
		e->sql_bytes = rec->sql_bytes;
		memcpy(e->sql, (const char *)buf + off + sizeof *rec, rec->sql_bytes);
		e->sql[rec->sql_bytes] = 0;
		off += rec->record_bytes;
	}
	CHECK_EQ(off, r.bytes);
	free(buf);
}

static uint64_t cursor_now(void) {
	batch b;
	read_all(0, &b);
	uint64_t n = b.next;
	batch_free(&b);
	return n;
}

static int utf8_valid(const char *s, size_t n) {
	size_t i = 0;
	while (i < n) {
		unsigned char c = (unsigned char)s[i];
		size_t len = c < 0x80 ? 1 : (c >> 5) == 6 ? 2 : (c >> 4) == 14 ? 3 : (c >> 3) == 30 ? 4 : 0;
		if (!len || i + len > n) return 0;
		for (size_t k = 1; k < len; k++)
			if (((unsigned char)s[i + k] & 0xC0) != 0x80) return 0;
		i += len;
	}
	return 1;
}

// Connections

typedef struct listing {
	sqlitedbg_connection c[64];
	char path[64][300];
	size_t n;
} listing;

static void list_cb(void *context, const sqlitedbg_connection *c) {
	listing *l = context;
	if (l->n < 64) {
		l->c[l->n] = *c;
		snprintf(l->path[l->n], sizeof l->path[l->n], "%s", c->path);
		l->c[l->n].path = l->path[l->n];
	}
	l->n++;
}

static size_t list_conns(listing *l) {
	l->n = 0;
	return sqlitedbg_each_connection(list_cb, l);
}

static const sqlitedbg_connection *find_conn(const listing *l, uint64_t id) {
	for (size_t i = 0; i < l->n && i < 64; i++)
		if (l->c[i].id == id) return &l->c[i];
	return NULL;
}

static uint64_t conn_id_of(sqlite3 *db) {
	sqlite3_stmt *st;
	uint64_t id = 0;
	CHECK_EQ(sqlite3_prepare_v2(db, "SELECT sqlitedbg_connection_id()", -1, &st, NULL), SQLITE_OK);
	if (sqlite3_step(st) == SQLITE_ROW) id = (uint64_t)sqlite3_column_int64(st, 0);
	sqlite3_finalize(st);
	return id;
}

// Tests

static sqlitedbg_config g_config = {BUFFER_BYTES, MAX_SQL};
static sqlite3 *g_main;

static void test_install(void) {
	sqlite3 *srv = NULL;
	CHECK_EQ(sqlitedbg_open_server(g_db_path, 0, &srv), SQLITE_MISUSE);
	CHECK(srv == NULL);
	CHECK_EQ(sqlitedbg_install(sqlite3_auto_extension, &g_config), SQLITE_OK);
	sqlitedbg_config other = {1 << 20, 1000};
	CHECK_EQ(sqlitedbg_install(sqlite3_auto_extension, &other), SQLITE_OK);
	CHECK_EQ(sqlitedbg_install(NULL, NULL), SQLITE_MISUSE);
	// still no app connection: the extension has no SQLite API to call
	CHECK_EQ(sqlitedbg_open_server(g_db_path, 0, &srv), SQLITE_MISUSE);

	listing l;
	CHECK_EQ(list_conns(&l), 0);
	g_main = open_app(g_db_path);
	// a second install registered the entry point once: one connection, one listing, one id
	CHECK_EQ(list_conns(&l), 1);
	CHECK_EQ(l.c[0].id, 1);
	CHECK(strcmp(l.c[0].path, g_db_path) == 0 || strstr(l.c[0].path, "app.db") != NULL);
	CHECK_EQ(l.c[0].read_only, 0);
	CHECK_EQ(l.c[0].closing, 0);
	CHECK_EQ(l.c[0].open_thread_id, tid());
	CHECK(l.c[0].opened_at_ns > 0);
	CHECK_EQ(conn_id_of(g_main), 1);
	// the second config was ignored: the 64-byte limit still applies
	uint64_t cur = cursor_now();
	char sql[200];
	snprintf(sql, sizeof sql, "SELECT '%0100d'", 0);
	exec_ok(g_main, sql);
	batch b;
	read_all(cur, &b);
	CHECK_EQ(b.count, 2);
	CHECK(b.events[0].flags & SQLITEDBG_EVENT_TRUNCATED);
	CHECK_EQ(b.events[0].sql_bytes, MAX_SQL);
	batch_free(&b);
}

static void test_events(void) {
	exec_ok(g_main, "CREATE TABLE t(a)");
	exec_ok(g_main, "CREATE TABLE log(a)");
	exec_ok(g_main, "CREATE TRIGGER trg AFTER INSERT ON t BEGIN INSERT INTO log VALUES(new.a); END");
	uint64_t cur = cursor_now();

	sqlite3_stmt *st;
	CHECK_EQ(sqlite3_prepare_v2(g_main, "INSERT INTO t VALUES(?)", -1, &st, NULL), SQLITE_OK);
	sqlite3_bind_int(st, 1, 42);
	CHECK_EQ(sqlite3_step(st), SQLITE_DONE);
	sqlite3_finalize(st);

	batch b;
	read_all(cur, &b);
	// the insert, the trigger it fires (its header, then its one step), then the insert's profile
	CHECK_EQ(b.count, 4);
	CHECK_EQ(b.events[0].kind, SQLITEDBG_EVENT_STATEMENT);
	CHECK(strcmp(b.events[0].sql, "INSERT INTO t VALUES(?)") == 0);
	CHECK_EQ(b.events[0].duration_ns, -1);
	CHECK_EQ(b.events[0].flags, 0);
	CHECK_EQ(b.events[1].kind, SQLITEDBG_EVENT_STATEMENT);
	CHECK(strcmp(b.events[1].sql, "-- TRIGGER trg") == 0);
	CHECK_EQ(b.events[2].kind, SQLITEDBG_EVENT_STATEMENT);
	CHECK(strcmp(b.events[2].sql, "-- INSERT INTO log VALUES(new.a)") == 0);
	CHECK_EQ(b.events[3].kind, SQLITEDBG_EVENT_PROFILE);
	CHECK(strcmp(b.events[3].sql, "INSERT INTO t VALUES(?)") == 0);
	CHECK(b.events[3].duration_ns >= 0);
	for (size_t i = 0; i < b.count; i++) {
		CHECK_EQ(b.events[i].conn, 1);
		CHECK_EQ(b.events[i].tid, tid());
		CHECK_EQ(b.events[i].seq, cur + 1 + i);
		CHECK(b.events[i].time_ns > 0);
	}
	CHECK_EQ(b.next, cur + 4);
	CHECK_EQ(b.dropped, 0);
	batch_free(&b);

	// nothing new: the cursor stays
	read_all(cur + 4, &b);
	CHECK_EQ(b.count, 0);
	CHECK_EQ(b.next, cur + 4);
	batch_free(&b);

	// one buffer too small for a record returns none and does not split one
	sqlitedbg_read_result r;
	char tiny[8];
	sqlitedbg_read_events(cur, tiny, sizeof tiny, &r);
	CHECK_EQ(r.count, 0);
	CHECK_EQ(r.bytes, 0);
	CHECK_EQ(r.next, cur);
}

static void test_expanded(void) {
	sqlite3_stmt *st;
	uint64_t cur = cursor_now();
	sqlitedbg_set_client_connected(1);
	CHECK_EQ(sqlite3_prepare_v2(g_main, "INSERT INTO t VALUES(?)", -1, &st, NULL), SQLITE_OK);
	sqlite3_bind_int(st, 1, 7);
	CHECK_EQ(sqlite3_step(st), SQLITE_DONE);
	sqlite3_finalize(st);
	sqlitedbg_set_client_connected(0);
	batch b;
	read_all(cur, &b);
	CHECK_EQ(b.count, 4);
	CHECK(strcmp(b.events[0].sql, "INSERT INTO t VALUES(7)") == 0);
	CHECK(b.events[0].flags & SQLITEDBG_EVENT_EXPANDED);
	// a trigger's text is not expanded: P is the outer statement
	CHECK(strncmp(b.events[1].sql, "--", 2) == 0);
	CHECK(strncmp(b.events[2].sql, "--", 2) == 0);
	CHECK_EQ(b.events[1].flags & SQLITEDBG_EVENT_EXPANDED, 0);
	CHECK_EQ(b.events[2].flags & SQLITEDBG_EVENT_EXPANDED, 0);
	CHECK(strcmp(b.events[3].sql, "INSERT INTO t VALUES(7)") == 0);
	CHECK(b.events[3].flags & SQLITEDBG_EVENT_EXPANDED);
	batch_free(&b);

	// after the client leaves, the bound value is not written in again
	cur = cursor_now();
	CHECK_EQ(sqlite3_prepare_v2(g_main, "INSERT INTO t VALUES(?)", -1, &st, NULL), SQLITE_OK);
	sqlite3_bind_int(st, 1, 8);
	CHECK_EQ(sqlite3_step(st), SQLITE_DONE);
	sqlite3_finalize(st);
	read_all(cur, &b);
	CHECK(strcmp(b.events[0].sql, "INSERT INTO t VALUES(?)") == 0);
	CHECK_EQ(b.events[0].flags, 0);
	batch_free(&b);

	// A long bound value does not outgrow the limit: expansion is cut like any other text
	char big[300];
	memset(big, 'x', sizeof big - 1);
	big[sizeof big - 1] = 0;
	cur = cursor_now();
	sqlitedbg_set_client_connected(1);
	CHECK_EQ(sqlite3_prepare_v2(g_main, "INSERT INTO t VALUES(?)", -1, &st, NULL), SQLITE_OK);
	sqlite3_bind_text(st, 1, big, -1, SQLITE_STATIC);
	CHECK_EQ(sqlite3_step(st), SQLITE_DONE);
	sqlite3_finalize(st);
	sqlitedbg_set_client_connected(0);
	read_all(cur, &b);
	CHECK(b.events[0].flags & SQLITEDBG_EVENT_EXPANDED);
	CHECK(b.events[0].flags & SQLITEDBG_EVENT_TRUNCATED);
	CHECK_EQ(b.events[0].sql_bytes, MAX_SQL);
	batch_free(&b);
}

static void test_truncation(void) {
	char sql[256];
	batch b;
	uint64_t cur;

	// "SELECT '" is 8 bytes; each euro sign is 3, so byte 64 is the third byte of one
	// that starts at 62: the cut must fall back to 62
	size_t n = (size_t)snprintf(sql, sizeof sql, "SELECT '");
	for (int i = 0; i < 25; i++) n += (size_t)snprintf(sql + n, sizeof sql - n, "\xE2\x82\xAC");
	snprintf(sql + n, sizeof sql - n, "'");
	cur = cursor_now();
	exec_ok(g_main, sql);
	read_all(cur, &b);
	CHECK_EQ(b.count, 2);
	for (int i = 0; i < 2; i++) {
		CHECK_EQ(b.events[i].sql_bytes, 62);
		CHECK(b.events[i].flags & SQLITEDBG_EVENT_TRUNCATED);
		CHECK(utf8_valid(b.events[i].sql, b.events[i].sql_bytes));
	}
	batch_free(&b);

	// a 4-byte character straddling the limit: 11 bytes of prefix, then 4-byte characters,
	// so byte 64 is the second byte of one that starts at 63
	n = (size_t)snprintf(sql, sizeof sql, "SELECT 1 /*");
	for (int i = 0; i < 20; i++) n += (size_t)snprintf(sql + n, sizeof sql - n, "\xF0\x9F\x98\x80");
	snprintf(sql + n, sizeof sql - n, "*/");
	cur = cursor_now();
	exec_ok(g_main, sql);
	read_all(cur, &b);
	CHECK_EQ(b.count, 2);
	CHECK_EQ(b.events[0].sql_bytes, 63);
	CHECK(utf8_valid(b.events[0].sql, b.events[0].sql_bytes));
	batch_free(&b);

	// exactly at the limit is whole, one byte over is cut
	for (int over = 0; over < 2; over++) {
		memset(sql, 0, sizeof sql);
		n = (size_t)snprintf(sql, sizeof sql, "SELECT 1 /*");
		while (n < (size_t)(MAX_SQL - 2 + over)) sql[n++] = 'x';
		sql[n++] = '*';
		sql[n++] = '/';
		sql[n] = 0;
		CHECK_EQ(strlen(sql), MAX_SQL + over);
		cur = cursor_now();
		exec_ok(g_main, sql);
		read_all(cur, &b);
		CHECK_EQ(b.events[0].sql_bytes, MAX_SQL);
		CHECK_EQ((b.events[0].flags & SQLITEDBG_EVENT_TRUNCATED) != 0, over);
		batch_free(&b);
	}
}

static void test_burst(void) {
	uint64_t cur = cursor_now();
	const int stmts = 2000;
	for (int i = 0; i < stmts; i++) exec_ok(g_main, "SELECT 1");
	const uint64_t made = 2 * (uint64_t)stmts;
	batch b;
	read_all(cur, &b);
	CHECK(b.count > 0);
	CHECK(b.count < made);
	CHECK_EQ(b.dropped, made - b.count);
	CHECK_EQ(b.next, cur + made);
	CHECK_EQ(b.events[0].seq, cur + made - b.count + 1);
	CHECK_EQ(b.events[b.count - 1].seq, cur + made);
	for (size_t i = 1; i < b.count; i++) CHECK_EQ(b.events[i].seq, b.events[i - 1].seq + 1);
	batch_free(&b);

	// nothing is reported twice
	uint64_t next = cur + made;
	read_all(next, &b);
	CHECK_EQ(b.count, 0);
	CHECK_EQ(b.dropped, 0);
	batch_free(&b);

	// a cursor from before the start counts everything that left
	read_all(0, &b);
	CHECK_EQ(b.dropped, b.events[0].seq - 1);
	CHECK_EQ(b.dropped + b.count, next);
	batch_free(&b);

	// a reader that kept up loses nothing: read after every 10 statements
	uint64_t pos = next, lost = 0, got = 0;
	for (int round = 0; round < 50; round++) {
		for (int i = 0; i < 10; i++) exec_ok(g_main, "SELECT 2");
		read_all(pos, &b);
		lost += b.dropped;
		got += b.count;
		pos = b.next;
		batch_free(&b);
	}
	CHECK_EQ(lost, 0);
	CHECK_EQ(got, 50 * 10 * 2);
}

static void test_close_and_list(void) {
	listing l;
	size_t before = list_conns(&l);
	sqlite3 *c = open_app(g_db_path);
	uint64_t id = conn_id_of(c);
	CHECK(id > 1);
	CHECK_EQ(list_conns(&l), before + 1);
	CHECK(find_conn(&l, id) != NULL);
	CHECK_EQ(find_conn(&l, id)->closing, 0);

	sqlite3_stmt *st;
	CHECK_EQ(sqlite3_prepare_v2(c, "SELECT * FROM t", -1, &st, NULL), SQLITE_OK);
	CHECK_EQ(sqlite3_step(st), SQLITE_ROW);
	uint64_t cur = cursor_now();
	CHECK_EQ(sqlite3_close(c), SQLITE_BUSY);
	// the close event fired, but the connection is still there: listed, marked closing
	CHECK_EQ(list_conns(&l), before + 1);
	CHECK_EQ(find_conn(&l, id)->closing, 1);
	batch b;
	read_all(cur, &b);
	CHECK_EQ(b.count, 1);
	CHECK_EQ(b.events[0].kind, SQLITEDBG_EVENT_CLOSE);
	CHECK_EQ(b.events[0].conn, id);
	CHECK_EQ(b.events[0].sql_bytes, 0);
	batch_free(&b);

	// the app retries once the statement is gone
	sqlite3_finalize(st);
	CHECK_EQ(list_conns(&l), before + 1);
	CHECK_EQ(sqlite3_close(c), SQLITE_OK);
	CHECK_EQ(list_conns(&l), before);
	CHECK(find_conn(&l, id) == NULL);

	// close_v2 with a statement open leaves a zombie, listed until it is finalized
	c = open_app(g_db_path);
	uint64_t id2 = conn_id_of(c);
	CHECK(id2 > id);
	CHECK_EQ(sqlite3_prepare_v2(c, "SELECT * FROM t", -1, &st, NULL), SQLITE_OK);
	CHECK_EQ(sqlite3_close_v2(c), SQLITE_OK);
	CHECK_EQ(list_conns(&l), before + 1);
	CHECK_EQ(find_conn(&l, id2)->closing, 1);
	sqlite3_finalize(st);
	CHECK_EQ(list_conns(&l), before);

	// ids are never reused
	c = open_app(g_db_path);
	CHECK(conn_id_of(c) > id2);
	CHECK_EQ(sqlite3_close(c), SQLITE_OK);

	// an in-memory database lists with an empty path, a read-only one as read-only
	c = open_app(":memory:");
	list_conns(&l);
	CHECK(find_conn(&l, conn_id_of(c)) != NULL);
	CHECK_EQ(find_conn(&l, conn_id_of(c))->path[0], 0);
	CHECK_EQ(sqlite3_close(c), SQLITE_OK);
	sqlite3 *ro = NULL;
	CHECK_EQ(sqlite3_open_v2(g_db_path, &ro, SQLITE_OPEN_READONLY, NULL), SQLITE_OK);
	list_conns(&l);
	CHECK_EQ(find_conn(&l, conn_id_of(ro))->read_only, 1);
	CHECK_EQ(sqlite3_close(ro), SQLITE_OK);
	CHECK_EQ(list_conns(&l), before);
}

static int step_all(sqlite3 *db, const char *sql) {
	sqlite3_stmt *st;
	int rc = sqlite3_prepare_v2(db, sql, -1, &st, NULL);
	if (rc != SQLITE_OK) return rc;
	while ((rc = sqlite3_step(st)) == SQLITE_ROW) {}
	sqlite3_finalize(st);
	return rc;
}

static void test_server(void) {
	listing l;
	size_t before = list_conns(&l);
	uint64_t cur = cursor_now();
	sqlite3 *srv = NULL;

	CHECK_EQ(sqlitedbg_open_server(g_db_path, 0, &srv), SQLITE_OK);
	CHECK(srv != NULL);
	CHECK_EQ(sqlite3_db_readonly(srv, "main"), 1);
	// not listed, and it has no connection id function
	CHECK_EQ(list_conns(&l), before);
	CHECK(sqlite3_prepare_v2(srv, "SELECT sqlitedbg_connection_id()", -1, &(sqlite3_stmt *){NULL}, NULL) != SQLITE_OK);

	CHECK_EQ(step_all(srv, "SELECT 'server-marker', count(*) FROM t"), SQLITE_DONE);
	CHECK_EQ(step_all(srv, "SELECT name FROM sqlite_schema"), SQLITE_DONE);
	CHECK_EQ(step_all(srv, "PRAGMA table_info(t)"), SQLITE_DONE);
	CHECK_EQ(step_all(srv, "PRAGMA quick_check"), SQLITE_DONE);
	// not traced
	batch b;
	read_all(cur, &b);
	CHECK_EQ(b.count, 0);
	batch_free(&b);

	// writes are refused, and so are the ways to lift the guard
	CHECK_EQ(step_all(srv, "INSERT INTO t VALUES(99)"), SQLITE_AUTH);
	CHECK_EQ(step_all(srv, "UPDATE t SET a=1"), SQLITE_AUTH);
	CHECK_EQ(step_all(srv, "DELETE FROM t"), SQLITE_AUTH);
	CHECK_EQ(step_all(srv, "CREATE TABLE x(a)"), SQLITE_AUTH);
	CHECK_EQ(step_all(srv, "DROP TABLE t"), SQLITE_AUTH);
	CHECK_EQ(step_all(srv, "PRAGMA query_only=0"), SQLITE_AUTH);
	CHECK_EQ(step_all(srv, "PRAGMA user_version=5"), SQLITE_AUTH);
	CHECK_EQ(step_all(srv, "ATTACH ':memory:' AS m"), SQLITE_AUTH);
	CHECK_EQ(step_all(srv, "SELECT 'still works'"), SQLITE_DONE);
	sqlite3_stmt *st;
	CHECK_EQ(sqlite3_prepare_v2(srv, "SELECT count(*) FROM t WHERE a=99", -1, &st, NULL), SQLITE_OK);
	CHECK_EQ(sqlite3_step(st), SQLITE_ROW);
	CHECK_EQ(sqlite3_column_int(st, 0), 0);
	sqlite3_finalize(st);
	CHECK_EQ(sqlite3_close_v2(srv), SQLITE_OK);
	CHECK_EQ(list_conns(&l), before);

	// a writable server connection writes, to what the app sees, and is still unlisted
	CHECK_EQ(sqlitedbg_open_server(g_db_path, 1, &srv), SQLITE_OK);
	CHECK_EQ(sqlite3_db_readonly(srv, "main"), 0);
	CHECK_EQ(step_all(srv, "INSERT INTO t VALUES(1234)"), SQLITE_DONE);
	CHECK_EQ(list_conns(&l), before);
	CHECK_EQ(sqlite3_close_v2(srv), SQLITE_OK);
	CHECK_EQ(sqlite3_prepare_v2(g_main, "SELECT count(*) FROM t WHERE a=1234", -1, &st, NULL), SQLITE_OK);
	CHECK_EQ(sqlite3_step(st), SQLITE_ROW);
	CHECK_EQ(sqlite3_column_int(st, 0), 1);
	sqlite3_finalize(st);

	// a missing file is not created by a read-only open, and nothing leaks
	char missing[320];
	snprintf(missing, sizeof missing, "%s/missing.db", g_dir);
	CHECK_EQ(sqlitedbg_open_server(missing, 0, &srv), SQLITE_CANTOPEN);
	CHECK(srv == NULL);
	CHECK_EQ(sqlitedbg_open_server(missing, 1, &srv), SQLITE_CANTOPEN);
	CHECK(srv == NULL);

	// the flag is per thread and per open: an app connection opened after is listed
	sqlite3 *app = open_app(":memory:");
	CHECK_EQ(list_conns(&l), before + 1);
	CHECK_EQ(sqlite3_close(app), SQLITE_OK);
}

typedef struct worker {
	int index;
	atomic_int *stop;
	long long stmts, closes;
	int failed;
} worker;

static void *worker_main(void *arg) {
	worker *w = arg;
	sqlite3 *db = NULL;
	if (sqlite3_open(":memory:", &db) != SQLITE_OK) {
		w->failed = 1;
		return NULL;
	}
	sqlite3_stmt *st;
	sqlite3_exec(db, "CREATE TABLE w(a, b)", NULL, NULL, NULL);
	w->stmts++;
	long long n = 0;
	while (!atomic_load(w->stop)) {
		if (sqlite3_prepare_v2(db, "INSERT INTO w VALUES(?, ?)", -1, &st, NULL) != SQLITE_OK) {
			w->failed = 1;
			break;
		}
		sqlite3_bind_int64(st, 1, n);
		sqlite3_bind_text(st, 2, "value", -1, SQLITE_STATIC);
		if (sqlite3_step(st) != SQLITE_DONE) w->failed = 1;
		sqlite3_finalize(st);
		w->stmts++;
		if (n % 7 == 0) {
			if (sqlite3_exec(db, "SELECT count(*) FROM w", NULL, NULL, NULL) != SQLITE_OK) w->failed = 1;
			w->stmts++;
		}
		if (n % 50 == 0) {
			// connection churn beside the other threads' statements
			sqlite3 *extra = NULL;
			if (sqlite3_open(":memory:", &extra) != SQLITE_OK) {
				w->failed = 1;
				break;
			}
			if (sqlite3_exec(extra, "SELECT 1", NULL, NULL, NULL) != SQLITE_OK) w->failed = 1;
			w->stmts++;
			if (sqlite3_close(extra) != SQLITE_OK) w->failed = 1;
			w->closes++;
		}
		n++;
	}
	if (sqlite3_close(db) != SQLITE_OK) w->failed = 1;
	w->closes++;
	return NULL;
}

typedef struct reader {
	atomic_int *stop;
	int failed;
	long long reads;
} reader;

static void list_noop(void *context, const sqlitedbg_connection *c) {
	(void)context;
	if (c->id == 0 || !c->path) *(int *)context = 1;
}

static void *reader_main(void *arg) {
	reader *r = arg;
	static unsigned char buf[2048];
	uint64_t after = 0;
	while (!atomic_load(r->stop)) {
		sqlitedbg_read_result res;
		sqlitedbg_read_events(after, buf, sizeof buf, &res);
		size_t off = 0;
		uint64_t prev = after;
		for (size_t i = 0; i < res.count; i++) {
			const sqlitedbg_event_record *rec = (const sqlitedbg_event_record *)(void *)(buf + off);
			if (rec->seq <= prev || rec->record_bytes % 8 || rec->kind < 1 || rec->kind > 3 ||
			    rec->record_bytes < sizeof *rec + rec->sql_bytes)
				r->failed = 1;
			prev = rec->seq;
			off += rec->record_bytes;
		}
		if (off != res.bytes) r->failed = 1;
		after = res.next;
		int bad = 0;
		sqlitedbg_each_connection(list_noop, &bad);
		if (bad) r->failed = 1;
		sqlitedbg_set_client_connected(r->reads++ % 3 == 0);
	}
	sqlitedbg_set_client_connected(0);
	return NULL;
}

static void test_threads(void) {
	listing l;
	size_t before = list_conns(&l);
	uint64_t cur = cursor_now();
	enum { N = 8 };
	pthread_t th[N], rt;
	worker w[N];
	reader rd;
	atomic_int stop = 0;
	memset(w, 0, sizeof w);
	memset(&rd, 0, sizeof rd);
	rd.stop = &stop;
	pthread_create(&rt, NULL, reader_main, &rd);
	for (int i = 0; i < N; i++) {
		w[i].index = i;
		w[i].stop = &stop;
		pthread_create(&th[i], NULL, worker_main, &w[i]);
	}
	struct timespec two = {2, 0};
	nanosleep(&two, NULL);
	atomic_store(&stop, 1);
	for (int i = 0; i < N; i++) pthread_join(th[i], NULL);
	pthread_join(rt, NULL);

	long long stmts = 0, closes = 0;
	for (int i = 0; i < N; i++) {
		CHECK_EQ(w[i].failed, 0);
		stmts += w[i].stmts;
		closes += w[i].closes;
	}
	CHECK_EQ(rd.failed, 0);
	CHECK(stmts > 1000);
	// every thread's connections are gone, and every event made was numbered
	CHECK_EQ(list_conns(&l), before);
	batch b;
	read_all(0, &b);
	CHECK_EQ(b.next - cur, 2 * (uint64_t)stmts + (uint64_t)closes);
	batch_free(&b);
	printf("  threads: %lld statements, %lld closes, %lld reads\n", stmts, closes, rd.reads);
}

// Fixture for EventDecoder's JVM test: a known run, as bytes plus a table of what they hold.

static void write_tsv_row(FILE *f, const sqlitedbg_event_record *r, const char *sql) {
	fprintf(f, "%llu\t%u\t%llu\t%lld\t%lld\t%lld\t%u\t%.*s\n", (unsigned long long)r->seq, r->kind,
	        (unsigned long long)r->connection_id, (long long)r->thread_id, (long long)r->time_ns,
	        (long long)r->duration_ns, r->flags, (int)r->sql_bytes, sql);
}

static void run_fixture_scenario(void) {
	char sql[256];
	exec_ok(g_main, "CREATE TABLE fx(a)");
	sqlite3_stmt *st;
	CHECK_EQ(sqlite3_prepare_v2(g_main, "INSERT INTO fx VALUES(?)", -1, &st, NULL), SQLITE_OK);
	sqlite3_bind_int(st, 1, 5);
	sqlite3_step(st);
	sqlite3_reset(st);
	sqlitedbg_set_client_connected(1);
	sqlite3_bind_int(st, 1, 6);
	sqlite3_step(st);
	sqlite3_finalize(st);
	sqlitedbg_set_client_connected(0);
	exec_ok(g_main, "SELECT 'h\xC3\xA9llo \xE2\x82\xAC \xF0\x9F\x98\x80'");
	exec_ok(g_main, "CREATE TABLE fxlog(a)");
	exec_ok(g_main, "CREATE TRIGGER fxt AFTER INSERT ON fx BEGIN INSERT INTO fxlog VALUES(new.a); END");
	exec_ok(g_main, "INSERT INTO fx VALUES(1)");
	size_t n = (size_t)snprintf(sql, sizeof sql, "SELECT '");
	for (int i = 0; i < 25; i++) n += (size_t)snprintf(sql + n, sizeof sql - n, "\xE2\x82\xAC");
	snprintf(sql + n, sizeof sql - n, "'");
	exec_ok(g_main, sql);
	sqlite3 *c = open_app(":memory:");
	exec_ok(c, "SELECT 1");
	CHECK_EQ(sqlite3_close(c), SQLITE_OK);
}

static unsigned char *slurp(const char *path, size_t *n) {
	FILE *f = fopen(path, "rb");
	if (!f) {
		perror(path);
		return NULL;
	}
	fseek(f, 0, SEEK_END);
	long len = ftell(f);
	fseek(f, 0, SEEK_SET);
	unsigned char *buf = malloc((size_t)len + 1);
	if (fread(buf, 1, (size_t)len, f) != (size_t)len) len = 0;
	buf[len] = 0;
	fclose(f);
	*n = (size_t)len;
	return buf;
}

// Walks records in bin and rewrites them as the table; used both to write and to check the fixture.
static int bin_to_tsv(const unsigned char *bin, size_t n, char **out) {
	size_t cap = 1 << 16;
	char *s = malloc(cap);
	FILE *f = fmemopen(s, cap, "w");
	size_t off = 0;
	int ok = 1;
	while (off < n) {
		const sqlitedbg_event_record *r = (const sqlitedbg_event_record *)(const void *)(bin + off);
		if (off + sizeof *r > n || r->record_bytes % 8 || off + r->record_bytes > n ||
		    r->record_bytes < sizeof *r + r->sql_bytes) {
			ok = 0;
			break;
		}
		write_tsv_row(f, r, (const char *)bin + off + sizeof *r);
		off += r->record_bytes;
	}
	fclose(f);
	*out = s;
	return ok;
}

static void write_fixture(const char *bin_path, const char *tsv_path) {
	uint64_t cur = cursor_now();
	run_fixture_scenario();
	size_t cap = 1 << 16;
	unsigned char *buf = malloc(cap);
	sqlitedbg_read_result r;
	sqlitedbg_read_events(cur, buf, cap, &r);
	CHECK_EQ(r.dropped, 0);
	CHECK(r.count > 10);
	FILE *f = fopen(bin_path, "wb");
	CHECK(f != NULL);
	if (f) {
		fwrite(buf, 1, r.bytes, f);
		fclose(f);
	}
	char *tsv;
	CHECK(bin_to_tsv(buf, r.bytes, &tsv));
	f = fopen(tsv_path, "wb");
	CHECK(f != NULL);
	if (f) {
		fputs(tsv, f);
		fclose(f);
	}
	free(tsv);
	free(buf);
}

// The committed fixture still has the layout of the header: its table is what its bytes hold
static void check_fixture(const char *bin_path, const char *tsv_path) {
	size_t bn, tn;
	unsigned char *bin = slurp(bin_path, &bn);
	unsigned char *tsv = slurp(tsv_path, &tn);
	CHECK(bin && tsv);
	if (!bin || !tsv) return;
	char *again;
	CHECK(bin_to_tsv(bin, bn, &again));
	CHECK(strcmp(again, (char *)tsv) == 0);
	free(again);
	free(bin);
	free(tsv);
}

int main(int argc, char **argv) {
	snprintf(g_dir, sizeof g_dir, "/tmp/sqlitedbg-test-XXXXXX");
	if (!mkdtemp(g_dir)) {
		perror("mkdtemp");
		return 2;
	}
	snprintf(g_db_path, sizeof g_db_path, "%s/app.db", g_dir);

	if (argc == 4 && strcmp(argv[1], "--check-fixture") == 0) {
		check_fixture(argv[2], argv[3]);
	} else {
		printf("# install\n");
		test_install();
		printf("# events\n");
		test_events();
		test_expanded();
		printf("# truncation\n");
		test_truncation();
		printf("# burst\n");
		test_burst();
		printf("# close and list\n");
		test_close_and_list();
		printf("# server connections\n");
		test_server();
		printf("# threads\n");
		test_threads();
		if (argc == 4 && strcmp(argv[1], "--write-fixture") == 0) write_fixture(argv[2], argv[3]);
		sqlite3_close(g_main);
	}

	char cmd[300];
	snprintf(cmd, sizeof cmd, "rm -rf '%s'", g_dir);
	if (system(cmd) != 0) fprintf(stderr, "could not remove %s\n", g_dir);
	printf("%d checks, %d failures\n", g_checks, g_failures);
	return g_failures ? 1 : 0;
}
