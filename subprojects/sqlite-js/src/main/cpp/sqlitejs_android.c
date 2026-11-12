// Android additions to sqlite-js for apps built with Code on the Go (ADFA-7082). Not sent
// upstream: JNI for SqliteJs, and the two entry points it names. Tested by
// src/test/c/glue_test.c (test.sh).

#include <jni.h>
#include <stdbool.h>
#include <string.h>
#include "sqlitejs.h"
// for SQLITE_EXTENSION_INIT3, which is a no-op when glue_test.c builds SQLite in (SQLITE_CORE)
#include "sqlite3ext.h"

SQLITE_EXTENSION_INIT3

// SqliteJs.ENTRY_POINT, and the entry point SQLite derives from libsqlitejs.so when none is
// given: sqlite-js alone. It runs none of the code stored in the database, so it is safe for a
// database file from anywhere.
int sqlite3_sqlitejs_init (sqlite3 *db, char **pzErrMsg, const sqlite3_api_routines *pApi) {
    return sqlite3_js_init(db, pzErrMsg, pApi);
}

// Loads the stored function in row rowid of js_functions. js_init_table(1) loads every row and
// stops at the first that fails, so it is given a TEMP table of the same name that holds only
// this row: an unqualified name finds the TEMP table first, and js_init_table's
// CREATE TABLE IF NOT EXISTS still sees the main one.
// A cancel (SqliteJs.cancelAll(), for a query on any connection) that lands while a function
// loads stops its code as it stops any JavaScript then running, and only loading it again puts the
// function on this connection. So such a load is tried again, up to STORED_FUNCTION_TRIES times
// in all, and its stored code runs again each time.
#define STORED_FUNCTION_TRIES   3

static void load_stored_function (sqlite3 *db, sqlite3_int64 rowid) {
    char *err = NULL;
    char *sql = sqlite3_mprintf(
        "CREATE TEMP TABLE js_functions AS SELECT name, kind, init_code, step_code, final_code, "
        "value_code, inverse_code FROM main.js_functions WHERE rowid = %lld; "
        "SELECT js_init_table(1);", rowid);
    int rc = SQLITE_NOMEM;
    for (int tries = 0; sql && (tries < STORED_FUNCTION_TRIES); tries++) {
        if (tries > 0) sqlite3_exec(db, "DROP TABLE IF EXISTS temp.js_functions", NULL, NULL, NULL);
        sqlite3_free(err);
        err = NULL;
        rc = sqlite3_exec(db, sql, NULL, NULL, &err);
        // a cancel ends the code with an uncatchable "interrupted" error, and interrupts the statement
        bool cancelled = (rc == SQLITE_INTERRUPT) || (err && strstr(err, "interrupted"));
        if ((rc == SQLITE_OK) || !cancelled) break;
    }
    if (rc != SQLITE_OK) {
        char *name = sqlite3_mprintf("%lld", rowid);
        sqlite3_stmt *vm = NULL;
        if (sqlite3_prepare_v2(db, "SELECT name FROM main.js_functions WHERE rowid = ?", -1, &vm, NULL) == SQLITE_OK) {
            sqlite3_bind_int64(vm, 1, rowid);
            if ((sqlite3_step(vm) == SQLITE_ROW) && sqlite3_column_text(vm, 0)) {
                sqlite3_free(name);
                name = sqlite3_mprintf("%s", (const char *)sqlite3_column_text(vm, 0));
            }
        }
        sqlite3_finalize(vm);
        sqlite3_log(rc, "sqlite-js: stored function %s did not load: %s", name ? name : "?", err ? err : sqlite3_errstr(rc));
        sqlite3_free(name);
    }
    sqlite3_free(err);
    sqlite3_free(sql);
    sqlite3_exec(db, "DROP TABLE IF EXISTS temp.js_functions", NULL, NULL, NULL);
}

