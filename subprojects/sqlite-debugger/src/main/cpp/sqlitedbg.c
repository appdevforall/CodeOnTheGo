// The SQLite debugger's extension (ADFA-7135). The contract is sqlitedbg.h.
//
// Built as a loadable-extension client (sqlite3ext.h): on a device every sqlite3_ call goes
// through the api table SQLite hands to the first sqlitedbg_extension_init, because libsqlitedbg.so
// links no SQLite of its own. Host tests define SQLITE_CORE, where the macros call SQLite directly.

#ifndef _GNU_SOURCE
#define _GNU_SOURCE
#endif

#include <pthread.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <unistd.h>
#include <sys/syscall.h>

#include "sqlite3ext.h"
#include "sqlitedbg.h"

SQLITE_EXTENSION_INIT1

#define DEFAULT_BUFFER_BYTES ((size_t)4 << 20)
#define DEFAULT_MAX_SQL_BYTES 4096u
#define DEFAULT_MAX_VALUE_BYTES 256u
#define DEFAULT_MAX_ROW_BYTES 4096u
#define RECORD_HEADER_BYTES 72u
#define ROW_HEADER_BYTES 8u
#define ROW_VALUE_BYTES 12u

_Static_assert(sizeof(sqlitedbg_event_record) == RECORD_HEADER_BYTES, "event record layout");
_Static_assert(sizeof(sqlitedbg_row_header) == ROW_HEADER_BYTES, "row header layout");
_Static_assert(sizeof(sqlitedbg_row_value) == ROW_VALUE_BYTES, "row value layout");

// Padding record written where the next record would not fit before the end of the ring, so a
// record is always contiguous. kind 0 is never a real event.
#define KIND_PAD 0u

// A statement the extension saw start on a connection (a TRACE_STMT that is not a trigger's or a
// nested statement's), until its PROFILE event.
typedef struct stmt_slot {
	sqlite3_stmt *stmt;           // NULL: free
	int64_t rows;                 // TRACE_ROW events so far
	int64_t recorded;             // of those, recorded as SQLITEDBG_EVENT_ROW
	int rows_cut;                 // a row went unrecorded because of the per-statement limit
	sqlite3_int64 total_changes;  // sqlite3_total_changes64 when it started
	uint64_t last_use;            // for evicting the least recently used slot
} stmt_slot;

typedef struct conn_state {
	struct conn_state *prev, *next;
	uint64_t id;
	char *path;
	int read_only;
	int closing;  // atomic: set by the close event, cleared by a later statement event
	int64_t opened_at_ns;
	int64_t open_thread_id;
	// Used only from the trace callback, which runs on the thread using the connection: requery's
	// SQLITE_THREADSAFE=2 means one thread at a time, so these need no lock.
	stmt_slot slots[SQLITEDBG_STATEMENT_SLOTS];
	uint64_t use_clock;
} conn_state;

// Taken only for the fields below; never while SQLite runs.
static pthread_mutex_t g_lock = PTHREAD_MUTEX_INITIALIZER;
// Serialises sqlitedbg_install so a second caller does not return before the first registered.
static pthread_mutex_t g_install_lock = PTHREAD_MUTEX_INITIALIZER;

static int g_installed;         // g_install_lock
static int g_api_ready;         // g_lock: sqlite3_api has been saved
static uint32_t g_max_sql;      // set before g_installed, then read-only
static int g_client_connected;  // atomic
// Row recording (sqlitedbg_set_row_capture); each read on its own, so a change mid-row is harmless.
static uint32_t g_max_rows;       // atomic; 0: off
static uint32_t g_max_value = DEFAULT_MAX_VALUE_BYTES;  // atomic
static uint32_t g_max_row = DEFAULT_MAX_ROW_BYTES;      // atomic

static conn_state *g_first, *g_last;
static uint64_t g_next_conn_id = 1;

