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
#define RECORD_HEADER_BYTES 56u

_Static_assert(sizeof(sqlitedbg_event_record) == RECORD_HEADER_BYTES, "event record layout");

// Padding record written where the next record would not fit before the end of the ring, so a
// record is always contiguous. kind 0 is never a real event.
#define KIND_PAD 0u

typedef struct conn_state {
	struct conn_state *prev, *next;
	uint64_t id;
	char *path;
	int read_only;
	int closing;
	int64_t opened_at_ns;
	int64_t open_thread_id;
} conn_state;

// Taken only for the fields below; never while SQLite runs.
static pthread_mutex_t g_lock = PTHREAD_MUTEX_INITIALIZER;
// Serialises sqlitedbg_install so a second caller does not return before the first registered.
static pthread_mutex_t g_install_lock = PTHREAD_MUTEX_INITIALIZER;

static int g_installed;         // g_install_lock
static int g_api_ready;         // g_lock: sqlite3_api has been saved
static uint32_t g_max_sql;      // set before g_installed, then read-only
static int g_client_connected;  // atomic

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

static void add_event(uint32_t kind, uint64_t connection_id, int64_t duration_ns, uint32_t flags,
                      const char *sql, size_t sql_len) {
	int64_t time = now_ns();
	int64_t tid = this_thread_id();
	size_t kept = utf8_cut(sql, sql_len, g_max_sql);
	if (kept < sql_len) flags |= SQLITEDBG_EVENT_TRUNCATED;
	size_t need = (RECORD_HEADER_BYTES + kept + 7) & ~(size_t)7;

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
		r->sql_bytes = (uint32_t)kept;
		memcpy((unsigned char *)r + RECORD_HEADER_BYTES, sql, kept);
		memset((unsigned char *)r + RECORD_HEADER_BYTES + kept, 0, need - RECORD_HEADER_BYTES - kept);
		g_head += need;
		g_used += need;
		if (g_head >= g_cap) g_head = 0;
	}
	pthread_mutex_unlock(&g_lock);
}

// sqlite3_trace_v2 callback. C is the connection's conn_state, which lives until SQLite frees the
// connection, so it outlives every callback.
static int trace_cb(unsigned type, void *c, void *p, void *x) {
	conn_state *cs = (conn_state *)c;
	switch (type) {
		case SQLITE_TRACE_STMT:
		case SQLITE_TRACE_PROFILE: {
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
			add_event(is_stmt ? SQLITEDBG_EVENT_STATEMENT : SQLITEDBG_EVENT_PROFILE, cs->id, duration, flags, sql,
			          strlen(sql));
			if (expanded) sqlite3_free(expanded);
			break;
		}
		case SQLITE_TRACE_CLOSE:
			// The close can still fail or leave a zombie, so only mark it: xDestroy frees.
			pthread_mutex_lock(&g_lock);
			cs->closing = 1;
			pthread_mutex_unlock(&g_lock);
			add_event(SQLITEDBG_EVENT_CLOSE, cs->id, -1, 0, "", 0);
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
	return sqlite3_trace_v2(db, SQLITE_TRACE_STMT | SQLITE_TRACE_PROFILE | SQLITE_TRACE_CLOSE, trace_cb, cs);
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
		c.closing = cs->closing;
		c.opened_at_ns = cs->opened_at_ns;
		c.open_thread_id = cs->open_thread_id;
		fn(context, &c);
		n++;
	}
	pthread_mutex_unlock(&g_lock);
	return n;
}

void sqlitedbg_read_events(uint64_t after, void *out, size_t out_bytes, sqlitedbg_read_result *result) {
	result->next = after;
	result->dropped = 0;
	result->bytes = 0;
	result->count = 0;
	pthread_mutex_lock(&g_lock);
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
			return SQLITE_DENY;
		case SQLITE_PRAGMA:
			// PRAGMA query_only=0 would lift the guard, and a PRAGMA that stores a value writes.
			// PRAGMA table_info(t) also has a second argument, so only these names are checked.
			if (a1 && (sqlite3_stricmp(a1, "query_only") == 0 || sqlite3_stricmp(a1, "writable_schema") == 0 ||
			           sqlite3_stricmp(a1, "wal_checkpoint") == 0 ||
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
	if (rc != SQLITE_OK && *db) {
		sqlite3_close_v2(*db);
		*db = NULL;
	}
	return rc;
}
