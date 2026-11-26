// JNI layer of the SQLite debugger (ADFA-7135): NativeBridge.kt over sqlitedbg.h. Like the
// extension it calls SQLite only through the api table (sqlite3ext.h), which sqlitedbg.c saves.
// Every handle here comes from sqlitedbg_open_server, which refuses to open before that.

#ifndef _GNU_SOURCE
#define _GNU_SOURCE
#endif

#include <dlfcn.h>
#include <jni.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

#include "sqlite3ext.h"
#include "expert/sqlite3expert.h"
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
	jlong values[4] = {(jlong)r.next, (jlong)r.dropped, (jlong)r.bytes, (jlong)r.reset};
	(*env)->SetLongArrayRegion(env, out, 0, 4, values);
}

static void bridge_set_client_connected(JNIEnv *env, jclass cls, jboolean connected) {
	(void)env;
	(void)cls;
	sqlitedbg_set_client_connected(connected ? 1 : 0);
}

static void bridge_set_row_capture(JNIEnv *env, jclass cls, jint max_rows, jint max_value_bytes, jint max_row_bytes) {
	(void)env;
	(void)cls;
	sqlitedbg_set_row_capture(max_rows > 0 ? (uint32_t)max_rows : 0, max_value_bytes > 0 ? (uint32_t)max_value_bytes : 0,
	                          max_row_bytes > 0 ? (uint32_t)max_row_bytes : 0);
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
	int capped;  // at_ms is the non-WAL cap, earlier than the call's own deadline
} deadline;

// Whether the database is in WAL mode. PRAGMA journal_mode without a value only reads the mode. Any
// failure counts as not WAL, which is the safe answer: the statement then gets the short cap.
static int is_wal_mode(sqlite3 *db) {
	sqlite3_stmt *st = NULL;
	int wal = 0;
	if (sqlite3_prepare_v2(db, "PRAGMA journal_mode", -1, &st, NULL) == SQLITE_OK && sqlite3_step(st) == SQLITE_ROW) {
		const unsigned char *mode = sqlite3_column_text(st, 0);
		wal = mode && sqlite3_stricmp((const char *)mode, "wal") == 0;
	}
	sqlite3_finalize(st);
	return wal;
}

static int progress_cb(void *p) {
	deadline *d = (deadline *)p;
	if (now_ms() < d->at_ms) return 0;
	d->hit = 1;
	return 1;
}

// Binds args[i] to parameter i + 1. Returns SQLITE_OK or a code with *message set.
// Binds v (null, Long, Double, String or byte[]) to parameter idx (from 1).
static int bind_value(JNIEnv *env, sqlite3 *db, sqlite3_stmt *st, int idx, jobject v, const char **message) {
	int rc;
	if (!v) {
		rc = sqlite3_bind_null(st, idx);
	} else if ((*env)->IsInstanceOf(env, v, g_long)) {
		jmethodID m = (*env)->GetMethodID(env, g_long, "longValue", "()J");
		rc = sqlite3_bind_int64(st, idx, (sqlite3_int64)(*env)->CallLongMethod(env, v, m));
	} else if ((*env)->IsInstanceOf(env, v, g_double)) {
		jmethodID m = (*env)->GetMethodID(env, g_double, "doubleValue", "()D");
		rc = sqlite3_bind_double(st, idx, (*env)->CallDoubleMethod(env, v, m));
	} else if ((*env)->IsInstanceOf(env, v, g_string)) {
		jsize len = (*env)->GetStringLength(env, (jstring)v);
		const jchar *chars = (*env)->GetStringChars(env, (jstring)v, NULL);
		if (!chars) return SQLITE_NOMEM;
		rc = sqlite3_bind_text16(st, idx, chars, len * 2, SQLITE_TRANSIENT);
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
		rc = sqlite3_bind_blob64(st, idx, copy, (sqlite3_uint64)len, SQLITE_TRANSIENT);
		free(copy);
	}
	if (rc != SQLITE_OK) {
		*message = sqlite3_errmsg(db);
		return rc;
	}
	return (*env)->ExceptionCheck(env) ? SQLITE_ERROR : SQLITE_OK;
}