static unsigned char *g_ring;
static size_t g_cap;
static size_t g_head;   // where the next record goes
static size_t g_tail;   // the oldest record; never a padding record while g_used > 0
static size_t g_used;   // bytes between tail and head, padding included
static uint64_t g_next_seq = 1;

// Set around the server's own sqlite3_open_v2: auto extensions run on the opening thread, inside it.
static __thread int tls_opening_server;
// Set around sqlitedbg_end_transaction's ROLLBACK, which the authorizer checks on this thread.
static __thread int tls_ending_transaction;

static int64_t now_ns(void) {
	struct timespec ts;
	clock_gettime(CLOCK_REALTIME, &ts);
	return (int64_t)ts.tv_sec * 1000000000LL + ts.tv_nsec;
}

static int64_t this_thread_id(void) {
#ifdef __ANDROID__
	return (int64_t)gettid();
#else
	return (int64_t)syscall(SYS_gettid);
#endif
}

static sqlitedbg_event_record *record_at(size_t offset) {
	return (sqlitedbg_event_record *)(void *)(g_ring + offset);
}

// Drops the oldest record, then any padding that is now the oldest. g_lock held.
static void evict_oldest(void) {
	do {
		uint32_t bytes = record_at(g_tail)->record_bytes;
		g_used -= bytes;
		g_tail += bytes;
		if (g_tail >= g_cap) g_tail = 0;
	} while (g_used > 0 && record_at(g_tail)->kind == KIND_PAD);
}

// The seq of the oldest event still buffered, or the next one to be made if none is. g_lock held.
static uint64_t oldest_seq(void) {
	return g_used > 0 ? record_at(g_tail)->seq : g_next_seq;
}

// Makes room for one record of `need` bytes at g_head. g_lock held; need <= g_cap.
static void make_room(size_t need) {
	for (;;) {
		if (g_used == 0) {
			g_head = g_tail = 0;
			return;
		}
		if (g_head > g_tail) {
			if (g_cap - g_head >= need) return;
			if (g_tail >= need) {
				sqlitedbg_event_record *pad = record_at(g_head);
				pad->record_bytes = (uint32_t)(g_cap - g_head);
				pad->kind = KIND_PAD;
				g_used += g_cap - g_head;
				g_head = 0;
				return;
			}
		} else if (g_tail - g_head >= need) {
			return;
		}
		evict_oldest();
	}
}

// Longest prefix of sql, at most max bytes, that ends on a character boundary.
static size_t utf8_cut(const char *sql, size_t len, size_t max) {
	if (len <= max) return len;
	while (max > 0 && ((unsigned char)sql[max] & 0xC0) == 0x80) max--;
	return max;
}

// Adds one record with `len` bytes of data, already within the limits.
static void add_record(uint32_t kind, uint64_t connection_id, int64_t duration_ns, int64_t rows, int64_t changes,
                       uint32_t flags, const void *data, size_t len) {
	int64_t time = now_ns();
	int64_t tid = this_thread_id();
	size_t need = (RECORD_HEADER_BYTES + len + 7) & ~(size_t)7;

	pthread_mutex_lock(&g_lock);
	uint64_t seq = g_next_seq++;
	if (need <= g_cap) {
		make_room(need);
		sqlitedbg_event_record *r = record_at(g_head);
		r->record_bytes = (uint32_t)need;
		r->kind = kind;
		r->seq = seq;
		r->connection_id = connection_id;
		r->thread_id = tid;
		r->time_ns = time;
		r->duration_ns = duration_ns;
		r->flags = flags;
		r->data_bytes = (uint32_t)len;
		r->rows = rows;
		r->changes = changes;
		memcpy((unsigned char *)r + RECORD_HEADER_BYTES, data, len);
		memset((unsigned char *)r + RECORD_HEADER_BYTES + len, 0, need - RECORD_HEADER_BYTES - len);
		g_head += need;
		g_used += need;
		if (g_head >= g_cap) g_head = 0;
	}
	pthread_mutex_unlock(&g_lock);
}

