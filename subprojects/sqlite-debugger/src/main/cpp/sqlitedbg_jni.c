// JNI layer of the SQLite debugger (ADFA-7135): NativeBridge.kt over sqlitedbg.h. Like the
// extension it calls SQLite only through the api table (sqlite3ext.h), which sqlitedbg.c saves.
// Every handle here comes from sqlitedbg_open_server, which refuses to open before that.

#ifndef _GNU_SOURCE
#define _GNU_SOURCE
#endif

#include <dlfcn.h>
#include <jni.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

#include "sqlite3ext.h"
#include "sqlitedbg.h"

SQLITE_EXTENSION_INIT3

#define BRIDGE_CLASS "org/appdevforall/sqlitedebugger/engine/NativeBridge"
#define EXCEPTION_CLASS "org/appdevforall/sqlitedebugger/engine/DebugEngineException"

static jclass g_object, g_string, g_array_list, g_long, g_double, g_exception;
static jmethodID g_string_from_bytes, g_list_init, g_list_add, g_long_value_of, g_double_value_of, g_exception_init;
static jstring g_utf8_name;

static jclass global_class(JNIEnv *env, const char *name) {
	jclass local = (*env)->FindClass(env, name);
	if (!local) return NULL;
	jclass global = (jclass)(*env)->NewGlobalRef(env, local);
	(*env)->DeleteLocalRef(env, local);
	return global;
}

// A Java String from UTF-8 bytes. NewStringUTF takes modified UTF-8, which differs for NUL and for
// characters outside the BMP, and SQLite text can hold both.
static jstring utf8_string(JNIEnv *env, const char *bytes, int n) {
	jbyteArray array = (*env)->NewByteArray(env, n);
	if (!array) return NULL;
	(*env)->SetByteArrayRegion(env, array, 0, n, (const jbyte *)bytes);
	jstring s = (jstring)(*env)->NewObject(env, g_string, g_string_from_bytes, array, g_utf8_name);
	(*env)->DeleteLocalRef(env, array);
	return s;
}

static void throw_engine(JNIEnv *env, int code, const char *message) {
	if ((*env)->ExceptionCheck(env)) return;
	jstring text = utf8_string(env, message, (int)strlen(message));
	if (!text) return;
	jobject ex = (*env)->NewObject(env, g_exception, g_exception_init, (jint)code, text);
	if (ex) (*env)->Throw(env, (jthrowable)ex);
}

static int64_t now_ms(void) {
	struct timespec ts;
	clock_gettime(CLOCK_REALTIME, &ts);
	return (int64_t)ts.tv_sec * 1000 + ts.tv_nsec / 1000000;
}

// install

static jint bridge_install(JNIEnv *env, jclass cls) {
	(void)env;
	(void)cls;
	// requery's library is already loaded by System.loadLibrary; NOLOAD only finds it, and its
	// sqlite3_ functions are all exported, so no separate handle to SQLite is needed
	void *lib = dlopen("libsqlite3x.so", RTLD_NOW | RTLD_NOLOAD);
	if (!lib) return -1;
	sqlitedbg_auto_extension_fn auto_extension = (sqlitedbg_auto_extension_fn)dlsym(lib, "sqlite3_auto_extension");
	int rc = auto_extension ? sqlitedbg_install(auto_extension, NULL) : -1;
	dlclose(lib);
	return rc;
}

// connections

typedef struct conn_list {
	jlong *meta;
	char **paths;
	size_t n, cap;
	int oom;
} conn_list;

static void collect_connection(void *context, const sqlitedbg_connection *c) {
	conn_list *l = (conn_list *)context;
	if (l->oom) return;
	if (l->n == l->cap) {
		size_t cap = l->cap ? l->cap * 2 : 16;
		jlong *meta = realloc(l->meta, cap * 5 * sizeof(jlong));
		if (meta) l->meta = meta;
		char **paths = realloc(l->paths, cap * sizeof(char *));
		if (paths) l->paths = paths;
		if (!meta || !paths) {
			l->oom = 1;
			return;
		}
		l->cap = cap;
	}
	char *path = strdup(c->path);
	if (!path) {
		l->oom = 1;
		return;
	}
	jlong *m = l->meta + l->n * 5;
	m[0] = (jlong)c->id;
	m[1] = c->read_only;
	m[2] = c->closing;
	m[3] = (jlong)c->opened_at_ns;
	m[4] = (jlong)c->open_thread_id;
	l->paths[l->n++] = path;
}

