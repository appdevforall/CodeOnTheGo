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

// The largest row data a test records (sqlitedbg_set_row_capture's max_row_bytes in the row tests).
#define MAX_ROW_DATA 1024

typedef struct ev {
	uint32_t kind, flags;
	uint64_t seq, conn;
	int64_t tid, time_ns, duration_ns, rows, changes;
	char sql[MAX_ROW_DATA + 8];  // SQL text, or a row's data for SQLITEDBG_EVENT_ROW
	uint32_t data_bytes;
} ev;

typedef struct batch {
	ev *events;
	size_t count;
	uint64_t next, dropped;
	int reset;
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
	b->reset = r.reset;
	size_t off = 0;
	uint64_t prev = r.reset ? 0 : after;
	for (size_t i = 0; i < r.count; i++) {
		const sqlitedbg_event_record *rec = (const sqlitedbg_event_record *)(void *)(buf + off);
		CHECK(rec->record_bytes >= sizeof *rec);
		CHECK_EQ(rec->record_bytes % 8, 0);
		CHECK(rec->data_bytes <= (rec->kind == SQLITEDBG_EVENT_ROW ? MAX_ROW_DATA : MAX_SQL));
		CHECK(rec->record_bytes >= sizeof *rec + rec->data_bytes);
		CHECK(rec->seq > prev);
		// rows and changes are -1 where they do not apply
		if (rec->kind != SQLITEDBG_EVENT_PROFILE && rec->kind != SQLITEDBG_EVENT_ROW) CHECK_EQ(rec->rows, -1);
		if (rec->kind != SQLITEDBG_EVENT_PROFILE) CHECK_EQ(rec->changes, -1);
		prev = rec->seq;
		ev *e = &b->events[i];
		e->kind = rec->kind;
		e->flags = rec->flags;
		e->seq = rec->seq;
		e->conn = rec->connection_id;
		e->tid = rec->thread_id;
		e->time_ns = rec->time_ns;
		e->duration_ns = rec->duration_ns;
		e->rows = rec->rows;
		e->changes = rec->changes;
		e->data_bytes = rec->data_bytes;
		memcpy(e->sql, (const char *)buf + off + sizeof *rec, rec->data_bytes);
		e->sql[rec->data_bytes] = 0;
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

extern int sqlitedbg_test_fail_init;  // sqlitedbg.c, built with SQLITEDBG_TEST
extern uint32_t sqlitedbg_test_max_row_bytes(void);  // likewise

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
	CHECK_EQ(b.events[0].data_bytes, MAX_SQL);
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
	// no rows; one row changed by the insert itself, not counting the trigger's
	CHECK_EQ(b.events[3].rows, 0);
	CHECK_EQ(b.events[3].changes, 1);
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

	// app SQL that starts with a comment is not a trigger: it is expanded like any other
	cur = cursor_now();
	sqlitedbg_set_client_connected(1);
	CHECK_EQ(sqlite3_prepare_v2(g_main, "-- note\nINSERT INTO t VALUES(?)", -1, &st, NULL), SQLITE_OK);
	sqlite3_bind_int(st, 1, 9);
	CHECK_EQ(sqlite3_step(st), SQLITE_DONE);
	sqlite3_finalize(st);
	sqlitedbg_set_client_connected(0);
	read_all(cur, &b);
	CHECK(b.count >= 2);
	CHECK(strstr(b.events[0].sql, "INSERT INTO t VALUES(9)") != NULL);
	CHECK(b.events[0].flags & SQLITEDBG_EVENT_EXPANDED);
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
	CHECK_EQ(b.events[0].data_bytes, MAX_SQL);
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
		CHECK_EQ(b.events[i].data_bytes, 62);
		CHECK(b.events[i].flags & SQLITEDBG_EVENT_TRUNCATED);
		CHECK(utf8_valid(b.events[i].sql, b.events[i].data_bytes));
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
	CHECK_EQ(b.events[0].data_bytes, 63);
	CHECK(utf8_valid(b.events[0].sql, b.events[0].data_bytes));
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
		CHECK_EQ(b.events[0].data_bytes, MAX_SQL);
		CHECK_EQ((b.events[0].flags & SQLITEDBG_EVENT_TRUNCATED) != 0, over);
		batch_free(&b);
	}
}

// Rows and changes (ADFA-7296)

typedef struct rv {
	int type, cut;
	uint32_t stored, full;
	const unsigned char *p;
} rv;

// Parses a ROW event's data into at most max values; checks it is exactly as long as data_bytes.
// Returns the values kept, or -1 if the data is malformed.
static int parse_row(const ev *e, uint32_t *columns, rv *vals, int max) {
	const unsigned char *d = (const unsigned char *)e->sql;
	size_t n = e->data_bytes, off = sizeof(sqlitedbg_row_header);
	if (n < off) return -1;
	sqlitedbg_row_header h;
	memcpy(&h, d, sizeof h);
	*columns = h.columns;
	if (h.kept > h.columns || (int)h.kept > max) return -1;
	for (uint32_t i = 0; i < h.kept; i++) {
		sqlitedbg_row_value v;
		if (n - off < sizeof v) return -1;
		memcpy(&v, d + off, sizeof v);
		off += sizeof v;
		if (v.stored_bytes > n - off || v.stored_bytes > v.full_bytes || v.reserved != 0 || v.cut != (v.stored_bytes < v.full_bytes)) return -1;
		vals[i] = (rv){v.type, v.cut, v.stored_bytes, v.full_bytes, d + off};
		off += v.stored_bytes;
	}
	return off == n ? (int)h.kept : -1;
}