static void add_event(uint32_t kind, uint64_t connection_id, int64_t duration_ns, int64_t rows, int64_t changes,
                      uint32_t flags, const char *sql, size_t sql_len) {
	size_t kept = utf8_cut(sql, sql_len, g_max_sql);
	if (kept < sql_len) flags |= SQLITEDBG_EVENT_TRUNCATED;
	add_record(kind, connection_id, duration_ns, rows, changes, flags, sql, kept);
}

static stmt_slot *find_slot(conn_state *cs, sqlite3_stmt *stmt) {
	for (int i = 0; i < SQLITEDBG_STATEMENT_SLOTS; i++)
		if (cs->slots[i].stmt == stmt) return &cs->slots[i];
	return NULL;
}

// The slot for a statement that starts: its old one if it is restarting, else a free one, else the
// least recently used.
static stmt_slot *claim_slot(conn_state *cs, sqlite3_stmt *stmt) {
	stmt_slot *slot = find_slot(cs, stmt);
	if (!slot) slot = find_slot(cs, NULL);
	if (!slot) {
		slot = &cs->slots[0];
		for (int i = 1; i < SQLITEDBG_STATEMENT_SLOTS; i++)
			if (cs->slots[i].last_use < slot->last_use) slot = &cs->slots[i];
	}
	memset(slot, 0, sizeof *slot);
	slot->stmt = stmt;
	slot->last_use = ++cs->use_clock;
	return slot;
}

static size_t put_value(unsigned char *out, uint8_t type, uint8_t cut, uint32_t stored, uint32_t full) {
	sqlitedbg_row_value v = {type, cut, 0, stored, full};
	memcpy(out, &v, sizeof v);
	return sizeof v;
}

// Records the row the statement is on. Each column is read with the accessor of its own type, so
// nothing is converted and the app's later sqlite3_column_type and values are unchanged.
static void record_row(conn_state *cs, sqlite3_stmt *stmt, int64_t number) {
	uint32_t max_value = __atomic_load_n(&g_max_value, __ATOMIC_RELAXED);
	size_t budget = __atomic_load_n(&g_max_row, __ATOMIC_RELAXED);
	// A record must fit beside a pad in the ring (see sqlitedbg_install).
	size_t ring_max = g_cap / 2 > RECORD_HEADER_BYTES ? g_cap / 2 - RECORD_HEADER_BYTES : 0;
	if (budget > ring_max) budget = ring_max;
	if (budget < ROW_HEADER_BYTES) return;
	unsigned char *data = (unsigned char *)malloc(budget);
	if (!data) return;
	int columns = sqlite3_column_count(stmt);
	size_t len = ROW_HEADER_BYTES;
	uint32_t kept = 0;
	uint32_t flags = 0;
	for (int i = 0; i < columns; i++) {
		if (budget - len < ROW_VALUE_BYTES) break;
		size_t room = budget - len - ROW_VALUE_BYTES;
		int type = sqlite3_column_type(stmt, i);
		unsigned char *v = data + len;
		if (type == SQLITE_INTEGER || type == SQLITE_FLOAT) {
			if (room < 8) break;
			if (type == SQLITE_INTEGER) {
				sqlite3_int64 n = sqlite3_column_int64(stmt, i);
				memcpy(v + ROW_VALUE_BYTES, &n, 8);
			} else {
				double d = sqlite3_column_double(stmt, i);
				memcpy(v + ROW_VALUE_BYTES, &d, 8);
			}
			len += put_value(v, (uint8_t)type, 0, 8, 8) + 8;
		} else if (type == SQLITE_TEXT || type == SQLITE_BLOB) {
			// For TEXT, sqlite3_column_text first and then sqlite3_column_bytes, as SQLite advises.
			const void *p = type == SQLITE_TEXT ? (const void *)sqlite3_column_text(stmt, i) : sqlite3_column_blob(stmt, i);
			size_t full = (size_t)sqlite3_column_bytes(stmt, i);
			size_t limit = room < max_value ? room : max_value;
			// p is NULL when SQLite is out of memory; utf8_cut would read through it.
			size_t stored = !p ? 0 : type == SQLITE_TEXT ? utf8_cut((const char *)p, full, limit) : (full < limit ? full : limit);
			uint8_t cut = stored < full;
			if (cut) flags |= SQLITEDBG_EVENT_TRUNCATED;
			if (stored) memcpy(v + ROW_VALUE_BYTES, p, stored);
			len += put_value(v, (uint8_t)type, cut, (uint32_t)stored, (uint32_t)full) + stored;
		} else {
			len += put_value(v, SQLITE_NULL, 0, 0, 0);
		}
		kept++;
	}
	if (kept < (uint32_t)columns) flags |= SQLITEDBG_EVENT_TRUNCATED;
	sqlitedbg_row_header h = {(uint32_t)columns, kept};
	memcpy(data, &h, sizeof h);
	add_record(SQLITEDBG_EVENT_ROW, cs->id, -1, number, -1, flags, data, len);
	free(data);
}

