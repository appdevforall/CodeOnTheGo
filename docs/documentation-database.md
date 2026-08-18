# Documentation Database

Reference for `documentation.db`, the SQLite database backing all in-app help: Tier 1/2 tooltips, plus the Tier 3 web content they link to, served by `WebServer`. Read this before touching anything under `localWebServer/`, `idetooltips/`, or `plugin-manager/.../documentation/`, or before writing/editing SQL against this database.

This is a **read-only, prebuilt** database — CoGo never creates or migrates its schema at runtime (see [ADR 0001](adr/0001-prefer-room-for-persistence.md), exception 1). The schema is owned by the separate `OfflineDocumentationTools` project (the `docdb-studio` tool); **never change it from this repo.**

## Where it lives

- Installed path: `context.getDatabasePath("documentation.db")` (`Environment.DOC_DB` in `common/.../utils/Environment.java`), i.e. the app's private `databases/` dir.
- Bundled as an asset and extracted on install/update by `BundledAssetsInstaller` / `SplitAssetsInstaller`.
- **Debug override:** if `/sdcard/Download/documentation.db` exists and is newer than the installed copy, `WebServer` and `ToolTipManager` swap to it at request time (`WebServer` compares timestamps at most once a second, not per request, since the path is FUSE-backed emulated storage) — a fast way to test a new database on-device without reinstalling. `WebServer`'s debug logging and experiment flags are also file-flag-gated under `/sdcard/Download/` (`CodeOnTheGo.webserver.debug`, `CodeOnTheGo.exp`, `CodeOnTheGo.webserver.cs0`).
- **Don't trust a local copy's on-disk schema or row content as ground truth without checking freshness first.** Any manually downloaded or debug-override copy is independent of git history — a stale one can have a different schema (e.g. missing `UNIQUE(path)` or `templateId`) or be missing rows that already exist in the current, maintained database. A stale copy caused a real near-miss in ADFA-5088: a SQL script validated against it would have silently overwritten curated production tooltip content for several tags. Diff or re-download before authoring SQL against a local copy's state, not just before shipping it.

## Schema

A **star schema**: a large fact table at the center, small dimension tables around it. There are two fact tables — `Content` (Tier 3 web content) and `Tooltips` (Tier 1/2 tooltips) — because they serve different lookup patterns.

### Tier 3: `Content` (the fact table)

```sql
CREATE TABLE Content (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    path TEXT NOT NULL,
    languageID INTEGER NOT NULL,
    content BLOB NOT NULL,
    contentTypeID INTEGER NOT NULL,
    templateId INTEGER,
    FOREIGN KEY (languageID) REFERENCES Languages(id),
    FOREIGN KEY (contentTypeID) REFERENCES ContentTypes(id),
    UNIQUE(path)
);
```

One row per file the web server can serve (HTML, CSS, JS, image, video, PDF, ...) — 30,000+ rows. Key points:

- **`path`** is the lookup key (indexed via the `UNIQUE` constraint) and is what `WebServer` matches the HTTP request path against. Paths carry a short source prefix to avoid collisions between doc sets, e.g. `k/index.html` (Kotlin) vs `j/index.html` (Java).
- **`content`** is compressed — Brotli for text-like formats, format-specific compression otherwise (images/video/fonts). `ContentTypes.compression` says which. Every migrated `Content` row with `ContentTypes.compression = 'brotli'` is Brotli-compressed against the single shared dictionary in `CompressionDictionary` (see below), converted in one pass by ADFA-5153 — but plugin-contributed Tier 3 rows (`PluginDocumentationManager`/`BrotliCompressor`, see below) are plain, dictionary-free Brotli, and there is no per-row flag distinguishing the two, because a dictionary-compressed stream and a plain one are not distinguishable at decode time by inspection. They *are* distinguishable by attempting the decode: attaching the *wrong* dictionary decodes without error to different bytes than were compressed (its backward distances resolve into real, just incorrect, bytes) — but attaching *no* dictionary to a stream that needs one reliably throws (`IOException`, "corrupted input"), since distances into the dictionary region are then out of bounds for any spec-compliant decoder. `WebServer` relies on exactly this: it tries the dictionary first and falls back to a plain decode on `IOException`, which correctly handles both dictionary-compressed and plain rows — but never rely on decode success/failure to detect a *wrong* dictionary, since that case is silent. Content over 1&nbsp;MB is split across multiple rows: the first row's path is the base path, continuation rows are `path-1`, `path-2`, ... (`languageId = 1`), reassembled by `WebServer` before returning.
- **`templateId`**: `0` (or unset) means `content` is legacy HTML with presentation baked in (the pre-CMS Release 0/1 format). A positive value means `content` is JSON *facts only*, rendered through the matching row in `Templates` (a Pebble template) — the ongoing move to a proper CMS that de-duplicates presentation across near-identical pages (e.g. `sin`/`cos` docs).
- The `UNIQUE(path)` constraint rejects any duplicate `path`, regardless of `languageID` — a second language for an existing path isn't supported yet (only `EN-us` currently exists). Getting there needs an upstream schema change to composite uniqueness on `(path, languageID)` (see *Known rough edges* below).