static int64_t rv_int(const rv *v) {
	int64_t x = 0;
	if (v->stored == 8) memcpy(&x, v->p, 8);
	return x;
}

static double rv_double(const rv *v) {
	double x = 0;
	if (v->stored == 8) memcpy(&x, v->p, 8);
	return x;
}

// The last event of `kind` whose SQL is `sql`, or NULL.
static const ev *find_event(const batch *b, uint32_t kind, const char *sql) {
	for (size_t i = b->count; i-- > 0;)
		if (b->events[i].kind == kind && strcmp(b->events[i].sql, sql) == 0) return &b->events[i];
	return NULL;
}

static size_t count_kind(const batch *b, uint32_t kind) {
	size_t n = 0;
	for (size_t i = 0; i < b->count; i++) n += b->events[i].kind == kind;
	return n;
}

static void test_rows(void) {
	exec_ok(g_main, "CREATE TABLE r(i INTEGER, f REAL, s TEXT, b BLOB, n)");
	// row 2's text is 20 two-byte characters (40 bytes) and its blob 40 bytes, both over the 15-byte
	// value limit below; 15 falls inside a character, so the text is cut to 14
	char long_text[64] = "";
	for (int k = 0; k < 20; k++) strcat(long_text, "\xC3\xA9");
	char sql[256];
	exec_ok(g_main, "INSERT INTO r VALUES(1, 1.5, 'one', x'0102', NULL)");
	snprintf(sql, sizeof sql, "INSERT INTO r VALUES(2, 2.5, '%s', zeroblob(40), NULL)", long_text);
	exec_ok(g_main, sql);
	exec_ok(g_main, "INSERT INTO r VALUES(3, 3.5, 'three', x'03', NULL)");
	exec_ok(g_main, "INSERT INTO r VALUES(4, 4.5, 'four', x'04', NULL)");
	exec_ok(g_main, "INSERT INTO r VALUES(5, 5.5, 'five', x'05', NULL)");

	sqlitedbg_set_row_capture(3, 15, MAX_ROW_DATA);
	sqlitedbg_set_client_connected(1);
	uint64_t cur = cursor_now();
	sqlite3_stmt *st;
	CHECK_EQ(sqlite3_prepare_v2(g_main, "SELECT i, f, s, b, n FROM r ORDER BY i", -1, &st, NULL), SQLITE_OK);
	int steps = 0;
	while (sqlite3_step(st) == SQLITE_ROW) {
		steps++;
		// the app sees its own types and values: the extension read each column with its own accessor
		CHECK_EQ(sqlite3_column_type(st, 0), SQLITE_INTEGER);
		CHECK_EQ(sqlite3_column_type(st, 1), SQLITE_FLOAT);
		CHECK_EQ(sqlite3_column_type(st, 2), SQLITE_TEXT);
		CHECK_EQ(sqlite3_column_type(st, 3), SQLITE_BLOB);
		CHECK_EQ(sqlite3_column_type(st, 4), SQLITE_NULL);
		CHECK_EQ(sqlite3_column_int64(st, 0), steps);
		CHECK(sqlite3_column_double(st, 1) == steps + 0.5);
	}
	sqlite3_finalize(st);
	sqlitedbg_set_client_connected(0);
	CHECK_EQ(steps, 5);

	batch b;
	read_all(cur, &b);
	// the statement, its first three rows, its profile
	CHECK_EQ(b.count, 5);
	CHECK_EQ(b.events[0].kind, SQLITEDBG_EVENT_STATEMENT);
	for (int k = 1; k <= 3; k++) {
		const ev *e = &b.events[k];
		CHECK_EQ(e->kind, SQLITEDBG_EVENT_ROW);
		CHECK_EQ(e->rows, k);
		CHECK_EQ(e->changes, -1);
		CHECK_EQ(e->duration_ns, -1);
		CHECK_EQ(e->conn, 1);
		CHECK_EQ(e->tid, tid());
		uint32_t columns = 0;
		rv v[5];
		CHECK_EQ(parse_row(e, &columns, v, 5), 5);
		CHECK_EQ(columns, 5);
		CHECK_EQ(v[0].type, SQLITE_INTEGER);
		CHECK_EQ(rv_int(&v[0]), k);
		CHECK_EQ(v[1].type, SQLITE_FLOAT);
		CHECK(rv_double(&v[1]) == k + 0.5);
		CHECK_EQ(v[2].type, SQLITE_TEXT);
		CHECK_EQ(v[3].type, SQLITE_BLOB);
		CHECK_EQ(v[4].type, SQLITE_NULL);
		CHECK_EQ(v[4].stored, 0);
		if (k == 2) {
			CHECK(e->flags & SQLITEDBG_EVENT_TRUNCATED);
			CHECK_EQ(v[2].full, 40);
			CHECK_EQ(v[2].stored, 14);
			CHECK_EQ(v[2].cut, 1);
			CHECK(utf8_valid((const char *)v[2].p, v[2].stored));
			CHECK(memcmp(v[2].p, long_text, 14) == 0);
			CHECK_EQ(v[3].full, 40);
			CHECK_EQ(v[3].stored, 15);
			CHECK_EQ(v[3].cut, 1);
		} else {
			CHECK_EQ(e->flags, 0);
			const char *want = k == 1 ? "one" : "three";
			CHECK_EQ(v[2].stored, strlen(want));
			CHECK(memcmp(v[2].p, want, strlen(want)) == 0);
			CHECK_EQ(v[3].cut, 0);
		}
	}
	CHECK(memcmp(b.events[1].sql + 8 + 12 + 8 + 12 + 8 + 12 + 3 + 12, "\x01\x02", 2) == 0);
	const ev *prof = &b.events[4];
	CHECK_EQ(prof->kind, SQLITEDBG_EVENT_PROFILE);
	// all five rows counted, two left unrecorded by the limit; a read changes nothing
	CHECK_EQ(prof->rows, 5);
	CHECK(prof->flags & SQLITEDBG_EVENT_ROWS_CUT);
	CHECK_EQ(prof->changes, -1);
	batch_free(&b);

	// with no client, or with recording off, rows are counted but not recorded
	cur = cursor_now();
	exec_ok(g_main, "SELECT i FROM r");
	sqlitedbg_set_row_capture(0, 0, 0);
	sqlitedbg_set_client_connected(1);
	exec_ok(g_main, "SELECT i FROM r WHERE i < 3");
	sqlitedbg_set_client_connected(0);
	read_all(cur, &b);
	CHECK_EQ(count_kind(&b, SQLITEDBG_EVENT_ROW), 0);
	CHECK_EQ(find_event(&b, SQLITEDBG_EVENT_PROFILE, "SELECT i FROM r")->rows, 5);
	CHECK_EQ(find_event(&b, SQLITEDBG_EVENT_PROFILE, "SELECT i FROM r")->flags, 0);
	CHECK_EQ(find_event(&b, SQLITEDBG_EVENT_PROFILE, "SELECT i FROM r WHERE i < 3")->rows, 2);
	batch_free(&b);
}

