// The "app" side of the JNI test: plain sqlite3 calls into libsqlite3x.so, as requery's do.
#include <jni.h>
#include <stdint.h>
#include "sqlite3.h"

JNIEXPORT jlong JNICALL Java_Helper_open(JNIEnv *e, jclass c, jstring p) {
	(void)c;
	const char *s = (*e)->GetStringUTFChars(e, p, 0);
	sqlite3 *db = 0;
	sqlite3_open(s, &db);
	(*e)->ReleaseStringUTFChars(e, p, s);
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