Dimensions: `Languages(id, value)` (4-letter codes, e.g. `EN-us`); `ContentTypes(id, value, compression)` (MIME type + compression scheme, ~30 rows).

### Tier 1/2: `Tooltips` (the other fact table)

```sql
CREATE TABLE Tooltips (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    categoryId INTEGER NOT NULL,
    tag TEXT NOT NULL,
    summary TEXT NOT NULL,
    detail TEXT NOT NULL,
    UNIQUE(categoryId, tag),
    FOREIGN KEY(categoryId) REFERENCES TooltipCategories(id)
);
```

- `summary` is Tier 1 (the initial popup), `detail` is Tier 2 (after "See more"); both may contain HTML.
- Looked up by `(categoryId, tag)`, which the IDE stamps on the UI widget that owns the tooltip. The `UNIQUE` constraint gives this lookup an index, so it's fast.
- `TooltipCategories(id, category)` is a tiny dimension table (four categories at the time of writing).
- `TooltipButtons(tooltipId, buttonNumberId, description, uri)` holds the Tier 3 links shown at the bottom of a Tier 2 tooltip — `tooltipId` -> `Tooltips.id`, `buttonNumberId` -> `TooltipButtonNumbers.id`. `uri` should resolve to a `Content.path` (after stripping `?query`/`#fragment`).
- `TooltipButtonNumbers(id)` exists only to pin a fixed, manually-assigned display order when a tooltip has multiple Tier 3 links. (Flagged in the source design doc as something worth redoing without a whole extra table.)

### Supporting tables

- **`CompressionDictionary(id, data)`** — single-row table (`id INTEGER PRIMARY KEY CHECK (id = 1)`) holding the raw Brotli dictionary every ADFA-5153-migrated `compression = 'brotli'` `Content` row is compressed against. Trained once, from a representative sample across the whole `Content` table, by `OfflineDocumentationTools`' `migrate_content_to_dictionary_brotli.py` / `populate_db.py` (never retrained after that — a dictionary-compressed row is only decodable against the exact dictionary it was compressed with, so replacing it would silently orphan every already-migrated row). Shipping the dictionary inside `documentation.db` itself, rather than as a separate bundled asset, keeps it version-locked to the content compressed against it. `WebServer` loads it lazily -- not merely from starting the server or swapping databases, but on the first content fetch that needs it after `database` changes -- and caches it from then on, reloading again only on the next database change (a swap can bring in a database with a different dictionary or none, so it can't stay cached across one). Per row, it tries decoding with the dictionary attached first via brotli4j's `attachDictionary`, falling back to a plain decode on failure — needed both for a database predating this migration (no `CompressionDictionary` table at all) and for plugin-contributed rows within an otherwise-migrated database (see `PluginDocumentationManager` below).
- **`Templates(id, name, content)`** — Pebble template source, keyed by id (and by `name` for well-known templates like `bookshelf`). Referenced by `Content.templateId`.
- **`Bookshelf(contentID, bookCategoryID, title, description)`** / **`BookCategories(id, category, description)`** — the Dynamic Bookshelf: one row per "book" (PDF or similar), linked to its Tier 3 page via `contentID` -> `Content.id`. Two DB triggers keep `Bookshelf` in sync when a PDF row is inserted/deleted from `Content`; `title`/`description` don't come from those triggers and must be set by hand. Non-PDF books need a separate ingestion path (plugin-provided, e.g. via `PluginDocumentationManager`).
- **`LastChange(documentationSet, changeTime, who)`** — audit trail for edits made through `docdb-studio`; not shown to end users. `DatabaseVersionResolver` reads the `documentationSet = 'wholedb'` row to report the DB's build/edit stamp in debug logging, falling back to the most recent row of any set if `'wholedb'` is missing.
- Misc `ide_tooltip_table` and `PUCC` tables are historical/example artifacts — not part of the live lookup paths above.

## How CoGo talks to this database

All three sites below open the file with `SQLiteDatabase.openDatabase(..., OPEN_READONLY)` — no writes, ever, from this app (see ADR 0001 for why raw SQLite is justified here instead of Room).