static void test_internal_rows(void) {
	// a CREATE TABLE runs SQLite's own nested statement that returns a row (the schema read); with
	// recording on, it is not recorded
	sqlitedbg_set_row_capture(10, 0, MAX_ROW_DATA);
	sqlitedbg_set_client_connected(1);
	uint64_t cur = cursor_now();
	exec_ok(g_main, "CREATE TABLE ri(a)");
	exec_ok(g_main, "CREATE INDEX rii ON ri(a)");
	sqlitedbg_set_client_connected(0);
	sqlitedbg_set_row_capture(0, 0, 0);
	batch b;
	read_all(cur, &b);
	CHECK_EQ(count_kind(&b, SQLITEDBG_EVENT_ROW), 0);
	const ev *e = find_event(&b, SQLITEDBG_EVENT_PROFILE, "CREATE TABLE ri(a)");
	CHECK(e != NULL);
	if (e) CHECK_EQ(e->rows, 0);
	batch_free(&b);
}

static void test_changes(void) {
	exec_ok(g_main, "CREATE TABLE c(a)");
	uint64_t cur = cursor_now();
	exec_ok(g_main, "INSERT INTO c SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3");
	exec_ok(g_main, "UPDATE c SET a = a + 1 WHERE a > 1");
	exec_ok(g_main, "DELETE FROM c WHERE a = 1");
	exec_ok(g_main, "SELECT a FROM c");
	// after the DELETE, sqlite3_changes64 still says 1: a CREATE TABLE does not set it
	exec_ok(g_main, "CREATE TABLE c2(a)");
	exec_ok(g_main, "UPDATE c SET a = a WHERE 0");
	// t's trigger inserts into log too: only the insert's own row counts
	exec_ok(g_main, "INSERT INTO t VALUES(5)");
	batch b;
	read_all(cur, &b);
	struct {
		const char *sql;
		int64_t changes, rows;
	} want[] = {
	    {"INSERT INTO c SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3", 3, 0},
	    {"UPDATE c SET a = a + 1 WHERE a > 1", 2, 0},
	    {"DELETE FROM c WHERE a = 1", 1, 0},
	    {"SELECT a FROM c", -1, 2},
	    {"CREATE TABLE c2(a)", 0, 0},
	    {"UPDATE c SET a = a WHERE 0", 0, 0},
	    {"INSERT INTO t VALUES(5)", 1, 0},
	};
	for (size_t i = 0; i < sizeof want / sizeof want[0]; i++) {
		const ev *e = find_event(&b, SQLITEDBG_EVENT_PROFILE, want[i].sql);
		CHECK(e != NULL);
		if (!e) continue;
		if (e->changes != want[i].changes) fprintf(stderr, "  changes of %s\n", want[i].sql);
		CHECK_EQ(e->changes, want[i].changes);
		CHECK_EQ(e->rows, want[i].rows);
	}
	batch_free(&b);
}