static jobjectArray bridge_connections(JNIEnv *env, jclass cls) {
	(void)cls;
	conn_list l = {0};
	sqlitedbg_each_connection(collect_connection, &l);
	jobjectArray result = NULL;
	if (l.oom) {
		throw_engine(env, SQLITE_NOMEM, "out of memory");
		goto done;
	}
	jlongArray meta = (*env)->NewLongArray(env, (jsize)(l.n * 5));
	jobjectArray paths = meta ? (*env)->NewObjectArray(env, (jsize)l.n, g_string, NULL) : NULL;
	if (!paths) goto done;
	(*env)->SetLongArrayRegion(env, meta, 0, (jsize)(l.n * 5), l.meta ? l.meta : (jlong[]){0});
	for (size_t i = 0; i < l.n; i++) {
		jstring s = utf8_string(env, l.paths[i], (int)strlen(l.paths[i]));
		if (!s) goto done;
		(*env)->SetObjectArrayElement(env, paths, (jsize)i, s);
		(*env)->DeleteLocalRef(env, s);
	}
	result = (*env)->NewObjectArray(env, 2, g_object, NULL);
	if (result) {
		(*env)->SetObjectArrayElement(env, result, 0, meta);
		(*env)->SetObjectArrayElement(env, result, 1, paths);
	}
done:
	for (size_t i = 0; i < l.n; i++) free(l.paths[i]);
	free(l.paths);
	free(l.meta);
	return result;
}

// events

static void bridge_read_events(JNIEnv *env, jclass cls, jlong after, jbyteArray buf, jlongArray out) {
	(void)cls;
	jsize cap = (*env)->GetArrayLength(env, buf);
	void *tmp = malloc((size_t)cap);
	if (!tmp) {
		throw_engine(env, SQLITE_NOMEM, "out of memory");
		return;
	}
	sqlitedbg_read_result r;
	sqlitedbg_read_events((uint64_t)after, tmp, (size_t)cap, &r);
	(*env)->SetByteArrayRegion(env, buf, 0, (jsize)r.bytes, (const jbyte *)tmp);
	free(tmp);
	jlong values[3] = {(jlong)r.next, (jlong)r.dropped, (jlong)r.bytes};
	(*env)->SetLongArrayRegion(env, out, 0, 3, values);
}

static void bridge_set_client_connected(JNIEnv *env, jclass cls, jboolean connected) {
	(void)env;
	(void)cls;
	sqlitedbg_set_client_connected(connected ? 1 : 0);
}

// server connections

static jlong bridge_open_server(JNIEnv *env, jclass cls, jbyteArray path, jboolean writable) {
	(void)cls;
	jsize n = (*env)->GetArrayLength(env, path);
	char *c_path = malloc((size_t)n + 1);
	if (!c_path) {
		throw_engine(env, SQLITE_NOMEM, "out of memory");
		return 0;
	}
	(*env)->GetByteArrayRegion(env, path, 0, n, (jbyte *)c_path);
	c_path[n] = 0;
	sqlite3 *db = NULL;
	int rc = sqlitedbg_open_server(c_path, writable ? 1 : 0, &db);
	free(c_path);
	if (rc != SQLITE_OK) {
		throw_engine(env, rc, rc == SQLITE_MISUSE ? "no app connection has opened yet" : sqlite3_errstr(rc));
		return 0;
	}
	return (jlong)(intptr_t)db;
}

static void bridge_close(JNIEnv *env, jclass cls, jlong handle) {
	(void)env;
	(void)cls;
	sqlite3_close_v2((sqlite3 *)(intptr_t)handle);
}

