# Documentation Database

Reference for `documentation.db`, the SQLite database backing all in-app help: Tier 1/2 tooltips, plus the Tier 3 web content they link to, served by `WebServer`. Read this before touching anything under `localWebServer/`, `idetooltips/`, or `plugin-manager/.../documentation/`, or before writing/editing SQL against this database.

This is a **read-only, prebuilt** database — CoGo never creates or migrates its schema at runtime (see [ADR 0001](adr/0001-prefer-room-for-persistence.md), exception 1). The schema is owned by the separate `OfflineDocumentationTools` project (the `docdb-studio` tool); **never change it from this repo.**

## Where it lives

- Installed path: `context.getDatabasePath("documentation.db")` (`Environment.DOC_DB` in `common/.../utils/Environment.java`), i.e. the app's private `databases/` dir.
- Bundled as an asset and extracted on install/update by `BundledAssetsInstaller` / `SplitAssetsInstaller`.
- **Debug override:** if `/sdcard/Download/documentation.db` exists and is newer than the installed copy, `WebServer` and `ToolTipManager` swap to it at request time (timestamp-compared per request, not just at startup) — a fast way to test a new database on-device without reinstalling. `WebServer`'s debug logging and experiment flags are also file-flag-gated under `/sdcard/Download/` (`CodeOnTheGo.webserver.debug`, `CodeOnTheGo.exp`, `CodeOnTheGo.webserver.cs0`).

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
- **`content`** is compressed — Brotli for text-like formats, format-specific compression otherwise (images/video/fonts). `ContentTypes.compression` says which. Content over 1&nbsp;MB is split across multiple rows: the first row's path is the base path, continuation rows are `path-1`, `path-2`, ... (`languageId = 1`), reassembled by `WebServer` before returning.
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

- **`Templates(id, name, content)`** — Pebble template source, keyed by id (and by `name` for well-known templates like `bookshelf`). Referenced by `Content.templateId`.
- **`Bookshelf(contentID, bookCategoryID, title, description)`** / **`BookCategories(id, category, description)`** — the Dynamic Bookshelf: one row per "book" (PDF or similar), linked to its Tier 3 page via `contentID` -> `Content.id`. Two DB triggers keep `Bookshelf` in sync when a PDF row is inserted/deleted from `Content`; `title`/`description` don't come from those triggers and must be set by hand. Non-PDF books need a separate ingestion path (plugin-provided, e.g. via `PluginDocumentationManager`).
- **`LastChange(documentationSet, changeTime, who)`** — audit trail for edits made through `docdb-studio`; not shown to end users. `DatabaseVersionResolver` reads the `documentationSet = 'wholedb'` row to report the DB's build/edit stamp in debug logging, falling back to the most recent row of any set if `'wholedb'` is missing.
- Misc `ide_tooltip_table` and `PUCC` tables are historical/example artifacts — not part of the live lookup paths above.

## How CoGo talks to this database

All three sites below open the file with `SQLiteDatabase.openDatabase(..., OPEN_READONLY)` — no writes, ever, from this app (see ADR 0001 for why raw SQLite is justified here instead of Room).

- **`app/.../localWebServer/WebServer.kt`** — serves Tier 3. On each `GET`, runs:

  ```sql
  SELECT C.content, CT.value, CT.compression, C.templateId
  FROM   Content C, ContentTypes CT
  WHERE  C.contentTypeID = CT.id
  AND    C.path = ?
  ```

  then reassembles chunked blobs, decompresses Brotli when the client can't accept it (or when a Pebble template needs a string to render), and instantiates the template if `templateId > 0`. Also serves a Dynamic Bookshelf JSON payload (joining `Content`/`Bookshelf`/`BookCategories`, rendered through the `bookshelf` template) and debug-only HTML dumps at `/pr/db` (`LastChange`, last 20 rows) and `/pr/pr` (recent projects, from a *different* database).
- **`idetooltips/.../ToolTipManager.kt`** — serves Tier 1/2. Looks up `Tooltips` joined to `TooltipCategories` by `(category, tag)`, then `TooltipButtons` for the Tier 3 links shown at the bottom.
- **`plugin-manager/.../documentation/PluginDocumentationManager.kt`** (with `Tier3AssetWalker.kt`, and the `DocumentationExtension` contract in `plugin-api`) — lets plugins contribute their own help content into the same lookup paths.

## Editing the database

Schema changes and data edits happen **outside this repo**, in `OfflineDocumentationTools/docdb-studio` (a Flet GUI over this same `documentation.db`). Conventions enforced there that matter if you're reasoning about data correctness here:

- The schema is locked — `docdb-studio`'s own `AGENTS.md` says never change it. If a new column/table is genuinely needed, it's a cross-repo change coordinated with that project, not something to route around in CoGo.
- Tooltip uniqueness is `(categoryId, tag)`; `TooltipButtons.uri` values are validated there against `Content.path` (post `?query`/`#fragment` stripping) before being allowed into the database.
- Every edit made through the tool updates `LastChange` for the affected documentation set, which is how `DatabaseVersionResolver`'s debug logging can say what build of the docs is loaded.

## Known rough edges

- A Tier 3 link that points off-device is a bug in the *content*, not the code — the web server and webview will happily follow it. If you see one while working in this area, it's a data problem to report upstream, not a `WebServer` bug to fix here.
- `Content`'s `UNIQUE(path)` constraint (rather than `UNIQUE(path, languageID)`) means a second language for an existing path can't currently be added without a schema change upstream — multi-language content isn't fully wired yet even though the `Languages` dimension anticipates it.
- `TooltipButtonNumbers` is a whole table whose only job is pinning a manual sort order; a lighter-weight mechanism (e.g. an ordering column directly on `TooltipButtons`) would remove a table.