// Binds args by position, ? and ?NNN alike: args[i] to parameter i + 1. Their number must be the
// statement's parameter count: a missing one would be bound as NULL and the query would run,
// returning a plausible empty result.
static int bind_positional(JNIEnv *env, sqlite3 *db, sqlite3_stmt *st, jobjectArray args, const char **message) {
	int wanted = sqlite3_bind_parameter_count(st);
	jsize count = (*env)->GetArrayLength(env, args);
	if (count != wanted) {
		char msg[96];
		snprintf(msg, sizeof msg, "the statement has %d parameter(s) but %d argument(s) were given", wanted, (int)count);
		throw_engine(env, SQLITE_RANGE, msg);
		return SQLITE_RANGE;
	}
	for (jsize i = 0; i < count; i++) {
		jobject v = (*env)->GetObjectArrayElement(env, args, i);
		int rc = bind_value(env, db, st, i + 1, v, message);
		if (v) (*env)->DeleteLocalRef(env, v);
		if (rc != SQLITE_OK) return rc;
	}
	return SQLITE_OK;
}

typedef struct {
	char *name;  // UTF-8, not terminated
	size_t len;
	int used;
} bind_key;

// Whether SQLite's parameter name (":id", "@id", "$id" or "?3") is key: written the same, or key
// without the prefix (":", "@" or "$").
static int key_matches(const char *param, const bind_key *key) {
	size_t plen = strlen(param);
	if (plen == key->len && memcmp(param, key->name, plen) == 0) return 1;
	if (key->len == 0 || strchr(":@$?", key->name[0])) return 0;
	return strchr(":@$", param[0]) && plen - 1 == key->len && memcmp(param + 1, key->name, key->len) == 0;
}

// Binds args by name: names[i] (UTF-8) names the parameter args[i] goes to. Every parameter must
// get exactly one value and every name must be used, so a misspelt name fails instead of leaving a
// NULL.
static int bind_named(JNIEnv *env, sqlite3 *db, sqlite3_stmt *st, jobjectArray args, jobjectArray names,
                      const char **message) {
	jsize n = (*env)->GetArrayLength(env, names);
	if ((*env)->GetArrayLength(env, args) != n) {
		*message = "names and args differ in length";
		return SQLITE_MISUSE;
	}
	bind_key *keys = calloc(n ? (size_t)n : 1, sizeof *keys);
	if (!keys) return SQLITE_NOMEM;
	int rc = SQLITE_OK;
	char msg[256];
	for (jsize i = 0; i < n && rc == SQLITE_OK; i++) {
		jbyteArray b = (jbyteArray)(*env)->GetObjectArrayElement(env, names, i);
		if (!b) {
			*message = "a parameter name is null";
			rc = SQLITE_MISUSE;
			break;
		}
		jsize len = (*env)->GetArrayLength(env, b);
		keys[i].name = malloc(len ? (size_t)len : 1);
		if (!keys[i].name) rc = SQLITE_NOMEM;
		else {
			(*env)->GetByteArrayRegion(env, b, 0, len, (jbyte *)keys[i].name);
			keys[i].len = (size_t)len;
		}
		(*env)->DeleteLocalRef(env, b);
	}
	int count = sqlite3_bind_parameter_count(st);
	for (int p = 1; p <= count && rc == SQLITE_OK; p++) {
		const char *param = sqlite3_bind_parameter_name(st, p);
		if (!param) {
			snprintf(msg, sizeof msg,
			         "parameter %d has no name (a ? or a gap in the ?NNN numbers): pass args as an array instead", p);
			throw_engine(env, SQLITE_RANGE, msg);
			rc = SQLITE_RANGE;
			break;
		}
		jsize found = -1;
		for (jsize i = 0; i < n; i++) {
			if (!key_matches(param, &keys[i])) continue;
			if (found >= 0) {
				snprintf(msg, sizeof msg, "parameter %.100s is given more than once", param);
				throw_engine(env, SQLITE_RANGE, msg);
				rc = SQLITE_RANGE;
				break;
			}
			found = i;
		}
		if (rc != SQLITE_OK) break;
		if (found < 0) {
			snprintf(msg, sizeof msg, "no value was given for parameter %.100s", param);
			throw_engine(env, SQLITE_RANGE, msg);
			rc = SQLITE_RANGE;
			break;
		}
		keys[found].used = 1;
		jobject v = (*env)->GetObjectArrayElement(env, args, found);
		rc = bind_value(env, db, st, p, v, message);
		if (v) (*env)->DeleteLocalRef(env, v);
	}
	for (jsize i = 0; i < n && rc == SQLITE_OK; i++) {
		if (keys[i].used) continue;
		int shown = keys[i].len > 100 ? 100 : (int)keys[i].len;
		snprintf(msg, sizeof msg, "the statement has no parameter named %.*s", shown, keys[i].name);
		throw_engine(env, SQLITE_RANGE, msg);
		rc = SQLITE_RANGE;
	}
	for (jsize i = 0; i < n; i++) free(keys[i].name);
	free(keys);
	return rc;
}

