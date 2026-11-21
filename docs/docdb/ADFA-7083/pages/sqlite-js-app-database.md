---
file: sqlite-js-app-database.html
title: Set up a database with JavaScript functions
description: The build settings, the two ways to load sqlite-js, and where to create JavaScript functions in an app.
parent: sqlite-js-app-top.html
prev: sqlite-js-app-top.html
next: sqlite-js-app-cancel.html
---

This page explains each step that the **JavaScript in SQLite** template takes to give a Room database JavaScript functions. Start with [JavaScript in SQLite for apps](sqlite-js-app-top.html) if you have not created the sample app yet.

The steps are:

1. Add the two libraries and three build settings.
2. Store your JavaScript functions in the database, before Room opens it.
3. Open the database with Room, and load sqlite-js on every connection.
4. Call the functions from your queries.

The examples on this page are a shorter form of the template's `ProductDatabase.kt`.

## Build settings

These lines of `app/build.gradle.kts` are the ones sqlite-js needs:

```gradle
android {
    defaultConfig {
        minSdk = 26
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }
}
dependencies {
    implementation(files("libs/sqlite-js.aar"))
    implementation(files("libs/sqlite-android.aar"))
    implementation(libs.androidx.core)
}
```

- `minSdk = 26`: sqlite-js needs Android 8.0 or later.
- `abiFilters`: the two libraries are built for ARM devices only, so the app is too.
- `useLegacyPackaging = true`: **do not remove this.** SQLite loads sqlite-js from a file. This setting makes Android copy the app's native libraries to files when it installs the app. Without it, they stay inside the APK, and SQLite finds no file to load.
- `libs/sqlite-js.aar` is sqlite-js. `libs/sqlite-android.aar` is an SQLite that can load it. `sqlite-android` needs `androidx.core`.

To use sqlite-js in another app, copy both `.aar` files from a project made with the template into that app's `app/libs` folder, and add these settings. Add Room as the template's `app/build.gradle.kts` does.

## Two ways to load sqlite-js

A database can keep JavaScript functions in a table named `js_functions`. sqlite-js can create those functions each time a connection opens, and creating a function runs its stored code. That code can run SQL and read your app's files. So `SqliteJs` gives you two entry points. An entry point is the name SQLite calls in the library when it loads it.

- `SqliteJs.ENTRY_POINT` loads sqlite-js and runs nothing that the database stores. It is safe for any database file.
- `SqliteJs.ENTRY_POINT_FOR_TRUSTED_DATABASE` loads sqlite-js, then creates the functions stored in `js_functions`, on every connection. Use it only for a database that your app made and keeps.

**Open a file your app did not make with `SqliteJs.ENTRY_POINT`.** That means a downloaded file, a file another app shared, or a restored backup. With the trusted entry point, code stored in such a file would run as soon as your app opened it.

```kotlin
/** Opens a database file that the app did not make. No code stored in the file runs. */
fun openSharedFile(context: Context, file: File): SQLiteDatabase {
    val config = SQLiteDatabaseConfiguration(file.path, SQLiteDatabase.OPEN_READONLY)
    config.customExtensions.add(
        SQLiteCustomExtension(SqliteJs.libraryPath(context), SqliteJs.ENTRY_POINT)
    )
    return SQLiteDatabase.openDatabase(config, null, null)
}
```

`SQLiteDatabase`, `SQLiteDatabaseConfiguration` and `SQLiteCustomExtension` come from `sqlite-android`, in the package `io.requery.android.database.sqlite`. `SqliteJs` is in the package `org.appdevforall.sqlitejs`.

With the trusted entry point, a stored function that fails to load is skipped. sqlite-js writes the reason to SQLite's log, and the other functions still load. A query that calls the skipped function fails with `no such function`.

## Store your functions before Room opens the database

Create your functions on a connection of your own, opened with `SqliteJs.ENTRY_POINT`, before Room opens the database. There are two reasons:

- **A connection that opened before a function was stored does not have it.** Room opens several connections, and each one gets the stored functions only when it opens.
- **A connection cannot redefine a function that it already has.** A connection opened with `SqliteJs.ENTRY_POINT` has none of the stored functions, so it can replace them all.

`js_init_table()` creates the table `js_functions`. After that, each `js_create_` function also stores the function it creates. The [sqlite-js function reference](sqlite-js-functions.html) describes them.

