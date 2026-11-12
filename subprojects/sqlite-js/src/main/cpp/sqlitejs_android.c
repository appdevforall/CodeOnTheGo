// Android additions to sqlite-js for apps built with Code on the Go (ADFA-7082). Not sent
// upstream: JNI for SqliteJs, and the entry point that loads stored functions.

#include <jni.h>
#include "sqlitejs.h"

SQLITE_EXTENSION_INIT3

// The entry point SQLite derives from libsqlitejs.so: loads sqlite-js, then the functions stored
// in the database's js_functions table (see js_init_table), if it has one. Each connection has
// its own JavaScript runtime, so every connection of a pool, as with write-ahead logging, must
// load them. A function stored after a connection opened is not in that connection. A stored
// function that fails to load is logged (sqlite3_log) instead of failing the connection, which
// would leave the app unable to open its database.
int sqlite3_sqlitejs_init (sqlite3 *db, char **pzErrMsg, const sqlite3_api_routines *pApi) {
    int rc = sqlite3_js_init(db, pzErrMsg, pApi);
    if (rc != SQLITE_OK) return rc;
    char *err = NULL;
    rc = sqlite3_exec(db, "SELECT js_init_table(1) FROM sqlite_master WHERE type = 'table' AND name = 'js_functions'", NULL, NULL, &err);
    if (rc != SQLITE_OK) sqlite3_log(rc, "sqlite-js: the stored functions did not load: %s", err ? err : sqlite3_errstr(rc));
    sqlite3_free(err);
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
