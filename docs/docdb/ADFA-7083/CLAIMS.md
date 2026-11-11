# Claims in the ADFA-7083 pages and how each was checked

`run` = executed by `test_examples.py` against the sqlite-js 1.3.7-1 host build. `code` = read in the patched source. `probe` = run by hand against the host build during the accuracy review (2026-11-11). `TODO` = still open.

## sqlite-js-top

| Claim | Check |
|-------|-------|
| `apt install sqlite sqlite-js` installs both; the `sqlite` package provides `sqlite3` | phone: `sqlite3` is provided by package `sqlite` (dpkg -S), 3.51.2. `apt install sqlite sqlite-js` from the stage repo (https://stage.appdevforall.org/apt/termux-main/, same signing key as production) installed sqlite-js 1.3.7-1 on aarch64, which loaded and passed run-tests.sh 77 of 77. Production gets it when the package is published there |
| `.load sqlite-js` finds `$PREFIX/lib/sqlite-js.so`; `.load` does not expand `$PREFIX` | run on phone: `.load sqlite-js`, `.load sqlite-js.so` and the full path without `.so` work; `.load $PREFIX/lib/sqlite-js` fails with `dlopen failed: library "$PREFIX/lib/sqlite-js.so" not found` |
| `js_create_scalar` prints 0; slugify and titles examples | run. The opening line no longer claims JavaScript is quicker than Java or Kotlin, which was opinion |
| A function lasts until `sqlite3` closes | run (functions page) |
| `js_init_table(1)` runs stored code; `db.exec` can run any SQL including `readfile()`/`writefile()` | probe: code stored with a side effect ran during `js_init_table(1)`, before any call; `db.exec("SELECT writefile(...)")` wrote the file and `readfile` returned a BLOB. Phone: both functions exist in the Termux shell |
| Ctrl-C stops code, query fails with `interrupted` | run (package run-tests AC8) |
| 64 MB memory limit; `out of memory` | run (AC9) |
| Do not put `.load` in `~/.sqliterc` | advice, follows from the above |

## sqlite-js-functions

| Claim | Check |
|-------|-------|
| Each `js_` function but `js_version` cannot be used in a view, trigger, CHECK, generated column or index stored in a database; TEMP views and triggers can use them | run (AC10, flags check); probe: `pragma_function_list` flags, a main-schema trigger fails with `unsafe use of js_eval()`, a TEMP view and TEMP trigger work |
| Examples for scalar, aggregate (per-group state), window, collation, `js_eval`, `js_init_table`, `js_version` | run |
| Redefining in a session that has the function fails with the quoted message; redefine before `js_init_table(1)`; the new version replaces the stored one | run (package AC4); probe: after `js_init_table(1)` the redefinition fails; without it, it succeeds and the next session's `js_init_table(1)` loads the new version |
| `init` can be NULL; no rows gives NULL without running `init` or `final` | run on phone (NULL init); probe and code (`js_execute_final` returns NULL without a context). No NULL-init example on the page, deliberately: the `init` example shows the usual pattern |
| `value`/`inverse` semantics | code, run |
| Collation: only the sign counts; a throwing collation compares as equal | run on phone: a throwing collation leaves the rows in their original order, i.e. equal |
| `js_init_table()` columns and "whenever that table exists, each function you create is stored" | run on phone: with the table left by an earlier session and no `js_init_table()` call, a new function was stored (`js_functions` listed both), and `js_init_table(1)` in a new session restored both |
| `js_load_text`/`js_load_blob`: empty file gives empty value; error text `Unable to open the file` | run on phone: empty file gives `''` / `X''`; a missing file gives `Unable to open the file` (the shell prefixes `Error: stepping, `) |
| `js_set_memory_limit`: default 64 MB, 0 no limit, a whole number 0 or more, error text, returns NULL | run (package AC9), code; probe: `'64'`, `-1` and `1.5` each give the error |
| `js_set_max_stack_size`: default 1 MB; 0 or non-numeric text turns the check off and deep recursion crashes | code (`JS_DEFAULT_STACK_SIZE`, `sqlite3_value_int` with no check); probe: `0` and `'abc'` each segfault on endless recursion; the default gives "Maximum call stack size exceeded" |
| `js_version(1)` is quickjs-ng | run (package AC2) |
| The six fixes listed | code, package tests |
| quickjs-ng is MIT-licensed; `LICENSE` and `LICENSE.quickjs` are in `$PREFIX/share/doc/sqlite-js/` | code: package build (`LICENSE.quickjs` collected from the sources); file list of the 1.3.7-1 aarch64 `.deb` checked |
| Apache-2.0 attribution wording | TODO: legal wording check; upstream names no copyright holder |

## sqlite-js-javascript

| Claim | Check |
|-------|-------|
| "Memory that variables still hold counts against the limit until you clear them" | probe: two 40 MB arrays held in globals fail with `out of memory`; clearing the first lets the second succeed |
| Engine supports const/let, arrow functions, classes, template strings, destructuring, Map/Set, BigInt, regexes, JSON, Math, Date, typed arrays | run on phone: Map, Set, BigInt (2n**70n), Date, Uint8Array, Float64Array, class, destructuring, arrow, template string |
| `window`, `fetch`, `setTimeout`, `require`, `process` undefined | run on phone: `window`, `document`, `fetch`, `alert`, `setTimeout`, `require`, `process`, `Buffer` are all undefined |
| Promises never finish | run (package AC11); probe: a `.then` callback had not run in the next statement |
| "JavaScript has no file, network or process functions of its own" | run (AC11: `std`, `os` not reachable); probe: the lower-case globals are `console db decodeURI decodeURIComponent encodeURI encodeURIComponent escape eval globalThis isFinite isNaN parseFloat parseInt performance print queueMicrotask scriptArgs undefined unescape` |
| `console.log` and `print` write to `sqlite3` standard output | run on phone: `console.log` and `print` both write to standard output |
| SQL-to-JS and JS-to-SQL conversion tables, including `NaN` -> NULL, other objects -> NULL, BigInt wraps outside 64 bits | run (package AC6, and the BigInt wraparound example), code; probe: BLOB arrives as `[object ArrayBuffer]`; `NaN`, arrays, `Date`, functions give NULL; `Infinity` and `2**60` give REAL; `2n**70n` gives 0 |
| 3.0 comes back as 3; INTEGER beyond 2^53 - 1 loses digits; BigInt workaround | run |
| `db.exec` result set: `next`, `get`, `name`, `columnCount`, `toArray` (remaining rows) | run; probe: `next()` is `true` then `false` at the end; `toArray()` after one `next()` returns the remaining two of three rows |
| Parameters convert like results | run (package AC6) |
| Variable sharing: `js_eval`, scalar functions, collations share; each aggregate has its own | run (AC3) |
| Ctrl-C works "even code that catches every error"; "`sqlite3` keeps running if you were typing commands" | run (AC8) |
| Stack overflow message `Maximum call stack size exceeded` | run by hand |
| `js_create_` reports syntax errors when the function is created | run (package AC5) |

## javascript-intro

| Claim | Check |
|-------|-------|
| Every snippet and its output | run |
| `const` is never reassigned, `let` can be; arrow function syntax; one kind of number for whole numbers and fractions (BigInt aside, which the intro does not cover) | JavaScript language facts |

## Format

| Claim | Check |
|-------|-------|
| Pages render through `helpmanual-topic.peb` | rendered with Jinja2 as a Pebble stand-in: title, body, breadcrumbs present. TODO: render in the app |
| Phone layout of tables and code blocks | body-only render in headless Chrome (code blocks wrap, tables fit); TODO: on a device at font scale 1.0 and 2.0 |
