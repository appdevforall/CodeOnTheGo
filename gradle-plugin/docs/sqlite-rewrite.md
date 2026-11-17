# SQLite rewrite (ADFA-7138)

When `cogo.sqliteDebugger=true`, the Gradle plugin registers an AGP class transform
(`com.itsaky.androidide.gradle.sqlite.SqliteRewriteVisitorFactory`, scope ALL, every variant) that moves
the app's and its libraries' classes from `android.database.sqlite` to requery 3.49.0, so debug and release
run the same SQLite. User source does not change.

## Turning it on

An existing project needs:

1. `cogo.sqliteDebugger=true` in `gradle.properties`. The plugin reads it with Gradle's `findProperty`, so the
   project's root file, the app module's own `gradle.properties` and the Gradle user home's
   (`GRADLE_USER_HOME`, `~/.gradle` under Code on the Go's home) all work, the user home first. The IDE's Quick
   Build check (`QuickBuildProjectSupport.isSqliteDebuggerEnabled`) reads those same three files, in that order,
   and logs a file it cannot read; it does not see `-P` arguments, which Code on the Go does not pass for this.
2. requery's AAR (`sqlite-android.aar`) in the app's `libs/`, as a file dependency
   (`implementation(files("libs/sqlite-android.aar"))`), as the ADFA-7082 template does.
3. androidx.sqlite and androidx.core on the runtime classpath (`androidx.sqlite:sqlite`, which Room, WorkManager
   and most androidx libraries bring, and `androidx.core:core`, which AppCompat brings). A file dependency brings
   none of the dependencies of the AAR: requery implements androidx.sqlite's interfaces, takes and calls
   androidx.core's `CancellationSignal` (the rewrite converts android's signal to it) and throws its
   `OperationCanceledException`, and its statement cache extends androidx.collection's `LruCache`, which
   androidx.core brings.

The helper classes the rewrite redirects to (`SqliteRewriteRuntime`) are not in `libs/`: Code on the Go bundles
`subprojects/sqlite-rewrite-runtime`'s release AAR, extracts it with the other tools and passes its path as
`androidide.sqliteRewriteRuntime.aar`, as it does for the debugger AAR. `SqliteRewritePlugin` adds it to the
runtime classpath of every variant of a project that opted in, and fails the build when the file is missing or
when a variant's runtime classpath cannot be reached (a variant type the plugin does not know, named in the message):
the rewrite is already registered for it, so going on would give an app that throws `NoClassDefFoundError`.
`SqliteDebuggerPlugin` fails the same way for the variants it adds the debugger to.

A task `check<Variant>SqliteRewriteClasspath` runs before the compile and the transform of each variant. It fails
with an actionable message when the variant's runtime classpath has no
`io/requery/android/database/sqlite/SQLiteDatabase.class`, `androidx/sqlite/db/SupportSQLiteDatabase.class` or
`androidx/core/os/CancellationSignal.class`.

The property is required because requery reaches apps only through `libs/`. Without it the rewritten classes
cannot load, so the transform runs only on projects that opted in. Commenting the line out turns it off.

## Rules

- Rename: the 10 classes requery shares (`SQLiteClosable`, `SQLiteCursor`, `SQLiteCursorDriver`,
  `SQLiteDatabase`, `SQLiteDatabase$CursorFactory`, `SQLiteOpenHelper`, `SQLiteProgram`, `SQLiteQuery`,
  `SQLiteQueryBuilder`, `SQLiteStatement`) and `android.database.DatabaseErrorHandler` and
  `DefaultDatabaseErrorHandler`. androidx's `FrameworkSQLiteOpenHelperFactory` is not renamed; see the design
  change below. Kept: the exceptions, `SQLiteTransactionListener`, `android.database.Cursor`, `CursorWindow`.
  Overrides count: a `SQLiteOpenHelper` subclass or a `SQLiteCursorDriver`/`CursorFactory` implementation
  ends up with requery's descriptors.
- Descriptor: `String[]` becomes `Object[]` (selectionArgs) for `query`, `queryWithFactory`, `rawQuery`,
  `rawQueryWithFactory` and `SQLiteCursorDriver.query`, in calls and in the override declaration.