```kotlin
private const val NAME = "products.db"

// Each string is one SQL statement that creates and stores one JavaScript function.
private val FUNCTIONS = listOf(
    """
    SELECT js_create_scalar('slugify', '(function (args) {
      return String(args[0]).toLowerCase().trim()
        .replace(/[^a-z0-9]+/g, "-").replace(/^-|-$/g, "");
    })')
    """,
    """
    SELECT js_create_aggregate('median',
      'const values = [];',
      '(function (args) { values.push(args[0]); })',
      '(function () {
         values.sort((a, b) => a - b);
         const mid = Math.floor(values.length / 2);
         return (values.length % 2) ? values[mid] : (values[mid - 1] + values[mid]) / 2;
       })')
    """,
    "SELECT js_create_scalar('spin', '(function (args) { while (true) {} })')",
)

fun storeFunctions(context: Context) {
    val file = context.getDatabasePath(NAME)
    file.parentFile?.mkdirs()
    val config = SQLiteDatabaseConfiguration(
        file.path, SQLiteDatabase.CREATE_IF_NECESSARY or SQLiteDatabase.OPEN_READWRITE
    )
    config.customExtensions.add(
        SQLiteCustomExtension(SqliteJs.libraryPath(context), SqliteJs.ENTRY_POINT)
    )
    SQLiteDatabase.openDatabase(config, null, null).use { db ->
        db.rawQuery("SELECT js_init_table()", emptyArray<Any>()).use { it.moveToFirst() }
        FUNCTIONS.forEach { sql ->
            db.rawQuery(sql, emptyArray<Any>()).use { it.moveToFirst() }
        }
    }
}
```

- `rawQuery` does not run a statement until you read its result, which is why each call ends with `moveToFirst()`.
- Storing the functions on every start costs little: a function that is already stored with the same code is not written again. It also means that a new version of your app replaces the functions whose code you changed.
- If a function cannot be stored, for example because the database is locked, `moveToFirst()` throws an exception. Its message contains `could not be stored in js_functions`.
- `spin` never returns. It is here only to show how to stop such a function, in [Stop JavaScript that never ends](sqlite-js-app-cancel.html).

## Open the database with Room

Give Room the `sqlite-android` helper factory, and add sqlite-js to its configuration with the trusted entry point. Every connection that Room opens then loads sqlite-js and the functions you stored.

```kotlin
fun openProductDatabase(context: Context): ProductDatabase {
    storeFunctions(context)
    val factory = RequerySQLiteOpenHelperFactory(
        listOf(
            RequerySQLiteOpenHelperFactory.ConfigurationOptions { config ->
                config.customExtensions.add(
                    SQLiteCustomExtension(
                        SqliteJs.libraryPath(context),
                        SqliteJs.ENTRY_POINT_FOR_TRUSTED_DATABASE,
                    )
                )
                config
            },
        ),
    )
    return Room.databaseBuilder(context, ProductDatabase::class.java, NAME)
        .openHelperFactory(factory)
        .build()
}
```

Call this once and keep the result for the whole app, as the template's `ProductDatabase.get()` does. Call it off the main thread: it reads and writes the database file.

## Call the functions from your queries

Mark each DAO method whose query calls a JavaScript function with `@SkipQueryVerification`. Room checks every query when it builds your app, with an SQLite of its own that has no JavaScript functions. Without the annotation, the build fails with `no such function`.

```kotlin
@Entity
data class Product(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val price: Double,
)

@Dao
interface ProductDao {
    @Insert
    suspend fun insert(products: List<Product>)

    @SkipQueryVerification
    @Query("SELECT slugify(name) FROM Product ORDER BY id")
    suspend fun slugs(): List<String>

    @SkipQueryVerification
    @Query("SELECT median(price) FROM Product")
    suspend fun medianPrice(): Double?

    @SkipQueryVerification
    @Query("SELECT spin()")
    suspend fun spin(): Int?
}

@Database(entities = [Product::class], version = 1, exportSchema = false)
abstract class ProductDatabase : RoomDatabase() {
    abstract fun products(): ProductDao
}
```

Room does not check a query that has the annotation, so a mistake in its SQL shows only when the query runs. Test each one.

## Change a function

Change its code in your app. The next time the app starts, `storeFunctions` replaces the stored function, and every connection that Room then opens gets the new one.

To remove a function, delete its row from `js_functions`, for example with `DELETE FROM js_functions WHERE name = 'spin'`, on the connection that stores your functions.
