---
file: sqlite-js-app-top.html
title: JavaScript in SQLite for apps
description: Call functions written in JavaScript from the SQL queries of an app that you build with Code on the Go.
parent: project-create-new.html
prev: project-create-new.html
next: sqlite-js-app-database.html
---

An app that you build with Code on the Go can call functions written in JavaScript from its SQL queries. The sqlite-js library adds them to the app's SQLite database. You write a JavaScript function, give it a name, and use it in a query like any built-in SQL function.

The quickest way to start is the **JavaScript in SQLite** project template. It creates a sample app that already works.

To write JavaScript functions for the `sqlite3` command in Terminal instead, see [JavaScript in SQLite (sqlite-js)](sqlite-js-top.html).

## Create the sample app

1. [Create a new project](project-create-new.html) and select the **JavaScript in SQLite** template.
2. Enter a name for the app and tap **Create project**.
3. Build the app and run it.

The project is written in Kotlin.

## What the sample app shows

The app keeps four products, each with a name and a price, in a Room database named `products.db`. Its queries call three JavaScript functions.

- **Run the JavaScript functions** runs two queries. The scalar function `slugify` turns each product name into a web address slug, so `Red Apple` becomes `red-apple`. The aggregate function `median` finds the median price. The app also runs them when it starts.
- **Start endless JavaScript** calls `spin`, a function that never returns. The app stays responsive, and **Cancel** stops the function. The app then shows how many milliseconds the stop took.
- **Open source notices** shows the license notices of the libraries in the app.

With the sample products, the app shows:

```text
Slugs:
red-apple
whole-wheat-bread
milk-1-litre
coffee-tea-sampler

Median price: 2.23
```

## Where to look in the project

- `ProductDatabase.kt`, in `app/src/main/java/` under your package name: the database, the three JavaScript functions, and `cancellable()`, which stops JavaScript that never ends.
- `MainActivity.kt`, in the same folder: the buttons, and the code that runs when you tap them.
- `app/build.gradle.kts`: the libraries and the build settings that sqlite-js needs.
- `app/libs/sqlite-js.aar`: sqlite-js, with its JavaScript engine.
- `app/libs/sqlite-android.aar`: an SQLite that can load sqlite-js.
- `app/src/main/assets/NOTICES.txt`: the license notices that the app shows.

## What sqlite-js needs

- **An ARM device.** The libraries are built for arm64-v8a and armeabi-v7a only.
- **Android 8.0 or later.** The app's `minSdk` must be 26 or more.
- **An SQLite that can load extensions.** sqlite-js is an SQLite extension, and Android's own SQLite gives an app no way to load one. The template adds `sqlite-android`, a library from the requery project that brings its own SQLite, and Room uses it.

These pages describe sqlite-js 1.4.4. To see which version an app has, run the query `SELECT js_version()`.

## License notices your app must carry

The app includes three open source works. Each license asks you to pass its notice on to the people who get your app:

| Work | License |
|------|---------|
| sqlite-js | Apache License 2.0 |
| `sqlite-android`, from the requery project | Apache License 2.0 |
| QuickJS (quickjs-ng), the JavaScript engine inside sqlite-js | MIT License |

The template's `app/src/main/assets/NOTICES.txt` has the notices and both license texts, and **Open source notices** shows that file. When you turn the sample into your own app, keep the file and keep a way for your users to read it. When you copy the libraries into another app, copy the file too.

## Learn more

- [Set up a database with JavaScript functions](sqlite-js-app-database.html): the build settings, the two ways to load sqlite-js, and where to create your functions.
- [Stop JavaScript that never ends](sqlite-js-app-cancel.html): `SqliteJs.cancelAll()` and what it interrupts.
- [sqlite-js function reference](sqlite-js-functions.html): `js_create_scalar`, `js_create_aggregate` and the other SQL functions that create and keep JavaScript functions.
- [JavaScript in sqlite-js](sqlite-js-javascript.html): how SQL values and JavaScript values convert, what the engine supports, and the limits.
- [JavaScript basics](javascript-intro.html): a short introduction for new JavaScript users.

The last three pages are written for `sqlite3` in Terminal. The SQL and the JavaScript on them work the same way in an app. Where they say session, read database connection: each connection has its own JavaScript engine, its own functions and its own limits. Ctrl-C and `.load` belong to Terminal only.