- Redirect to `org.appdevforall.sqliterewrite.SqliteRewriteRuntime`: `SQLiteDatabase.openDatabase` with
  flags (Android's flag values differ from requery's; the flags are compiled into the app),
  `Context.openOrCreateDatabase` / `deleteDatabase` (INVOKEVIRTUAL on `Context` or a subclass; subclasses
  are found with AGP's class loader, plus a fixed list of the framework ones), and an
  `android.os.CancellationSignal` argument of a requery-bound call, converted right before the call (it
  is always the last argument), and `android.database.DatabaseUtils.queryNumEntries`, `longForQuery` and
  `stringForQuery` (every overload; requery has no `DatabaseUtils`, so the runtime carries Android's own
  implementations, written against Android's types).
- Fail the build, naming class, method and line: a call to a member requery lacks (for example
  `execPerConnectionSQL`, `setCustomScalarFunction`, `SQLiteQueryBuilder.isStrict`; also the deprecated cursor
  members `SQLiteCursor` inherits from android's `AbstractCursor` and `AbstractWindowedCursor` that requery's
  copies lack: `isBlob`, `isFloat`, `isLong`, `isString`, `getUpdatedField`, `isFieldUpdated`,
  `getNotificationUris`, `setNotificationUris`, and the protected field `mWindow`; `SqliteRewriteResolutionTest` probes the inherited members of every
  renamed class against requery), SQL text with `COLLATE UNICODE` (any case or whitespace, in any string
  constant, which covers Room's generated `_Impl` classes: requery's native SQLite has no ICU, so the collation
  does not exist), any use of
  `SQLiteDatabase.OpenParams`, a call that passes android's `CursorWindow` to a renamed class (calls on
  `CursorWindow` itself are fine), and a cast of
  an SQLite cursor to `android.database.AbstractWindowedCursor`, `AbstractCursor` or `CrossProcessCursor`
  (requery's cursors extend its own copies of the first two, and implement only `android.database.Cursor`).
  A cast guarded by `instanceof` of the same type on the same local (`if (c instanceof X) ((X) c)`, `&&`, `?:`,
  or an early return when it is false) is not reported. `COLLATE LOCALIZED` only logs a warning: it works, but
  compares bytes (no ICU) instead of sorting by locale. All violations of a class are reported together, with
  the way out for a library class, which the user cannot change: comment out `cogo.sqliteDebugger=true`. A platform method outside the renamed set whose
  descriptor mentions a renamed class (found with javap over android.jar: the other `DatabaseUtils` methods
  `blobFileDescriptorForQuery` and `bindObjectToProgram`, `DatabaseUtils$InsertHelper`, `OpenParams`
  accessors) cannot link once the descriptor is renamed, so a call to one that is not redirected above fails
  the build. `SqliteRewriteResolutionTest` probes every such member in android.jar.
  A `Context.openOrCreateDatabase` / `deleteDatabase` call that cannot be redirected also fails the build:
  `super.` calls (INVOKESPECIAL), calls whose owner class cannot be resolved (it might be a Context), and
  a method with one of those signatures that overrides it in a Context subclass (the redirect would skip
  the override).
- Method references: a handle in an invokedynamic (`db::rawQuery`, `ctx::deleteDatabase`) that any rule
  above would change, redirect or reject fails the build, naming class, method and line, with the advice to
  write a lambda (its body is an ordinary call, which the rules handle). The rules are not applied to the
  handle itself: it cannot be redirected in place, and its descriptor is fixed by the call site.
- Frames: `COPY_FRAMES`, AGP's default. The one change that breaks them is a retyped
  `SQLiteCursorDriver.query` implementation, whose `String[]` parameter becomes `Object[]` while its body, and
  the full frames copied from it, still say `String[]`. For each non-abstract method whose descriptor is retyped
  that way, the rewriter inserts `ALOAD n; CHECKCAST [Ljava/lang/String;; ASTORE n` at method entry, so the body
  stays valid, and writes every frame of that method in full (compressed frames are relative to the implicit first
  frame, which now has the wide type). A driver that is handed an `Object[]` with an element that is not a
  `String` throws `ClassCastException` on entry; requery's own drivers pass strings.

Excluded: `io.requery.**`. The helper's package is renamed only (a redirect would call itself).
`androidx.sqlite.db.framework.**` and `androidx.core.database.**` are rewritten but exempt from the fail rule: see
below, and androidx.core's `SQLiteCursorCompat$Api28Impl` calls `SQLiteCursor.setFillWindowForwardOnly`, which requery
lacks, so failing the build for it would fail every app that depends on androidx.core (found by the transform test
on androidx.core 1.9.0). Calling `SQLiteCursorCompat.setFillWindowForwardOnly` on requery's cursor throws
`NoSuchMethodError`.

## Survey: what reaches Android's SQLite (androidx.sqlite framework 2.6.2, Room 2.8.4, WorkManager 2.10.0)

| Where | Reference | Handling |
| --- | --- | --- |
| `RoomDatabase$Builder.build`, `PrePackagedCopyOpenHelper`, WorkManager's `WorkDatabase$Companion` | `new FrameworkSQLiteOpenHelperFactory` | kept: androidx's factory runs on requery through the renamed classes (design change below) |
| `FrameworkSQLiteDatabase`, `FrameworkSQLiteOpenHelper$OpenHelper`, `FrameworkSQLiteProgram`/`Statement` | every member of the renamed classes they use | all resolve in requery after the rules; `SqliteRewriteResolutionTest` transforms the real 2.6.2 framework classes, verifies them and resolves every reference (access and static-ness included) |
| `FrameworkSQLiteDatabase$Api30Impl` | `execPerConnectionSQL` (NO in requery's own class) | the only reference to a member requery lacks; it resolves to `SupportSQLiteDatabase`'s default method, which throws `UnsupportedOperationException` |
| `FrameworkSQLiteDatabase.query(.., CancellationSignal)` | `rawQueryWithFactory` with android's signal | signal converted |
| `FrameworkSQLiteOpenHelper$OpenHelper`, `PrePackagedCopyOpenHelper` | `Context.deleteDatabase`, `getDatabasePath` | redirect (`deleteDatabase`); `getDatabasePath` needs none |
| Room `DBUtil.query` | `instanceof` and cast to `AbstractWindowedCursor`, `CursorWindow.getNumRows` | untouched: the value comes from `SupportSQLiteDatabase.query`, not an SQLite API, and the cast is guarded by `instanceof`, which is false for requery's cursor (Room skips its copy optimisation) |
| Room `checkUniquenessException` | `SQLiteConstraintException` | kept class |
| `androidx.sqlite.driver.AndroidSQLiteDriver.open` | `SQLiteDatabase.openOrCreateDatabase(String, null)` and `new FrameworkSQLiteDatabase(SQLiteDatabase)` | works only if `FrameworkSQLiteDatabase` is rewritten too |

### Design change (2026-11-13): androidx's factory stays

The first version renamed `new FrameworkSQLiteOpenHelperFactory()` to requery's `RequerySQLiteOpenHelperFactory`
and excluded the factory class. That is dropped. requery's factory reads only the context, the name and the
callback of the `SupportSQLiteOpenHelper.Configuration` (checked with javap): it ignores `noBackupDirectory` and
`allowDataLossOnRecovery`, and has none of the framework's cross-process lock. WorkManager 2.10 sets the first
two, so its database would be recreated empty after `WorkDatabasePathHelper.migrateDatabase` on each start.

Instead the androidx.sqlite framework classes (`androidx.sqlite.db.framework.*`, rewritten with the fail rule
off) run on requery through the normal rename and descriptor rules: `FrameworkSQLiteOpenHelper`, its inner
`OpenHelper` (a subclass of the renamed `SQLiteOpenHelper`), `FrameworkSQLiteDatabase`, `-Statement` and
`-Program` keep their behaviour, including the no-backup directory and the recovery flags, and call requery's
classes. They are not excluded, which also keeps `androidx.sqlite.driver.AndroidSQLiteDriver` working: it builds a
`FrameworkSQLiteDatabase` from a plain `SQLiteDatabase`.

Every reference of these classes resolves in requery. The one member requery's own `SQLiteDatabase` lacks is
`execPerConnectionSQL`, which `FrameworkSQLiteDatabase$Api30Impl` calls; it resolves to the default method of
`SupportSQLiteDatabase` (checked with javap), which throws `UnsupportedOperationException`. So
`execPerConnectionSQL` through the `SupportSQLiteDatabase` interface stays unsupported, and the fail rule is off for
this package, so the build does not report it.

## Checking a release APK

`scripts/check-release-apk-no-sqlite-debugger.sh <apk>` fails when an APK carries the debugger (native library,
provider, `<queries>` entry or classes). QA runs it on the release APK of an opted-in project: the release build must never contain the debugger.

## Known limits

- Room's `BundledSQLiteDriver` is not covered (it brings its own SQLite).
- requery's native library is built without ICU: there is no `UNICODE` collator (a `COLLATE UNICODE` string
  constant fails the build, see above) and `LOCALIZED` compares bytes (a warning).
- `SupportSQLiteDatabase.execPerConnectionSQL` is unsupported (see the design change): no build failure, an
  `UnsupportedOperationException` when called.
- Passing an Android `CursorWindow` where a (renamed) `SQLiteClosable` is expected cannot work: android's
  `CursorWindow` extends android's `SQLiteClosable`, which stays the platform's class. Calls that name a
  `CursorWindow` in a renamed class's descriptor fail the build; calls on a `CursorWindow` are left alone.
- The items below were reported by review and not verified here: `ATTACH` on a WAL database (requery runs it on
  one connection only; Android turns write-ahead logging off on the first `ATTACH`); a cancelled query throws
  `androidx.core.os.OperationCanceledException`, not `android.os.OperationCanceledException`; page size and
  `wal_autocheckpoint` defaults can differ from the platform's; there is no compatibility WAL mode; the cursor
  window is a fixed 2 MB, and a row larger than it throws `IllegalStateException`; `beginTransactionReadOnly`
  is not in requery (it fails the build) and its read-only semantics have no counterpart; requery has no
  `StrictMode` hooks, so StrictMode does not report database access on the main thread.
- Quick Build is not applied to a project with `cogo.sqliteDebugger=true`: its hot payload is compiled by
  the Quick Build daemon from the project's source (and its baseline payload is diverted out of the classes
  pipeline), so those classes would still use `android.database.sqlite` next to rewritten baseline classes.
  The plugin logs why and the build is a normal one. The IDE does not provision Quick Build for such a project
  either (`QuickBuildProjectSupport.sqliteDebuggerMessage`): it shows `quick_build_disabled_sqlite_debugger`, which
  says Quick Build is off while the SQLite debugger is on.
- Classes loaded by name (`Class.forName`, reflection, strings naming `android.database.sqlite.*`) are not
  rewritten.
- The `AbstractWindowedCursor` check follows the cursor through locals and the stack only. A cast of a
  cursor that reached the method through a field, a parameter or another call is not seen (the app throws
  `ClassCastException` at run time), and a cast of a cursor from an SQLite API to a type that is not an
  SQLite cursor cannot be a false positive.
- Compile-time constants are copied into callers, so uses of `NO_LOCALIZED_COLLATORS`, `JOURNAL_MODE_*` or `SYNC_MODE_*` are
  not visible to the fail rule. `NO_LOCALIZED_COLLATORS` is dropped by the flag translation.
- The file permission bits of the `mode` of `Context.openOrCreateDatabase` are not applied.
- A direct subclass of `SQLiteQueryBuilder` or `SQLiteCursor` that overrides a member requery lacks is not
  reported.
- `instanceof` is not rewritten: `cursor instanceof AbstractWindowedCursor`, `AbstractCursor` or
  `CrossProcessCursor` is silently false for requery's cursors (no build failure), which is what Room's
  `DBUtil.query` relies on.
- Android's `CancellationSignal` holds one listener: the conversion replaces any listener the caller set.
- Which `SQLiteException` subclass requery throws for an error code (for example `SQLiteConstraintException`, which
  Room catches) is decided by its native layer; this rewrite does not change it and the survey did not check it.
