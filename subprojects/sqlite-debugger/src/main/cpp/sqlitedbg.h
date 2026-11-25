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
#define SQLITEDBG_INTERFACE_VERSION 3

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
    // 1 once the trace "close" event has fired. The close may still fail (SQLITE_BUSY with an
    // unfinalized statement), and the app may retry it, so the connection stays listed until SQLite
    // frees it. A later statement event on the connection resets this to 0, as it shows the close
    // failed. A zombie left by sqlite3_close_v2 also reads 0 once a statement still pending on it
    // runs (checked in SQLite 3.49.1), until its last statement is finalized and SQLite frees it.
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
    // A statement returned a row (SQLITE_TRACE_ROW). Recorded only while a client is connected and
    // row recording is on (sqlitedbg_set_row_capture); the data is the row's values, in the row
    // layout below, not SQL. rows is the row's number in its statement, from 1.
    SQLITEDBG_EVENT_ROW = 4,
};

enum {
    // The data was cut: SQL at max_sql_bytes, or a row's values at the row limits.
    SQLITEDBG_EVENT_TRUNCATED = 1u << 0,
    // sql has its bound values written in (sqlite3_expanded_sql). Only while a client is
    // connected (sqlitedbg_set_client_connected): expanding a 1 MB value costs 2 MB on every run.
    SQLITEDBG_EVENT_EXPANDED = 1u << 1,
    // SQLITEDBG_EVENT_PROFILE only: rows were left unrecorded because the statement returned more than
    // the per-statement limit while row recording was on.
    SQLITEDBG_EVENT_ROWS_CUT = 1u << 2,
};

// One event as sqlitedbg_read_events copies it out: this header, then data_bytes of data, then zero
// padding to a multiple of 8 bytes. The data is UTF-8 SQL (no terminating NUL) for every kind but
// SQLITEDBG_EVENT_ROW, whose data is its values (below). Fields are in the CPU's byte order
// (little-endian on every ABI we build). The JNI layer hands the bytes to Kotlin unchanged, so
// EventDecoder.kt mirrors this layout; change both together.
typedef struct sqlitedbg_event_record {
    uint32_t record_bytes;      // the whole record, padding included
    uint32_t kind;              // SQLITEDBG_EVENT_STATEMENT, _PROFILE, _CLOSE or _ROW
    uint64_t seq;               // 1, 2, 3...: every event made, dropped or not, gets the next one
    uint64_t connection_id;     // sqlitedbg_connection.id
    int64_t thread_id;          // gettid() of the thread running the statement
    int64_t time_ns;            // CLOCK_REALTIME when the event happened
    int64_t duration_ns;        // SQLITEDBG_EVENT_PROFILE only; -1 for the others
    uint32_t flags;             // SQLITEDBG_EVENT_TRUNCATED, _EXPANDED, _ROWS_CUT
    uint32_t data_bytes;
    // SQLITEDBG_EVENT_PROFILE: the rows the statement returned, whether or not they were recorded.
    // SQLITEDBG_EVENT_ROW: this row's number, from 1. -1 otherwise, and -1 for a statement the
    // extension did not see start (SQLite's own nested statements, or more than
    // SQLITEDBG_STATEMENT_SLOTS statements stepped in turn on one connection).
    int64_t rows;
    // SQLITEDBG_EVENT_PROFILE of a statement that may write (sqlite3_stmt_readonly false): the rows
    // it changed itself, as sqlite3_changes64 counts them (not those changed by triggers or foreign
    // key actions); 0 when it changed none, as a CREATE TABLE. -1 for a read-only statement, for the
    // other kinds, and when rows is -1.
    int64_t changes;
} sqlitedbg_event_record;       // 72 bytes; the data follows

// How many statements per connection the extension follows at once, to number their rows and count
// their changes. A connection that steps more statements in turn than this loses the oldest:
// its rows are not recorded and its PROFILE event has rows and changes -1.
#define SQLITEDBG_STATEMENT_SLOTS 16

// The data of an SQLITEDBG_EVENT_ROW: this header, then `kept` values, each a sqlitedbg_row_value
// followed by stored_bytes of value. Nothing is aligned: read each field with memcpy.
typedef struct sqlitedbg_row_header {
    uint32_t columns;           // sqlite3_column_count of the statement
    uint32_t kept;              // values that follow; fewer than columns when the row limit was reached
} sqlitedbg_row_header;         // 8 bytes

typedef struct sqlitedbg_row_value {
    uint8_t type;               // SQLITE_INTEGER, SQLITE_FLOAT, SQLITE_TEXT, SQLITE_BLOB or SQLITE_NULL
    uint8_t cut;                // 1 if fewer than full_bytes were stored
    uint16_t reserved;          // 0
    uint32_t stored_bytes;      // bytes that follow: 8 for INTEGER (int64) and FLOAT (IEEE double),
                                // 0 for NULL, UTF-8 for TEXT (cut at a character boundary), BLOB bytes
    uint32_t full_bytes;        // the value's whole size (sqlite3_column_bytes for TEXT and BLOB)
} sqlitedbg_row_value;          // 12 bytes

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
    // 1 if `after` was at or past the next seq to be assigned, which no cursor of this process can be: it
    // comes from an earlier process (seq restarts at 1 in each) or was made up. The read then ran as if
    // `after` were 0, and next and dropped refer to that read. 0 otherwise, including `after` equal to
    // the newest seq (a reader that is caught up).
    int reset;
} sqlitedbg_read_result;