// The first value of a ROW event, as an int (INTEGER) or its first byte (TEXT).
static int64_t first_value(const ev *e, int *type) {
	uint32_t columns;
	rv v[2];
	*type = 0;
	if (parse_row(e, &columns, v, 2) < 1) return -1;
	*type = v[0].type;
	return v[0].type == SQLITE_INTEGER ? rv_int(&v[0]) : v[0].stored ? v[0].p[0] : -1;
}

static void test_interleaved_rows(void) {
	// two statements stepped in turn on one connection keep their own row numbers
	sqlitedbg_set_row_capture(10, 0, MAX_ROW_DATA);
	sqlitedbg_set_client_connected(1);
	uint64_t cur = cursor_now();
	sqlite3_stmt *a, *s;
	CHECK_EQ(sqlite3_prepare_v2(g_main, "SELECT 10 UNION ALL SELECT 20", -1, &a, NULL), SQLITE_OK);
	CHECK_EQ(sqlite3_prepare_v2(g_main, "SELECT 'x' UNION ALL SELECT 'y' UNION ALL SELECT 'z'", -1, &s, NULL),
	         SQLITE_OK);
	CHECK_EQ(sqlite3_step(a), SQLITE_ROW);
	CHECK_EQ(sqlite3_step(s), SQLITE_ROW);
	CHECK_EQ(sqlite3_step(a), SQLITE_ROW);
	CHECK_EQ(sqlite3_step(s), SQLITE_ROW);
	CHECK_EQ(sqlite3_step(s), SQLITE_ROW);
	sqlite3_finalize(a);
	sqlite3_finalize(s);
	sqlitedbg_set_client_connected(0);
	sqlitedbg_set_row_capture(0, 0, 0);
	batch b;
	read_all(cur, &b);
	int64_t want_rows[] = {1, 1, 2, 2, 3};
	int64_t want_first[] = {10, 'x', 20, 'y', 'z'};
	size_t k = 0;
	for (size_t i = 0; i < b.count; i++) {
		if (b.events[i].kind != SQLITEDBG_EVENT_ROW) continue;
		int type;
		int64_t first = first_value(&b.events[i], &type);
		if (k < 5) {
			CHECK_EQ(b.events[i].rows, want_rows[k]);
			CHECK_EQ(first, want_first[k]);
			CHECK_EQ(type, k == 0 || k == 2 ? SQLITE_INTEGER : SQLITE_TEXT);
		}
		k++;
	}
	CHECK_EQ(k, 5);
	CHECK_EQ(find_event(&b, SQLITEDBG_EVENT_PROFILE, "SELECT 10 UNION ALL SELECT 20")->rows, 2);
	CHECK_EQ(find_event(&b, SQLITEDBG_EVENT_PROFILE, "SELECT 'x' UNION ALL SELECT 'y' UNION ALL SELECT 'z'")->rows, 3);
	batch_free(&b);

	// one statement more than there are slots, each started in turn: the oldest loses its slot, so its
	// later rows go unrecorded and its profile has no counts
	enum { N = SQLITEDBG_STATEMENT_SLOTS + 1 };
	sqlite3_stmt *st[N];
	char sql[64];
	sqlitedbg_set_row_capture(10, 0, MAX_ROW_DATA);
	sqlitedbg_set_client_connected(1);
	cur = cursor_now();
	for (int i = 0; i < N; i++) {
		snprintf(sql, sizeof sql, "SELECT %d UNION ALL SELECT %d", i, i);
		CHECK_EQ(sqlite3_prepare_v2(g_main, sql, -1, &st[i], NULL), SQLITE_OK);
		CHECK_EQ(sqlite3_step(st[i]), SQLITE_ROW);
	}
	CHECK_EQ(sqlite3_step(st[0]), SQLITE_ROW);
	CHECK_EQ(sqlite3_step(st[1]), SQLITE_ROW);
	for (int i = 0; i < N; i++) sqlite3_finalize(st[i]);
	sqlitedbg_set_client_connected(0);
	sqlitedbg_set_row_capture(0, 0, 0);
	read_all(cur, &b);
	// one row each, plus statement 1's second row; statement 0's second row had no slot
	CHECK_EQ(count_kind(&b, SQLITEDBG_EVENT_ROW), N + 1);
	const ev *p0 = find_event(&b, SQLITEDBG_EVENT_PROFILE, "SELECT 0 UNION ALL SELECT 0");
	const ev *p1 = find_event(&b, SQLITEDBG_EVENT_PROFILE, "SELECT 1 UNION ALL SELECT 1");
	CHECK(p0 && p1);
	if (p0) CHECK_EQ(p0->rows, -1);
	if (p0) CHECK_EQ(p0->changes, -1);
	if (p1) CHECK_EQ(p1->rows, 2);
	batch_free(&b);
}

