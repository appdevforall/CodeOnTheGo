# Claims in the ADFA-7083 pages and how each was checked

`run` = executed by `test_examples.py` against the sqlite-js 1.3.7-1 host build. `code` = read in the patched source. `TODO` = still to verify, for the accuracy review.

## sqlite-js-top

| Claim | Check |
|-------|-------|
| `apt install sqlite sqlite-js` installs both; the `sqlite` package provides `sqlite3` | phone: `sqlite3` is provided by package `sqlite` (dpkg -S), 3.51.2. TODO: `apt install sqlite-js` from the stage repo (sqlite-js was installed with `dpkg -i`; production has no sqlite-js) |
| `.load sqlite-js` finds `$PREFIX/lib/sqlite-js.so`; `.load` does not expand `$PREFIX` | run on phone: `.load sqlite-js`, `.load sqlite-js.so` and the full path without `.so` work; `.load $PREFIX/lib/sqlite-js` fails with `dlopen failed: library "$PREFIX/lib/sqlite-js.so" not found` |
| `js_create_scalar` prints 0; slugify and titles examples | run |
| A function lasts until `sqlite3` closes | run (functions page) |
| `js_init_table(1)` runs stored code; `db.exec` can run any SQL including `readfile()`/`writefile()` | run on phone: `writefile`/`readfile` exist in the shell and work; `db.exec` ran a `readfile` query without error but returned only `{"columnCount":1}`, so the claim that it can run them is by code, not by output |
| Ctrl-C stops code, query fails with `interrupted` | run (package run-tests AC8) |
| 64 MB memory limit; `out of memory` | run (AC9) |
| Do not put `.load` in `~/.sqliterc` | advice, follows from the above |

## sqlite-js-functions

| Claim | Check |
|-------|-------|
| Each `js_` function but `js_version` works only in top-level SQL | run (AC10, flags check) |
| Examples for scalar, aggregate (per-group state), window, collation, `js_eval`, `js_init_table`, `js_version` | run |
| Redefining in the same session fails with the quoted message | run (package AC4) |
| `init` can be NULL | run on phone (NULL init, aggregate gives 2). TODO: add an example to the page |
| `value`/`inverse` semantics | code, run |
| Collation: only the sign counts; a throwing collation compares as equal | run on phone: a throwing collation leaves the rows in their original order, i.e. equal |
| `js_init_table()` columns and "whenever that table exists, each function you create is stored" | run on phone: with the table left by an earlier session and no `js_init_table()` call, a new function was stored (`js_functions` listed both), and `js_init_table(1)` in a new session restored both |
| `js_load_text`/`js_load_blob`: empty file gives empty value; error text `Unable to open the file` | run on phone: empty file gives `''` / `X''`; a missing file gives `Unable to open the file` (the shell prefixes `Error: stepping, `) |
| `js_set_memory_limit`: default 64 MB, 0 no limit, integer only, error text, returns NULL | run (package AC9), code |
| `js_set_max_stack_size`: default 1 MB; 0 crashes deep recursion | code (`JS_DEFAULT_STACK_SIZE`); run by hand: size 0 + recursion segfaults, default gives "Maximum call stack size exceeded" |
| `js_version(1)` is quickjs-ng | run (package AC2) |
| The six fixes listed | code, package tests |
| Apache-2.0 attribution wording | TODO: legal wording check; upstream names no copyright holder |

## sqlite-js-javascript

| Claim | Check |
|-------|-------|
| Engine supports const/let, arrow functions, classes, template strings, destructuring, Map/Set, BigInt, regexes, JSON, Math, Date, typed arrays | run on phone: Map, Set, BigInt (2n**70n), Date, Uint8Array, Float64Array, class, destructuring, arrow, template string |
| `window`, `fetch`, `setTimeout`, `require`, `process` undefined | run on phone: `window`, `document`, `fetch`, `alert`, `setTimeout`, `require`, `process`, `Buffer` are all undefined |
| Promises never finish | run (package AC11) |
| "JavaScript has no file, network or process functions of its own" | run (AC11: `std`, `os` not reachable) |
| `console.log` and `print` write to `sqlite3` standard output | run on phone: `console.log` and `print` both write to standard output |
| SQL-to-JS and JS-to-SQL conversion tables | run (package AC6), code |
| 3.0 comes back as 3; INTEGER beyond 2^53 - 1 loses digits; BigInt workaround | run |
| `db.exec` result set: `next`, `get`, `name`, `columnCount`, `toArray` | run |
| Parameters convert like results | run (package AC6) |
| Variable sharing: `js_eval`, scalar functions, collations share; each aggregate has its own | run (AC3) |
| Ctrl-C works "even code that catches every error"; "`sqlite3` keeps running if you were typing commands" | run (AC8) |
| Stack overflow message `Maximum call stack size exceeded` | run by hand |
| `js_create_` reports syntax errors when the function is created | run (package AC5) |

## javascript-intro

| Claim | Check |
|-------|-------|
| Every snippet and its output | run |
| `const` is never reassigned, `let` can be; arrow function syntax | JavaScript language facts |

## Format

| Claim | Check |
|-------|-------|
| Pages render through `helpmanual-topic.peb` | rendered with Jinja2 as a Pebble stand-in: title, body, breadcrumbs present. TODO: render in the app |
| Phone layout of tables and code blocks | body-only render in headless Chrome (code blocks wrap, tables fit); TODO: on a device at font scale 1.0 and 2.0 |