// sqlite3_trace_v2 callback. C is the connection's conn_state, which lives until SQLite frees the
// connection, so it outlives every callback.
static int trace_cb(unsigned type, void *c, void *p, void *x) {
	conn_state *cs = (conn_state *)c;
	switch (type) {
		case SQLITE_TRACE_STMT:
		case SQLITE_TRACE_PROFILE: {
			// SQLite fires the close event before it checks for unfinalized statements, so a close that
			// then fails with SQLITE_BUSY leaves the connection open. A statement event proves it is.
			if (__atomic_load_n(&cs->closing, __ATOMIC_RELAXED)) __atomic_store_n(&cs->closing, 0, __ATOMIC_RELAXED);
			sqlite3_stmt *stmt = (sqlite3_stmt *)p;
			const char *sql = NULL;
			char *expanded = NULL;
			uint32_t flags = 0;
			int is_stmt = type == SQLITE_TRACE_STMT;
			if (is_stmt) sql = (const char *)x;
			else sql = sqlite3_sql(stmt);
			if (!sql) sql = "";
			// For a trigger, X is its own "-- ..." text while P is the outer statement, whose expansion
			// would be the wrong text. For the statement itself X is its SQL. Not a prefix test: app SQL
			// may start with a comment.
			int is_trigger = 0;
			if (is_stmt) {
				const char *own = sqlite3_sql(stmt);
				is_trigger = !own || strcmp(own, sql) != 0;
			}
			if (__atomic_load_n(&g_client_connected, __ATOMIC_RELAXED) && !is_trigger) {
				expanded = sqlite3_expanded_sql(stmt);
				if (expanded) {
					sql = expanded;
					flags |= SQLITEDBG_EVENT_EXPANDED;
				}
			}
			int64_t duration = is_stmt ? -1 : *(sqlite3_int64 *)x;
			int64_t rows = -1, changes = -1;
			if (is_stmt) {
				// SQLite's own nested statements (the schema read a CREATE TABLE runs, for one) are traced
				// with "--" text like a trigger, so they get no slot and their rows are never recorded.
				if (!is_trigger) claim_slot(cs, stmt)->total_changes = sqlite3_total_changes64(sqlite3_db_handle(stmt));
			} else {
				stmt_slot *slot = find_slot(cs, stmt);
				if (slot) {
					rows = slot->rows;
					if (slot->rows_cut) flags |= SQLITEDBG_EVENT_ROWS_CUT;
					if (!sqlite3_stmt_readonly(stmt)) {
						// sqlite3_changes64 is this statement's count once it has run, but only INSERT, UPDATE
						// and DELETE set it: after a CREATE TABLE it still holds the last write's. A total that
						// did not move means the statement changed nothing.
						sqlite3 *db = sqlite3_db_handle(stmt);
						changes = sqlite3_total_changes64(db) == slot->total_changes ? 0 : sqlite3_changes64(db);
					}
					memset(slot, 0, sizeof *slot);
				}
			}
			add_event(is_stmt ? SQLITEDBG_EVENT_STATEMENT : SQLITEDBG_EVENT_PROFILE, cs->id, duration, rows, changes,
			          flags, sql, strlen(sql));
			if (expanded) sqlite3_free(expanded);
			break;
		}
		case SQLITE_TRACE_ROW: {
			sqlite3_stmt *stmt = (sqlite3_stmt *)p;
			stmt_slot *slot = find_slot(cs, stmt);
			if (!slot) break;
			slot->rows++;
			slot->last_use = ++cs->use_clock;
			uint32_t max_rows = __atomic_load_n(&g_max_rows, __ATOMIC_RELAXED);
			if (!max_rows || !__atomic_load_n(&g_client_connected, __ATOMIC_RELAXED)) break;
			if (slot->recorded >= max_rows) {
				slot->rows_cut = 1;
				break;
			}
			slot->recorded++;
			record_row(cs, stmt, slot->rows);
			break;
		}
		case SQLITE_TRACE_CLOSE:
			// The close can still fail or leave a zombie, so only mark it: xDestroy frees.
			__atomic_store_n(&cs->closing, 1, __ATOMIC_RELAXED);
			add_event(SQLITEDBG_EVENT_CLOSE, cs->id, -1, -1, -1, 0, "", 0);
			break;
	}
	return 0;
}