// A UTF-8 prefix of at most max bytes ending on a character boundary.
static int utf8_prefix(const unsigned char *s, int len, int max) {
	if (len <= max) return len;
	while (max > 0 && (s[max] & 0xC0) == 0x80) max--;
	return max;
}

// Prepares sql, which must hold exactly one statement: SQLite compiles only the first statement of a
// string and reads no further than a NUL, so anything after either would be dropped without a word.
// Returns SQLITE_OK with *st set, or a code with *message set (left NULL for sqlite3_errmsg).
static int prepare_one(sqlite3 *db, const jchar *sql, jsize len, sqlite3_stmt **st, const char **message) {
	*st = NULL;
	for (jsize i = 0; i < len; i++) {
		if (sql[i] == 0) {
			*message = "the SQL holds a NUL character, and SQLite reads no further than it";
			return SQLITE_ERROR;
		}
	}
	const void *tail = NULL;
	int rc = sqlite3_prepare16_v2(db, sql, len * 2, st, &tail);
	if (rc != SQLITE_OK) return rc;
	if (!*st) {
		*message = "the SQL holds no statement, only whitespace or comments";
		return SQLITE_ERROR;
	}
	// Whitespace, comments and semicolons prepare to no statement. Anything else is a second
	// statement, also when it fails to prepare: the server's authorizer refuses a write then.
	int left = len * 2 - (int)((const char *)tail - (const char *)sql);
	sqlite3_stmt *next = NULL;
	if (left > 0 && (sqlite3_prepare16_v2(db, tail, left, &next, NULL) != SQLITE_OK || next)) {
		sqlite3_finalize(next);
		sqlite3_finalize(*st);
		*st = NULL;
		*message = "the SQL holds more than one statement; run one statement per call";
		return SQLITE_ERROR;
	}
	return SQLITE_OK;
}

// One cell as a Java object, or NULL for SQL NULL (or on failure, with an exception pending).
// *cut is set when a TEXT or BLOB was cut at its limit, and *bytes grows by what the cell costs the
// result: the TEXT or BLOB bytes kept, 8 for a number.
static jobject cell_value(JNIEnv *env, sqlite3_stmt *st, int col, jint max_text, jint max_blob, int *cut,
                          int64_t *bytes) {
	*cut = 0;
	switch (sqlite3_column_type(st, col)) {
		case SQLITE_INTEGER:
			*bytes += 8;
			return (*env)->CallStaticObjectMethod(env, g_long, g_long_value_of, (jlong)sqlite3_column_int64(st, col));
		case SQLITE_FLOAT:
			*bytes += 8;
			return (*env)->CallStaticObjectMethod(env, g_double, g_double_value_of, (jdouble)sqlite3_column_double(st, col));
		case SQLITE_TEXT: {
			const unsigned char *text = sqlite3_column_text(st, col);
			if (!text) {  // out of memory
				throw_engine(env, SQLITE_NOMEM, "out of memory");
				return NULL;
			}
			int len = sqlite3_column_bytes(st, col);
			int keep = utf8_prefix(text, len, max_text);
			if (keep < len) *cut = 1;
			*bytes += keep;
			return utf8_string(env, (const char *)text, keep);
		}
		case SQLITE_BLOB: {
			const void *blob = sqlite3_column_blob(st, col);
			int len = sqlite3_column_bytes(st, col);
			if (!blob && len > 0) {  // out of memory
				throw_engine(env, SQLITE_NOMEM, "out of memory");
				return NULL;
			}
			int keep = len > max_blob ? max_blob : len;
			if (keep < len) *cut = 1;
			*bytes += keep;
			jbyteArray array = (*env)->NewByteArray(env, keep);
			if (array && keep > 0) (*env)->SetByteArrayRegion(env, array, 0, keep, (const jbyte *)blob);
			return array;
		}
		default:
			return NULL;
	}
}