// Copies out the events with seq > after, oldest first, as whole records, until out is full. An
// `after` that is not a seq this process has assigned (see sqlitedbg_read_result.reset) reads from 0.
// The copy is made under the extension's lock with no SQLite call, so it never waits on a
// statement. A record larger than out_bytes is never split: an out smaller than one record of
// max_sql_bytes may return count 0 with events still waiting. Never fails.
void sqlitedbg_read_events(uint64_t after, void *out, size_t out_bytes, sqlitedbg_read_result *result);

// Whether a client is connected. While it is, statements are recorded with their bound values
// written in, and rows if row recording is on. Off at install.
void sqlitedbg_set_client_connected(int connected);

// Turns row recording on (max_rows_per_statement > 0) or off (0, as at install). While it is on and
// a client is connected, the first max_rows_per_statement rows of each statement become
// SQLITEDBG_EVENT_ROW events. TEXT and BLOB values longer than max_value_bytes are cut (0: 256), and
// a row's data stops at max_row_bytes (0: 4 KiB; never more than half the buffer less one header).
// Each value is read with the accessor of its own type (sqlite3_column_type first), so the app sees
// the same values and types afterwards: sqlite3_column_text converts a value in place, and on a BLOB
// it makes the app's own sqlite3_column_type return SQLITE_TEXT (SQLite 3.49.1, sqlitedbg_test.c). Rows of statements the extension did
// not see start are never recorded: that drops the rows of SQLite's own nested statements, such as
// the schema read a CREATE TABLE runs. PROFILE events count rows whether or not this is on.
void sqlitedbg_set_row_capture(uint32_t max_rows_per_statement, uint32_t max_value_bytes,
                               uint32_t max_row_bytes);

// Server connections

#define SQLITEDBG_SERVER_BUSY_MS 3000

// The longest a server statement may run on a database that is not in WAL mode (rollback journal,
// also "memory" and "off"). Such a statement holds a SHARED lock from its first step to its end, and
// the app's COMMIT waits for it in the app's busy handler: requery's busy timeout is 2500 ms
// (BUSY_TIMEOUT_MS in android_database_SQLiteConnection.cpp, requery/sqlite-android 3.49.0, copied from
// AOSP). The cap sits below that, with margin for the busy handler's back-off and the app's own work, so
// a long read never makes the app's commit fail with "database is locked" (ADFA-7136 acceptance
// criterion 5). A WAL database needs no cap for a read: readers and the writer do not block each other.
// A write is capped in every mode: it holds the write lock, which the app's own writes wait for in the
// same busy handler (ADFA-7298).
#define SQLITEDBG_SERVER_NON_WAL_MS 1500

// Opens path for the server with the same SQLite the app uses, SQLITE_OPEN_READONLY unless writable
// (never SQLITE_OPEN_CREATE), with an authorizer. On every server connection it refuses BEGIN, COMMIT,
// ROLLBACK, SAVEPOINT and RELEASE, ATTACH and DETACH, and the PRAGMAs that would reach the app rather
// than the data: locking_mode and busy_timeout (a client could hold a lock across statements, or wait
// on the app's for as long as it likes), the process-wide hard_heap_limit, soft_heap_limit and
// temp_store_directory, query_only, writable_schema, and setting journal_mode or schema_version.
// Read-only also sets PRAGMA query_only and refuses every write. Within one statement a lock is held
// while it runs, so the JNI layer stops a read on a database that is not in WAL mode, and every
// write, after SQLITEDBG_SERVER_NON_WAL_MS. Read-only mode guards against accidents; it is not a
// security boundary (ADFA-7131, D11), and a writable connection can change any data the app's can.
// Never traced and never listed. The caller owns *db and closes it with sqlite3_close_v2.
// A server connection, unlike an app connection, may use a progress handler (for time limits).
// The connection waits up to SQLITEDBG_SERVER_BUSY_MS for a lock the app holds and then reports
// SQLITE_BUSY.
// Returns SQLITE_MISUSE before any app connection has opened (the extension then has no SQLite
// API to call), or what sqlite3_open_v2 returns. A failure after the open (PRAGMA query_only, the
// authorizer or the busy timeout) closes the connection, sets *db to NULL and returns that code, as
// does a failed open: *db is non-NULL only with SQLITE_OK.
int sqlitedbg_open_server(const char *path, int writable, sqlite3 **db);

// Ends a transaction left open on a server connection with ROLLBACK, which the authorizer lets past
// for this call only: a client's BEGIN, COMMIT and ROLLBACK stay refused. The JNI layer calls it after
// every query and execute as a backstop; nothing a client can send opens one. Returns SQLITE_OK when none was open or it was rolled back, else what ROLLBACK
// returned, and the transaction is still open.
int sqlitedbg_end_transaction(sqlite3 *db);

#ifdef __cplusplus
}
#endif

#endif