// SQLite calls this when it really frees the connection (or if registering the function failed).
static void conn_destroy(void *p) {
	conn_state *cs = (conn_state *)p;
	pthread_mutex_lock(&g_lock);
	if (cs->prev) cs->prev->next = cs->next;
	else g_first = cs->next;
	if (cs->next) cs->next->prev = cs->prev;
	else g_last = cs->prev;
	pthread_mutex_unlock(&g_lock);
	free(cs->path);
	free(cs);
}

static void connection_id_fn(sqlite3_context *ctx, int argc, sqlite3_value **argv) {
	(void)argc;
	(void)argv;
	sqlite3_result_int64(ctx, (sqlite3_int64)((conn_state *)sqlite3_user_data(ctx))->id);
}

#ifdef SQLITEDBG_TEST
// Host tests: 1 fails the allocation, 2 makes registering the function fail.
int sqlitedbg_test_fail_init;
// Host tests: the row limit in force, since the tests' 8 KiB buffer caps a row before it would.
uint32_t sqlitedbg_test_max_row_bytes(void) { return __atomic_load_n(&g_max_row, __ATOMIC_RELAXED); }
#endif

static int init_connection(sqlite3 *db, const sqlite3_api_routines *api);

// An internal failure must never fail the app's own sqlite3_open: log it and leave the connection
// untracked.
int sqlitedbg_extension_init(sqlite3 *db, char **err, const sqlite3_api_routines *api) {
	(void)err;
	if (tls_opening_server) return SQLITE_OK;
	int rc = init_connection(db, api);
	if (rc != SQLITE_OK) sqlite3_log(rc, "sqlitedbg: connection not tracked (%d)", rc);
	return SQLITE_OK;
}