typedef struct deadline {
	int64_t at_ms;
	int hit;
} deadline;

static int progress_cb(void *p) {
	deadline *d = (deadline *)p;
	if (now_ms() < d->at_ms) return 0;
	d->hit = 1;
	return 1;
}

// Binds args[i] to parameter i + 1. Returns SQLITE_OK or a code with *message set.
static int bind_args(JNIEnv *env, sqlite3 *db, sqlite3_stmt *st, jobjectArray args, const char **message) {
	jsize count = (*env)->GetArrayLength(env, args);
	for (jsize i = 0; i < count; i++) {
		jobject v = (*env)->GetObjectArrayElement(env, args, i);
		int rc;
		if (!v) {
			rc = sqlite3_bind_null(st, i + 1);
		} else if ((*env)->IsInstanceOf(env, v, g_long)) {
			jmethodID m = (*env)->GetMethodID(env, g_long, "longValue", "()J");
			rc = sqlite3_bind_int64(st, i + 1, (sqlite3_int64)(*env)->CallLongMethod(env, v, m));
		} else if ((*env)->IsInstanceOf(env, v, g_double)) {
			jmethodID m = (*env)->GetMethodID(env, g_double, "doubleValue", "()D");
			rc = sqlite3_bind_double(st, i + 1, (*env)->CallDoubleMethod(env, v, m));
		} else if ((*env)->IsInstanceOf(env, v, g_string)) {
			jsize len = (*env)->GetStringLength(env, (jstring)v);
			const jchar *chars = (*env)->GetStringChars(env, (jstring)v, NULL);
			if (!chars) return SQLITE_NOMEM;
			rc = sqlite3_bind_text16(st, i + 1, chars, len * 2, SQLITE_TRANSIENT);
			(*env)->ReleaseStringChars(env, (jstring)v, chars);
		} else {
			jclass bytes_class = (*env)->FindClass(env, "[B");
			int is_bytes = bytes_class && (*env)->IsInstanceOf(env, v, bytes_class);
			if (bytes_class) (*env)->DeleteLocalRef(env, bytes_class);
			if (!is_bytes) {
				*message = "unsupported argument type";
				return SQLITE_MISUSE;
			}
			jsize len = (*env)->GetArrayLength(env, (jbyteArray)v);
			void *copy = malloc(len ? (size_t)len : 1);
			if (!copy) return SQLITE_NOMEM;
			(*env)->GetByteArrayRegion(env, (jbyteArray)v, 0, len, (jbyte *)copy);
			rc = sqlite3_bind_blob64(st, i + 1, copy, (sqlite3_uint64)len, SQLITE_TRANSIENT);
			free(copy);
		}
		if (v) (*env)->DeleteLocalRef(env, v);
		if (rc != SQLITE_OK) {
			*message = sqlite3_errmsg(db);
			return rc;
		}
		if ((*env)->ExceptionCheck(env)) return SQLITE_ERROR;
	}
	return SQLITE_OK;
}

// A UTF-8 prefix of at most max bytes ending on a character boundary.
static int utf8_prefix(const unsigned char *s, int len, int max) {
	if (len <= max) return len;
	while (max > 0 && (s[max] & 0xC0) == 0x80) max--;
	return max;
}

// One cell as a Java object, or NULL for SQL NULL (or on failure, with an exception pending).
static jobject cell_value(JNIEnv *env, sqlite3_stmt *st, int col, jint max_text, jint max_blob, int *truncated) {
	switch (sqlite3_column_type(st, col)) {
		case SQLITE_INTEGER:
			return (*env)->CallStaticObjectMethod(env, g_long, g_long_value_of, (jlong)sqlite3_column_int64(st, col));
		case SQLITE_FLOAT:
			return (*env)->CallStaticObjectMethod(env, g_double, g_double_value_of, (jdouble)sqlite3_column_double(st, col));
		case SQLITE_TEXT: {
			const unsigned char *text = sqlite3_column_text(st, col);
			int len = sqlite3_column_bytes(st, col);
			int keep = utf8_prefix(text, len, max_text);
			if (keep < len) (*truncated)++;
			return utf8_string(env, (const char *)text, keep);
		}
		case SQLITE_BLOB: {
			const void *blob = sqlite3_column_blob(st, col);
			int len = sqlite3_column_bytes(st, col);
			int keep = len > max_blob ? max_blob : len;
			if (keep < len) (*truncated)++;
			jbyteArray array = (*env)->NewByteArray(env, keep);
			if (array && keep > 0) (*env)->SetByteArrayRegion(env, array, 0, keep, (const jbyte *)blob);
			return array;
		}
		default:
			return NULL;
	}
}

