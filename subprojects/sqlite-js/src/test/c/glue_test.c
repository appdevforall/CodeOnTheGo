// Host test of sqlitejs_android.c's entry points (ADFA-7082), built and run by ../../../test.sh
// with sqlite-js and SQLite compiled in (SQLITE_CORE). Prints TAP; exits non-zero on a failure.

#include <pthread.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include "sqlite3.h"

// test.sh builds this a second time with GLUE_AS_SQLITE_3_40, and sqlitejs.c told to ask this
// function for SQLite's version: sqlite-js then works as on SQLite before 3.41, which has no
// sqlite3_is_interrupted(), and only its own records show that a connection was interrupted.
#ifdef GLUE_AS_SQLITE_3_40
int glue_sqlite3_libversion_number (void);
int glue_sqlite3_libversion_number (void) { return 3040000; }
#endif

int sqlite3_sqlitejs_init (sqlite3 *db, char **pzErrMsg, const sqlite3_api_routines *pApi);
int sqlite3_sqlitejs_trusted_init (sqlite3 *db, char **pzErrMsg, const sqlite3_api_routines *pApi);
void sqlitejs_cancel_all (void);

typedef int (*entry_point) (sqlite3 *, char **, const sqlite3_api_routines *);

static int tests = 0, failures = 0;
static char logged[4096];

static void on_log (void *arg, int code, const char *msg) {
    (void)arg; (void)code;
    size_t used = strlen(logged);
    snprintf(logged + used, sizeof(logged) - used, "%s\n", msg);
}

static void check (int ok, const char *what) {
    tests++;
    if (!ok) failures++;
    printf("%s %d - %s\n", ok ? "ok" : "not ok", tests, what);
}

static sqlite3 *open_with (const char *path, entry_point init) {
    sqlite3 *db = NULL;
    char *err = NULL;
    if (sqlite3_open(path, &db) != SQLITE_OK || init(db, &err, NULL) != SQLITE_OK) {
        fprintf(stderr, "cannot open %s: %s\n", path, err ? err : sqlite3_errmsg(db));
        exit(2);
    }
    return db;
}

// cancel_once(): cancels all running JavaScript the first time it is called, as cancelAll() for
// a query on another connection would; counts its calls
static int cancel_calls = 0;

static void cancel_once (sqlite3_context *context, int argc, sqlite3_value **argv) {
    (void)argc; (void)argv;
    if (cancel_calls++ == 0) sqlitejs_cancel_all();
    sqlite3_result_null(context);
}

static sqlite3 *open_trusted_with_cancel_once (const char *path) {
    sqlite3 *db = NULL;
    char *err = NULL;
    if (sqlite3_open(path, &db) != SQLITE_OK
        || sqlite3_create_function(db, "cancel_once", 0, SQLITE_UTF8, NULL, cancel_once, NULL, NULL) != SQLITE_OK
        || sqlite3_sqlitejs_trusted_init(db, &err, NULL) != SQLITE_OK) {
        fprintf(stderr, "cannot open %s: %s\n", path, err ? err : sqlite3_errmsg(db));
        exit(2);
    }
    return db;
}

// started(): marks that the JavaScript under test is running; cancel_when_started() runs on another
// thread and cancels then, so that the cancel lands while that JavaScript runs
static int js_started = 0;

static void started (sqlite3_context *context, int argc, sqlite3_value **argv) {
    (void)argc; (void)argv;
    __atomic_store_n(&js_started, 1, __ATOMIC_RELEASE);
    sqlite3_result_null(context);
}

static void *cancel_when_started (void *arg) {
    (void)arg;
    for (int i = 0; (i < 2000) && !__atomic_load_n(&js_started, __ATOMIC_ACQUIRE); i++) usleep(10 * 1000);
    usleep(20 * 1000);
    sqlitejs_cancel_all();
    return NULL;
}

// a statement whose JavaScript a cancel must stop: one that never ends would hang the test
static void on_alarm (int sig) {
    (void)sig;
    static const char msg[] = "not ok - a cancelled statement did not end in 30 s\n";
    if (write(STDOUT_FILENO, msg, sizeof(msg) - 1) < 0) _exit(3);
    _exit(1);
}

// Runs sql, cancelling all JavaScript once it has called started(); returns its result code
static int exec_cancelled (sqlite3 *db, const char *sql) {
    pthread_t thread;
    js_started = 0;
    if (pthread_create(&thread, NULL, cancel_when_started, NULL) != 0) return SQLITE_ERROR;
    alarm(30);
    int rc = sqlite3_exec(db, sql, NULL, NULL, NULL);
    alarm(0);
    pthread_join(thread, NULL);
    return rc;
}

