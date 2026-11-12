# Claims in the ADFA-7083 pages and how each was checked

`run` = executed by `test_examples.py` against a host build of the package (64 of 64 on 1.3.7-4, patches 0001 to 0008, 2026-11-12: 27 SQL examples, 18 of them run a second time in the same session, and 19 links; earlier, 1.3.7-1 and 1.3.7-2). `code` = read in the patched source. `probe` = run by hand against the host build, in the accuracy review (2026-11-11) or the review of PR 2241 (2026-11-12). `TODO` = still open.

## sqlite-js-top

| Claim | Check |
|-------|-------|
| `apt install sqlite sqlite-js` installs both; the `sqlite` package provides `sqlite3` | phone: `sqlite3` is provided by package `sqlite` (dpkg -S), 3.51.2. `apt install sqlite sqlite-js` from the stage repo (https://stage.appdevforall.org/apt/termux-main/, same signing key as production) installed sqlite-js 1.3.7-1 on aarch64, which loaded and passed run-tests.sh 77 of 77. 1.3.7-3 and 1.3.7-4 from stage passed 91 of 91 on aarch64, and the arm packages passed 91 of 91 as a 32-bit process on the same phone, which runs 32-bit code. Production gets it when the package is published there |
| These pages describe 1.3.7-3 and later; `dpkg -s sqlite-js` shows the installed version; `apt install` brings an older one up to date | code: 0008, which the javascript page describes, is in 1.3.7-3; host: `dpkg -s` prints a `Version:` line. TODO: `dpkg -s sqlite-js` on the phone |
| `.shell echo $PREFIX/lib/sqlite-js` in `sqlite3` prints the path | probe on host `sqlite3` 3.46.1: `.shell echo $HOME` prints the expanded path. TODO: on the phone |
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
| Redefining in a session that has the function fails with the quoted message; create the new version first in a new session, without `js_init_table(1)`; it replaces the stored one, and the next session's `js_init_table(1)` creates it | run (package AC4); probe: after `js_init_table(1)` the redefinition fails; without it, it succeeds and the next session's `js_init_table(1)` loads the new version. Probe on 1.3.7-4 (2026-11-12), file database: in a new session the redefinition succeeded and `f()` gave the new version; the next session's `js_init_table(1)` gave the new `f` and the unchanged `g` |
| `js_init_table(1)` runs once per session and fails with the same message if a stored function already exists in it | probe on 1.3.7-4: a second `js_init_table(1)`, and `js_init_table(1)` after creating any function, fail with the message. Code: `sqlite3_create_function_v2` returns `SQLITE_BUSY` when it replaces a function while the calling statement runs, and sqlite-js reports that as the message |
| `init` can be NULL; no rows gives NULL without running `init` or `final` | run on phone (NULL init); probe and code (`js_execute_final` returns NULL without a context). No NULL-init example on the page, deliberately: the `init` example shows the usual pattern |
| `value`/`inverse` semantics | code, run |
| Collation: only the sign counts; a throwing collation compares as equal | run on phone: a throwing collation leaves the rows in their original order, i.e. equal |
| `js_init_table()` columns and "whenever that table exists, each function you create is stored" | run on phone: with the table left by an earlier session and no `js_init_table()` call, a new function was stored (`js_functions` listed both), and `js_init_table(1)` in a new session restored both |
| `js_load_text`/`js_load_blob`: empty file gives empty value; error text `Unable to open the file` | run on phone: empty file gives `''` / `X''`; a missing file gives `Unable to open the file` (the shell prefixes `Error: stepping, `) |
| `js_set_memory_limit`: default 64 MB, 0 no limit, a whole number 0 or more, error text, returns NULL | run (package AC9), code; probe: `'64'`, `-1` and `1.5` each give the error |
| `js_set_max_stack_size` (1.3.7-2, patch 0007): default 1 MB; only a whole number the stack allows, else an error naming the largest size; 0 turns the check off and deep recursion crashes | code (patch 0007); probe on the 1.3.7-2 host build: `'abc'`, `-1`, `1.5`, `NULL`, `1073741824` and `16777216` give the error, `262144` and `1048576` are accepted, endless recursion fails cleanly just below the largest size; package run-tests.sh AC13 (87 of 87). 1.3.7-1 instead crashed on text, 0, or sizes above the stack. Phone: 1.3.7-2 passed 87 of 87; 1.3.7-3 and 1.3.7-4 passed 91 of 91 on aarch64 and arm |
| `js_version(1)` is quickjs-ng | run (package AC2) |
| The eight fixes listed | code, package tests (patches 0001 to 0008) |
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
| `db.exec` result set: `next`, `get`, `name`, `columnCount`, `toArray` (remaining rows); the statement runs only at `next()`/`toArray()`; a run-time error makes `next()` and `toArray()` throw with SQLite's message (1.3.7-3, patch 0008); SQL errors are thrown by `db.exec` | run (the notes example: an INSERT without `next()` adds nothing; the colors example: a failed UNIQUE INSERT is caught with SQLite's message, and it fails on 1.3.7-2, which returns `added`); probe on the 1.3.7-4 host build: a failed UNIQUE INSERT through `next()` fails the query with `UNIQUE constraint failed: u.x`, caught as `InternalError`; `toArray()` over a 3-row query whose second row overflows (`abs(-9223372036854775808)`) fails with `integer overflow`; `next()` is `false` at the end of the rows and after a successful INSERT; `db.exec("SELEC 1")` throws `near "SELEC": syntax error`. Before 0008, run-time errors were dropped: `next()` returned `false` and `toArray()` the rows before the error. Code: `js_rowset_next`/`js_rowset_to_array` throw unless `sqlite3_step` returns `SQLITE_ROW` or `SQLITE_DONE` |
| Parameters convert like results | run (package AC6) |
| Variable sharing: `js_eval`, scalar functions, collations share; each aggregate has its own; aggregate and window functions cannot see `js_eval` variables, and can define what they need in `init` | run (AC3); probe on 1.3.7-4: after `globalThis.rate = 2`, an aggregate's and a window function's `step` fail with `rate is not defined`; `const rate = 2` in `init` gives 6 for rows 1 and 2 |
| Top-level `const`, `let` and `class` names last for the session, so running the code again fails with `redeclaration of`; braces keep them to one run and still return the last expression | run: `test_examples.py` runs each example that creates nothing twice; before the braces it failed with `redeclaration of 'name'`, `'total'`, `'half'`, `'result'` and `'Point'` |
| Ctrl-C works "even code that catches every error"; "`sqlite3` keeps running if you were typing commands" | run (AC8) |
| Stack overflow message `Maximum call stack size exceeded` | run by hand |
| `js_create_` reports syntax errors when the function is created | run (package AC5) |

## javascript-intro

| Claim | Check |
|-------|-------|
| Every snippet and its output, also when run a second time | run |
| Months count from 0; `true` shows as 1 | run (the Dates and Text examples) |
| `const` is never reassigned, `let` can be; arrow function syntax; one kind of number for whole numbers and fractions (BigInt aside, which the intro does not cover) | JavaScript language facts |

## Format

| Claim | Check |
|-------|-------|
| Pages render through `helpmanual-topic.peb` | rendered with Pebble 4.1.1, the app's engine before it was vendored, from a test database built by `apply.py`: the four pages, `terminal-top` and `cogo-top` render, with titles, unescaped bodies and working links. TODO: render in the app |
| `apply.py`: a second run changes nothing; it refuses a database below version 2; the Terminal page's link has a heading of its own | run on copies of documentation.db 3.0.0: the second run printed `unchanged` for each page and left the file byte-identical; one declaring 1.9.0 was refused with no row changed; `LastChange`'s `content-i` and `wholedb` rows were stamped, not added |
| Phone layout of tables and code blocks | body-only render in headless Chrome (code blocks wrap, tables fit); TODO: on a device at font scale 1.0 and 2.0 |