static jobjectArray bridge_query(JNIEnv *env, jclass cls, jlong handle, jstring sql, jobjectArray args,
                                 jobjectArray names, jlong offset,
                                 jint max_rows, jint max_text, jint max_blob, jint max_total, jlong deadline_ms) {
	(void)cls;
	sqlite3 *db = (sqlite3 *)(intptr_t)handle;
	sqlite3_stmt *st = NULL;
	jobjectArray result = NULL;
	jlong *cut_cells = NULL;  // row * columns + column of each cut cell
	size_t cut_n = 0, cut_cap = 0;
	deadline dl = {(int64_t)deadline_ms, 0, 0};
	int handler_set = 0;
	const char *message = NULL;
	int rc;

	if (now_ms() >= dl.at_ms) {
		throw_engine(env, SQLITE_INTERRUPT, "the time limit passed before the query started");
		goto done;
	}
	jsize sql_len = (*env)->GetStringLength(env, sql);
	const jchar *sql_chars = (*env)->GetStringChars(env, sql, NULL);
	if (!sql_chars) goto done;
	rc = prepare_one(db, sql_chars, sql_len, &st, &message);
	(*env)->ReleaseStringChars(env, sql, sql_chars);
	if (rc != SQLITE_OK) {
		throw_engine(env, rc, message ? message : sqlite3_errmsg(db));
		goto done;
	}

	// Every server tool runs its statements here, so this is the one place that bounds a read on a database
	// that is not in WAL mode: it holds a SHARED lock until the statement ends, and the app's commit gives
	// up after requery's 2500 ms busy timeout (see SQLITEDBG_SERVER_NON_WAL_MS). The mode is read per call.
	if (now_ms() + SQLITEDBG_SERVER_NON_WAL_MS < dl.at_ms && !is_wal_mode(db)) {
		// counted from here, after the mode check, which can wait on the app's lock
		int64_t cap_at = now_ms() + SQLITEDBG_SERVER_NON_WAL_MS;
		if (cap_at < dl.at_ms) {
			dl.at_ms = cap_at;
			dl.capped = 1;
		}
	}

	// SQLite checks the handler every 1000 virtual machine steps and stops the statement when it
	// returns non-zero; it is cleared before this returns, so it never outlives `dl`.
	sqlite3_progress_handler(db, 1000, progress_cb, &dl);
	handler_set = 1;

	rc = names ? bind_named(env, db, st, args, names, &message) : bind_positional(env, db, st, args, &message);
	if (rc != SQLITE_OK) {
		if (!(*env)->ExceptionCheck(env)) throw_engine(env, rc, message ? message : "cannot bind");
		goto done;
	}

	int ncols = sqlite3_column_count(st);
	jobjectArray columns = (*env)->NewObjectArray(env, ncols, g_string, NULL);
	// The declared type of each column, null for an expression (sqlite3_column_decltype)
	jobjectArray decltypes = columns ? (*env)->NewObjectArray(env, ncols, g_string, NULL) : NULL;
	jobject rows = decltypes ? (*env)->NewObject(env, g_array_list, g_list_init) : NULL;
	if (!rows) goto done;
	for (int c = 0; c < ncols; c++) {
		const char *name = sqlite3_column_name(st, c);
		jstring s = utf8_string(env, name ? name : "", name ? (int)strlen(name) : 0);
		if (!s) goto done;
		(*env)->SetObjectArrayElement(env, columns, c, s);
		(*env)->DeleteLocalRef(env, s);
		const char *decl = sqlite3_column_decltype(st, c);
		if (decl) {
			jstring d = utf8_string(env, decl, (int)strlen(decl));
			if (!d) goto done;
			(*env)->SetObjectArrayElement(env, decltypes, c, d);
			(*env)->DeleteLocalRef(env, d);
		}
	}

	int has_more = 0, row_count = 0;
	int64_t total = 0;
	jlong skipped = 0;
	while ((rc = sqlite3_step(st)) == SQLITE_ROW) {
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
		int64_t row_bytes = 0;
		size_t cut_before = cut_n;
		for (int c = 0; c < ncols; c++) {
			int cut;
			jobject cell = cell_value(env, st, c, max_text, max_blob, &cut, &row_bytes);
			if ((*env)->ExceptionCheck(env)) goto done;
			if (cut) {
				if (cut_n == cut_cap) {
					size_t cap = cut_cap ? cut_cap * 2 : 16;
					jlong *grown = realloc(cut_cells, cap * sizeof(jlong));
					if (!grown) {
						throw_engine(env, SQLITE_NOMEM, "out of memory");
						goto done;
					}
					cut_cells = grown;
					cut_cap = cap;
				}
				cut_cells[cut_n++] = (jlong)row_count * ncols + c;
			}
			if (cell) {
				(*env)->SetObjectArrayElement(env, row, c, cell);
				(*env)->DeleteLocalRef(env, cell);
			}
		}
		// The total bounds the host app's heap. The first row always goes in, so a page makes
		// progress; one row is bounded by the per-cell limits.
		if (row_count > 0 && total + row_bytes > max_total) {
			(*env)->DeleteLocalRef(env, row);
			cut_n = cut_before;
			has_more = 1;
			break;
		}
		total += row_bytes;
		(*env)->CallBooleanMethod(env, rows, g_list_add, row);
		(*env)->DeleteLocalRef(env, row);
		if ((*env)->ExceptionCheck(env)) goto done;
		row_count++;
	}
	if (!has_more && rc != SQLITE_DONE) {
		if (dl.hit && rc == SQLITE_INTERRUPT && dl.capped) {
			char msg[256];
			snprintf(msg, sizeof msg,
			         "the statement was stopped after %.1f s: this database is not in WAL mode, so a longer read would "
			         "block the app's own writes. Use a narrower query or a LIMIT; a database in WAL mode has no such limit",
			         SQLITEDBG_SERVER_NON_WAL_MS / 1000.0);
			throw_engine(env, SQLITE_INTERRUPT, msg);
		} else if (dl.hit && rc == SQLITE_INTERRUPT) throw_engine(env, SQLITE_INTERRUPT, "the query ran past its time limit");
		else throw_engine(env, rc, sqlite3_errmsg(db));
		goto done;
	}

	// {cut cell count, hasMore, then the index of each cut cell}
	jlongArray counts = (*env)->NewLongArray(env, (jsize)(2 + cut_n));
	result = counts ? (*env)->NewObjectArray(env, 4, g_object, NULL) : NULL;
	if (!result) goto done;
	jlong head[2] = {(jlong)cut_n, has_more};
	(*env)->SetLongArrayRegion(env, counts, 0, 2, head);
	if (cut_n) (*env)->SetLongArrayRegion(env, counts, 2, (jsize)cut_n, cut_cells);
	(*env)->SetObjectArrayElement(env, result, 0, columns);
	(*env)->SetObjectArrayElement(env, result, 1, rows);
	(*env)->SetObjectArrayElement(env, result, 2, counts);
	(*env)->SetObjectArrayElement(env, result, 3, decltypes);

done:
	if (handler_set) sqlite3_progress_handler(db, 0, NULL, NULL);
	sqlite3_finalize(st);
	free(cut_cells);
	// Backstop: a transaction left open (a writable connection, or a way past the authorizer)
	// would hold a lock on the app's database until the next query. A failure is the call's error,
	// unless the query already failed: that error is kept.
	rc = sqlitedbg_end_transaction(db);
	if (rc != SQLITE_OK) {
		char msg[256];
		snprintf(msg, sizeof msg, "a transaction was left open and rolling it back failed: %s", sqlite3_errmsg(db));
		throw_engine(env, rc, msg);
		result = NULL;
	}
	return result;
}

