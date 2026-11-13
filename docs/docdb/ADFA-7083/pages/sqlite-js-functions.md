---
file: sqlite-js-functions.html
title: sqlite-js function reference
description: Every sqlite-js function, with its arguments, results and errors.
parent: sqlite-js-top.html
prev: sqlite-js-top.html
next: sqlite-js-javascript.html
---

This page lists every SQL function that sqlite-js adds. First [load sqlite-js](sqlite-js-top.html). Each `js_` function except `js_version` works only in your own SQL statements, not inside a view, trigger, CHECK constraint, generated column or index stored in a database. A TEMP view or trigger, which lasts only for your session, can use them.

An error in your JavaScript, such as a thrown exception or a syntax error, becomes an SQL error that carries the JavaScript message.

## Create a function

In the code you pass, a **function expression** is a JavaScript function written inside parentheses, like `(function (args) { ... })`. Every `js_create_` function returns `0` when it succeeds.

A function cannot be redefined in a session that already has it: you get `Function updates must be performed using a separate database connection.` To change one, close `sqlite3`, open it again and create the new version first, without calling `js_init_table(1)`, which would create the old one. If the database keeps your functions, the new version replaces the stored one, and `js_init_table(1)` creates it in your next session.

### js_create_scalar

```text
js_create_scalar(name, code)
```

Creates a scalar function. `code` is a function expression. It receives one argument, an array with the SQL arguments, and returns the result. Both arguments must be TEXT.

```sql
SELECT js_create_scalar('add_all', '(function (args) {
  return args.reduce((sum, x) => sum + x, 0);
})');
SELECT add_all(1, 2, 3), add_all();
-- 6|0
```

### js_create_aggregate

```text
js_create_aggregate(name, init, step, final)
```

Creates an aggregate function.

- `init` is JavaScript code that runs before the first row of each group, to set up variables. It can be NULL.
- `step` is a function expression that runs once for each row, with the array of arguments.
- `final` is a function expression that runs after the last row and returns the result.

The variables you set up in `init` belong to one group. Each group, and each use of the function in a query, has its own. If there are no rows, the function returns NULL, and neither `init` nor `final` runs.

```sql
SELECT js_create_aggregate('longest',
  'best = "";',
  '(function (args) { if (args[0].length > best.length) best = args[0]; })',
  '(function () { return best; })');
WITH w(g, s) AS (VALUES ('a', 'x'), ('a', 'xyz'), ('b', 'hello'), ('a', 'xy'))
SELECT g, longest(s) FROM w GROUP BY g ORDER BY g;
-- a|xyz
-- b|hello
```

### js_create_window

```text
js_create_window(name, init, step, final, value, inverse)
```

Creates a window function, an aggregate that can be used with `OVER`. It takes the same `init`, `step` and `final` as an aggregate, and two more:

- `value` is a function expression that returns the current result, while the window is still moving.
- `inverse` is a function expression that removes a row that leaves the window. It receives the array of arguments for that row.

```sql
SELECT js_create_window('moving_sum', 'total = 0;',
  '(function (args) { total += args[0]; })',
  '(function () { return total; })',
  '(function () { return total; })',
  '(function (args) { total -= args[0]; })');
WITH v(x) AS (VALUES (1), (2), (3), (4))
SELECT group_concat(s, ' ') FROM (
  SELECT moving_sum(x) OVER (ORDER BY x ROWS BETWEEN 1 PRECEDING AND CURRENT ROW) AS s FROM v);
-- 1 3 5 7
```

### js_create_collation

```text
js_create_collation(name, code)
```

Creates a collation, a way to compare two strings. `code` is a function expression that receives two strings and returns a negative number if the first sorts first, zero if they are equal, and a positive number if the second sorts first. Only the sign counts. Use the collation with `COLLATE name`.

```sql
SELECT js_create_collation('by_length', '(function (a, b) {
  return a.length - b.length || a.localeCompare(b);
})');
WITH w(s) AS (VALUES ('ccc'), ('a'), ('bb'))
SELECT group_concat(s, ' ') FROM (SELECT s FROM w ORDER BY s COLLATE by_length);
-- a bb ccc
```

If a collation throws an exception or runs out of memory, the two strings compare as equal. `ORDER BY` then gives a wrong order and no error, so keep collation code simple.

## Run code

### js_eval

```text
js_eval(code)
```

Runs JavaScript and returns the value of its last expression. Variables you create stay available to later `js_eval` calls, to your scalar functions and to your collations in the same session.

```sql
SELECT js_eval('globalThis.rate = 0.2; 0');
SELECT js_eval('Math.round(100 * (1 + rate))');
-- 120
```

