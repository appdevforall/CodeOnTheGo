---
file: sqlite-js-top.html
title: JavaScript in SQLite (sqlite-js)
description: Write SQLite functions in JavaScript and call them from SQL in Terminal.
parent: terminal-top.html
prev: terminal-top.html
next: sqlite-js-functions.html
---

sqlite-js adds SQL functions written in JavaScript to the `sqlite3` command in Terminal. You write a JavaScript function, give it a name, and then use it in a query like any built-in SQL function.

A small JavaScript function is quick to write and change, and the code can live in the database next to the data it works on.

You can write four kinds of function:

- A **scalar** function returns one value for each row, such as turning a title into a web address.
- An **aggregate** function turns many rows into one value, such as a median.
- A **window** function is an aggregate that can also work over a sliding range of rows, such as a moving sum.
- A **collation** decides how text sorts.

## Install sqlite-js

You need an Internet connection. Open [Terminal](terminal-top.html) and enter:

```text
apt update
apt install sqlite sqlite-js
```

The `sqlite` package provides the `sqlite3` command that sqlite-js works with.

These pages describe sqlite-js 1.3.7-3 and later versions, such as 1.3.7-p20261112-0. To see which version you have, enter `dpkg -s sqlite-js` and read its `Version` line. `apt update` and then `apt install sqlite-js` bring an older one up to date.

## Load sqlite-js

Open a database in `sqlite3`, then load the extension. Load it in each session that needs it.

```text
sqlite3 my.db
.load sqlite-js
```

If `sqlite3` cannot find sqlite-js, give `.load` the full path. To print it, enter `.shell echo $PREFIX/lib/sqlite-js` in `sqlite3`, or `echo $PREFIX/lib/sqlite-js` in Terminal. `.load` does not understand `$PREFIX` itself.

## Your first function

A scalar function is JavaScript code in parentheses. It gets the SQL arguments as an array named `args`. `js_create_scalar` prints `0` when it creates the function.

```sql
SELECT js_create_scalar('slugify', '(function (args) {
  return args[0].toLowerCase().replace(/[^a-z0-9]+/g, "-").replace(/^-|-$/g, "");
})');
SELECT slugify('Hello, World!');
-- hello-world
```

You can use the function in any query in the same session:

```sql
WITH titles(t) AS (VALUES ('Hello, World!'), ('Code on the Go'))
SELECT t, slugify(t) FROM titles;
-- Hello, World!|hello-world
-- Code on the Go|code-on-the-go
```

A function you create lasts until you close `sqlite3`. To keep your functions in the database, see [sqlite-js function reference](sqlite-js-functions.html).

## Stay safe

- **Load sqlite-js only for databases you trust.** A database can store JavaScript, and `js_init_table(1)` runs it with your access. Through `db.exec`, that code can run any SQL, including `readfile()` and `writefile()`, which read and write your files.
- **Do not add `.load sqlite-js` to `~/.sqliterc`.** That file runs for every database you open.
- **Ctrl-C stops code that never ends.** The query fails with `interrupted`.
- **JavaScript may use 64 MB of memory per session.** Code that needs more fails with `out of memory`.

## Learn more

- [sqlite-js function reference](sqlite-js-functions.html): every function, with arguments, results and errors.
- [JavaScript in sqlite-js](sqlite-js-javascript.html): how SQL values and JavaScript values convert, what the engine supports, and the limits.
- [JavaScript basics](javascript-intro.html): a short introduction for new JavaScript users.

sqlite-js is developed at github.com/sqliteai/sqlite-js under the Apache License 2.0. The version in Code on the Go is newer than the 1.3.7 release and includes fixes from App Dev For All, described in the reference.
