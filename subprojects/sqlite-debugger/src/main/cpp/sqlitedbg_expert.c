// SQLite's ext/expert (expert/, from SQLite 3.49.1, unchanged; public domain like the rest of SQLite),
// compiled into the extension for suggest_indexes. Like sqlitedbg.c it reaches SQLite through the api
// table (sqlite3ext.h), so it runs on requery's SQLite. Included rather than compiled on its own so
// that the progress handler can be set on expert's own connections (p->dbv, p->dbm).
#include "sqlite3ext.h"
SQLITE_EXTENSION_INIT3

#include "expert/sqlite3expert.c"
#include "sqlitedbg.h"

int sqlitedbg_expert_run(sqlite3 *db, const char *sql, int sample, int (*progress)(void *), void *arg,
                         sqlite3expert **out, char **errmsg) {
	*out = NULL;
	*errmsg = NULL;
	// expert opens two in-memory connections; the extension must not list or trace them as the app's
	sqlitedbg_private_open(1);
	sqlite3expert *p = sqlite3_expert_new(db, errmsg);
	sqlitedbg_private_open(0);
	if (!p) return SQLITE_ERROR;
	sqlite3_progress_handler(p->dbv, 1000, progress, arg);
	sqlite3_progress_handler(p->dbm, 1000, progress, arg);
	// Sampling reads that percentage of the rows of every table with a candidate index, on db, and registers
	// sqlite_expert_sample on db for the run, and at 100 sqlite_expert_rem too (expert removes them after); the
	// temp table is made on expert's own connection p->dbv. At 0 nothing of the app's
	// data is read, and suggestions rest on SQLite's default estimates.
	int rc = sqlite3_expert_config(p, EXPERT_CONFIG_SAMPLE, sample < 0 ? 0 : sample > 100 ? 100 : sample);
	// The handler runs every 1000 VM steps, which a small schema may never reach: check between steps too.
	if (rc == SQLITE_OK) rc = progress(arg) ? SQLITE_INTERRUPT : sqlite3_expert_sql(p, sql, errmsg);
	if (rc == SQLITE_OK) rc = progress(arg) ? SQLITE_INTERRUPT : sqlite3_expert_analyze(p, errmsg);
	if (rc != SQLITE_OK) {
		sqlite3_expert_destroy(p);
		return rc;
	}
	*out = p;
	return SQLITE_OK;
}
