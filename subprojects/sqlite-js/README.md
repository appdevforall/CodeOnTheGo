# sqlite-js for apps

An AAR that lets apps built with Code on the Go define and call JavaScript functions in SQLite (ADFA-7082). It is the sqlite-js SQLite extension, which runs JavaScript with QuickJS (quickjs-ng), with our patches. Nothing in CoGo itself depends on it: the JavaScript-in-SQLite project template does.

`createSqliteJsAar` writes `build/libs/sqlite-js.aar`. The manual `release-sqlite-js.yml` workflow builds it from `main` and publishes it to the moving `sqlite-js-latest` release, from which dev-assets fetches it into the template.

## Contents

- `upstream/`: the sqlite-js 1.4.1 release (Apache-2.0), the first with our earlier changes (upstream pull requests #11 to #19), with QuickJS amalgamated in `libs/quickjs.c` (MIT), and `patches/` applied. Only the files the build uses, plus `LICENSE` and `LICENSE.quickjs`.
- `patches/`: our changes, none of them upstream yet:
  - 0001 adds `sqlitejs_cancel_all()`: Android cannot otherwise stop JavaScript that never returns. It is offered upstream in sqliteai/sqlite-js issue 20.
  - 0002 keeps QuickJS's stack check within the thread's stack in two cases where code that called itself without end crashed the process: a thread with less than 64 KB of stack left, and a main thread first measured deep in its stack.
  - 0003 collects garbage after any "out of memory" error and whenever a memory limit is set. Before, garbage could leave an allocation that fits in the limit failing on every try.
- `src/main/cpp/`: the CMake build of `libsqlitejs.so`, and `sqlitejs_android.c`, which is ours: JNI for `SqliteJs`, and the two entry points it names. The version script exports only the entry points (and upstream's `sqlite3_js_init`) and `JNI_OnLoad`.
- `src/test/c/glue_test.c` and `test.sh`: the tests the Android build cannot run. See Testing.
- `src/main/java/`: `SqliteJs`.
- `src/main/resources/META-INF/`: the license notices of sqlite-js and QuickJS, which go into every app that uses the AAR.

The AAR has `libsqlitejs.so` for arm64-v8a and armeabi-v7a, the ABIs of the phones apps built with CoGo run on, in one artifact, so this module has no v7/v8 flavors. Its minSdk is 26, and it does not need core-library desugaring (see `AndroidModuleConf`).

## Using it in an app

The library is an SQLite extension, so the app needs an SQLite that can load extensions. The template uses requery's `sqlite-android` under Room:

```kotlin
val factory = RequerySQLiteOpenHelperFactory(listOf(
    RequerySQLiteOpenHelperFactory.ConfigurationOptions { config ->
        // the app's own database, so its stored functions may load: see below
        config.customExtensions.add(SQLiteCustomExtension(SqliteJs.libraryPath(context), SqliteJs.ENTRY_POINT_FOR_TRUSTED_DATABASE))
        config
    },
))
Room.databaseBuilder(context, AppDb::class.java, name).openHelperFactory(factory).build()
```

- `SqliteJs.ENTRY_POINT` loads sqlite-js and runs nothing stored in the database, so it is safe for any database file.
- `SqliteJs.ENTRY_POINT_FOR_TRUSTED_DATABASE` also loads the functions stored in the database's `js_functions` table (see `js_init_table`), on every connection, since each connection has its own JavaScript runtime. Loading a stored function runs its code, which can run SQL and read the app's files, so use it only for a database the app made and keeps. Open a downloaded or shared file, or a restored backup, with `ENTRY_POINT`: with the trusted entry point, its stored code would run as soon as the app opened it. A stored function that fails to load is logged (`sqlite3_log`) and skipped, and the rest still load. One whose loading `cancelAll()` stops (see below) is loaded again, up to three tries, and its stored code runs again each time.
- A connection that opened before a function was stored does not have it. So store the functions before Room opens the database, on a connection opened with `ENTRY_POINT`, since a connection cannot redefine a function it has.
- Room checks queries at build time with its own SQLite, which has no JavaScript functions: mark DAO methods that call them `@SkipQueryVerification`.
- SQLite loads the extension from a file, so set `packaging.jniLibs.useLegacyPackaging = true`.
- Neither Room nor Android's cancelling can stop a JavaScript function that never returns: Room 2.7 and later pass no cancellation signal, and Android cancels with a progress handler, which SQLite never calls while a function runs. `SqliteJs.cancelAll()` stops the JavaScript running at that moment, in every query on every connection: each call fails with "interrupted", and its statement is interrupted too, so that one whose JavaScript cannot report an error (a collation) also stops. That includes calls in queries the app did not mean to stop, which then need to be retried. An interrupted `INSERT`, `UPDATE` or `DELETE` inside an explicit transaction rolls the whole transaction back, so retry the transaction, not only the statement. So call it only when a runaway function is known to be running, for example from a Cancel button, not whenever a coroutine is cancelled. It stops only JavaScript that has started, so call it again until the query being stopped ends, as the template's `cancellable()` does. It does not stop the JavaScript sqlite-js runs while it sets up a new connection. It takes effect when JavaScript next runs: SQL that the JavaScript runs itself, through `db.exec` (a long `next()` or `toArray()`), finishes before the cancel reaches it.

Built from 1.3.7 with our changes as patches, it was tested in a trial app (requery 3.49.0, Room 2.8.4) on a Galaxy Note20 Ultra, as a 64-bit and as a 32-bit process, on a new and an existing database, with Room's default write-ahead logging: a scalar and an aggregate from DAOs, deep recursion from four pool threads, 32 parallel queries, and a runaway query stopped by `cancelAll()` within 5 ms.

## Testing

The Android build compiles but does not run sqlite-js. `test.sh` builds it on the host and runs upstream's test suite (`test/main.c`, which is not vendored) on its source with `patches/` applied, and `src/test/c/glue_test.c` on `upstream/` and `sqlitejs_android.c`: what each entry point loads and runs, that a stored function that fails to load is skipped, and that one whose loading is cancelled loads on a second try. It needs gcc, make and a JDK (for `jni.h`). The `test-sqlite-js.yml` workflow runs `vendor.sh --check` and `test.sh` on every pull request that changes this module.

## Updating

`vendor.sh` rebuilds `upstream/` and the license notices from the source archive of the sqlite-js release `VERSION` (checked against its SHA-256) and `patches/`. `vendor.sh --check` tells whether they match. To move to a new release, change `VERSION` and `SHA256` in `vendor.sh`. For a commit between releases, point `URL` at the commit's archive (`archive/<commit>.tar.gz`) and name `VERSION` as the Termux package does; the scripts take the archive's top directory, whatever it is named. Drop a patch once upstream has it, refresh the rest, and run `vendor.sh`.