static int init_connection(sqlite3 *db, const sqlite3_api_routines *api) {

	pthread_mutex_lock(&g_lock);
	if (!g_api_ready) {
		SQLITE_EXTENSION_INIT2(api);
		g_api_ready = 1;
	}
	pthread_mutex_unlock(&g_lock);

	conn_state *cs = (conn_state *)calloc(1, sizeof *cs);
#ifdef SQLITEDBG_TEST
	if (sqlitedbg_test_fail_init == 1) {
		free(cs);
		cs = NULL;
	}
#endif
	if (!cs) return SQLITE_NOMEM;
	const char *filename = sqlite3_db_filename(db, "main");
	cs->path = strdup(filename ? filename : "");
	if (!cs->path) {
		free(cs);
		return SQLITE_NOMEM;
	}
	cs->read_only = sqlite3_db_readonly(db, "main") == 1;
	cs->opened_at_ns = now_ns();
	cs->open_thread_id = this_thread_id();

	pthread_mutex_lock(&g_lock);
	cs->id = g_next_conn_id++;
	cs->prev = g_last;
	if (g_last) g_last->next = cs;
	else g_first = cs;
	g_last = cs;
	pthread_mutex_unlock(&g_lock);

	// On failure SQLite has already called conn_destroy, so cs is gone.
	const char *fn_name = "sqlitedbg_connection_id";
#ifdef SQLITEDBG_TEST
	if (sqlitedbg_test_fail_init == 2) fn_name = NULL;  // SQLite refuses a NULL name
#endif
	int rc = sqlite3_create_function_v2(db, fn_name, 0, SQLITE_UTF8 | SQLITE_INNOCUOUS, cs,
	                                    connection_id_fn, NULL, NULL, conn_destroy);
	if (rc != SQLITE_OK) return rc;
	return sqlite3_trace_v2(db, SQLITE_TRACE_STMT | SQLITE_TRACE_PROFILE | SQLITE_TRACE_ROW | SQLITE_TRACE_CLOSE,
	                        trace_cb, cs);
}

int sqlitedbg_install(sqlitedbg_auto_extension_fn auto_extension, const sqlitedbg_config *config) {
	if (!auto_extension) return SQLITE_MISUSE;
	pthread_mutex_lock(&g_install_lock);
	if (g_installed) {
		pthread_mutex_unlock(&g_install_lock);
		return SQLITE_OK;
	}
	size_t cap = config && config->buffer_bytes ? config->buffer_bytes : DEFAULT_BUFFER_BYTES;
	uint32_t max_sql = config && config->max_sql_bytes ? config->max_sql_bytes : DEFAULT_MAX_SQL_BYTES;
	// Room for two of the largest records, or the ring could never hold one beside a pad.
	size_t min_cap = 2 * ((RECORD_HEADER_BYTES + (size_t)max_sql + 7) & ~(size_t)7);
	if (cap < min_cap) cap = min_cap;
	cap &= ~(size_t)7;
	unsigned char *ring = (unsigned char *)calloc(1, cap);
	if (!ring) {
		pthread_mutex_unlock(&g_install_lock);
		return SQLITE_NOMEM;
	}
	pthread_mutex_lock(&g_lock);
	g_ring = ring;
	g_cap = cap;
	g_head = g_tail = g_used = 0;
	g_max_sql = max_sql;
	pthread_mutex_unlock(&g_lock);

	int rc = auto_extension((void (*)(void))sqlitedbg_extension_init);
	if (rc == SQLITE_OK) {
		g_installed = 1;
	} else {
		pthread_mutex_lock(&g_lock);
		g_ring = NULL;
		g_cap = 0;
		pthread_mutex_unlock(&g_lock);
		free(ring);
	}
	pthread_mutex_unlock(&g_install_lock);
	return rc;
}

size_t sqlitedbg_each_connection(void (*fn)(void *context, const sqlitedbg_connection *connection),
                                 void *context) {
	size_t n = 0;
	pthread_mutex_lock(&g_lock);
	for (conn_state *cs = g_first; cs; cs = cs->next) {
		sqlitedbg_connection c;
		c.id = cs->id;
		c.path = cs->path;
		c.read_only = cs->read_only;
		c.closing = __atomic_load_n(&cs->closing, __ATOMIC_RELAXED);
		c.opened_at_ns = cs->opened_at_ns;
		c.open_thread_id = cs->open_thread_id;
		fn(context, &c);
		n++;
	}
	pthread_mutex_unlock(&g_lock);
	return n;
}