// stops every statement once it has run 1000 instructions, as Android's cancelling does
static int stop_statement (void *arg) {
    (void)arg;
    return 1;
}

static int exec (sqlite3 *db, const char *sql) {
    return sqlite3_exec(db, sql, NULL, NULL, NULL);
}

// the first column of the first row as text, into out; returns the statement's result code
static int query_text (sqlite3 *db, const char *sql, char *out, size_t size) {
    sqlite3_stmt *vm = NULL;
    out[0] = 0;
    int rc = sqlite3_prepare_v2(db, sql, -1, &vm, NULL);
    if (rc == SQLITE_OK) {
        rc = sqlite3_step(vm);
        if (rc == SQLITE_ROW) {
            snprintf(out, size, "%s", sqlite3_column_text(vm, 0) ? (const char *)sqlite3_column_text(vm, 0) : "NULL");
            rc = SQLITE_OK;
        } else if (rc == SQLITE_DONE) {
            rc = SQLITE_OK;
        }
    }
    if (rc != SQLITE_OK) snprintf(out, size, "%s", sqlite3_errmsg(db));
    sqlite3_finalize(vm);
    return rc;
}

static int answers (sqlite3 *db, const char *sql, const char *want) {
    char got[256];
    query_text(db, sql, got, sizeof(got));
    if (strcmp(got, want) != 0) fprintf(stderr, "# %s: want %s, got %s\n", sql, want, got);
    return strcmp(got, want) == 0;
}

