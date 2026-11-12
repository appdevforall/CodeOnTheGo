# sqlite-js for apps

An AAR that lets apps built with Code on the Go define and call JavaScript functions in SQLite (ADFA-7082). It is the sqlite-js SQLite extension, which runs JavaScript with QuickJS (quickjs-ng), with our patches. Nothing in CoGo itself depends on it: the JavaScript-in-SQLite project template does.

`createSqliteJsAar` writes `build/libs/sqlite-js.aar`. The manual `release-sqlite-js.yml` workflow builds it from `main` and publishes it to the moving `sqlite-js-latest` release, from which dev-assets fetches it into the template.

## Contents

- `upstream/`: sqlite-js 1.3.7 (Apache-2.0), with QuickJS amalgamated in `libs/quickjs.c` (MIT), and `patches/` applied. Only the files the build uses, plus `LICENSE` and `LICENSE.quickjs`.
- `patches/`: our changes, in order. 0001 to 0008 are the same as the Termux package's (terminal-packages `packages/sqlite-js`). 0009 makes each call measure its own thread's stack, which a connection pool needs. 0010 adds `sqlitejs_cancel_all()`. 0001 to 0009 are sent upstream as sqliteai/sqlite-js pull requests #11 to #19; 0010 is ours.
- `src/main/cpp/`: the CMake build of `libsqlitejs.so`, and `sqlitejs_android.c`, which is ours: JNI for `SqliteJs`, and the entry point that loads stored functions. The version script exports only the two entry points and `JNI_OnLoad`.
- `src/main/java/`: `SqliteJs`.
- `src/main/resources/META-INF/`: the license notices of sqlite-js and QuickJS, which go into every app that uses the AAR.

The AAR has `libsqlitejs.so` for arm64-v8a, armeabi-v7a and x86_64, so this module has no v7/v8 flavors. Its minSdk is 26, and it does not need core-library desugaring (see `AndroidModuleConf`).

## Using it in an app

The library is an SQLite extension, so the app needs an SQLite that can load extensions. The template uses requery's `sqlite-android` under Room:

```kotlin
val factory = RequerySQLiteOpenHelperFactory(listOf(
    RequerySQLiteOpenHelperFactory.ConfigurationOptions { config ->
        config.customExtensions.add(SQLiteCustomExtension(SqliteJs.libraryPath(context), SqliteJs.ENTRY_POINT))
        config
    },
))
Room.databaseBuilder(context, AppDb::class.java, name).openHelperFactory(factory).build()
```

- Each connection has its own JavaScript runtime. `SqliteJs.ENTRY_POINT` loads the functions stored in the database's `js_functions` table (see `js_init_table`) on every connection, but a connection that opened before a function was stored does not have it. So store the functions before Room opens the database, on a connection opened with `SqliteJs.ENTRY_POINT_FOR_DEFINING`, which does not load them, since a connection cannot redefine a function it has.
- Room checks queries at build time with its own SQLite, which has no JavaScript functions: mark DAO methods that call them `@SkipQueryVerification`.
- SQLite loads the extension from a file, so set `packaging.jniLibs.useLegacyPackaging = true`.
- Neither Room nor Android's cancelling can stop a JavaScript function that never returns: Room 2.7 and later pass no cancellation signal, and Android cancels with a progress handler, which SQLite never calls while a function runs. Call `SqliteJs.cancelAll()` when the app gives up on a query, for example when its coroutine is cancelled. JavaScript running at that moment fails with "interrupted"; JavaScript started afterwards runs normally.

Tested in a trial app (requery 3.49.0, Room 2.8.4) on a Galaxy Note20 Ultra, as a 64-bit and as a 32-bit process, on a new and an existing database, with Room's default write-ahead logging: a scalar and an aggregate from DAOs, deep recursion from four pool threads, 32 parallel queries, and a runaway query stopped by `cancelAll()` within 5 ms.

## Updating

`vendor.sh` rebuilds `upstream/` and the license notices from the sqlite-js release (checked against its SHA-256) and `patches/`. `vendor.sh --check` tells whether they match. To move to a new sqlite-js release, change `VERSION` and `SHA256` in `vendor.sh`, drop the patches upstream has released, refresh the rest, and run `vendor.sh`.