void sqlitedbg_read_events(uint64_t after, void *out, size_t out_bytes, sqlitedbg_read_result *result) {
	result->dropped = 0;
	result->bytes = 0;
	result->count = 0;
	result->reset = 0;
	pthread_mutex_lock(&g_lock);
	// seq restarts at 1 in every process, so a cursor at or past the next seq to be assigned comes from an
	// earlier process (or is made up): read from the start, and say so.
	if (after >= g_next_seq) {
		result->reset = 1;
		after = 0;
	}
	result->next = after;
	if (g_ring) {
		uint64_t oldest = oldest_seq();
		if (oldest > after && oldest - after > 1) {
			result->dropped = oldest - after - 1;
			// Past the dropped events, so they are reported once even if nothing is copied below.
			result->next = oldest - 1;
		}
		size_t offset = g_tail, left = g_used;
		while (left > 0) {
			const sqlitedbg_event_record *r = record_at(offset);
			uint32_t bytes = r->record_bytes;
			if (r->kind != KIND_PAD && r->seq > after) {
				if (result->bytes + bytes > out_bytes) break;
				memcpy((unsigned char *)out + result->bytes, r, bytes);
				result->bytes += bytes;
				result->count++;
				result->next = r->seq;
			}
			left -= bytes;
			offset += bytes;
			if (offset >= g_cap) offset = 0;
		}
	}
	pthread_mutex_unlock(&g_lock);
}

void sqlitedbg_set_client_connected(int connected) {
	__atomic_store_n(&g_client_connected, connected ? 1 : 0, __ATOMIC_RELAXED);
}

void sqlitedbg_set_row_capture(uint32_t max_rows_per_statement, uint32_t max_value_bytes, uint32_t max_row_bytes) {
	__atomic_store_n(&g_max_value, max_value_bytes ? max_value_bytes : DEFAULT_MAX_VALUE_BYTES, __ATOMIC_RELAXED);
	uint32_t row = max_row_bytes ? max_row_bytes : DEFAULT_MAX_ROW_BYTES;
	__atomic_store_n(&g_max_row, row < SQLITEDBG_MAX_ROW_BYTES ? row : SQLITEDBG_MAX_ROW_BYTES, __ATOMIC_RELAXED);
	__atomic_store_n(&g_max_rows, max_rows_per_statement, __ATOMIC_RELAXED);
}

