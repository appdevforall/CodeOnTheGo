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
  The same conversion without a cast fails too: a value declared `SQLiteCursor`, or a subclass, returned, passed,
  called on, stored in a field, or held in a local as one of those three types (`AbstractCursor up(SQLiteCursor c)
  { return c; }`). A row loaded from an `SQLiteCursor[]` (or an array of a subclass) counts the same way
  (`AbstractCursor up(SQLiteCursor[] cs) { return cs[0]; }`). javac writes no `CHECKCAST` for it, and the renamed class would not verify. So does storing one
  into an array of those types (`AbstractCursor[] a; a[0] = c;`), which would throw `ArrayStoreException`: the
  array must be one on every path to the store where it is not `null`, from a parameter, field, call or `new`, or
  loaded from an array of them (`AbstractCursor[][] g; g[0][0] = c;`), and the value an SQLite cursor on every path
  where it is not `null`. A value that is one on some paths only (`Object v = b ? c : matrixCursor;`) logs a
  warning with class, method and line instead, once per place: the check does not match the paths of the value with
  those of the array, so it cannot tell whether one path brings both
  (`if (useDb) v = c; else { a = cache; v = m; } if (a != null) a[0] = v;` never throws).
  A cast guarded by `instanceof` of the same type on the same local (`if (c instanceof X) ((X) c)`, `&&`, `?:`,
  or an early return when it is false) is not reported when the guard holds on every path to the cast: in
  `if (b || c instanceof X) ((X) c)` the cast is reached with `b` alone, so it is reported. `COLLATE LOCALIZED` only logs a warning: it works, but
  compares bytes (no ICU) instead of sorting by locale. All violations of a class are reported together, with
  the way out for a library class, which the user cannot change: comment out `cogo.sqliteDebugger=true`. A platform method outside the renamed set whose
  descriptor mentions a renamed class (found with javap over android.jar: the other `DatabaseUtils` methods
  `blobFileDescriptorForQuery` and `bindObjectToProgram`, `DatabaseUtils$InsertHelper`, `OpenParams`
  accessors) cannot link once the descriptor is renamed, so a call to one that is not redirected above fails
  the build. `SqliteRewriteResolutionTest` probes every such member in android.jar.
  A `Context.openOrCreateDatabase` / `deleteDatabase` call that cannot be redirected also fails the build:
  `super.` calls (INVOKESPECIAL), calls whose owner class cannot be resolved (it might be a Context), and
  a method with one of those signatures that overrides it in a Context subclass (the redirect would skip
  the override). So does an override of `SQLiteQueryBuilder.query` with a `CancellationSignal` in a subclass: its
  calls get requery's descriptor while the override keeps Android's, so they would reach requery's method and skip
  it. Retyping the override would mean turning requery's signal back into Android's inside its body.
  Likewise a call on an owner that AGP's class loader cannot resolve fails when its name and descriptor are those of
  a member a rule above changes or rejects in a renamed class another class can extend (not the final
  `SQLiteDatabase`, `SQLiteQuery` or `SQLiteStatement`) and the descriptor names an SQLite-related type (any
  `android.database.sqlite` or requery type, `Cursor`, `CancellationSignal`, `DatabaseErrorHandler`): the owner may
  inherit that member, and its renamed ancestors are unknown. When the descriptor names none (`isStrict()Z`,
  `isString(I)Z`, `setWindow(CursorWindow)`, `mWindow`), the call or field access only logs a warning with class,
  method and line: libraries reference optional classes the app does not ship, and failing every app build over them
  would be worse than missing the case. `CursorWindow` is not an SQLite type: it carries every ContentProvider cursor
  across processes, and android's kept `AbstractWindowedCursor` declares `setWindow(CursorWindow)` and `mWindow` too,
  so a library's own windowed cursor matches them.
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
  The mode is per variant, but `COPY_FRAMES` cannot weaken another instrumentation in the same build (Hilt, Firebase,
  an app's own `transformClassesWith`) that needs frames computed: `setAsmFramesComputationMode` keeps the highest
  mode any plugin asks for (AGP 7.3.0, 8.8.2 and 9.3.1 compare the enum ordinals; checked with javap), and
  `COPY_FRAMES` is the lowest. Checked once (2026-11-17, AGP 9.3.1, not a standing test) in the order a last-call-wins
  rule would break: CoGo applies its plugin in `afterEvaluate`, so its `onVariants` runs after the app's. The
  functional tests' sample app, opted in, got a `SQLiteOpenHelper` subclass, a `SQLiteCursorDriver` and a second
  `AsmClassVisitorFactory` (scope ALL) that inserts `if (0 != 0) throw null` at the start of every method but
  constructors of the app, androidx.sqlite and Room, and asks for `COMPUTE_FRAMES_FOR_INSTRUMENTED_METHODS`.
  demoDebug and demoRelease assembled (D8, R8). Their transformed classes, 232 each, pass ASM's `CheckClassAdapter`
  and the JVM's verifier (each class linked against android.jar and the instrumented dependency jars), except Room's
  `LimitOffsetDataSource`, which cannot load because the app has no `androidx.paging`. `Driver.query` carries the
  `CHECKCAST` prologue and the branch, with a computed frame at the branch target. The control, the same build
  without the request for computed frames, fails the JVM's verifier (`Expecting a stackmap frame at branch
  target`), so the check does see stale frames.

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
provider, `<queries>` entry or classes). The release build must never contain the debugger. R8 renames and drops
classes in a minified release, so the class check can miss them; the manifest and native-library checks are the
ones that hold.

`:gradle-plugin:sqliteReleaseApkTest` (`SqliteReleaseApkTest`) runs it on every push, in the `build_apk` job of
`.github/workflows/debug.yml`. It builds the minified release and the debug variant of the functional tests' sample
app, opted in, with the debugger and rewrite runtime AARs this repo builds and requery 3.49.0 from JitPack as a file
dependency, and runs the script on both APKs. The release must pass. The debug APK must fail on the provider and on
`libsqlitedbg.so`, which shows the check still sees what the AAR brings. The task builds the AARs it needs, so
`:gradle-plugin:test` leaves this test out. QA can still run the script on any release APK.

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
  one connection only; Android turns write-ahead logging off on the first `ATTACH`); page size and
  `wal_autocheckpoint` defaults can differ from the platform's; there is no compatibility WAL mode; the cursor
  window is a fixed 2 MB, and a row larger than it throws `IllegalStateException`; `beginTransactionReadOnly`
  is not in requery (it fails the build) and its read-only semantics have no counterpart; requery has no
  `StrictMode` hooks, so StrictMode does not report database access on the main thread.
- Quick Build is not applied to a project with `cogo.sqliteDebugger=true`: its hot payload is compiled by
  the Quick Build daemon from the project's source (and its baseline payload is diverted out of the classes
  pipeline), so those classes would still use `android.database.sqlite` next to rewritten baseline classes.
  The plugin logs why and the build is a normal one. The IDE does not provision Quick Build for such a project
  either (`GradleQuickBuildProvisioner.sqliteDebuggerRefusal`): it shows `quick_build_disabled_sqlite_debugger`, which
  says Quick Build is off while the SQLite debugger is on. The editor's status line then reads
  `quick_build_status_off_sqlite_debugger`, and a tap on it does what a tap on the Quick Build button does: it
  shows that message again, or, while the button is greyed out (a release variant, a plugin project), why it is.
- The array-store check is path-insensitive, so it gives a false positive when the cursor is null on exactly the path where
  the array is set (`a = null; c = null; if (b) a = p; else c = query(); a[0] = c` fails the build although the store
  never runs with both set).
- Classes loaded by name (`Class.forName`, reflection, strings naming `android.database.sqlite.*`) are not
  rewritten. A string constant that names one of the renamed classes (dots or slashes, `$` or `.` before a nested
  class, alone or in a type descriptor) logs a warning with class, method and line, in the classes the fail rule
  covers; names of kept classes such as `SQLiteException`, and longer names that contain a renamed one, such as
  `xandroid.database.sqlite.SQLiteDatabase`, do not.
- Stores of an SQLite cursor into an array known only as a `Cursor[]` or `Object[]` are allowed on purpose (failing
  them would fail ordinary apps), but such an array can be an `AbstractCursor[]` at run time (arrays are covariant),
  and a store whose array type differs between branches that merge is not checked (`null` on a branch counts as
  no difference). Both can still throw `ArrayStoreException`, in release builds too (the rewrite runs in every
  variant of an opted-in app). So can a store whose value is an SQLite cursor on some paths only, which logs a
  warning and builds (see Rules): it throws when one path brings both the cursor and the array. An element
  loaded from an `SQLiteCursor[]` (or an array of a subclass) counts as an SQLite cursor, and a row of an
  `SQLiteCursor[][]` is followed as an `SQLiteCursor[]`, at any depth (so `return g[0][0]` as an `AbstractCursor`
  fails the build); rows of other arrays are not followed, and one counts as a value that is not an SQLite cursor.
  That includes an array that is an `SQLiteCursor[]` on one branch only (an `Object[]` or an `SQLiteCursor[][]` on
  the other), except that when the analysis reaches the `SQLiteCursor[]` branch first, its row still counts as an
  SQLite cursor on some paths, so storing it logs that warning; with the branches swapped it logs nothing.
- An array of `SQLiteCursor`, or of a subclass, used without a cast as an array of `AbstractCursor`,
  `AbstractWindowedCursor` or `CrossProcessCursor` (`AbstractCursor[] a = new SQLiteCursor[1];`; arrays are
  covariant) is not reported. After the rename it is an array of requery's `SQLiteCursor`, which is none of those,
  so a class that passes or returns it as one, or stores it in a field of that type, does not verify. Stored into an
  array of those arrays (`AbstractCursor[][] g; g[0] = s;`), it verifies, and the store throws `ArrayStoreException`
  at run time.
- The `AbstractWindowedCursor` check follows the cursor through locals and the stack only. A cast of a
  cursor that reached the method through a field, a parameter or another call is not seen (the app throws
  `ClassCastException` at run time), and a cast of a cursor from an SQLite API to a type that is not an
  SQLite cursor cannot be a false positive.
- Compile-time constants are copied into callers, so uses of `NO_LOCALIZED_COLLATORS`, `JOURNAL_MODE_*` or `SYNC_MODE_*` are
  not visible to the fail rule. `NO_LOCALIZED_COLLATORS` is dropped by the flag translation.
- The file permission bits of the `mode` of `Context.openOrCreateDatabase` are not applied.
- A direct subclass of `SQLiteQueryBuilder` or `SQLiteCursor` that overrides a member requery lacks is not
  reported.
- On a `SQLiteCursor` subclass whose class the build cannot resolve, `getWindow()` is not detected, and
  `setWindow(CursorWindow)` and `mWindow` only log a warning; each fails at run time. `getWindow()` is not in the rule
  table, and failing every unresolved member that names a `CursorWindow` would fail ordinary apps (android's kept
  `AbstractWindowedCursor` declares all three, and `CrossProcessCursor` declares `getWindow()`). On any class the
  build cannot resolve, a call that matches the rule table fails the build only when its descriptor names an
  SQLite-related type (see Rules); any other match, such as `isString(I)Z`, `isStrict()Z` or `setWindow(CursorWindow)`,
  logs a warning with class, method and line and is left as it is, and fails at run time if the class does extend the
  renamed class.
- `instanceof` is not rewritten: `cursor instanceof AbstractWindowedCursor`, `AbstractCursor` or
  `CrossProcessCursor` is silently false for requery's cursors (no build failure), which is what Room's
  `DBUtil.query` relies on.
- A cancelled query throws `androidx.core.os.OperationCanceledException`, not
  `android.os.OperationCanceledException`: requery checks androidx.core's signal, which the rewrite converts Android's
  to, and its native layer throws the same class (checked with javap and in requery 3.49.0's `.so`). The two classes
  are unrelated, and the rewrite does not change `catch` clauses, so a `catch (android.os.OperationCanceledException e)`
  around a query, or around reading its cursor, no longer catches a cancellation, which reaches the caller instead. An
  app that catches it should catch both, `catch (android.os.OperationCanceledException |
  androidx.core.os.OperationCanceledException e)`, which works with the SQLite debugger on and off.
- Android's `CancellationSignal` holds one listener: the conversion replaces any listener the caller set. Each
  query gets its own androidx.core signal, and cancelling the Android signal cancels every one still in use, so
  two lazy queries that share it both stop (requery keeps a query's signal for its cursor's later reads). The
  runtime's forwarder is held only as the Android signal's listener (`SqliteRewriteRuntime.FORWARDERS` holds signals
  and forwarders weakly), so nothing of a signal the app dropped is kept: a strong value outlived its key until the
  map's next call. A caller that sets its own listener after a query lets that forwarder go: the next query on the
  signal gets a new one, and queries from before the caller's listener no longer stop with the signal.
- Which `SQLiteException` subclass requery throws for an error code (for example `SQLiteConstraintException`, which
  Room catches) is decided by its native layer; this rewrite does not change it and the survey did not check it.