// Index suggestions: [candidates, then sql, indexes and plan for each statement] (sqlite3_expert_report).
static jobjectArray bridge_suggest_indexes(JNIEnv *env, jclass cls, jlong handle, jbyteArray sql_utf8, jlong deadline_ms) {
	(void)cls;
	sqlite3 *db = (sqlite3 *)(intptr_t)handle;
	deadline dl = {(int64_t)deadline_ms, 0, 0};
	sqlite3expert *ex = NULL;
	char *err = NULL;
	jobjectArray result = NULL;

	if (now_ms() >= dl.at_ms) {
		throw_engine(env, SQLITE_INTERRUPT, "the time limit passed before the analysis started");
		return NULL;
	}
	jsize len = (*env)->GetArrayLength(env, sql_utf8);
	char *text = malloc((size_t)len + 1);
	if (!text) {
		throw_engine(env, SQLITE_NOMEM, "out of memory");
		return NULL;
	}
	(*env)->GetByteArrayRegion(env, sql_utf8, 0, len, (jbyte *)text);
	text[len] = 0;
	if (memchr(text, 0, (size_t)len)) {
		free(text);
		throw_engine(env, SQLITE_ERROR, "the SQL holds a NUL character, and SQLite reads no further than it");
		return NULL;
	}
	sqlite3_progress_handler(db, 1000, progress_cb, &dl);
	int rc = sqlitedbg_expert_run(db, text, progress_cb, &dl, &ex, &err);
	sqlite3_progress_handler(db, 0, NULL, NULL);
	free(text);
	if (rc != SQLITE_OK) {
		if (dl.hit) throw_engine(env, SQLITE_INTERRUPT, "the analysis ran past its time limit");
		else throw_engine(env, rc, err ? err : sqlite3_errstr(rc));
		sqlite3_free(err);
		return NULL;
	}
	int n = sqlite3_expert_count(ex);
	result = (*env)->NewObjectArray(env, 1 + 3 * n, g_string, NULL);
	for (int i = -1; result && i < n; i++) {
		static const int kinds[] = {EXPERT_REPORT_SQL, EXPERT_REPORT_INDEXES, EXPERT_REPORT_PLAN};
		for (int k = 0; k < (i < 0 ? 1 : 3); k++) {
			const char *r = sqlite3_expert_report(ex, i < 0 ? 0 : i, i < 0 ? EXPERT_REPORT_CANDIDATES : kinds[k]);
			if (!r) continue;
			jstring js = utf8_string(env, r, (int)strlen(r));
			if (!js) {
				result = NULL;
				break;
			}
			(*env)->SetObjectArrayElement(env, result, i < 0 ? 0 : 1 + 3 * i + k, js);
			(*env)->DeleteLocalRef(env, js);
		}
	}
	sqlite3_expert_destroy(ex);
	return result;
}

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {
	(void)reserved;
	JNIEnv *env;
	if ((*vm)->GetEnv(vm, (void **)&env, JNI_VERSION_1_6) != JNI_OK) return JNI_ERR;

	g_object = global_class(env, "java/lang/Object");
	if (!g_object) return JNI_ERR;
	g_string = global_class(env, "java/lang/String");
	if (!g_string) return JNI_ERR;
	g_array_list = global_class(env, "java/util/ArrayList");
	if (!g_array_list) return JNI_ERR;
	g_long = global_class(env, "java/lang/Long");
	if (!g_long) return JNI_ERR;
	g_double = global_class(env, "java/lang/Double");
	if (!g_double) return JNI_ERR;
	g_exception = global_class(env, EXCEPTION_CLASS);
	if (!g_exception) return JNI_ERR;
	jstring utf8 = (*env)->NewStringUTF(env, "UTF-8");
	if (!utf8) return JNI_ERR;
	g_utf8_name = (jstring)(*env)->NewGlobalRef(env, utf8);
	(*env)->DeleteLocalRef(env, utf8);
	if (!g_utf8_name) return JNI_ERR;

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
	    {"setRowCapture", "(III)V", (void *)bridge_set_row_capture},
	    {"query", "(JLjava/lang/String;[Ljava/lang/Object;[[BJIIIIJ)[Ljava/lang/Object;", (void *)bridge_query},
	    {"suggestIndexes", "(J[BJ)[Ljava/lang/String;", (void *)bridge_suggest_indexes},
	    {"close", "(J)V", (void *)bridge_close},
	};
	jclass bridge = (*env)->FindClass(env, BRIDGE_CLASS);
	if (!bridge || (*env)->RegisterNatives(env, bridge, methods, sizeof methods / sizeof methods[0]) != JNI_OK)
		return JNI_ERR;
	return JNI_VERSION_1_6;
}