static void test_row_limits(void) {
	// the row's data stops at max_row_bytes: the header (8) and one INTEGER (12 + 8) fit in 40, a
	// second INTEGER does not
	sqlitedbg_set_row_capture(10, 0, 40);
	sqlitedbg_set_client_connected(1);
	uint64_t cur = cursor_now();
	exec_ok(g_main, "SELECT 1, 2");
	// a value limit of 1 cuts a two-byte character to nothing, which is still a whole value
	sqlitedbg_set_row_capture(10, 1, MAX_ROW_DATA);
	exec_ok(g_main, "SELECT '\xC3\xA9', x'0a0b'");
	sqlitedbg_set_client_connected(0);
	sqlitedbg_set_row_capture(0, 0, 0);
	batch b;
	read_all(cur, &b);
	CHECK_EQ(count_kind(&b, SQLITEDBG_EVENT_ROW), 2);
	const ev *wide = NULL, *cut = NULL;
	for (size_t i = 0; i < b.count; i++) {
		if (b.events[i].kind != SQLITEDBG_EVENT_ROW) continue;
		if (!wide) wide = &b.events[i];
		else cut = &b.events[i];
	}
	uint32_t columns;
	rv v[2];
	if (wide) {
		CHECK_EQ(parse_row(wide, &columns, v, 2), 1);
		CHECK_EQ(columns, 2);
		CHECK_EQ(rv_int(&v[0]), 1);
		CHECK(wide->flags & SQLITEDBG_EVENT_TRUNCATED);
		CHECK(wide->data_bytes <= 40);
	}
	if (cut) {
		CHECK_EQ(parse_row(cut, &columns, v, 2), 2);
		CHECK_EQ(v[0].type, SQLITE_TEXT);
		CHECK_EQ(v[0].stored, 0);
		CHECK_EQ(v[0].full, 2);
		CHECK_EQ(v[1].type, SQLITE_BLOB);
		CHECK_EQ(v[1].stored, 1);
		CHECK_EQ(v[1].p[0], 0x0a);
		CHECK(cut->flags & SQLITEDBG_EVENT_TRUNCATED);
	}
	CHECK(wide && cut);
	batch_free(&b);
	// a row limit above SQLITEDBG_MAX_ROW_BYTES is lowered to it, so no row record outgrows a reader's batch
	sqlitedbg_set_row_capture(1, 0, UINT32_MAX);
	CHECK_EQ(sqlitedbg_test_max_row_bytes(), SQLITEDBG_MAX_ROW_BYTES);
	sqlitedbg_set_row_capture(1, 0, 1000);
	CHECK_EQ(sqlitedbg_test_max_row_bytes(), 1000);
	sqlitedbg_set_row_capture(0, 0, 0);
	CHECK_EQ(sqlitedbg_test_max_row_bytes(), 4096);
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
	const uint64_t first_seq = b.events[0].seq;
	const size_t stored = b.count;
	batch_free(&b);

	// drops are reported once even when nothing is copied: a record too big for out stays
	// unpassed, and the cursor moves up to it
	{
		sqlitedbg_read_result r;
		char tiny[8];
		sqlitedbg_read_events(cur, tiny, sizeof tiny, &r);
		CHECK_EQ(r.count, 0);
		CHECK_EQ(r.dropped, made - stored);
		CHECK_EQ(r.next, first_seq - 1);
		sqlitedbg_read_result again;
		sqlitedbg_read_events(r.next, tiny, sizeof tiny, &again);
		CHECK_EQ(again.dropped, 0);
		CHECK_EQ(again.next, r.next);
	}

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

static void test_cursor_reset(void) {
	exec_ok(g_main, "SELECT 1");
	batch b;
	read_all(0, &b);
	const uint64_t newest = b.next;
	const size_t stored = b.count;
	CHECK(newest > 1);
	CHECK_EQ(b.reset, 0);
	batch_free(&b);

	// caught up: the newest seq is a valid cursor
	read_all(newest, &b);
	CHECK_EQ(b.reset, 0);
	CHECK_EQ(b.count, 0);
	CHECK_EQ(b.next, newest);
	batch_free(&b);

	// one past the newest, and far past it, cannot be a cursor of this process: read from the start
	const uint64_t past[] = {newest + 1, newest + 1000, UINT64_MAX};
	for (size_t i = 0; i < sizeof past / sizeof past[0]; i++) {
		read_all(past[i], &b);
		CHECK_EQ(b.reset, 1);
		CHECK_EQ(b.count, stored);
		CHECK_EQ(b.next, newest);
		CHECK_EQ(b.dropped + b.count, newest);
		batch_free(&b);
	}

	// the first event made after a reset is read normally by the cursor the reset gave
	exec_ok(g_main, "SELECT 2");
	read_all(newest, &b);
	CHECK_EQ(b.reset, 0);
	CHECK_EQ(b.count, 2);
	batch_free(&b);
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
	CHECK_EQ(b.events[0].data_bytes, 0);
	batch_free(&b);

	// the failed close left the connection open: a statement on it clears closing, a second failed close sets it
	exec_ok(c, "SELECT 1");
	list_conns(&l);
	CHECK_EQ(find_conn(&l, id)->closing, 0);
	CHECK_EQ(sqlite3_close(c), SQLITE_BUSY);
	list_conns(&l);
	CHECK_EQ(find_conn(&l, id)->closing, 1);

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

static void test_init_failure(void) {
	listing l;
	size_t before = list_conns(&l);
	for (int mode = 1; mode <= 2; mode++) {
		sqlitedbg_test_fail_init = mode;
		sqlite3 *db = NULL;
		// the app's open must not fail because the debugger could not track the connection
		CHECK_EQ(sqlite3_open(":memory:", &db), SQLITE_OK);
		sqlitedbg_test_fail_init = 0;
		CHECK(db != NULL);
		if (!db) continue;
		CHECK_EQ(list_conns(&l), before);
		exec_ok(db, "CREATE TABLE u(a)");
		CHECK_EQ(sqlite3_close(db), SQLITE_OK);
	}
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

// Each of these would let a client keep a lock on the app's database (ADFA-7135 review, H3).
static void test_server_holds_no_locks(void) {
	static const char *const denied[] = {
		"BEGIN",
		"BEGIN IMMEDIATE",
		"BEGIN EXCLUSIVE",
		"COMMIT",
		"END",
		"ROLLBACK",
		"SAVEPOINT s",
		"PRAGMA locking_mode=EXCLUSIVE",
		"PRAGMA locking_mode",
		"PRAGMA busy_timeout=60000",
		"PRAGMA busy_timeout",
		// process-wide settings that would reach the app's own connections
		"PRAGMA hard_heap_limit=1000000000",
		"PRAGMA hard_heap_limit",
		"PRAGMA soft_heap_limit=1000000000",
		"PRAGMA soft_heap_limit",
		"PRAGMA temp_store_directory='/tmp'",
	};
	for (size_t i = 0; i < sizeof denied / sizeof denied[0]; i++) {
		sqlite3 *srv = NULL;
		CHECK_EQ(sqlitedbg_open_server(g_db_path, 0, &srv), SQLITE_OK);
		int rc = step_all(srv, denied[i]);
		if (rc != SQLITE_AUTH) fprintf(stderr, "not refused: %s (%d)\n", denied[i], rc);
		CHECK_EQ(rc, SQLITE_AUTH);
		// a read on the same connection, then the app must still be able to write
		CHECK_EQ(step_all(srv, "SELECT count(*) FROM t"), SQLITE_DONE);
		char *err = NULL;
		rc = sqlite3_exec(g_main, "INSERT INTO t VALUES(4242)", NULL, NULL, &err);
		if (rc != SQLITE_OK) fprintf(stderr, "app write after \"%s\": %s\n", denied[i], err ? err : "?");
		sqlite3_free(err);
		CHECK_EQ(rc, SQLITE_OK);
		exec_ok(g_main, "DELETE FROM t WHERE a=4242");
		CHECK_EQ(sqlite3_close_v2(srv), SQLITE_OK);
	}

	// an ordinary read leaves the server connection with no lock and in autocommit
	sqlite3 *srv = NULL;
	CHECK_EQ(sqlitedbg_open_server(g_db_path, 0, &srv), SQLITE_OK);
	CHECK_EQ(step_all(srv, "SELECT * FROM t"), SQLITE_DONE);
	CHECK_EQ(sqlite3_get_autocommit(srv), 1);
	exec_ok(g_main, "INSERT INTO t VALUES(4243)");
	exec_ok(g_main, "DELETE FROM t WHERE a=4243");
	CHECK_EQ(sqlite3_close_v2(srv), SQLITE_OK);
}

extern int (*const sqlitedbg_test_server_authorizer)(void *, int, const char *, const char *, const char *,
                                                     const char *);  // sqlitedbg.c, built with SQLITEDBG_TEST

// The backstop for a transaction left open on a server connection (ADFA-7135 review, F37): the
// authorizer refuses ROLLBACK like any transaction statement, so sqlitedbg_end_transaction's own has
// to get past it, and only that one.
static void test_server_ends_transaction(void) {
	sqlite3 *srv = NULL;
	CHECK_EQ(sqlitedbg_open_server(g_db_path, 0, &srv), SQLITE_OK);
	CHECK_EQ(sqlitedbg_end_transaction(srv), SQLITE_OK);  // none open
	CHECK_EQ(sqlite3_get_autocommit(srv), 1);

	// a transaction opened past the authorizer, as a hole in it would leave one, holding a read lock
	CHECK_EQ(sqlite3_set_authorizer(srv, NULL, NULL), SQLITE_OK);
	CHECK_EQ(step_all(srv, "BEGIN"), SQLITE_DONE);
	CHECK_EQ(step_all(srv, "SELECT count(*) FROM t"), SQLITE_DONE);
	CHECK_EQ(sqlite3_set_authorizer(srv, sqlitedbg_test_server_authorizer, NULL), SQLITE_OK);
	CHECK_EQ(sqlite3_get_autocommit(srv), 0);
	CHECK_EQ(sqlite3_exec(g_main, "INSERT INTO t VALUES(4244)", NULL, NULL, NULL), SQLITE_BUSY);

	// the client still cannot end it or open another
	CHECK_EQ(step_all(srv, "ROLLBACK"), SQLITE_AUTH);
	CHECK_EQ(step_all(srv, "COMMIT"), SQLITE_AUTH);
	CHECK_EQ(step_all(srv, "BEGIN"), SQLITE_AUTH);
	CHECK_EQ(sqlite3_get_autocommit(srv), 0);

	CHECK_EQ(sqlitedbg_end_transaction(srv), SQLITE_OK);
	CHECK_EQ(sqlite3_get_autocommit(srv), 1);
	exec_ok(g_main, "INSERT INTO t VALUES(4244)");
	exec_ok(g_main, "DELETE FROM t WHERE a=4244");

	// the exemption ends with the call
	CHECK_EQ(step_all(srv, "BEGIN"), SQLITE_AUTH);
	CHECK_EQ(step_all(srv, "ROLLBACK"), SQLITE_AUTH);
	CHECK_EQ(step_all(srv, "SELECT count(*) FROM t"), SQLITE_DONE);
	CHECK_EQ(sqlite3_close_v2(srv), SQLITE_OK);
}

typedef struct locker {
	char path[320];
	atomic_int locked, release;
} locker;

// An app connection that holds an exclusive lock (rollback-journal mode) until told to release it.
static void *locker_main(void *arg) {
	locker *l = arg;
	sqlite3 *db = open_app(l->path);
	exec_ok(db, "BEGIN EXCLUSIVE");
	atomic_store(&l->locked, 1);
	while (!atomic_load(&l->release)) usleep(1000);
	exec_ok(db, "COMMIT");
	sqlite3_close(db);
	return NULL;
}

static double since_ms(const struct timespec *t0) {
	struct timespec t1;
	clock_gettime(CLOCK_MONOTONIC, &t1);
	return (t1.tv_sec - t0->tv_sec) * 1e3 + (t1.tv_nsec - t0->tv_nsec) / 1e6;
}

static void *release_later(void *arg) {
	usleep(500 * 1000);
	atomic_store(&((locker *)arg)->release, 1);
	return NULL;
}

// A server read waits for the app's lock (rollback-journal mode) instead of failing at once.
static void test_server_waits_for_locks(void) {
	locker l = {0};
	snprintf(l.path, sizeof l.path, "%s/journal.db", g_dir);
	sqlite3 *app = open_app(l.path);
	exec_ok(app, "PRAGMA journal_mode=DELETE");
	exec_ok(app, "CREATE TABLE j(a); INSERT INTO j VALUES(1)");
	sqlite3_close(app);

	// the lock is released after 500 ms: the read waits, then succeeds
	pthread_t holder, releaser;
	pthread_create(&holder, NULL, locker_main, &l);
	while (!atomic_load(&l.locked)) usleep(1000);
	sqlite3 *srv = NULL;
	CHECK_EQ(sqlitedbg_open_server(l.path, 0, &srv), SQLITE_OK);
	struct timespec t0;
	clock_gettime(CLOCK_MONOTONIC, &t0);
	pthread_create(&releaser, NULL, release_later, &l);
	CHECK_EQ(step_all(srv, "SELECT * FROM j"), SQLITE_DONE);
	double waited = since_ms(&t0);
	CHECK(waited >= 300);
	CHECK(waited < SQLITEDBG_SERVER_BUSY_MS);
	pthread_join(releaser, NULL);
	pthread_join(holder, NULL);
	CHECK_EQ(sqlite3_close_v2(srv), SQLITE_OK);

	// a lock held past the timeout gives SQLITE_BUSY after about that long, not at once
	atomic_store(&l.locked, 0);
	atomic_store(&l.release, 0);
	pthread_create(&holder, NULL, locker_main, &l);
	while (!atomic_load(&l.locked)) usleep(1000);
	CHECK_EQ(sqlitedbg_open_server(l.path, 0, &srv), SQLITE_OK);
	clock_gettime(CLOCK_MONOTONIC, &t0);
	CHECK_EQ(step_all(srv, "SELECT * FROM j"), SQLITE_BUSY);
	waited = since_ms(&t0);
	CHECK(waited >= SQLITEDBG_SERVER_BUSY_MS - 500);
	CHECK(waited < SQLITEDBG_SERVER_BUSY_MS + 2000);
	atomic_store(&l.release, 1);
	pthread_join(holder, NULL);
	CHECK_EQ(step_all(srv, "SELECT * FROM j"), SQLITE_DONE);
	CHECK_EQ(sqlite3_close_v2(srv), SQLITE_OK);
}

typedef struct worker {
	int index;
	atomic_int *stop;
	long long stmts, closes;
	long long row_stmts;  // statements that return one row: each adds at most one row event
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
			w->row_stmts++;
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
			w->row_stmts++;
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
			if (rec->seq <= prev || rec->record_bytes % 8 || rec->kind < 1 || rec->kind > 4 ||
			    rec->record_bytes < sizeof *rec + rec->data_bytes)
				r->failed = 1;
			prev = rec->seq;
			off += rec->record_bytes;
		}
		if (off != res.bytes) r->failed = 1;
		after = res.next;
		int bad = 0;
		sqlitedbg_each_connection(list_noop, &bad);
		if (bad) r->failed = 1;
		// row recording switched on and off while the workers' statements return rows
		sqlitedbg_set_row_capture(r->reads % 2 ? 4 : 0, 0, 0);
		sqlitedbg_set_client_connected(r->reads++ % 3 == 0);
	}
	sqlitedbg_set_client_connected(0);
	sqlitedbg_set_row_capture(0, 0, 0);
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

	long long stmts = 0, closes = 0, row_stmts = 0;
	for (int i = 0; i < N; i++) {
		CHECK_EQ(w[i].failed, 0);
		stmts += w[i].stmts;
		closes += w[i].closes;
		row_stmts += w[i].row_stmts;
	}
	CHECK_EQ(rd.failed, 0);
	CHECK(stmts > 1000);
	// every thread's connections are gone, and every event made was numbered
	CHECK_EQ(list_conns(&l), before);
	batch b;
	read_all(0, &b);
	// two events per statement, one per close, and one per recorded row: at most one per row statement
	uint64_t made = b.next - cur, base = 2 * (uint64_t)stmts + (uint64_t)closes;
	CHECK(made >= base);
	CHECK(made <= base + (uint64_t)row_stmts);
	batch_free(&b);
	printf("  threads: %lld statements, %lld closes, %lld reads\n", stmts, closes, rd.reads);
}

// Fixture for EventDecoder's JVM test: a known run, as bytes plus a table of what they hold.

// A ROW event's data as text: "<columns>/<kept>|" and then each value, comma-separated, as a type
// letter (i, f, t, b, n), "*" if it was cut, ":" and the value: an integer in decimal, a float with
// %.17g, text as is, a blob in lowercase hex, nothing for NULL. Returns 0 if the data is malformed.
static int write_row_data(FILE *f, const unsigned char *d, size_t n) {
	sqlitedbg_row_header h;
	if (n < sizeof h) return 0;
	memcpy(&h, d, sizeof h);
	fprintf(f, "%u/%u|", h.columns, h.kept);
	size_t off = sizeof h;
	for (uint32_t i = 0; i < h.kept; i++) {
		sqlitedbg_row_value v;
		if (n - off < sizeof v) return 0;
		memcpy(&v, d + off, sizeof v);
		off += sizeof v;
		if (v.stored_bytes > n - off) return 0;
		const unsigned char *p = d + off;
		const char *letter = v.type == SQLITE_INTEGER ? "i" : v.type == SQLITE_FLOAT ? "f" : v.type == SQLITE_TEXT ? "t"
		                     : v.type == SQLITE_BLOB  ? "b" : "n";
		fprintf(f, "%s%s%s:", i ? "," : "", letter, v.cut ? "*" : "");
		if (v.type == SQLITE_INTEGER || v.type == SQLITE_FLOAT) {
			if (v.stored_bytes != 8) return 0;
			long long x;
			double y;
			memcpy(&x, p, 8);
			memcpy(&y, p, 8);
			if (v.type == SQLITE_INTEGER) fprintf(f, "%lld", x);
			else fprintf(f, "%.17g", y);
		} else if (v.type == SQLITE_TEXT) {
			fprintf(f, "%.*s", (int)v.stored_bytes, (const char *)p);
		} else if (v.type == SQLITE_BLOB) {
			for (uint32_t k = 0; k < v.stored_bytes; k++) fprintf(f, "%02x", p[k]);
		}
		off += v.stored_bytes;
	}
	return off == n;
}

// seq, kind, connection, thread, time, duration, flags, rows, changes, then the SQL or the row data.
static int write_tsv_row(FILE *f, const sqlitedbg_event_record *r, const char *data) {
	fprintf(f, "%llu\t%u\t%llu\t%lld\t%lld\t%lld\t%u\t%lld\t%lld\t", (unsigned long long)r->seq, r->kind,
	        (unsigned long long)r->connection_id, (long long)r->thread_id, (long long)r->time_ns,
	        (long long)r->duration_ns, r->flags, (long long)r->rows, (long long)r->changes);
	int ok = 1;
	if (r->kind == SQLITEDBG_EVENT_ROW) ok = write_row_data(f, (const unsigned char *)data, r->data_bytes);
	else fprintf(f, "%.*s", (int)r->data_bytes, data);
	fputc('\n', f);
	return ok;
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
	// recorded rows (ADFA-7296): every type, a text and a blob cut at 4 bytes, and a profile with its
	// counts and ROWS_CUT; then a write's change count
	sqlitedbg_set_row_capture(1, 4, 1024);
	sqlitedbg_set_client_connected(1);
	exec_ok(g_main, "SELECT 7, 2.5, 'h\xC3\xA9llo', x'0a0b0c0d0e', NULL UNION ALL SELECT -9, -0.125, 'ab', x'', NULL");
	sqlitedbg_set_client_connected(0);
	sqlitedbg_set_row_capture(0, 0, 0);
	exec_ok(g_main, "DELETE FROM fxlog");
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
		    r->record_bytes < sizeof *r + r->data_bytes) {
			ok = 0;
			break;
		}
		if (!write_tsv_row(f, r, (const char *)bin + off + sizeof *r)) {
			ok = 0;
			break;
		}
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
		printf("# rows and changes\n");
		test_rows();
		test_internal_rows();
		test_changes();
		test_interleaved_rows();
		test_row_limits();
		printf("# burst\n");
		test_burst();
		printf("# cursor reset\n");
		test_cursor_reset();
		printf("# close and list\n");
		test_close_and_list();
		printf("# init failure\n");
		test_init_failure();
		printf("# server connections\n");
		test_server();
		printf("# server holds no locks\n");
		test_server_holds_no_locks();
		test_server_ends_transaction();
		printf("# server waits for locks\n");
		test_server_waits_for_locks();
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