static jobjectArray bridge_query(JNIEnv *env, jclass cls, jlong handle, jstring sql, jobjectArray args, jlong offset,
                                 jint max_rows, jint max_text, jint max_blob, jlong deadline_ms) {
	(void)cls;
	sqlite3 *db = (sqlite3 *)(intptr_t)handle;
	sqlite3_stmt *st = NULL;
	jobjectArray result = NULL;
	deadline dl = {(int64_t)deadline_ms, 0};
	int handler_set = 0;
	const char *message = NULL;
	int rc;

	if (now_ms() >= dl.at_ms) {
		throw_engine(env, SQLITE_INTERRUPT, "the time limit passed before the query started");
		return NULL;
	}
	jsize sql_len = (*env)->GetStringLength(env, sql);
	const jchar *sql_chars = (*env)->GetStringChars(env, sql, NULL);
	if (!sql_chars) return NULL;
	rc = sqlite3_prepare16_v2(db, sql_chars, sql_len * 2, &st, NULL);
	(*env)->ReleaseStringChars(env, sql, sql_chars);
	if (rc != SQLITE_OK) {
		throw_engine(env, rc, sqlite3_errmsg(db));
		return NULL;
	}

	// SQLite checks the handler every 1000 virtual machine steps and stops the statement when it
	// returns non-zero; it is cleared before this returns, so it never outlives `dl`.
	sqlite3_progress_handler(db, 1000, progress_cb, &dl);
	handler_set = 1;

	rc = st ? bind_args(env, db, st, args, &message) : SQLITE_OK;
	if (rc != SQLITE_OK) {
		if (!(*env)->ExceptionCheck(env)) throw_engine(env, rc, message ? message : "cannot bind");
		goto done;
	}

	int ncols = st ? sqlite3_column_count(st) : 0;
	jobjectArray names = (*env)->NewObjectArray(env, ncols, g_string, NULL);
	jobject rows = names ? (*env)->NewObject(env, g_array_list, g_list_init) : NULL;
	if (!rows) goto done;
	for (int c = 0; c < ncols; c++) {
		const char *name = sqlite3_column_name(st, c);
		jstring s = utf8_string(env, name ? name : "", name ? (int)strlen(name) : 0);
		if (!s) goto done;
		(*env)->SetObjectArrayElement(env, names, c, s);
		(*env)->DeleteLocalRef(env, s);
	}

	int truncated = 0, has_more = 0, row_count = 0;
	jlong skipped = 0;
	while (st && (rc = sqlite3_step(st)) == SQLITE_ROW) {
		if (skipped < offset) {
			skipped++;
			continue;
		}
		if (row_count >= max_rows) {
			has_more = 1;
			break;
		}
		jobjectArray row = (*env)->NewObjectArray(env, ncols, g_object, NULL);
		if (!row) goto done;
		for (int c = 0; c < ncols; c++) {
			jobject cell = cell_value(env, st, c, max_text, max_blob, &truncated);
			if ((*env)->ExceptionCheck(env)) goto done;
			if (cell) {
				(*env)->SetObjectArrayElement(env, row, c, cell);
				(*env)->DeleteLocalRef(env, cell);
			}
		}
		(*env)->CallBooleanMethod(env, rows, g_list_add, row);
		(*env)->DeleteLocalRef(env, row);
		if ((*env)->ExceptionCheck(env)) goto done;
		row_count++;
	}
	if (st && !has_more && rc != SQLITE_DONE) {
		if (dl.hit && rc == SQLITE_INTERRUPT) throw_engine(env, SQLITE_INTERRUPT, "the query ran past its time limit");
		else throw_engine(env, rc, sqlite3_errmsg(db));
		goto done;
	}

	jlongArray counts = (*env)->NewLongArray(env, 2);
	result = counts ? (*env)->NewObjectArray(env, 3, g_object, NULL) : NULL;
	if (!result) goto done;
	jlong values[2] = {truncated, has_more};
	(*env)->SetLongArrayRegion(env, counts, 0, 2, values);
	(*env)->SetObjectArrayElement(env, result, 0, names);
	(*env)->SetObjectArrayElement(env, result, 1, rows);
	(*env)->SetObjectArrayElement(env, result, 2, counts);

done:
	if (handler_set) sqlite3_progress_handler(db, 0, NULL, NULL);
	sqlite3_finalize(st);
	// Backstop: a transaction left open (a writable connection, or a way past the authorizer)
	// would hold a lock on the app's database until the next query.
	if (sqlite3_get_autocommit(db) == 0) sqlite3_exec(db, "ROLLBACK", NULL, NULL, NULL);
	return result;
}

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {
	(void)reserved;
	JNIEnv *env;
	if ((*vm)->GetEnv(vm, (void **)&env, JNI_VERSION_1_6) != JNI_OK) return JNI_ERR;

	g_object = global_class(env, "java/lang/Object");
	g_string = global_class(env, "java/lang/String");
	g_array_list = global_class(env, "java/util/ArrayList");
	g_long = global_class(env, "java/lang/Long");
	g_double = global_class(env, "java/lang/Double");
	g_exception = global_class(env, EXCEPTION_CLASS);
	jstring utf8 = (*env)->NewStringUTF(env, "UTF-8");
	if (!g_object || !g_string || !g_array_list || !g_long || !g_double || !g_exception || !utf8) return JNI_ERR;
	g_utf8_name = (jstring)(*env)->NewGlobalRef(env, utf8);

	g_string_from_bytes = (*env)->GetMethodID(env, g_string, "<init>", "([BLjava/lang/String;)V");
	g_list_init = (*env)->GetMethodID(env, g_array_list, "<init>", "()V");
	g_list_add = (*env)->GetMethodID(env, g_array_list, "add", "(Ljava/lang/Object;)Z");
	g_long_value_of = (*env)->GetStaticMethodID(env, g_long, "valueOf", "(J)Ljava/lang/Long;");
	g_double_value_of = (*env)->GetStaticMethodID(env, g_double, "valueOf", "(D)Ljava/lang/Double;");
	g_exception_init = (*env)->GetMethodID(env, g_exception, "<init>", "(ILjava/lang/String;)V");
	if (!g_string_from_bytes || !g_list_init || !g_list_add || !g_long_value_of || !g_double_value_of || !g_exception_init)
		return JNI_ERR;

	static const JNINativeMethod methods[] = {
	    {"install", "()I", (void *)bridge_install},
	    {"connections", "()[Ljava/lang/Object;", (void *)bridge_connections},
	    {"readEvents", "(J[B[J)V", (void *)bridge_read_events},
	    {"setClientConnected", "(Z)V", (void *)bridge_set_client_connected},
	    {"openServer", "([BZ)J", (void *)bridge_open_server},
	    {"query", "(JLjava/lang/String;[Ljava/lang/Object;JIIIJ)[Ljava/lang/Object;", (void *)bridge_query},
	    {"close", "(J)V", (void *)bridge_close},
	};
	jclass bridge = (*env)->FindClass(env, BRIDGE_CLASS);
	if (!bridge || (*env)->RegisterNatives(env, bridge, methods, sizeof methods / sizeof methods[0]) != JNI_OK)
		return JNI_ERR;
	return JNI_VERSION_1_6;
}
