-- ADFA-5088: Tooltips + Content rows for the Plugin Manager screen.
--
-- The Plugin Manager screen previously showed one shared tooltip
-- ("plugin.manager") for its toolbar, download icon, FAB, empty state,
-- and every plugin row. The corresponding code change replaces that
-- with a distinct TooltipTag per widget; this script adds the
-- documentation database rows those new tags look up.
--
-- None of these tags existed before (the old shared "plugin.manager"
-- tag itself had no Tooltips row either), so every row here is a plain
-- INSERT - there are no existing stubs to UPDATE.
--
-- All rows use categoryId = 1 ("ide"), languageId = 1 ("EN-us"),
-- contentTypeId = 12 ("text/html", brotli-compressed).
--
-- Apply against the real documentation.db:
--   sqlite3 documentation.db < ADFA-5088-plugin-manager-tooltips.sql
--
-- The Content section uses `.system echo "<html>" | brotli -Z > /tmp/x.br`
-- immediately before each `INSERT ... READFILE('/tmp/x.br')` so the
-- uncompressed HTML is visible in this script. `.system` and READFILE()
-- require the sqlite3 CLI (not a library binding). If `.system` is
-- disabled in your sqlite3 build, run the `echo ... | brotli -Z > file`
-- line yourself via a shell first, then run just the INSERT statements.

-- ---------------------------------------------------------------------
-- Tooltips: INSERTs (all new tags)
-- ---------------------------------------------------------------------

INSERT INTO Tooltips (categoryId, tag, summary, detail) VALUES (1, 'plugin.manager.toolbar',
  'Plugin Manager lists your installed plugins.',
  'Use this screen to install a new plugin, open a plugin''s details, or find more plugins. The back button returns to Preferences.');

INSERT INTO Tooltips (categoryId, tag, summary, detail) VALUES (1, 'plugin.manager.download',
  'Find more plugins to install.',
  'Opens a webpage where you can find plugins to add to Code on the Go.');

INSERT INTO Tooltips (categoryId, tag, summary, detail) VALUES (1, 'plugin.manager.fab.install',
  'Install a plugin from a file.',
  'Opens a file picker so you can choose a plugin package (a .cgp file) to install.');

INSERT INTO Tooltips (categoryId, tag, summary, detail) VALUES (1, 'plugin.manager.emptystate',
  'No plugins installed yet.',
  'This message appears when you have no plugins installed. Use the download icon to find plugins, or the + button to install one from a file.');

INSERT INTO Tooltips (categoryId, tag, summary, detail) VALUES (1, 'plugin.manager.list',
  'Your installed plugins.',
  'Each row below shows one installed plugin. Tap a row to see its details, or use its menu button for more actions.');

INSERT INTO Tooltips (categoryId, tag, summary, detail) VALUES (1, 'plugin.manager.item',
  'Tap for this plugin''s details.',
  'Shows this plugin''s name, status, and version. Tap the row to see full details, including its description and version history.');

INSERT INTO Tooltips (categoryId, tag, summary, detail) VALUES (1, 'plugin.manager.item.menu',
  'More actions for this plugin.',
  'Opens a menu with actions for this plugin, such as enable, disable, uninstall, or view details. Which actions appear depends on the plugin''s current state.');

-- ---------------------------------------------------------------------
-- Content: Tier 3 HTML pages, one INSERT per Tooltips row above.
-- ---------------------------------------------------------------------

.system echo "<p>Plugin Manager lists every plugin currently installed in Code on the Go.</p><p>From here you can install a new plugin from a file, find more plugins online, and open any installed plugin's details or actions.</p>" | brotli -Z > /tmp/adfa5088-pm-toolbar.br
INSERT INTO Content (path, languageId, contentTypeId, content) VALUES ('i/plugin/manager/toolbar', 1, 12, READFILE('/tmp/adfa5088-pm-toolbar.br'));

.system echo "<p>The download icon opens a webpage where you can find plugins to add to Code on the Go.</p><p>This opens in your browser or an in-app web view, outside Code on the Go itself.</p>" | brotli -Z > /tmp/adfa5088-pm-download.br
INSERT INTO Content (path, languageId, contentTypeId, content) VALUES ('i/plugin/manager/download', 1, 12, READFILE('/tmp/adfa5088-pm-download.br'));

.system echo "<p>The + button installs a plugin you already have as a file.</p><p>Tap it to open a file picker, choose a plugin package (a .cgp file), and confirm the install. This does not download anything - use the download icon first if you need to find a plugin file.</p>" | brotli -Z > /tmp/adfa5088-pm-fab-install.br
INSERT INTO Content (path, languageId, contentTypeId, content) VALUES ('i/plugin/manager/fab/install', 1, 12, READFILE('/tmp/adfa5088-pm-fab-install.br'));

.system echo "<p>This message appears when Plugin Manager has no plugins to show.</p><p>Use the download icon at the top of the screen to find plugins, or the + button to install a plugin file you already have.</p>" | brotli -Z > /tmp/adfa5088-pm-emptystate.br
INSERT INTO Content (path, languageId, contentTypeId, content) VALUES ('i/plugin/manager/emptystate', 1, 12, READFILE('/tmp/adfa5088-pm-emptystate.br'));

.system echo "<p>This list shows every plugin installed in Code on the Go, one row per plugin.</p><p>Each row shows the plugin's name, version, and current status. Tap a row for its details, or use its menu button for more actions.</p>" | brotli -Z > /tmp/adfa5088-pm-list.br
INSERT INTO Content (path, languageId, contentTypeId, content) VALUES ('i/plugin/manager/list', 1, 12, READFILE('/tmp/adfa5088-pm-list.br'));

.system echo "<p>This row represents one installed plugin.</p><p>It shows the plugin's name, version, and whether it is enabled, disabled, or failed to load. Tap the row to open its full details.</p>" | brotli -Z > /tmp/adfa5088-pm-item.br
INSERT INTO Content (path, languageId, contentTypeId, content) VALUES ('i/plugin/manager/item', 1, 12, READFILE('/tmp/adfa5088-pm-item.br'));

.system echo "<p>This button opens a menu of actions for this plugin.</p><p>Depending on the plugin's current state, the menu can include enabling it, disabling it, uninstalling it, or viewing its details.</p>" | brotli -Z > /tmp/adfa5088-pm-item-menu.br
INSERT INTO Content (path, languageId, contentTypeId, content) VALUES ('i/plugin/manager/item/menu', 1, 12, READFILE('/tmp/adfa5088-pm-item-menu.br'));
