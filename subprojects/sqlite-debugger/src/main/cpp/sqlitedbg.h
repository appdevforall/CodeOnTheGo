// The SQLite debugger's extension (ADFA-7135): the interface its JNI layer and its host tests
// build against. Debug builds of apps built with Code on the Go only (ADFA-7131, D1).
//
// The extension runs on every connection the app's SQLite (requery's libsqlite3x.so) opens after
// sqlitedbg_install(). It keeps a list of the app's open connections and a buffer of statement
// events, and opens connections for the server (ADFA-7136). It never sets the progress handler or
// the update hook of an app connection: requery sets and clears the progress handler around
// cancellable statements, and the update hook belongs to the app.
//
// Threads: every function here may be called from any thread. requery builds SQLite with
// SQLITE_THREADSAFE=2, so a connection is used by one thread at a time; the extension's own state
// is guarded by its own lock, which is never held while SQLite runs a statement.

#ifndef SQLITEDBG_H
#define SQLITEDBG_H

#include <stddef.h>
#include <stdint.h>
#include "sqlite3.h"

#ifdef __cplusplus
extern "C" {
#endif

// The interface version. Raise it when anything below changes shape.
#define SQLITEDBG_INTERFACE_VERSION 1

// sqlite3_auto_extension's type. On a device the JNI layer looks it up in libsqlite3x.so with
// dlsym (requery exports every sqlite3_ function); host tests pass SQLite's own.
typedef int (*sqlitedbg_auto_extension_fn)(void (*entry_point)(void));

typedef struct sqlitedbg_config {
    // Size of the event buffer. Events are dropped, oldest first, when it is full. 0: 4 MiB.
    size_t buffer_bytes;
    // Longest SQL text kept in one event; longer text is cut at a UTF-8 character boundary and
    // the event gets SQLITEDBG_EVENT_TRUNCATED. 0: 4 KiB.
    uint32_t max_sql_bytes;
} sqlitedbg_config;

// Registers the extension for every connection opened from now on, and sets up the buffer.
// Connections already open are not seen (a known limit, ADFA-7131). A second call does nothing and
// returns SQLITE_OK; config is read on the first call only. config may be NULL for the defaults.
// Returns SQLITE_OK, SQLITE_NOMEM, or what auto_extension returns.
int sqlitedbg_install(sqlitedbg_auto_extension_fn auto_extension, const sqlitedbg_config *config);

// The SQLite entry point the extension registers. Exposed for host tests only: the app never
// calls it, and it is never loaded with load_extension().
int sqlitedbg_extension_init(sqlite3 *db, char **err, const sqlite3_api_routines *api);

// Connections

typedef struct sqlitedbg_connection {
    // Unique within the process, starting at 1, never reused.
    uint64_t id;
    // sqlite3_db_filename(db, "main") when the connection opened: "" for an in-memory or temporary
    // database. Valid only during the sqlitedbg_each_connection callback.
    const char *path;
    // 1 if the main database is read-only (sqlite3_db_readonly).
    int read_only;
    // 1 once the trace "close" event has fired. The close may still fail, and the app may retry
    // it, so the connection stays listed until SQLite frees it.
    int closing;
    // CLOCK_REALTIME, nanoseconds, when the extension first saw the connection.
    int64_t opened_at_ns;
    // gettid() of the thread that opened it.
    int64_t open_thread_id;
} sqlitedbg_connection;

// Calls fn once for each app connection SQLite has not yet freed, oldest first. Server connections
// (sqlitedbg_open_server) are not listed. fn runs with the extension's lock held: it must be quick
// and must not call into SQLite or this interface. Returns how many connections it reported.
size_t sqlitedbg_each_connection(void (*fn)(void *context, const sqlitedbg_connection *connection),
                                 void *context);

// Events

enum {
    // A statement starts running (SQLITE_TRACE_STMT); also each trigger it fires, whose sql starts
    // with "--".
    SQLITEDBG_EVENT_STATEMENT = 1,
    // A statement finished (SQLITE_TRACE_PROFILE); duration_ns is set.
    SQLITEDBG_EVENT_PROFILE = 2,
    // The app asked SQLite to close the connection (SQLITE_TRACE_CLOSE); sql is empty.
    SQLITEDBG_EVENT_CLOSE = 3,
};

enum {
    // sql was cut at max_sql_bytes.
    SQLITEDBG_EVENT_TRUNCATED = 1u << 0,
    // sql has its bound values written in (sqlite3_expanded_sql). Only while a client is
    // connected (sqlitedbg_set_client_connected): expanding a 1 MB value costs 2 MB on every run.
    SQLITEDBG_EVENT_EXPANDED = 1u << 1,
};

// One event as sqlitedbg_read_events copies it out: this header, then sql_bytes of UTF-8 (no
// terminating NUL), then zero padding to a multiple of 8 bytes. Fields are in the CPU's byte order
// (little-endian on every ABI we build). The JNI layer hands the bytes to Kotlin unchanged, so
// EventDecoder.kt mirrors this layout; change both together.
typedef struct sqlitedbg_event_record {
    uint32_t record_bytes;      // the whole record, padding included
    uint32_t kind;              // SQLITEDBG_EVENT_STATEMENT, _PROFILE or _CLOSE
    uint64_t seq;               // 1, 2, 3...: every event made, dropped or not, gets the next one
    uint64_t connection_id;     // sqlitedbg_connection.id
    int64_t thread_id;          // gettid() of the thread running the statement
    int64_t time_ns;            // CLOCK_REALTIME when the event happened
    int64_t duration_ns;        // SQLITEDBG_EVENT_PROFILE only; -1 for the others
    uint32_t flags;             // SQLITEDBG_EVENT_TRUNCATED, SQLITEDBG_EVENT_EXPANDED
    uint32_t sql_bytes;
} sqlitedbg_event_record;       // 56 bytes; the SQL text follows

typedef struct sqlitedbg_read_result {
    // The seq of the last event copied out, or if none was, `after` moved past any dropped events
    // (so they are reported once): pass it as `after` next time. A record too big for out is not
    // passed.
    uint64_t next;
    // Events after `after` that left the buffer before they could be read.
    uint64_t dropped;
    // Bytes of records written to out.
    size_t bytes;
    // Records written to out.
    size_t count;
} sqlitedbg_read_result;

// Copies out the events with seq > after, oldest first, as whole records, until out is full.
// The copy is made under the extension's lock with no SQLite call, so it never waits on a
// statement. A record larger than out_bytes is never split: an out smaller than one record of
// max_sql_bytes may return count 0 with events still waiting. Never fails.
void sqlitedbg_read_events(uint64_t after, void *out, size_t out_bytes, sqlitedbg_read_result *result);

// Whether a client is connected. While it is, statements are recorded with their bound values
// written in. Off at install.
void sqlitedbg_set_client_connected(int connected);

// Server connections

// Opens path for the server with the same SQLite the app uses: SQLITE_OPEN_READONLY unless
// writable, PRAGMA query_only when read-only, and an authorizer that refuses ordinary writes when
// read-only. Read-only mode guards against accidents; it is not a security boundary (ADFA-7131,
// D11). Never traced and never listed. The caller owns *db and closes it with sqlite3_close_v2.
// A server connection, unlike an app connection, may use a progress handler (for time limits).
// Returns SQLITE_MISUSE before any app connection has opened (the extension then has no SQLite
// API to call), or what sqlite3_open_v2 returns.
int sqlitedbg_open_server(const char *path, int writable, sqlite3 **db);

#ifdef __cplusplus
}
#endif

#endif
