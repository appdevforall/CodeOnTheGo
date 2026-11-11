# Claims in the ADFA-7083 pages and how each was checked

`run` = executed by `test_examples.py` against the sqlite-js 1.3.7-1 host build. `code` = read in the patched source. `TODO` = still to verify, for the accuracy review.

## sqlite-js-top

| Claim | Check |
|-------|-------|
| `apt install sqlite sqlite-js` installs both; the `sqlite` package provides `sqlite3` | TODO: package names and apt behavior on a phone (ADFA-7081 phone test) |
| `.load sqlite-js` finds `$PREFIX/lib/sqlite-js.so`; `.load` does not expand `$PREFIX` | TODO phone; same claim as sqlite-brotli's README, verified there |
| `js_create_scalar` prints 0; slugify and titles examples | run |
| A function lasts until `sqlite3` closes | run (functions page) |
| `js_init_table(1)` runs stored code; `db.exec` can run any SQL including `readfile()`/`writefile()` | code; TODO: `readfile`/`writefile` exist in the Termux `sqlite3` shell |
| Ctrl-C stops code, query fails with `interrupted` | run (package run-tests AC8) |
| 64 MB memory limit; `out of memory` | run (AC9) |
| Do not put `.load` in `~/.sqliterc` | advice, follows from the above |

## sqlite-js-functions

| Claim | Check |
|-------|-------|
| Each `js_` function but `js_version` works only in top-level SQL | run (AC10, flags check) |
| Examples for scalar, aggregate (per-group state), window, collation, `js_eval`, `js_init_table`, `js_version` | run |
| Redefining in the same session fails with the quoted message | run (package AC4) |
| `init` can be NULL | code (`init_code` optional); TODO: add a run example |
| `value`/`inverse` semantics | code, run |
| Collation: only the sign counts; a throwing collation compares as equal | code (patch history, 1.3.6 changelog); TODO run |
| `js_init_table()` columns and "whenever that table exists, each function you create is stored" | code (`js_add_to_table`: returns early if the table is missing); TODO: run a case where the table exists from an earlier session and `js_init_table()` is not called |
| `js_load_text`/`js_load_blob`: empty file gives empty value; error text `Unable to open the file` | code; TODO run |
| `js_set_memory_limit`: default 64 MB, 0 no limit, integer only, error text, returns NULL | run (package AC9), code |
| `js_set_max_stack_size`: default 1 MB; 0 crashes deep recursion | code (`JS_DEFAULT_STACK_SIZE`); run by hand: size 0 + recursion segfaults, default gives "Maximum call stack size exceeded" |
| `js_version(1)` is quickjs-ng | run (package AC2) |
| The six fixes listed | code, package tests |
| Apache-2.0 attribution wording | TODO: legal wording check; upstream names no copyright holder |

## sqlite-js-javascript

| Claim | Check |
|-------|-------|
| Engine supports const/let, arrow functions, classes, template strings, destructuring, Map/Set, BigInt, regexes, JSON, Math, Date, typed arrays | run: class/destructuring/template/map example; TODO: probe Map, Set, BigInt, Date, typed arrays individually |
| `window`, `fetch`, `setTimeout`, `require`, `process` undefined | run (also `document`, `alert`, `Buffer` named in prose: TODO) |
| Promises never finish | run (package AC11) |
| "JavaScript has no file, network or process functions of its own" | run (AC11: `std`, `os` not reachable) |
| `console.log` and `print` write to `sqlite3` standard output | run by hand (`print` TODO) |
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
