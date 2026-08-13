-- ADFA-5088: Tooltips + Content rows for the Plugin Manager screen.
--
-- The Plugin Manager screen previously showed one shared tooltip
-- ("plugin.manager") for its toolbar, download icon, FAB, empty state,
-- and every plugin row. The corresponding code change replaces that
-- with a distinct TooltipTag per widget; this script adds the
-- documentation database rows those new tags look up.
--
-- None of the new tags below existed before. The old shared "plugin.manager"
-- tag (a different string from every tag below) is now dead - no code
-- references it any more - so this script deletes it and its one
-- TooltipButtons row, but leaves the Content page that button linked to
-- (i/plugin-install.html) in place, since Content isn't clearly unreachable
-- the way the Tooltips/TooltipButtons rows are.
--
-- Both Tooltips (UNIQUE(categoryId, tag)) and Content (UNIQUE(path)) are
-- idempotent `INSERT ... ON CONFLICT ... DO UPDATE` upserts below, so the
-- whole script is safe to re-run.
--
-- All rows use categoryId = 1 ("ide"), languageId = 1 ("EN-us"),
-- contentTypeId = 12 ("text/html", brotli-compressed).
--
-- Apply against the real documentation.db:
--   sqlite3 documentation.db < ADFA-5088-plugin-manager-tooltips.sql
-- The whole script runs inside one transaction (BEGIN/COMMIT below) with
-- `.bail on`, so any failure - a bad SQL statement, or a Brotli payload
-- caught by the guard below - stops the script and leaves the database
-- untouched (the open transaction rolls back when the connection closes)
-- rather than half-applied.
--
-- Every Brotli payload is written under /tmp/adfa5088-pm-workdir, an
-- owner-only (mode 700) directory this script creates fresh and removes
-- at the end - not bare /tmp filenames, which are guessable and world-
-- writable, so another local user could pre-plant a symlink or race the
-- write/read pair (CWE-377). `mkdir -m` sets the mode atomically at
-- creation, and the preceding `rm -rf` means each run starts from a
-- directory it fully owns rather than trusting one left over from an
-- earlier run.
--
-- For each Content row: `.system rm -f <workdir>/x.br` clears any stale
-- file, `.system echo "<html>" | brotli -Z > <workdir>/x.br` writes the
-- compressed payload (the uncompressed HTML is visible right there in
-- the command), then `INSERT INTO _content_guard SELECT
-- READFILE('<workdir>/x.br')` is a deliberate assertion: `.system`
-- failures aren't SQL errors and `.bail` can't see them directly, but a
-- failed or empty Brotli run leaves the file missing or empty, and
-- _content_guard's `NOT NULL` + `CHECK (length(content) > 0)` turn that
-- into a real SQL error `.bail` does catch - before the real `INSERT INTO
-- Content` below it can run with bad data. _content_guard is a TEMP
-- table: connection-local, dropped automatically, never touches the real
-- schema.
--
-- `.system`, `.bail`, and `READFILE()` require the sqlite3 CLI (not a
-- library binding). If `.system` is disabled in your sqlite3 build,
-- create the mode-700 working directory and run each `rm -f`/`echo ... |
-- brotli -Z > file` pair yourself via a shell first, then run just the
-- INSERT statements (the _content_guard assertion becomes redundant at
-- that point - the file either exists and is non-empty by the time you
-- run the script, or you'd have already seen the shell command fail).

.bail on
BEGIN;

-- A temp table (connection-local, never touches the real schema) whose
-- CHECK constraint turns a silently-empty or failed Brotli payload into a
-- real SQL error .bail can catch, before it ever reaches the real Content
-- table.
CREATE TEMP TABLE _content_guard (content BLOB NOT NULL CHECK (length(content) > 0));

-- Route every Brotli payload through an owner-only (mode 700) working
-- directory instead of bare /tmp filenames: a fixed name under world-
-- writable /tmp is guessable, so another local user could pre-plant a
-- symlink or race the write/read pair. mkdir -m sets the mode atomically
-- at creation (no separate chmod, no window with a wider mode); the prior
-- rm -rf makes each run start from a clean directory it fully owns,
-- rather than trusting one left over from an earlier run.
.system rm -rf /tmp/adfa5088-pm-workdir
.system mkdir -m 700 /tmp/adfa5088-pm-workdir

-- ---------------------------------------------------------------------
-- Remove the dead "plugin.manager" tag: no code path can reach it any
-- more now that every widget has its own tag. Delete the TooltipButtons
-- row first (it references Tooltips.id via a foreign key).
-- ---------------------------------------------------------------------

DELETE FROM TooltipButtons
WHERE tooltipId = (SELECT id FROM Tooltips WHERE tag = 'plugin.manager' AND categoryId = 1);

DELETE FROM Tooltips WHERE tag = 'plugin.manager' AND categoryId = 1;

-- ---------------------------------------------------------------------
-- Tooltips: idempotent upserts (all new tags)
-- ---------------------------------------------------------------------

INSERT INTO Tooltips (categoryId, tag, summary, detail) VALUES (1, 'plugin.manager.toolbar', 'Plugin Manager lists your installed plugins.', 'Use this screen to install a new plugin, open a plugin''s details, or find more plugins. The back button returns to Preferences.')
  ON CONFLICT (categoryId, tag) DO UPDATE SET summary = excluded.summary, detail = excluded.detail;

INSERT INTO Tooltips (categoryId, tag, summary, detail) VALUES (1, 'plugin.manager.download', 'Find more plugins to install.', 'Opens a webpage where you can find plugins to add to Code on the Go.')
  ON CONFLICT (categoryId, tag) DO UPDATE SET summary = excluded.summary, detail = excluded.detail;

INSERT INTO Tooltips (categoryId, tag, summary, detail) VALUES (1, 'plugin.manager.fab.install', 'Install a plugin from a file.', 'Opens a file picker so you can choose a plugin package (a .cgp file) to install.')
  ON CONFLICT (categoryId, tag) DO UPDATE SET summary = excluded.summary, detail = excluded.detail;

INSERT INTO Tooltips (categoryId, tag, summary, detail) VALUES (1, 'plugin.manager.emptystate', 'No plugins installed yet.', 'This message appears when you have no plugins installed. Use the download icon to find plugins, or the + button to install one from a file.')
  ON CONFLICT (categoryId, tag) DO UPDATE SET summary = excluded.summary, detail = excluded.detail;

INSERT INTO Tooltips (categoryId, tag, summary, detail) VALUES (1, 'plugin.manager.list', 'Your installed plugins.', 'Each row below shows one installed plugin. Tap a row to see its details, or use its menu button for more actions.')
  ON CONFLICT (categoryId, tag) DO UPDATE SET summary = excluded.summary, detail = excluded.detail;

INSERT INTO Tooltips (categoryId, tag, summary, detail) VALUES (1, 'plugin.manager.item', 'Tap for this plugin''s details.', 'Shows this plugin''s name, status, and version. Tap the row to see full details, including its description and version history.')
  ON CONFLICT (categoryId, tag) DO UPDATE SET summary = excluded.summary, detail = excluded.detail;

INSERT INTO Tooltips (categoryId, tag, summary, detail) VALUES (1, 'plugin.manager.item.menu', 'More actions for this plugin.', 'Opens a menu with actions for this plugin, such as enable, disable, uninstall, or view details. Which actions appear depends on the plugin''s current state.')
  ON CONFLICT (categoryId, tag) DO UPDATE SET summary = excluded.summary, detail = excluded.detail;

-- ---------------------------------------------------------------------
-- Content: Tier 3 HTML pages, one INSERT per Tooltips row above.
-- ---------------------------------------------------------------------

.system rm -f /tmp/adfa5088-pm-workdir/adfa5088-pm-toolbar.br
.system echo "<p>Plugin Manager lists every plugin currently installed in Code on the Go.</p><p>From here you can install a new plugin from a file, find more plugins online, and open any installed plugin's details or actions.</p>" | brotli -Z > /tmp/adfa5088-pm-workdir/adfa5088-pm-toolbar.br
INSERT INTO _content_guard SELECT READFILE('/tmp/adfa5088-pm-workdir/adfa5088-pm-toolbar.br');
INSERT INTO Content (path, languageId, contentTypeId, content) VALUES ('i/plugin/manager/toolbar', 1, 12, READFILE('/tmp/adfa5088-pm-workdir/adfa5088-pm-toolbar.br')) ON CONFLICT (path) DO UPDATE SET content = excluded.content;

.system rm -f /tmp/adfa5088-pm-workdir/adfa5088-pm-download.br
.system echo "<p>The download icon opens a webpage where you can find plugins to add to Code on the Go.</p><p>This opens in your browser or an in-app web view, outside Code on the Go itself.</p>" | brotli -Z > /tmp/adfa5088-pm-workdir/adfa5088-pm-download.br
INSERT INTO _content_guard SELECT READFILE('/tmp/adfa5088-pm-workdir/adfa5088-pm-download.br');
INSERT INTO Content (path, languageId, contentTypeId, content) VALUES ('i/plugin/manager/download', 1, 12, READFILE('/tmp/adfa5088-pm-workdir/adfa5088-pm-download.br')) ON CONFLICT (path) DO UPDATE SET content = excluded.content;

.system rm -f /tmp/adfa5088-pm-workdir/adfa5088-pm-fab-install.br
.system echo "<p>The + button installs a plugin you already have as a file.</p><p>Tap it to open a file picker, choose a plugin package (a .cgp file), and confirm the install. This does not download anything - use the download icon first if you need to find a plugin file.</p>" | brotli -Z > /tmp/adfa5088-pm-workdir/adfa5088-pm-fab-install.br
INSERT INTO _content_guard SELECT READFILE('/tmp/adfa5088-pm-workdir/adfa5088-pm-fab-install.br');
INSERT INTO Content (path, languageId, contentTypeId, content) VALUES ('i/plugin/manager/fab/install', 1, 12, READFILE('/tmp/adfa5088-pm-workdir/adfa5088-pm-fab-install.br')) ON CONFLICT (path) DO UPDATE SET content = excluded.content;

.system rm -f /tmp/adfa5088-pm-workdir/adfa5088-pm-emptystate.br
.system echo "<p>This message appears when Plugin Manager has no plugins to show.</p><p>Use the download icon at the top of the screen to find plugins, or the + button to install a plugin file you already have.</p>" | brotli -Z > /tmp/adfa5088-pm-workdir/adfa5088-pm-emptystate.br
INSERT INTO _content_guard SELECT READFILE('/tmp/adfa5088-pm-workdir/adfa5088-pm-emptystate.br');
INSERT INTO Content (path, languageId, contentTypeId, content) VALUES ('i/plugin/manager/emptystate', 1, 12, READFILE('/tmp/adfa5088-pm-workdir/adfa5088-pm-emptystate.br')) ON CONFLICT (path) DO UPDATE SET content = excluded.content;

.system rm -f /tmp/adfa5088-pm-workdir/adfa5088-pm-list.br
.system echo "<p>This list shows every plugin installed in Code on the Go, one row per plugin.</p><p>Each row shows the plugin's name, version, and current status. Tap a row for its details, or use its menu button for more actions.</p>" | brotli -Z > /tmp/adfa5088-pm-workdir/adfa5088-pm-list.br
INSERT INTO _content_guard SELECT READFILE('/tmp/adfa5088-pm-workdir/adfa5088-pm-list.br');
INSERT INTO Content (path, languageId, contentTypeId, content) VALUES ('i/plugin/manager/list', 1, 12, READFILE('/tmp/adfa5088-pm-workdir/adfa5088-pm-list.br')) ON CONFLICT (path) DO UPDATE SET content = excluded.content;

.system rm -f /tmp/adfa5088-pm-workdir/adfa5088-pm-item.br
.system echo "<p>This row represents one installed plugin.</p><p>It shows the plugin's name, version, and whether it is enabled, disabled, or failed to load. Tap the row to open its full details.</p>" | brotli -Z > /tmp/adfa5088-pm-workdir/adfa5088-pm-item.br
INSERT INTO _content_guard SELECT READFILE('/tmp/adfa5088-pm-workdir/adfa5088-pm-item.br');
INSERT INTO Content (path, languageId, contentTypeId, content) VALUES ('i/plugin/manager/item', 1, 12, READFILE('/tmp/adfa5088-pm-workdir/adfa5088-pm-item.br')) ON CONFLICT (path) DO UPDATE SET content = excluded.content;

.system rm -f /tmp/adfa5088-pm-workdir/adfa5088-pm-item-menu.br
.system echo "<p>This button opens a menu of actions for this plugin.</p><p>Depending on the plugin's current state, the menu can include enabling it, disabling it, uninstalling it, or viewing its details.</p>" | brotli -Z > /tmp/adfa5088-pm-workdir/adfa5088-pm-item-menu.br
INSERT INTO _content_guard SELECT READFILE('/tmp/adfa5088-pm-workdir/adfa5088-pm-item-menu.br');
INSERT INTO Content (path, languageId, contentTypeId, content) VALUES ('i/plugin/manager/item/menu', 1, 12, READFILE('/tmp/adfa5088-pm-workdir/adfa5088-pm-item-menu.br')) ON CONFLICT (path) DO UPDATE SET content = excluded.content;

.system rm -rf /tmp/adfa5088-pm-workdir
COMMIT;
