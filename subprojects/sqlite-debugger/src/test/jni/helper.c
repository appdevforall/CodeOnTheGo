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