// SqliteJs.ENTRY_POINT_FOR_TRUSTED_DATABASE: sqlite-js, then the functions stored in the
// database's js_functions table (see js_init_table), if it has one. Loading a function runs its
// stored code, which can run SQL and read files, so this is only for a database the app made and
// keeps. Each connection has its own JavaScript runtime, so every connection of a pool, as with
// write-ahead logging, loads them; a function stored after a connection opened is not in that
// connection. A stored function that fails to load is logged (sqlite3_log) and skipped, and the
// rest still load: failing the connection would leave the app unable to open its database.
int sqlite3_sqlitejs_trusted_init (sqlite3 *db, char **pzErrMsg, const sqlite3_api_routines *pApi) {
    int rc = sqlite3_js_init(db, pzErrMsg, pApi);
    if (rc != SQLITE_OK) return rc;

    // Most databases have no stored functions. The table is looked up first, so that SQLite does
    // not log a "no such table" error for each of their connections.
    sqlite3_stmt *vm = NULL;
    rc = sqlite3_prepare_v2(db, "SELECT 1 FROM main.sqlite_master WHERE type = 'table' AND name = 'js_functions' COLLATE NOCASE", -1, &vm, NULL);
    if (rc == SQLITE_OK) rc = sqlite3_step(vm);
    if ((rc != SQLITE_ROW) && (rc != SQLITE_DONE)) sqlite3_log(rc, "sqlite-js: cannot look for stored functions: %s", sqlite3_errmsg(db));
    sqlite3_finalize(vm);
    if (rc != SQLITE_ROW) return SQLITE_OK;

    // the rows are listed first: loading one changes the TEMP schema
    rc = sqlite3_prepare_v2(db, "SELECT rowid FROM main.js_functions ORDER BY rowid", -1, &vm, NULL);
    if (rc != SQLITE_OK) {
        sqlite3_log(rc, "sqlite-js: cannot list the stored functions: %s", sqlite3_errmsg(db));
        sqlite3_finalize(vm);
        return SQLITE_OK;
    }
    sqlite3_int64 *rowids = NULL;
    int count = 0, room = 0;
    while ((rc = sqlite3_step(vm)) == SQLITE_ROW) {
        if (count == room) {
            int more_room = (room) ? room * 2 : 16;
            sqlite3_int64 *more = sqlite3_realloc64(rowids, (sqlite3_uint64)more_room * sizeof(*rowids));
            if (!more) {
                rc = SQLITE_NOMEM;
                break;
            }
            rowids = more;
            room = more_room;
        }
        rowids[count++] = sqlite3_column_int64(vm, 0);
    }
    if (rc != SQLITE_DONE) {
        sqlite3_log(rc, "sqlite-js: cannot list all the stored functions, so only %d load: %s", count,
                    (rc == SQLITE_NOMEM) ? sqlite3_errstr(rc) : sqlite3_errmsg(db));
    }
    sqlite3_finalize(vm);
    for (int i = 0; i < count; i++) load_stored_function(db, rowids[i]);
    sqlite3_free(rowids);
    return SQLITE_OK;
}

static void JNICALL cancel_all (JNIEnv *env, jclass clazz) {
    (void)env;
    (void)clazz;
    sqlitejs_cancel_all();
}

// Called when the app loads the library with System.loadLibrary (SqliteJs does), not when
// SQLite loads it as an extension: both get the same library.
JNIEXPORT jint JNI_OnLoad (JavaVM *vm, void *reserved) {
    (void)reserved;
    JNIEnv *env = NULL;
    if ((*vm)->GetEnv(vm, (void **)&env, JNI_VERSION_1_6) != JNI_OK) return JNI_ERR;
    jclass clazz = (*env)->FindClass(env, "org/appdevforall/sqlitejs/SqliteJs");
    if (clazz == NULL) return JNI_ERR;
    static const JNINativeMethod methods[] = {
        {"cancelAll", "()V", (void *)cancel_all},
    };
    jint rc = (*env)->RegisterNatives(env, clazz, methods, sizeof(methods) / sizeof(methods[0]));
    (*env)->DeleteLocalRef(env, clazz);
    return (rc == JNI_OK) ? JNI_VERSION_1_6 : JNI_ERR;
}