## Keep functions in the database

By default a function lasts only for the session. `js_init_table` makes the functions you create stay in the database.

### js_init_table

```text
js_init_table()
js_init_table(1)
```

`js_init_table()` creates the table `js_functions`, with the columns `name`, `kind`, `init_code`, `step_code`, `final_code`, `value_code` and `inverse_code`. Whenever that table exists, each function you create is stored in it, under the same name.

`js_init_table(1)` also creates every function stored in the table, which is how a new session gets them back. Call it once, at the start of the session: if a stored function already exists in the session, because you created it or already called `js_init_table(1)`, it fails with `Function updates must be performed using a separate database connection.`

```sql
SELECT js_init_table();
SELECT js_create_scalar('twice', '(function (args) { return args[0] * 2; })');
SELECT name, kind FROM js_functions;
-- twice|scalar
```

> Call `js_init_table(1)` only on databases you trust. It runs the JavaScript that the database stores.

## Read files

### js_load_text and js_load_blob

```text
js_load_text(path)
js_load_blob(path)
```

Return the contents of a file, as TEXT or as a BLOB. An empty file gives an empty value. If the file cannot be opened, you get the error `Unable to open the file`.

## Limits

### js_set_memory_limit

```text
js_set_memory_limit(bytes)
```

Sets the most memory, in bytes, that JavaScript may use in this session. The default is 64 MB. `0` means no limit. The argument must be a whole number, 0 or more, or you get the error `The memory limit must be an integer number of bytes, or 0 for no limit`. The function returns NULL. A limit below the memory that variables still hold leaves no room for any JavaScript until you raise it again.

### js_set_max_stack_size

```text
js_set_max_stack_size(bytes)
```

Sets the size of the JavaScript stack, which limits how deeply functions can call themselves. The default is 1 MB. Give a whole number of bytes, such as `1048576`, no larger than the stack of `sqlite3` allows. Anything else is an error, and the error message names the largest size allowed. If the stack has no limit, as after `ulimit -s unlimited`, sqlite-js cannot measure it and allows at most 1 MB. Do not use `0`: it turns the check off, and code that calls itself without end then crashes `sqlite3`.

## Version

### js_version

```text
js_version()
js_version(1)
```

`js_version()` returns the version of sqlite-js. `js_version(1)` returns the version of the JavaScript engine, quickjs-ng.

```sql
SELECT js_version();
-- 1.4.2
```

## What changed in sqlite-js 1.4

sqlite-js 1.4.0 includes fixes that App Dev For All sent to the upstream project. Compared with 1.3.7:

- Ctrl-C stops JavaScript that never ends.
- JavaScript may use 64 MB of memory, and `js_set_memory_limit` changes the limit.
- The functions that run code, read files or change limits cannot be used in a view, trigger or index stored in a database.
- A function can return a BLOB, by returning an `ArrayBuffer` or a typed array such as `Uint8Array`.
- A whole number comes back as an INTEGER, even beyond the 32-bit range.
- An exception in an aggregate or window function fails the query.
- `js_set_max_stack_size` accepts only a size the stack can hold, or 0, which turns the check off. With a size set, code that calls itself without end fails instead of crashing `sqlite3`.
- An error while `db.exec` runs a statement, such as a failed constraint, is thrown instead of being ignored.

sqlite-js 1.4.2 has more fixes, several of them sent or reported by App Dev For All. Compared with 1.4.1:

- A result set from `db.exec` lasts only until the call into JavaScript that created it returns. Reading it in a later call throws an error, where before it went on with its rows.
- Ctrl-C no longer leaves `sqlite3` failing every later query with `interrupted` when JavaScript kept a result set.
- Code that calls itself without end fails instead of crashing `sqlite3` in more cases. If the stack has no limit, `js_set_max_stack_size` allows at most 1 MB.
- JavaScript that runs out of memory while much of its memory is garbage no longer fails again on every try.
- A query that computes a JavaScript window function no longer crashes `sqlite3` when JavaScript drops its result set partway.

## Credits

This page adapts the README of sqlite-js (the project sqliteai/sqlite-js on GitHub), which is licensed under the Apache License, Version 2.0. It has been rewritten and changed for Code on the Go. The software is provided "as is", without warranties or conditions of any kind.

The JavaScript engine, quickjs-ng, is built into sqlite-js and is licensed under the MIT License. The license texts are installed with the package, in `$PREFIX/share/doc/sqlite-js/`: `LICENSE` for sqlite-js and `LICENSE.quickjs` for the engine.
