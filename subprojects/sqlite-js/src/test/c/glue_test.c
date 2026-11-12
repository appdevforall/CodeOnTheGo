// Host test of sqlitejs_android.c's entry points (ADFA-7082), built and run by ../../../test.sh
// with sqlite-js and SQLite compiled in (SQLITE_CORE). Prints TAP; exits non-zero on a failure.

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include "sqlite3.h"

int sqlite3_sqlitejs_init (sqlite3 *db, char **pzErrMsg, const sqlite3_api_routines *pApi);
int sqlite3_sqlitejs_trusted_init (sqlite3 *db, char **pzErrMsg, const sqlite3_api_routines *pApi);

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

    unlink(path);
    unlink(plain);
    rmdir(dir);
    printf("1..%d\n", tests);
    if (failures) printf("# FAILED %d of %d\n", failures, tests);
    return failures ? 1 : 0;
}