- **`app/.../localWebServer/WebServer.kt`** — serves Tier 3 over HTTP on port 6174, for WebViews that aren't wired to the interceptor above and for the `/pr/` developer endpoints. Requests are handled on a small worker pool, not on the accept loop. It reads through `DocumentationContentSource` and adds only HTTP framing. The query the source runs is:

  ```sql
  SELECT C.content, CT.value, CT.compression, C.templateId
  FROM   Content C, ContentTypes CT
  WHERE  C.contentTypeID = CT.id
  AND    C.path = ?
  ```

  then reassembles chunked blobs, always decompresses Brotli content (attaching `CompressionDictionary`'s bytes first, if loaded — see above) since neither transport negotiates `Content-Encoding` with the client, and instantiates the template if `templateId > 0`. Also serves a Dynamic Bookshelf JSON payload (joining `Content`/`Bookshelf`/`BookCategories`, rendered through the `bookshelf` template) and debug-only HTML dumps at `/pr/db` (`LastChange`, last 20 rows) and `/pr/pr` (recent projects, from a *different* database).
- **`common/.../documentation/DocumentationContentSource.kt`** — the one pipeline that reads this database: row lookup, chunked-row reassembly, dictionary-aware Brotli decode, and the sdcard debug-database swap, under a read/write lock so several threads can read while a swap can't close the handle under them. It also renders the rows that are Pebble template contexts (`templateId > 0`, the Kotlin doc set's pages), so both transports below serve finished pages and neither needs the template engine itself. All of that logic exists once.
- **`common/.../documentation/DocumentationRequestInterceptor.kt`** — serves Tier 3 *in-process* for the app's WebViews (`HelpActivity`, the tooltip fragment, `FAQActivity`), through `WebViewClient.shouldInterceptRequest`, so a page's assets cost a database read instead of a TCP connection each (ADFA-5176). It matches the same `http://localhost:6174/...` URL space, so the strings.xml entries, `ToolTipManager`'s link builder and the `DocumentationExtension` contract need no changes; anything it declines — a templated page, a `/pr/` endpoint, an unknown path, a failed read — falls through to `WebServer` unchanged. Set `/sdcard/Download/CodeOnTheGo.nointercept` to force documentation back onto the server.
- **`idetooltips/.../ToolTipManager.kt`** — serves Tier 1/2. Looks up `Tooltips` joined to `TooltipCategories` by `(category, tag)`, then `TooltipButtons` for the Tier 3 links shown at the bottom.
- **`plugin-manager/.../documentation/PluginDocumentationManager.kt`** (with `Tier3AssetWalker.kt`, and the `DocumentationExtension` contract in `plugin-api`) — lets plugins contribute their own help content into the same lookup paths.

## Editing the database

Schema changes and data edits happen **outside this repo**, in `OfflineDocumentationTools/docdb-studio` (a Flet GUI over this same `documentation.db`). Conventions enforced there that matter if you're reasoning about data correctness here:

- The schema is locked — `docdb-studio`'s own `AGENTS.md` says never change it. If a new column/table is genuinely needed, it's a cross-repo change coordinated with that project, not something to route around in CoGo.
- Tooltip uniqueness is `(categoryId, tag)`; `TooltipButtons.uri` values are validated there against `Content.path` (post `?query`/`#fragment` stripping) before being allowed into the database.
- Every edit made through the tool updates `LastChange` for the affected documentation set, which is how `DatabaseVersionResolver`'s debug logging can say what build of the docs is loaded.

### Writing one-off SQL scripts against this database

Some tickets (e.g. ADFA-5088) ship a one-off `.sql` script under `docs/docdb/` for a `docdb-studio` maintainer to run against the real database, rather than editing it directly through the tool. Gotchas found writing those scripts:

- **Keep each `.system` line simple.** The sqlite3 CLI's `.system` dot-command can hit a content-dependent shell-parsing failure when a line chains multiple operators (`;`, `&&`, `||`, parentheses) — it reproduces for some input strings and not others, so it won't necessarily show up in a quick test. Stick to one plain `command | pipe > file` per `.system` line.
- **`.bail on` is required for `BEGIN`/`COMMIT` to actually mean atomic.** Without it, a mid-script SQL error prints to stderr but the script *keeps going* — including reaching the final `COMMIT`, which then persists whatever succeeded before the error (verified empirically, not just documented behavior). `.bail` also can't see `.system` shell failures directly, so a failed or empty Brotli payload (which leaves its target file missing or zero-length) needs its own check: insert its `READFILE()` into a throwaway `CREATE TEMP TABLE` guarded by `NOT NULL CHECK (length(content) > 0)` immediately before the real `Content` insert, turning that failure into a real SQL error `.bail` will catch. See `docs/docdb/ADFA-5088-preference-tooltips.sql` for the working pattern.
- **Don't write Brotli payloads to bare `/tmp/*.br` filenames.** A fixed, guessable name directly under world-writable `/tmp` lets another local user pre-plant a symlink or race the write/read pair between the `.system echo | brotli` write and the `READFILE()` read (CWE-377). Create an owner-only working directory instead — `rm -rf` it, then `mkdir -m 700` it (the mode is set atomically at creation, with no window where it's briefly world-accessible) — write every payload under that directory, and remove it again before `COMMIT`. See the same script for the working pattern.

## Known rough edges

- A Tier 3 link that points off-device is a bug in the *content*, not the code — the web server and webview will happily follow it. If you see one while working in this area, it's a data problem to report upstream, not a `WebServer` bug to fix here.
- `Content`'s `UNIQUE(path)` constraint (rather than `UNIQUE(path, languageID)`) means a second language for an existing path can't currently be added without a schema change upstream — multi-language content isn't fully wired yet even though the `Languages` dimension anticipates it.
- `TooltipButtonNumbers` is a whole table whose only job is pinning a manual sort order; a lighter-weight mechanism (e.g. an ordering column directly on `TooltipButtons`) would remove a table.