static int server_authorizer(void *unused, int action, const char *a1, const char *a2, const char *db,
                             const char *trigger) {
	(void)unused;
	(void)a2;
	(void)db;
	(void)trigger;
	switch (action) {
		case SQLITE_INSERT:
		case SQLITE_UPDATE:
		case SQLITE_DELETE:
		case SQLITE_CREATE_INDEX:
		case SQLITE_CREATE_TABLE:
		case SQLITE_CREATE_TEMP_INDEX:
		case SQLITE_CREATE_TEMP_TABLE:
		case SQLITE_CREATE_TEMP_TRIGGER:
		case SQLITE_CREATE_TEMP_VIEW:
		case SQLITE_CREATE_TRIGGER:
		case SQLITE_CREATE_VIEW:
		case SQLITE_CREATE_VTABLE:
		case SQLITE_DROP_INDEX:
		case SQLITE_DROP_TABLE:
		case SQLITE_DROP_TEMP_INDEX:
		case SQLITE_DROP_TEMP_TABLE:
		case SQLITE_DROP_TEMP_TRIGGER:
		case SQLITE_DROP_TEMP_VIEW:
		case SQLITE_DROP_TRIGGER:
		case SQLITE_DROP_VIEW:
		case SQLITE_DROP_VTABLE:
		case SQLITE_ALTER_TABLE:
		case SQLITE_REINDEX:
		case SQLITE_ANALYZE:
		case SQLITE_ATTACH:
		case SQLITE_DETACH:
		// A client could open a transaction or savepoint and hold a lock on the app's database.
		case SQLITE_SAVEPOINT:
			return SQLITE_DENY;
		case SQLITE_TRANSACTION:
			// Only the ROLLBACK of sqlitedbg_end_transaction, which ends one, gets past.
			return tls_ending_transaction && a1 && strcmp(a1, "ROLLBACK") == 0 ? SQLITE_OK : SQLITE_DENY;
		case SQLITE_PRAGMA:
			// PRAGMA query_only=0 would lift the guard, and a PRAGMA that stores a value writes.
			// PRAGMA table_info(t) also has a second argument, so only these names are checked.
			// locking_mode=EXCLUSIVE keeps the lock after a read, and busy_timeout (any form) lets a
			// statement wait on the app's lock for as long as the client likes. The heap limits and
			// temp_store_directory are process-wide: they would change the app's own connections.
			if (a1 && (sqlite3_stricmp(a1, "query_only") == 0 || sqlite3_stricmp(a1, "writable_schema") == 0 ||
			           sqlite3_stricmp(a1, "locking_mode") == 0 || sqlite3_stricmp(a1, "busy_timeout") == 0 ||
			           sqlite3_stricmp(a1, "hard_heap_limit") == 0 || sqlite3_stricmp(a1, "soft_heap_limit") == 0 ||
			           sqlite3_stricmp(a1, "temp_store_directory") == 0 || sqlite3_stricmp(a1, "wal_checkpoint") == 0 ||
			           sqlite3_stricmp(a1, "incremental_vacuum") == 0 || sqlite3_stricmp(a1, "optimize") == 0))
				return SQLITE_DENY;
			if (a1 && a2 &&
			    (sqlite3_stricmp(a1, "journal_mode") == 0 || sqlite3_stricmp(a1, "user_version") == 0 ||
			     sqlite3_stricmp(a1, "application_id") == 0 || sqlite3_stricmp(a1, "schema_version") == 0))
				return SQLITE_DENY;
			return SQLITE_OK;
		default:
			return SQLITE_OK;
	}
}

#ifdef SQLITEDBG_TEST
// Host tests: the authorizer, to put back after opening a transaction with it cleared, as a way past
// it would leave a server connection.
int (*const sqlitedbg_test_server_authorizer)(void *, int, const char *, const char *, const char *,
                                              const char *) = server_authorizer;
#endif

int sqlitedbg_end_transaction(sqlite3 *db) {
	if (sqlite3_get_autocommit(db)) return SQLITE_OK;
	tls_ending_transaction = 1;
	int rc = sqlite3_exec(db, "ROLLBACK", NULL, NULL, NULL);
	tls_ending_transaction = 0;
	return rc;
}

int sqlitedbg_open_server(const char *path, int writable, sqlite3 **db) {
	*db = NULL;
	pthread_mutex_lock(&g_lock);
	int ready = g_api_ready;
	pthread_mutex_unlock(&g_lock);
	if (!ready) return SQLITE_MISUSE;

	tls_opening_server = 1;
	int rc = sqlite3_open_v2(path, db, writable ? SQLITE_OPEN_READWRITE : SQLITE_OPEN_READONLY, NULL);
	tls_opening_server = 0;
	if (rc == SQLITE_OK && !writable) {
		rc = sqlite3_exec(*db, "PRAGMA query_only=1", NULL, NULL, NULL);
		if (rc == SQLITE_OK) rc = sqlite3_set_authorizer(*db, server_authorizer, NULL);
	}
	// In rollback-journal mode a reader fails at once while the app holds a write lock. Wait a little,
	// well under the tools' 50 s deadline; the client cannot change it (PRAGMA busy_timeout is refused).
	if (rc == SQLITE_OK) rc = sqlite3_busy_timeout(*db, SQLITEDBG_SERVER_BUSY_MS);
	if (rc != SQLITE_OK && *db) {
		sqlite3_close_v2(*db);
		*db = NULL;
	}
	return rc;
}