int main (void) {
    char dir[] = "/tmp/sqlitejs-glue-XXXXXX";
    if (!mkdtemp(dir)) { perror("mkdtemp"); return 2; }
    char path[512];
    snprintf(path, sizeof(path), "%s/test.db", dir);
    sqlite3_config(SQLITE_CONFIG_LOG, on_log, NULL);

    // A database with stored functions: a and c from js_create_scalar, b with code that does not
    // parse, and d whose stored code writes to a table when it is loaded.
    sqlite3 *db = open_with(path, sqlite3_sqlitejs_init);
    check(exec(db, "CREATE TABLE ran(x)") == SQLITE_OK
          && exec(db, "SELECT js_init_table()") == SQLITE_OK
          && exec(db, "SELECT js_create_scalar('a', '(function (args) { return 1; })')") == SQLITE_OK
          && exec(db, "INSERT INTO js_functions (name, kind, step_code) VALUES ('b', 'scalar', 'this is not ( JavaScript')") == SQLITE_OK
          && exec(db, "SELECT js_create_scalar('c', '(function (args) { return 3; })')") == SQLITE_OK
          && exec(db, "INSERT INTO js_functions (name, kind, step_code) VALUES ('d', 'scalar', "
                      "'(db.exec(\"INSERT INTO ran VALUES (1)\").next(), function (args) { return 4; })')") == SQLITE_OK,
          "a database with stored functions a, b (broken), c and d (writes when loaded)");
    sqlite3_close(db);

    // ENTRY_POINT loads none of it, and none of the stored code runs
    db = open_with(path, sqlite3_sqlitejs_init);
    check(answers(db, "SELECT a()", "no such function: a"), "ENTRY_POINT does not load stored functions");
    check(answers(db, "SELECT count(*) FROM ran", "0"), "ENTRY_POINT runs no stored code");
    check(answers(db, "SELECT js_eval('6*7')", "42"), "ENTRY_POINT loads sqlite-js");
    sqlite3_close(db);

    // ENTRY_POINT_FOR_TRUSTED_DATABASE loads every function but the broken one, and logs it
    logged[0] = 0;
    db = open_with(path, sqlite3_sqlitejs_trusted_init);
    check(answers(db, "SELECT a()", "1"), "the trusted entry point loads a");
    check(answers(db, "SELECT c()", "3"), "it loads c, stored after the broken b");
    check(answers(db, "SELECT d()", "4"), "it loads d");
    check(answers(db, "SELECT b()", "no such function: b"), "it skips b");
    check(strstr(logged, "stored function b did not load") != NULL, "it logs that b did not load");
    check(strstr(logged, "stored function a") == NULL && strstr(logged, "stored function c") == NULL,
          "it logs nothing about the functions that loaded");
    check(answers(db, "SELECT count(*) FROM ran", "1"), "loading d ran its stored code once");
    check(answers(db, "SELECT count(*) FROM temp.sqlite_master WHERE name = 'js_functions'", "0"),
          "no TEMP js_functions table is left behind");
    check(exec(db, "SELECT js_create_scalar('e', '(function (args) { return 5; })')") == SQLITE_OK
          && answers(db, "SELECT count(*) FROM main.js_functions WHERE name = 'e'", "1"),
          "a function made afterwards is stored in the database");
    sqlite3_close(db);

    // A database without js_functions opens with the trusted entry point, and logs nothing
    char plain[512];
    snprintf(plain, sizeof(plain), "%s/plain.db", dir);
    logged[0] = 0;
    db = open_with(plain, sqlite3_sqlitejs_trusted_init);
    check(answers(db, "SELECT js_eval('1+1')", "2"), "the trusted entry point opens a database without js_functions");
    check(logged[0] == 0, "it logs nothing for a database without js_functions");
    sqlite3_close(db);

    // A js_functions that cannot be listed (a table without rowid) is logged, not silent
    char odd[512];
    snprintf(odd, sizeof(odd), "%s/odd.db", dir);
    db = open_with(odd, sqlite3_sqlitejs_init);
    exec(db, "CREATE TABLE js_functions (name TEXT PRIMARY KEY) WITHOUT ROWID");
    sqlite3_close(db);
    logged[0] = 0;
    db = open_with(odd, sqlite3_sqlitejs_trusted_init);
    check(strstr(logged, "cannot list the stored functions") != NULL, "it logs a js_functions it cannot list");
    sqlite3_close(db);
    unlink(odd);

    // The table is found whatever the case of its name, as SQLite finds a table (CodeRabbit)
    char upper[512];
    snprintf(upper, sizeof(upper), "%s/upper.db", dir);
    db = open_with(upper, sqlite3_sqlitejs_init);
    check(exec(db, "SELECT js_init_table()") == SQLITE_OK
          && exec(db, "SELECT js_create_scalar('u', '(function (args) { return 7; })')") == SQLITE_OK
          && exec(db, "ALTER TABLE js_functions RENAME TO renamed") == SQLITE_OK
          && exec(db, "ALTER TABLE renamed RENAME TO JS_FUNCTIONS") == SQLITE_OK
          && answers(db, "SELECT name FROM sqlite_master WHERE type = 'table' AND name LIKE 'js_functions'", "JS_FUNCTIONS"),
          "a database whose stored functions are in a table named JS_FUNCTIONS");
    sqlite3_close(db);
    db = open_with(upper, sqlite3_sqlitejs_trusted_init);
    check(answers(db, "SELECT u()", "7"), "the trusted entry point loads them");
    sqlite3_close(db);
    unlink(upper);

    // A cancel that lands while a stored function loads, from cancelAll() for a query on another
    // connection, stops its code as it stops any JavaScript then running. The function is loaded
    // again, rather than left out of this connection for good.
    char cancelled[512];
    snprintf(cancelled, sizeof(cancelled), "%s/cancelled.db", dir);
    db = open_with(cancelled, sqlite3_sqlitejs_init);
    check(exec(db, "SELECT js_init_table()") == SQLITE_OK
          && exec(db, "INSERT INTO js_functions (name, kind, step_code) VALUES ('w', 'scalar', "
                      "'(db.exec(\"SELECT cancel_once()\").next(), (function () { for (let i = 0; i < 1000000; i++); })(), "
                      "function (args) { return 8; })')") == SQLITE_OK,
          "a database with a function w whose code cancels the first time it loads");
    sqlite3_close(db);
    logged[0] = 0;
    db = open_trusted_with_cancel_once(cancelled);
    check(cancel_calls == 2, "the trusted entry point loads w again after the cancel stops it");
    check(answers(db, "SELECT w()", "8"), "w is loaded");
    check(strstr(logged, "stored function w did not load") == NULL, "it does not log that w did not load");
    sqlite3_close(db);
    unlink(cancelled);

    // The same with code that keeps a result set open (CodeRabbit). The cancel interrupts the
    // connection, and an open result set kept that interrupt in effect: every try to load the
    // function failed, and so did every later statement on the connection.
    char held[512];
    snprintf(held, sizeof(held), "%s/held.db", dir);
    db = open_with(held, sqlite3_sqlitejs_init);
    check(exec(db, "SELECT js_init_table()") == SQLITE_OK
          && exec(db, "INSERT INTO js_functions (name, kind, step_code) VALUES ('h', 'scalar', "
                      "'(globalThis.held = db.exec(\"SELECT 1 UNION ALL SELECT 2\"), held.next(), "
                      "db.exec(\"SELECT cancel_once()\").next(), (function () { for (let i = 0; i < 1000000; i++); })(), "
                      "function (args) { return 9; })')") == SQLITE_OK,
          "a database with a function h whose code keeps a result set open and cancels the first time it loads");
    sqlite3_close(db);
    cancel_calls = 0;
    logged[0] = 0;
    db = open_trusted_with_cancel_once(held);
    check(cancel_calls == 2, "the trusted entry point loads h again after the cancel stops it");
    check(answers(db, "SELECT h()", "9"), "h is loaded");
    check(answers(db, "SELECT 1 + 2", "3"), "the connection runs SQL afterwards");
    exec(db, "SELECT js_eval('held.toArray(); 0')");
    sqlite3_close(db);
    unlink(held);

    // After a cancel, the result sets JavaScript keeps open are ended, as the interrupt would
    // otherwise stay in effect: every later statement on the connection failed. Run as on SQLite
    // before 3.41 too (GLUE_AS_SQLITE_3_40), where the cancel's own record shows it (0006).
    signal(SIGALRM, on_alarm);
    char kept[512];
    snprintf(kept, sizeof(kept), "%s/kept.db", dir);
    db = open_with(kept, sqlite3_sqlitejs_init);
    sqlite3_create_function(db, "started", 0, SQLITE_UTF8, NULL, started, NULL, NULL);
    check(exec_cancelled(db, "SELECT js_eval('globalThis.held = db.exec(\"SELECT 1 UNION ALL SELECT 2\"); held.next(); "
                             "db.exec(\"SELECT started()\").next(); for (;;) {}')") != SQLITE_OK,
          "a cancel stops JavaScript that keeps a result set open");
    check(answers(db, "SELECT 1 + 2", "3"), "the connection runs SQL after that cancel");
    check(answers(db, "SELECT js_eval('held.next()')", "interrupted"), "the kept result set was ended");

    // Ending a result set that is computing a window function runs the function's final, which
    // begins after the cancel: the cancel must still stop it, also before SQLite 3.41 (0007).
    check(exec(db, "SELECT js_create_window('loop_final', 'total = 0;', '(function (args) { total += args[0]; })', "
                   "'(function () { while (true) {} })', '(function () { return total; })', "
                   "'(function (args) { total -= args[0]; })')") == SQLITE_OK,
          "a window function whose final never ends");
    check(exec_cancelled(db, "SELECT js_eval('globalThis.held = db.exec(\"SELECT loop_final(column1) OVER (ORDER BY column1) "
                             "FROM (VALUES (1), (2), (3))\"); held.next(); db.exec(\"SELECT started()\").next(); for (;;) {}')") != SQLITE_OK,
          "a cancel ends a kept result set whose window function's final never ends");
    check(answers(db, "SELECT 1 + 2", "3"), "the connection runs SQL after that cancel");

    // A progress handler that stops a statement, as Android's cancelling does, interrupts nothing
    // else, so it leaves result sets that earlier calls kept. Before 3.41, which cannot tell it
    // from an interrupt, they are ended.
    check(answers(db, "SELECT js_eval('globalThis.kept = db.exec(\"SELECT 1 UNION ALL SELECT 2\"); kept.next()')", "1"),
          "JavaScript keeps a result set open");
    sqlite3_progress_handler(db, 1000, stop_statement, NULL);
    exec(db, "SELECT js_eval('try { db.exec(\"WITH RECURSIVE c(x) AS (SELECT 1 UNION ALL SELECT x + 1 FROM c WHERE x < 100000000) "
             "SELECT count(*) FROM c\").next(); } catch (e) {} 0')");
    sqlite3_progress_handler(db, 0, NULL, NULL);
#ifdef GLUE_AS_SQLITE_3_40
    check(answers(db, "SELECT js_eval('kept.next()')", "interrupted"),
          "before 3.41, a statement a progress handler stopped ends the kept result set");
#else
    check(answers(db, "SELECT js_eval('kept.next()')", "1"),
          "a statement a progress handler stopped leaves the kept result set");
#endif
    exec(db, "SELECT js_eval('kept = null; held = null; 0')");
    sqlite3_close_v2(db);
    unlink(kept);

    unlink(path);
    unlink(plain);
    rmdir(dir);
    printf("1..%d\n", tests);
    if (failures) printf("# FAILED %d of %d\n", failures, tests);
    return failures ? 1 : 0;
}
