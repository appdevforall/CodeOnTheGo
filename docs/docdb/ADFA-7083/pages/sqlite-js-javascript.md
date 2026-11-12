---
file: sqlite-js-javascript.html
title: JavaScript in sqlite-js
description: How SQL and JavaScript values convert, what the JavaScript engine supports, and its limits.
parent: sqlite-js-top.html
prev: sqlite-js-functions.html
next: javascript-intro.html
---

sqlite-js runs your code with quickjs-ng, a small JavaScript engine. This page explains what that means for your code. To see the engine version, enter `SELECT js_version(1);`.

## What the engine supports

The engine supports modern JavaScript: `const` and `let`, arrow functions, classes, template strings, destructuring, `Map` and `Set`, `BigInt`, regular expressions, `JSON`, `Math`, `Date`, typed arrays and the standard array and string methods.

```sql
SELECT js_eval('{
  class Point { constructor(x, y) { this.x = x; this.y = y; } get sum() { return this.x + this.y; } }
  const { sum } = new Point(3, 4);
  `${[1, 2, 3].map(n => n * n).join("+")} and ${sum}`;
}');
-- 1+4+9 and 7
```

## What the engine does not have

sqlite-js runs JavaScript inside SQLite, not in a web page or in Node.js. These do not exist:

- Web page features: `window`, `document`, `fetch`, `alert` and `setTimeout`.
- Node.js features: `require`, `process` and `Buffer`.

```sql
SELECT js_eval('[typeof window, typeof fetch, typeof setTimeout, typeof require, typeof process].join(" ")');
-- undefined undefined undefined undefined undefined
```

Promises never finish, because nothing runs the queue that finishes them, so `async` functions and `.then` callbacks never run their next step. Write ordinary, synchronous code.

JavaScript has no file, network or process functions of its own.

## Print messages

`console.log` and `print` write to the standard output of `sqlite3`, so the text appears among the query results.

## Values from SQL to JavaScript

| SQL | JavaScript |
|-----|------------|
| NULL | `null` |
| INTEGER, REAL | a number |
| TEXT | a string |
| BLOB | an `ArrayBuffer` |

Your function receives its SQL arguments in an array, so the first argument is `args[0]`.

## Values from JavaScript to SQL

| JavaScript | SQL |
|------------|-----|
| `null`, `undefined` | NULL |
| `true`, `false` | INTEGER 1 or 0 |
| a whole number from -(2^53 - 1) to 2^53 - 1 | INTEGER |
| any other number | REAL (`NaN` becomes NULL) |
| a BigInt | INTEGER, if it fits (see below) |
| a string | TEXT |
| an `ArrayBuffer`, or a typed array such as `Uint8Array` | BLOB |
| any other object | NULL |

To keep an object, return `JSON.stringify(value)`, which is a string.

```sql
SELECT quote(js_eval('true')), quote(js_eval('new Uint8Array([1, 2, 3])')), quote(js_eval('({a: 1})')), js_eval('JSON.stringify({a: 1})');
-- 1|X'010203'|NULL|{"a":1}
```

Apart from BigInt, JavaScript has one number type, so it cannot tell 3.0 from 3. A REAL that holds a whole number, such as 3.0, comes back as the INTEGER 3. An INTEGER larger than 2^53 - 1 loses digits in JavaScript. To keep every digit, pass it as TEXT and convert it with `BigInt`:

```sql
SELECT js_create_scalar('big_next', '(function (args) { return BigInt(args[0]) + 1n; })');
SELECT big_next(CAST(9223372036854775806 AS TEXT));
-- 9223372036854775807
```

A BigInt result must fit in an INTEGER, from -2^63 to 2^63 - 1. A larger one wraps around without an error:

```sql
SELECT big_next(CAST(9223372036854775807 AS TEXT));
-- -9223372036854775808
```

## Run SQL from JavaScript

The global object `db` runs SQL on the same database. `db.exec(sql, ...parameters)` takes a statement, with `?` for each parameter, and returns a result set. The statement runs only when you call `next()` or `toArray()`.

| Result set | What it does |
|------------|--------------|
| `next()` | Runs the statement to its next row. It returns `true`, or `false` when there are no more rows. If the statement fails, it throws an error. |
| `get(i)` | Returns column `i` of the current row, counting from 0. |
| `name(i)` | Returns the name of column `i`. |
| `columnCount` | The number of columns. |
| `toArray()` | Returns all the remaining rows, each as an array. If the statement fails partway, it throws an error. |

```sql
SELECT js_eval('(function () {
  const rows = db.exec("SELECT ? * 2 AS doubled, ? AS label", 21, "answer");
  rows.next();
  return rows.name(0) + "=" + rows.get(0) + " " + rows.get(1);
})()');
-- doubled=42 answer

SELECT js_eval('JSON.stringify(db.exec("SELECT 1 AS a, 2 AS b UNION ALL SELECT 3, 4").toArray())');
-- [[1,2],[3,4]]
```

Parameters convert like function results do, so a `Uint8Array` is bound as a BLOB.

To change data, call `next()` once on the result set. Without it, nothing happens.

```sql
CREATE TABLE notes(t TEXT);
SELECT js_eval('db.exec("INSERT INTO notes VALUES (?)", "kept"); db.exec("INSERT INTO notes VALUES (?)", "run").next(); 0');
SELECT group_concat(t) FROM notes;
-- 0
-- run
```

If the statement fails, for example on a `UNIQUE` constraint, `next()` or `toArray()` throws an error with SQLite's message. Unless your JavaScript catches it, the SQL that called the JavaScript fails with that message. An error in the SQL itself, such as a misspelled name, is thrown by `db.exec`.

```sql
CREATE TABLE colors(name TEXT UNIQUE);
INSERT INTO colors VALUES ('red');
SELECT js_eval('(function () {
  try {
    db.exec("INSERT INTO colors VALUES (?)", "red").next();
    return "added";
  } catch (e) {
    return "not added: " + e.message;
  }
})()');
-- not added: UNIQUE constraint failed: colors.name
```

## Variables and state

- Variables that you create with `js_eval`, such as `globalThis.rate = 0.2`, are shared by `js_eval`, your scalar functions and your collations. Aggregate and window functions cannot see them: define what they need in their `init` code.
- A name declared with `const`, `let` or `class` at the top level of `js_eval` code also lasts for the session, so running the same code again fails with `redeclaration of`. Put the code in braces, `{ ... }`, to keep its names to one run. `js_eval` still returns the value of the last expression.
- Each running aggregate or window function has variables of its own, which its `init` code sets up. That is why two groups never mix their state.

## Limits

- **Memory.** JavaScript may use 64 MB in a session. Memory that variables still hold counts against the limit until you clear them. See `js_set_memory_limit`.
- **Endless code.** Ctrl-C stops running JavaScript, even code that catches every error. The query fails with `interrupted` and `sqlite3` keeps running if you were typing commands.
- **Deep calls.** Functions that call themselves too deeply fail with `Maximum call stack size exceeded`. See `js_set_max_stack_size`.

## Errors

A thrown exception stops the query, and its message becomes the SQL error. For example, `SELECT js_eval('throw new Error("boom")');` fails with an error that contains `boom`. A syntax error fails the same way, and `js_create_` functions report it when you create the function.
