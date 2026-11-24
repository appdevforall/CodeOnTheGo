// The "app" side of the JNI test: plain sqlite3 calls into libsqlite3x.so, as requery's do.
#include <jni.h>
#include <stdint.h>
#include <stdlib.h>
#include "sqlite3.h"

// The path arrives as UTF-8 bytes and is opened as such, as sqlitedbg_open_server's path is: a
// GetStringUTFChars path would be modified UTF-8. Returns 0 when sqlite3_open fails.
JNIEXPORT jlong JNICALL Java_Helper_openBytes(JNIEnv *e, jclass c, jbyteArray p) {
	(void)c;
	jsize n = (*e)->GetArrayLength(e, p);
	char *s = malloc((size_t)n + 1);
	if (!s) return 0;
	(*e)->GetByteArrayRegion(e, p, 0, n, (jbyte *)s);
	s[n] = 0;
	sqlite3 *db = 0;
	int rc = sqlite3_open(s, &db);
	free(s);
	if (rc != SQLITE_OK) {
		sqlite3_close(db);
		return 0;
	}
	return (jlong)(intptr_t)db;
}

JNIEXPORT jint JNICALL Java_Helper_exec(JNIEnv *e, jclass c, jlong db, jstring q) {
	(void)c;
	const char *s = (*e)->GetStringUTFChars(e, q, 0);
	int rc = sqlite3_exec((sqlite3 *)(intptr_t)db, s, 0, 0, 0);
	(*e)->ReleaseStringUTFChars(e, q, s);
	return rc;
}

JNIEXPORT jint JNICALL Java_Helper_close(JNIEnv *e, jclass c, jlong db) {
	(void)e;
	(void)c;
	return sqlite3_close((sqlite3 *)(intptr_t)db);
}

static int deny_transactions(void *u, int action, const char *a1, const char *a2, const char *d, const char *t) {
	(void)u;
	(void)a1;
	(void)a2;
	(void)d;
	(void)t;
	return action == SQLITE_TRANSACTION ? SQLITE_DENY : SQLITE_OK;
}

// An authorizer on any connection, a server one included, that refuses BEGIN, COMMIT and ROLLBACK,
// or none (deny false).
JNIEXPORT jint JNICALL Java_Helper_denyTransactions(JNIEnv *e, jclass c, jlong db, jboolean deny) {
	(void)e;
	(void)c;
	return sqlite3_set_authorizer((sqlite3 *)(intptr_t)db, deny ? deny_transactions : 0, 0);
}

// The app's busy timeout; requery sets 2500 ms on every app connection.
JNIEXPORT jint JNICALL Java_Helper_busyTimeout(JNIEnv *e, jclass c, jlong db, jint ms) {
	(void)e;
	(void)c;
	return sqlite3_busy_timeout((sqlite3 *)(intptr_t)db, ms);
}

// sqlite3_get_autocommit: 1 when no transaction is open.
JNIEXPORT jint JNICALL Java_Helper_autocommit(JNIEnv *e, jclass c, jlong db) {
	(void)e;
	(void)c;
	return sqlite3_get_autocommit((sqlite3 *)(intptr_t)db);
}
