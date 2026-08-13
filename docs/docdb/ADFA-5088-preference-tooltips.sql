-- ADFA-5088: Tooltips + Content rows for every Preferences menu item.
--
-- Deliverable 1 (already merged) tags every Preferences row with its own
-- idetooltips TooltipTag.PREFS_* constant. This script fills in the
-- documentation database rows those tags look up: a Tooltips row per tag
-- (Tier 1 `summary` + Tier 2 `detail`) and a matching Content row (Tier 3
-- HTML page) at a new, item-granular `i/prefs/...` path.
--
-- Deliberately NOT included: prefs.general, prefs.editor, prefs.editor.xml,
-- prefs.termux, and prefs.git already exist in the real documentation.db
-- with good, real (non-empty) summary/detail text -- these tags are reused
-- as-is for the corresponding screen row, so this script leaves them alone
-- rather than overwriting curated production content with a draft.
--
-- Both Tooltips (UNIQUE(categoryId, tag)) and Content (UNIQUE(path)) are
-- idempotent `INSERT ... ON CONFLICT ... DO UPDATE` upserts below, so the
-- whole script is safe to re-run.
--
-- All rows use categoryId = 1 ("ide"), languageId = 1 ("EN-us"),
-- contentTypeId = 12 ("text/html", brotli-compressed).
--
-- Apply against the real documentation.db:
--   sqlite3 documentation.db < ADFA-5088-preference-tooltips.sql
-- The whole script runs inside one transaction (BEGIN/COMMIT below) with
-- `.bail on`, so any failure - a bad SQL statement, or a Brotli payload
-- caught by the guard below - stops the script and leaves the database
-- untouched (the open transaction rolls back when the connection closes)
-- rather than half-applied.
--
-- Every Brotli payload is written under /tmp/adfa5088-prefs-workdir, an
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
-- rather than trusting one left over from an earlier run. mkdir itself
-- can fail without .bail seeing it (e.g. another process recreates the
-- path between the rm -rf and the mkdir), so assert the mode really is
-- 700 before trusting the directory with anything - the same guard-table
-- trick _content_guard uses for the Brotli payloads below.
.system rm -rf /tmp/adfa5088-prefs-workdir
.system mkdir -m 700 /tmp/adfa5088-prefs-workdir
.system stat --printf='%a' /tmp/adfa5088-prefs-workdir > /tmp/adfa5088-prefs-workdir/.mode
CREATE TEMP TABLE _workdir_guard (mode TEXT NOT NULL CHECK (mode = '700'));
INSERT INTO _workdir_guard SELECT CAST(READFILE('/tmp/adfa5088-prefs-workdir/.mode') AS TEXT);

-- Tooltips: idempotent upserts (existing empty stub rows + brand new tags,
-- all in one form so the script can be re-run safely)

INSERT INTO Tooltips (categoryId, tag, summary, detail) VALUES (1, 'prefs.general.uimode', 'Choose light mode, dark mode, or match your device''s system setting.', 'This setting controls the color theme of the app. Pick Light, Dark, or Follow system. Follow system switches automatically when your device''s theme changes.')
  ON CONFLICT (categoryId, tag) DO UPDATE SET summary = excluded.summary, detail = excluded.detail;

INSERT INTO Tooltips (categoryId, tag, summary, detail) VALUES (1, 'prefs.general.language', 'Choose the display language for Code on the Go.', 'This setting changes the language of the app menus and text. Pick System Default to use your device language, or choose a specific language from the list.')
  ON CONFLICT (categoryId, tag) DO UPDATE SET summary = excluded.summary, detail = excluded.detail;

INSERT INTO Tooltips (categoryId, tag, summary, detail) VALUES (1, 'prefs.general.openlast', 'Reopen your last project automatically when the app starts.', 'When on, Code on the Go opens the last project you worked on as soon as it starts. When off, the app shows the project list instead.')
  ON CONFLICT (categoryId, tag) DO UPDATE SET summary = excluded.summary, detail = excluded.detail;

INSERT INTO Tooltips (categoryId, tag, summary, detail) VALUES (1, 'prefs.general.confirmopen', 'Ask for confirmation before opening your last project.', 'When on, Code on the Go shows a confirmation prompt before it reopens your last project. This gives you a chance to go to the project list instead.')
  ON CONFLICT (categoryId, tag) DO UPDATE SET summary = excluded.summary, detail = excluded.detail;

INSERT INTO Tooltips (categoryId, tag, summary, detail) VALUES (1, 'prefs.editor.fontsize', 'Set the editor text size.', 'This changes the size of code text in the editor, measured in sp (scale-independent pixels). Use a slider to pick a value between 6 and 32.')
  ON CONFLICT (categoryId, tag) DO UPDATE SET summary = excluded.summary, detail = excluded.detail;

INSERT INTO Tooltips (categoryId, tag, summary, detail) VALUES (1, 'prefs.editor.tabsize', 'Set how many spaces one tab indents.', 'This sets the number of spaces the editor uses for each indent level. Choose from 2, 4, 6, or 8 spaces.')
  ON CONFLICT (categoryId, tag) DO UPDATE SET summary = excluded.summary, detail = excluded.detail;

INSERT INTO Tooltips (categoryId, tag, summary, detail) VALUES (1, 'prefs.editor.nonprinting', 'Choose which whitespace and line-break marks the editor shows.', 'This opens a dialog with checkboxes for leading, trailing, inner, and empty-line whitespace, plus line-break marks. Turn on any you want the editor to display.')
  ON CONFLICT (categoryId, tag) DO UPDATE SET summary = excluded.summary, detail = excluded.detail;

INSERT INTO Tooltips (categoryId, tag, summary, detail) VALUES (1, 'prefs.editor.softtab', 'Insert spaces instead of a tab character when you press Tab.', 'When on, pressing the Tab key inserts spaces. When off, it inserts a tab character.')
  ON CONFLICT (categoryId, tag) DO UPDATE SET summary = excluded.summary, detail = excluded.detail;

INSERT INTO Tooltips (categoryId, tag, summary, detail) VALUES (1, 'prefs.editor.wordwrap', 'Break long lines so they fit on the screen.', 'When on, the editor wraps long lines onto multiple visual lines instead of scrolling sideways. This does not change the file content.')
  ON CONFLICT (categoryId, tag) DO UPDATE SET summary = excluded.summary, detail = excluded.detail;

INSERT INTO Tooltips (categoryId, tag, summary, detail) VALUES (1, 'prefs.editor.magnifier', 'Show a magnified view of text while you select it.', 'When on, pressing and holding on text shows a zoomed-in view of the area around your finger. This makes it easier to place the cursor precisely.')
  ON CONFLICT (categoryId, tag) DO UPDATE SET summary = excluded.summary, detail = excluded.detail;

INSERT INTO Tooltips (categoryId, tag, summary, detail) VALUES (1, 'prefs.editor.wordboundaries', 'Use spaces to define word boundaries when you double-tap to select.', 'When on, double-tapping a word selects text up to the nearest space. When off, the editor also uses punctuation to decide where a word ends.')
  ON CONFLICT (categoryId, tag) DO UPDATE SET summary = excluded.summary, detail = excluded.detail;

INSERT INTO Tooltips (categoryId, tag, summary, detail) VALUES (1, 'prefs.editor.matchcase', 'Match code suggestions even when letter case differs.', 'When on, autocomplete suggestions match class and member names regardless of whether you type upper or lower case letters.')
  ON CONFLICT (categoryId, tag) DO UPDATE SET summary = excluded.summary, detail = excluded.detail;

INSERT INTO Tooltips (categoryId, tag, summary, detail) VALUES (1, 'prefs.editor.deletelines', 'Delete a whole blank line with one backspace.', 'When on, pressing backspace on a line with no visible text removes the entire line at once.')
  ON CONFLICT (categoryId, tag) DO UPDATE SET summary = excluded.summary, detail = excluded.detail;

INSERT INTO Tooltips (categoryId, tag, summary, detail) VALUES (1, 'prefs.editor.smartbackspace', 'Delete a full indent level with one backspace.', 'When on, pressing backspace at the start of an indented line removes the whole indent level at once, instead of one character at a time.')
  ON CONFLICT (categoryId, tag) DO UPDATE SET summary = excluded.summary, detail = excluded.detail;

INSERT INTO Tooltips (categoryId, tag, summary, detail) VALUES (1, 'prefs.editor.stickyscroll', 'Keep the current code block header visible while scrolling.', 'When on, the editor pins the header line of the current class or method at the top of the screen while you scroll through its body.')
  ON CONFLICT (categoryId, tag) DO UPDATE SET summary = excluded.summary, detail = excluded.detail;

INSERT INTO Tooltips (categoryId, tag, summary, detail) VALUES (1, 'prefs.editor.pinlines', 'Keep line numbers visible when scrolling sideways.', 'When on, line numbers stay in place on the left side of the screen even when you scroll a long line horizontally.')
  ON CONFLICT (categoryId, tag) DO UPDATE SET summary = excluded.summary, detail = excluded.detail;

INSERT INTO Tooltips (categoryId, tag, summary, detail) VALUES (1, 'prefs.editor.googlestyle', 'Format Java code using Google''s style rules.', 'When on, the code formatter applies Google''s Java style conventions, such as its indentation and spacing rules, instead of the default style.')
  ON CONFLICT (categoryId, tag) DO UPDATE SET summary = excluded.summary, detail = excluded.detail;

INSERT INTO Tooltips (categoryId, tag, summary, detail) VALUES (1, 'prefs.buildrun', 'Change settings for building and running your app.', 'Set additional Gradle flags and control whether the app launches automatically after a run installs it.')
  ON CONFLICT (categoryId, tag) DO UPDATE SET summary = excluded.summary, detail = excluded.detail;

INSERT INTO Tooltips (categoryId, tag, summary, detail) VALUES (1, 'prefs.buildrun.autolaunch', 'Launch the app automatically after a successful run installs it.', 'When on, Code on the Go opens your app right after installing it, with no extra confirmation step.')
  ON CONFLICT (categoryId, tag) DO UPDATE SET summary = excluded.summary, detail = excluded.detail;

INSERT INTO Tooltips (categoryId, tag, summary, detail) VALUES (1, 'prefs.buildrun.flags', 'Choose extra Gradle flags to add to every build.', 'This opens a dialog with checkboxes for common Gradle command-line flags, such as --info or --offline. Any flag you turn on is added to every Gradle task the IDE runs.')
  ON CONFLICT (categoryId, tag) DO UPDATE SET summary = excluded.summary, detail = excluded.detail;

INSERT INTO Tooltips (categoryId, tag, summary, detail) VALUES (1, 'prefs.termux.loglevel', 'Set how much detail the terminal writes to its internal log.', 'This controls the terminal''s own internal logging level, used for troubleshooting the terminal itself. Higher levels record more detail.')
  ON CONFLICT (categoryId, tag) DO UPDATE SET summary = excluded.summary, detail = excluded.detail;

INSERT INTO Tooltips (categoryId, tag, summary, detail) VALUES (1, 'prefs.termux.keylogging', 'Log every key you press in the terminal, for debugging.', 'When on, the terminal records each key press to the system log. This is very verbose and can slow the app down, so leave it off unless you are debugging a keyboard issue.')
  ON CONFLICT (categoryId, tag) DO UPDATE SET summary = excluded.summary, detail = excluded.detail;

INSERT INTO Tooltips (categoryId, tag, summary, detail) VALUES (1, 'prefs.termux.margin', 'Adjust the terminal margin so the on-screen keyboard does not cover it.', 'When on, the terminal adjusts its margin to avoid being covered by the on-screen keyboard. If you notice screen flickering, turn this off.')
  ON CONFLICT (categoryId, tag) DO UPDATE SET summary = excluded.summary, detail = excluded.detail;

INSERT INTO Tooltips (categoryId, tag, summary, detail) VALUES (1, 'prefs.termux.nohardkeyboard', 'Show the on-screen keyboard only when no physical keyboard is connected.', 'When on, the terminal hides its on-screen keyboard while a hardware keyboard is connected, and shows it again once the hardware keyboard is disconnected.')
  ON CONFLICT (categoryId, tag) DO UPDATE SET summary = excluded.summary, detail = excluded.detail;

INSERT INTO Tooltips (categoryId, tag, summary, detail) VALUES (1, 'prefs.termux.softkeyboard', 'Show the on-screen keyboard in the terminal.', 'When on, the terminal shows its own on-screen keyboard for typing commands. This is on by default.')
  ON CONFLICT (categoryId, tag) DO UPDATE SET summary = excluded.summary, detail = excluded.detail;

INSERT INTO Tooltips (categoryId, tag, summary, detail) VALUES (1, 'prefs.xml.closebracket', 'Put a tag closing bracket on its own new line.', 'When on, the formatter places the final ">" or "/>" on a new line after the last attribute. This is off by default.')
  ON CONFLICT (categoryId, tag) DO UPDATE SET summary = excluded.summary, detail = excluded.detail;

INSERT INTO Tooltips (categoryId, tag, summary, detail) VALUES (1, 'prefs.xml.emptyelements', 'Choose how the formatter writes empty XML elements.', 'Pick Expand to write empty elements as an open and a close tag, Collapse to write them as a single self-closing tag, or Ignore to leave them as they are.')
  ON CONFLICT (categoryId, tag) DO UPDATE SET summary = excluded.summary, detail = excluded.detail;

INSERT INTO Tooltips (categoryId, tag, summary, detail) VALUES (1, 'prefs.xml.maxlinewidth', 'Set the maximum characters allowed on one line.', 'This sets the line-width limit, in characters, before the formatter wraps a line onto more than one line. The default is 80.')
  ON CONFLICT (categoryId, tag) DO UPDATE SET summary = excluded.summary, detail = excluded.detail;

INSERT INTO Tooltips (categoryId, tag, summary, detail) VALUES (1, 'prefs.xml.preserveattributes', 'Keep existing line breaks between attributes.', 'When on, the formatter leaves attributes on the lines you already put them on, instead of joining them onto one line. This is on by default.')
  ON CONFLICT (categoryId, tag) DO UPDATE SET summary = excluded.summary, detail = excluded.detail;

INSERT INTO Tooltips (categoryId, tag, summary, detail) VALUES (1, 'prefs.xml.preservenewlines', 'Set how many blank lines to keep between elements.', 'This sets the maximum number of blank lines the formatter keeps between elements when it reformats a file. Extra blank lines beyond this number are removed. The default is 2.')
  ON CONFLICT (categoryId, tag) DO UPDATE SET summary = excluded.summary, detail = excluded.detail;

INSERT INTO Tooltips (categoryId, tag, summary, detail) VALUES (1, 'prefs.xml.spacebeforeclose', 'Add a space before a tag self-closing slash.', 'When on, the formatter writes a space before "/>", producing "<foo />" instead of "<foo/>". This is on by default.')
  ON CONFLICT (categoryId, tag) DO UPDATE SET summary = excluded.summary, detail = excluded.detail;

INSERT INTO Tooltips (categoryId, tag, summary, detail) VALUES (1, 'prefs.xml.splitattribindent', 'Set the indent size for attributes on split lines.', 'This sets how many spaces the formatter uses to indent an attribute placed on its own line. By default it is half the editor tab size.')
  ON CONFLICT (categoryId, tag) DO UPDATE SET summary = excluded.summary, detail = excluded.detail;

INSERT INTO Tooltips (categoryId, tag, summary, detail) VALUES (1, 'prefs.xml.trimwhitespace', 'Remove extra spaces at the end of lines.', 'When on, the formatter deletes any whitespace left at the end of each line. This is on by default.')
  ON CONFLICT (categoryId, tag) DO UPDATE SET summary = excluded.summary, detail = excluded.detail;

INSERT INTO Tooltips (categoryId, tag, summary, detail) VALUES (1, 'prefs.devoptions', 'Change experimental and debugging settings.', 'These settings are for troubleshooting Code on the Go itself, not for normal project configuration. Only change them if you are diagnosing a problem.')
  ON CONFLICT (categoryId, tag) DO UPDATE SET summary = excluded.summary, detail = excluded.detail;

INSERT INTO Tooltips (categoryId, tag, summary, detail) VALUES (1, 'prefs.devoptions.dumplogs', 'Save the IDE internal logs to a file.', 'When on, Code on the Go writes its internal logs to a file at $HOME/.cg/logs, for troubleshooting.')
  ON CONFLICT (categoryId, tag) DO UPDATE SET summary = excluded.summary, detail = excluded.detail;

INSERT INTO Tooltips (categoryId, tag, summary, detail) VALUES (1, 'prefs.devoptions.logsender', 'Show logs from your running app inside Code on the Go.', 'When on, Code on the Go displays log output from apps you run. Turn this off to stop showing those logs.')
  ON CONFLICT (categoryId, tag) DO UPDATE SET summary = excluded.summary, detail = excluded.detail;

INSERT INTO Tooltips (categoryId, tag, summary, detail) VALUES (1, 'prefs.editor.nonprinting.leading', 'Show whitespace at the start of each line.', 'When on, the editor marks spaces and tabs that come before the first visible character on a line.')
  ON CONFLICT (categoryId, tag) DO UPDATE SET summary = excluded.summary, detail = excluded.detail;

INSERT INTO Tooltips (categoryId, tag, summary, detail) VALUES (1, 'prefs.editor.nonprinting.trailing', 'Show whitespace at the end of each line.', 'When on, the editor marks spaces and tabs that come after the last visible character on a line.')
  ON CONFLICT (categoryId, tag) DO UPDATE SET summary = excluded.summary, detail = excluded.detail;

INSERT INTO Tooltips (categoryId, tag, summary, detail) VALUES (1, 'prefs.editor.nonprinting.inner', 'Show whitespace between words.', 'When on, the editor marks spaces and tabs that appear between two visible characters on a line.')
  ON CONFLICT (categoryId, tag) DO UPDATE SET summary = excluded.summary, detail = excluded.detail;

INSERT INTO Tooltips (categoryId, tag, summary, detail) VALUES (1, 'prefs.editor.nonprinting.emptylines', 'Show whitespace on lines with no visible text.', 'When on, the editor marks spaces and tabs on lines that contain only whitespace.')
  ON CONFLICT (categoryId, tag) DO UPDATE SET summary = excluded.summary, detail = excluded.detail;

INSERT INTO Tooltips (categoryId, tag, summary, detail) VALUES (1, 'prefs.editor.nonprinting.linebreaks', 'Show a mark at the end of each line.', 'When on, the editor draws a small symbol where each line ends, so line breaks are visible.')
  ON CONFLICT (categoryId, tag) DO UPDATE SET summary = excluded.summary, detail = excluded.detail;

INSERT INTO Tooltips (categoryId, tag, summary, detail) VALUES (1, 'prefs.xml.trimfinalnewline', 'Remove blank lines at the end of an XML file.', 'When on, the formatter deletes empty lines at the end of the file, leaving no trailing blank lines. This is off by default.')
  ON CONFLICT (categoryId, tag) DO UPDATE SET summary = excluded.summary, detail = excluded.detail;

INSERT INTO Tooltips (categoryId, tag, summary, detail) VALUES (1, 'prefs.xml.insertfinalnewline', 'Add one newline at the end of an XML file.', 'When on, the formatter makes sure the file ends with exactly one newline character. This is on by default.')
  ON CONFLICT (categoryId, tag) DO UPDATE SET summary = excluded.summary, detail = excluded.detail;

INSERT INTO Tooltips (categoryId, tag, summary, detail) VALUES (1, 'prefs.xml.splitattributes', 'Put each XML attribute on its own line.', 'When on, the formatter places every attribute of a tag on a separate line instead of one line. This is on by default.')
  ON CONFLICT (categoryId, tag) DO UPDATE SET summary = excluded.summary, detail = excluded.detail;

INSERT INTO Tooltips (categoryId, tag, summary, detail) VALUES (1, 'prefs.xml.joincdatalines', 'Combine multiple CDATA lines into one.', 'When on, the formatter merges the lines of a multi-line CDATA section into a single line. This is off by default.')
  ON CONFLICT (categoryId, tag) DO UPDATE SET summary = excluded.summary, detail = excluded.detail;

INSERT INTO Tooltips (categoryId, tag, summary, detail) VALUES (1, 'prefs.xml.joincommentlines', 'Combine multiple single-line comments into one.', 'When on, the formatter merges adjacent single-line XML comments into a single line. This is on by default.')
  ON CONFLICT (categoryId, tag) DO UPDATE SET summary = excluded.summary, detail = excluded.detail;

INSERT INTO Tooltips (categoryId, tag, summary, detail) VALUES (1, 'prefs.xml.joincontentlines', 'Combine multiple lines of element text into one.', 'When on, the formatter merges multiple lines of text inside an XML element into a single line. This is off by default.')
  ON CONFLICT (categoryId, tag) DO UPDATE SET summary = excluded.summary, detail = excluded.detail;

INSERT INTO Tooltips (categoryId, tag, summary, detail) VALUES (1, 'prefs.xml.preserveemptycontent', 'Keep tags that stand alone on an empty line as they are.', 'When on, the formatter leaves an element alone on its own blank line unchanged, instead of collapsing it. This is on by default.')
  ON CONFLICT (categoryId, tag) DO UPDATE SET summary = excluded.summary, detail = excluded.detail;

INSERT INTO Tooltips (categoryId, tag, summary, detail) VALUES (1, 'prefs.buildrun.flags.stacktrace', 'Show a full stack trace when a build fails.', 'Sets the Gradle --stacktrace flag. When a build fails, Gradle prints the full stack trace of the failure instead of a short summary.')
  ON CONFLICT (categoryId, tag) DO UPDATE SET summary = excluded.summary, detail = excluded.detail;

INSERT INTO Tooltips (categoryId, tag, summary, detail) VALUES (1, 'prefs.buildrun.flags.info', 'Show detailed log messages for each build task.', 'Sets the Gradle --info flag. Gradle prints detailed log messages as each task runs, instead of just a summary line for each task.')
  ON CONFLICT (categoryId, tag) DO UPDATE SET summary = excluded.summary, detail = excluded.detail;

INSERT INTO Tooltips (categoryId, tag, summary, detail) VALUES (1, 'prefs.buildrun.flags.debug', 'Show maximum detail in build logs.', 'Sets the Gradle --debug flag. Gradle prints very detailed, low-level log messages for every task. This produces a large amount of output and can slow the build down.')
  ON CONFLICT (categoryId, tag) DO UPDATE SET summary = excluded.summary, detail = excluded.detail;

INSERT INTO Tooltips (categoryId, tag, summary, detail) VALUES (1, 'prefs.buildrun.flags.scan', 'Publish a Gradle Build Scan report link.', 'Sets the Gradle --scan flag. After the build, Gradle uploads build data and gives you a link to a Build Scan report with detailed information.')
  ON CONFLICT (categoryId, tag) DO UPDATE SET summary = excluded.summary, detail = excluded.detail;

INSERT INTO Tooltips (categoryId, tag, summary, detail) VALUES (1, 'prefs.buildrun.flags.warningmodeall', 'Show every deprecation warning during the build.', 'Sets the Gradle --warning-mode all flag. Gradle lists every individual deprecation warning instead of just a count at the end of the build.')
  ON CONFLICT (categoryId, tag) DO UPDATE SET summary = excluded.summary, detail = excluded.detail;

INSERT INTO Tooltips (categoryId, tag, summary, detail) VALUES (1, 'prefs.buildrun.flags.buildcache', 'Reuse outputs from a previous build to speed up this one.', 'Sets the Gradle --build-cache flag. Gradle reuses task outputs from an earlier build when the inputs have not changed, which can make the build faster.')
  ON CONFLICT (categoryId, tag) DO UPDATE SET summary = excluded.summary, detail = excluded.detail;

INSERT INTO Tooltips (categoryId, tag, summary, detail) VALUES (1, 'prefs.buildrun.flags.offline', 'Build without any network access.', 'Sets the Gradle --offline flag. Gradle uses only dependencies already downloaded to your device and does not try to reach the network.')
  ON CONFLICT (categoryId, tag) DO UPDATE SET summary = excluded.summary, detail = excluded.detail;

INSERT INTO Tooltips (categoryId, tag, summary, detail) VALUES (1, 'prefs.termux.crashreports', 'Show a notification if the terminal crashes.', 'When on, Code on the Go shows a notification with a crash report after the terminal process crashes. This is on by default.')
  ON CONFLICT (categoryId, tag) DO UPDATE SET summary = excluded.summary, detail = excluded.detail;

INSERT INTO Tooltips (categoryId, tag, summary, detail) VALUES (1, 'prefs.git.username', 'Set the name used as the author of your commits.', 'This name is recorded on every Git commit you make from Code on the Go.')
  ON CONFLICT (categoryId, tag) DO UPDATE SET summary = excluded.summary, detail = excluded.detail;

INSERT INTO Tooltips (categoryId, tag, summary, detail) VALUES (1, 'prefs.git.useremail', 'Set the email address used as the author of your commits.', 'This email address is recorded on every Git commit you make from Code on the Go.')
  ON CONFLICT (categoryId, tag) DO UPDATE SET summary = excluded.summary, detail = excluded.detail;

INSERT INTO Tooltips (categoryId, tag, summary, detail) VALUES (1, 'prefs.pluginmanager', 'Manage IDE plugins and extensions.', 'This opens Plugin Manager, where you can install, remove, and configure plugins that add features to Code on the Go.')
  ON CONFLICT (categoryId, tag) DO UPDATE SET summary = excluded.summary, detail = excluded.detail;

INSERT INTO Tooltips (categoryId, tag, summary, detail) VALUES (1, 'prefs.about', 'See app version and other information about Code on the Go.', 'This opens the About screen, with details such as the app version and credits.')
  ON CONFLICT (categoryId, tag) DO UPDATE SET summary = excluded.summary, detail = excluded.detail;

-- Content: Tier 3 HTML pages, one INSERT per Tooltips row above.
-- Each pair shows the uncompressed HTML via `.system echo ... | brotli -Z`
-- then inserts the resulting compressed file with READFILE().

.system rm -f /tmp/adfa5088-prefs-workdir/adfa5088-prefs-general.br
.system echo "<p>The General screen holds settings that are not specific to the editor or to a single project. Use it to set the app theme, choose a display language, and control how Code on the Go opens the last project on launch.</p><p>Changes here apply immediately and affect the whole app.</p>" | brotli -Z > /tmp/adfa5088-prefs-workdir/adfa5088-prefs-general.br
INSERT INTO _content_guard SELECT READFILE('/tmp/adfa5088-prefs-workdir/adfa5088-prefs-general.br');
INSERT INTO Content (path, languageId, contentTypeId, content) VALUES ('i/prefs/general', 1, 12, READFILE('/tmp/adfa5088-prefs-workdir/adfa5088-prefs-general.br')) ON CONFLICT (path) DO UPDATE SET content = excluded.content;

.system rm -f /tmp/adfa5088-prefs-workdir/adfa5088-prefs-general-uimode.br
.system echo "<p>UI mode sets the color theme for Code on the Go.</p><ul><li><b>Light</b>: always use light colors.</li><li><b>Dark</b>: always use dark colors.</li><li><b>Follow system</b>: match your device's current theme, and switch automatically when it changes.</li></ul>" | brotli -Z > /tmp/adfa5088-prefs-workdir/adfa5088-prefs-general-uimode.br
INSERT INTO _content_guard SELECT READFILE('/tmp/adfa5088-prefs-workdir/adfa5088-prefs-general-uimode.br');
INSERT INTO Content (path, languageId, contentTypeId, content) VALUES ('i/prefs/general/uimode', 1, 12, READFILE('/tmp/adfa5088-prefs-workdir/adfa5088-prefs-general-uimode.br')) ON CONFLICT (path) DO UPDATE SET content = excluded.content;

.system rm -f /tmp/adfa5088-prefs-workdir/adfa5088-prefs-general-language.br
.system echo "<p>Language sets the display language for the Code on the Go interface.</p><p>Choose System Default to use your device language setting, or pick one of the supported languages directly. The app restarts the affected screens to apply the change.</p>" | brotli -Z > /tmp/adfa5088-prefs-workdir/adfa5088-prefs-general-language.br
INSERT INTO _content_guard SELECT READFILE('/tmp/adfa5088-prefs-workdir/adfa5088-prefs-general-language.br');
INSERT INTO Content (path, languageId, contentTypeId, content) VALUES ('i/prefs/general/language', 1, 12, READFILE('/tmp/adfa5088-prefs-workdir/adfa5088-prefs-general-language.br')) ON CONFLICT (path) DO UPDATE SET content = excluded.content;

.system rm -f /tmp/adfa5088-prefs-workdir/adfa5088-prefs-general-openlast.br
.system echo "<p>Open last project controls what happens when you start Code on the Go.</p><p>Turn it on to skip the project list and go straight into your most recent project. Turn it off to see the project list every time.</p>" | brotli -Z > /tmp/adfa5088-prefs-workdir/adfa5088-prefs-general-openlast.br
INSERT INTO _content_guard SELECT READFILE('/tmp/adfa5088-prefs-workdir/adfa5088-prefs-general-openlast.br');
INSERT INTO Content (path, languageId, contentTypeId, content) VALUES ('i/prefs/general/openlast', 1, 12, READFILE('/tmp/adfa5088-prefs-workdir/adfa5088-prefs-general-openlast.br')) ON CONFLICT (path) DO UPDATE SET content = excluded.content;

.system rm -f /tmp/adfa5088-prefs-workdir/adfa5088-prefs-general-confirmopen.br
.system echo "<p>Confirm project opening adds a confirmation step before Code on the Go reopens your last project automatically.</p><p>Use this if you often want to switch to a different project instead of continuing the last one.</p>" | brotli -Z > /tmp/adfa5088-prefs-workdir/adfa5088-prefs-general-confirmopen.br
INSERT INTO _content_guard SELECT READFILE('/tmp/adfa5088-prefs-workdir/adfa5088-prefs-general-confirmopen.br');
INSERT INTO Content (path, languageId, contentTypeId, content) VALUES ('i/prefs/general/confirmopen', 1, 12, READFILE('/tmp/adfa5088-prefs-workdir/adfa5088-prefs-general-confirmopen.br')) ON CONFLICT (path) DO UPDATE SET content = excluded.content;

.system rm -f /tmp/adfa5088-prefs-workdir/adfa5088-prefs-editor.br
.system echo "<p>The Editor screen holds settings for the code editor: font size, tab size, word wrap, whitespace display, and other editing behavior.</p><p>Formatting options specific to XML files are on a separate sub-screen.</p>" | brotli -Z > /tmp/adfa5088-prefs-workdir/adfa5088-prefs-editor.br
INSERT INTO _content_guard SELECT READFILE('/tmp/adfa5088-prefs-workdir/adfa5088-prefs-editor.br');
INSERT INTO Content (path, languageId, contentTypeId, content) VALUES ('i/prefs/editor', 1, 12, READFILE('/tmp/adfa5088-prefs-workdir/adfa5088-prefs-editor.br')) ON CONFLICT (path) DO UPDATE SET content = excluded.content;

.system rm -f /tmp/adfa5088-prefs-workdir/adfa5088-prefs-editor-fontsize.br
.system echo "<p>Font size sets the text size used in the code editor, in sp units.</p><p>Choose a larger value to make code easier to read, or a smaller value to fit more code on the screen. The allowed range is 6 to 32.</p>" | brotli -Z > /tmp/adfa5088-prefs-workdir/adfa5088-prefs-editor-fontsize.br
INSERT INTO _content_guard SELECT READFILE('/tmp/adfa5088-prefs-workdir/adfa5088-prefs-editor-fontsize.br');
INSERT INTO Content (path, languageId, contentTypeId, content) VALUES ('i/prefs/editor/fontsize', 1, 12, READFILE('/tmp/adfa5088-prefs-workdir/adfa5088-prefs-editor-fontsize.br')) ON CONFLICT (path) DO UPDATE SET content = excluded.content;

.system rm -f /tmp/adfa5088-prefs-workdir/adfa5088-prefs-editor-tabsize.br
.system echo "<p>Tab size sets the number of spaces that one tab character represents in the editor.</p><p>Pick a value that matches your project code style: 2, 4, 6, or 8 spaces.</p>" | brotli -Z > /tmp/adfa5088-prefs-workdir/adfa5088-prefs-editor-tabsize.br
INSERT INTO _content_guard SELECT READFILE('/tmp/adfa5088-prefs-workdir/adfa5088-prefs-editor-tabsize.br');
INSERT INTO Content (path, languageId, contentTypeId, content) VALUES ('i/prefs/editor/tabsize', 1, 12, READFILE('/tmp/adfa5088-prefs-workdir/adfa5088-prefs-editor-tabsize.br')) ON CONFLICT (path) DO UPDATE SET content = excluded.content;

.system rm -f /tmp/adfa5088-prefs-workdir/adfa5088-prefs-editor-nonprinting.br
.system echo "<p>Show non-printing characters controls which invisible characters, such as spaces, tabs, and line breaks, the editor marks visibly.</p><p>Open this setting to choose which kinds to show: leading whitespace, trailing whitespace, whitespace between words, whitespace on empty lines, and line-break marks.</p>" | brotli -Z > /tmp/adfa5088-prefs-workdir/adfa5088-prefs-editor-nonprinting.br
INSERT INTO _content_guard SELECT READFILE('/tmp/adfa5088-prefs-workdir/adfa5088-prefs-editor-nonprinting.br');
INSERT INTO Content (path, languageId, contentTypeId, content) VALUES ('i/prefs/editor/nonprinting', 1, 12, READFILE('/tmp/adfa5088-prefs-workdir/adfa5088-prefs-editor-nonprinting.br')) ON CONFLICT (path) DO UPDATE SET content = excluded.content;

.system rm -f /tmp/adfa5088-prefs-workdir/adfa5088-prefs-editor-nonprinting-leading.br
.system echo "<p>Leading marks whitespace characters, such as spaces and tabs, that appear before the first visible character on a line.</p><p>Turn this on to see indentation whitespace clearly.</p>" | brotli -Z > /tmp/adfa5088-prefs-workdir/adfa5088-prefs-editor-nonprinting-leading.br
INSERT INTO _content_guard SELECT READFILE('/tmp/adfa5088-prefs-workdir/adfa5088-prefs-editor-nonprinting-leading.br');
INSERT INTO Content (path, languageId, contentTypeId, content) VALUES ('i/prefs/editor/nonprinting/leading', 1, 12, READFILE('/tmp/adfa5088-prefs-workdir/adfa5088-prefs-editor-nonprinting-leading.br')) ON CONFLICT (path) DO UPDATE SET content = excluded.content;

.system rm -f /tmp/adfa5088-prefs-workdir/adfa5088-prefs-editor-nonprinting-trailing.br
.system echo "<p>Trailing marks whitespace characters that appear after the last visible character on a line.</p><p>Turn this on to spot stray trailing spaces or tabs.</p>" | brotli -Z > /tmp/adfa5088-prefs-workdir/adfa5088-prefs-editor-nonprinting-trailing.br
INSERT INTO _content_guard SELECT READFILE('/tmp/adfa5088-prefs-workdir/adfa5088-prefs-editor-nonprinting-trailing.br');
INSERT INTO Content (path, languageId, contentTypeId, content) VALUES ('i/prefs/editor/nonprinting/trailing', 1, 12, READFILE('/tmp/adfa5088-prefs-workdir/adfa5088-prefs-editor-nonprinting-trailing.br')) ON CONFLICT (path) DO UPDATE SET content = excluded.content;

.system rm -f /tmp/adfa5088-prefs-workdir/adfa5088-prefs-editor-nonprinting-inner.br
.system echo "<p>Inner marks whitespace characters that appear between words or tokens within a line, rather than at the start or end.</p><p>Turn this on to check for irregular spacing inside a line.</p>" | brotli -Z > /tmp/adfa5088-prefs-workdir/adfa5088-prefs-editor-nonprinting-inner.br
INSERT INTO _content_guard SELECT READFILE('/tmp/adfa5088-prefs-workdir/adfa5088-prefs-editor-nonprinting-inner.br');
INSERT INTO Content (path, languageId, contentTypeId, content) VALUES ('i/prefs/editor/nonprinting/inner', 1, 12, READFILE('/tmp/adfa5088-prefs-workdir/adfa5088-prefs-editor-nonprinting-inner.br')) ON CONFLICT (path) DO UPDATE SET content = excluded.content;

.system rm -f /tmp/adfa5088-prefs-workdir/adfa5088-prefs-editor-nonprinting-emptylines.br
.system echo "<p>Empty lines marks whitespace characters on lines that have no visible text, only spaces or tabs.</p><p>Turn this on to spot stray whitespace on otherwise blank lines.</p>" | brotli -Z > /tmp/adfa5088-prefs-workdir/adfa5088-prefs-editor-nonprinting-emptylines.br
INSERT INTO _content_guard SELECT READFILE('/tmp/adfa5088-prefs-workdir/adfa5088-prefs-editor-nonprinting-emptylines.br');
INSERT INTO Content (path, languageId, contentTypeId, content) VALUES ('i/prefs/editor/nonprinting/emptylines', 1, 12, READFILE('/tmp/adfa5088-prefs-workdir/adfa5088-prefs-editor-nonprinting-emptylines.br')) ON CONFLICT (path) DO UPDATE SET content = excluded.content;

.system rm -f /tmp/adfa5088-prefs-workdir/adfa5088-prefs-editor-nonprinting-linebreaks.br
.system echo "<p>Line breaks draws a small marker at the end of each line to show where the line break occurs.</p><p>Turn this on to see line endings clearly, for example when comparing files with different line-ending styles.</p>" | brotli -Z > /tmp/adfa5088-prefs-workdir/adfa5088-prefs-editor-nonprinting-linebreaks.br
INSERT INTO _content_guard SELECT READFILE('/tmp/adfa5088-prefs-workdir/adfa5088-prefs-editor-nonprinting-linebreaks.br');
INSERT INTO Content (path, languageId, contentTypeId, content) VALUES ('i/prefs/editor/nonprinting/linebreaks', 1, 12, READFILE('/tmp/adfa5088-prefs-workdir/adfa5088-prefs-editor-nonprinting-linebreaks.br')) ON CONFLICT (path) DO UPDATE SET content = excluded.content;

.system rm -f /tmp/adfa5088-prefs-workdir/adfa5088-prefs-editor-softtab.br
.system echo "<p>Use soft tab controls what the editor inserts when you press the Tab key.</p><p>Turn this on to insert spaces instead of a tab character. This can help keep code consistent across editors that render tabs differently.</p>" | brotli -Z > /tmp/adfa5088-prefs-workdir/adfa5088-prefs-editor-softtab.br
INSERT INTO _content_guard SELECT READFILE('/tmp/adfa5088-prefs-workdir/adfa5088-prefs-editor-softtab.br');
INSERT INTO Content (path, languageId, contentTypeId, content) VALUES ('i/prefs/editor/softtab', 1, 12, READFILE('/tmp/adfa5088-prefs-workdir/adfa5088-prefs-editor-softtab.br')) ON CONFLICT (path) DO UPDATE SET content = excluded.content;

.system rm -f /tmp/adfa5088-prefs-workdir/adfa5088-prefs-editor-wordwrap.br
.system echo "<p>Word wrap breaks long lines of code into multiple visual lines so they fit within the visible width of the editor.</p><p>The underlying file is not changed; only the way it is displayed changes.</p>" | brotli -Z > /tmp/adfa5088-prefs-workdir/adfa5088-prefs-editor-wordwrap.br
INSERT INTO _content_guard SELECT READFILE('/tmp/adfa5088-prefs-workdir/adfa5088-prefs-editor-wordwrap.br');
INSERT INTO Content (path, languageId, contentTypeId, content) VALUES ('i/prefs/editor/wordwrap', 1, 12, READFILE('/tmp/adfa5088-prefs-workdir/adfa5088-prefs-editor-wordwrap.br')) ON CONFLICT (path) DO UPDATE SET content = excluded.content;

.system rm -f /tmp/adfa5088-prefs-workdir/adfa5088-prefs-editor-magnifier.br
.system echo "<p>Enable magnifier shows a zoomed-in view of the text around your finger when you press and hold to select text.</p><p>This makes it easier to position the cursor precisely on a small screen.</p>" | brotli -Z > /tmp/adfa5088-prefs-workdir/adfa5088-prefs-editor-magnifier.br
INSERT INTO _content_guard SELECT READFILE('/tmp/adfa5088-prefs-workdir/adfa5088-prefs-editor-magnifier.br');
INSERT INTO Content (path, languageId, contentTypeId, content) VALUES ('i/prefs/editor/magnifier', 1, 12, READFILE('/tmp/adfa5088-prefs-workdir/adfa5088-prefs-editor-magnifier.br')) ON CONFLICT (path) DO UPDATE SET content = excluded.content;

.system rm -f /tmp/adfa5088-prefs-workdir/adfa5088-prefs-editor-wordboundaries.br
.system echo "<p>Use spaces as word boundaries changes how the editor decides what counts as one word when you double-tap to select text.</p><p>When on, only blank space marks the edge of a word. When off, the editor also treats punctuation, such as periods and underscores, as word boundaries.</p>" | brotli -Z > /tmp/adfa5088-prefs-workdir/adfa5088-prefs-editor-wordboundaries.br
INSERT INTO _content_guard SELECT READFILE('/tmp/adfa5088-prefs-workdir/adfa5088-prefs-editor-wordboundaries.br');
INSERT INTO Content (path, languageId, contentTypeId, content) VALUES ('i/prefs/editor/wordboundaries', 1, 12, READFILE('/tmp/adfa5088-prefs-workdir/adfa5088-prefs-editor-wordboundaries.br')) ON CONFLICT (path) DO UPDATE SET content = excluded.content;

.system rm -f /tmp/adfa5088-prefs-workdir/adfa5088-prefs-editor-matchcase.br
.system echo "<p>Match completions in lower case controls whether the code editor autocomplete feature is case-sensitive.</p><p>When on, typing in lower case still matches suggestions that use upper case letters, such as class names.</p>" | brotli -Z > /tmp/adfa5088-prefs-workdir/adfa5088-prefs-editor-matchcase.br
INSERT INTO _content_guard SELECT READFILE('/tmp/adfa5088-prefs-workdir/adfa5088-prefs-editor-matchcase.br');
INSERT INTO Content (path, languageId, contentTypeId, content) VALUES ('i/prefs/editor/matchcase', 1, 12, READFILE('/tmp/adfa5088-prefs-workdir/adfa5088-prefs-editor-matchcase.br')) ON CONFLICT (path) DO UPDATE SET content = excluded.content;

.system rm -f /tmp/adfa5088-prefs-workdir/adfa5088-prefs-editor-deletelines.br
.system echo "<p>Delete empty lines on backspace changes what happens when you press backspace on a line with no visible text.</p><p>When on, the whole empty line is removed in one step, instead of removing one whitespace character at a time.</p>" | brotli -Z > /tmp/adfa5088-prefs-workdir/adfa5088-prefs-editor-deletelines.br
INSERT INTO _content_guard SELECT READFILE('/tmp/adfa5088-prefs-workdir/adfa5088-prefs-editor-deletelines.br');
INSERT INTO Content (path, languageId, contentTypeId, content) VALUES ('i/prefs/editor/deletelines', 1, 12, READFILE('/tmp/adfa5088-prefs-workdir/adfa5088-prefs-editor-deletelines.br')) ON CONFLICT (path) DO UPDATE SET content = excluded.content;

.system rm -f /tmp/adfa5088-prefs-workdir/adfa5088-prefs-editor-smartbackspace.br
.system echo "<p>Smart backspace indent changes what one backspace press removes when the cursor is inside leading indentation.</p><p>When on, it removes a full indent level at once. When off, it removes one character at a time.</p>" | brotli -Z > /tmp/adfa5088-prefs-workdir/adfa5088-prefs-editor-smartbackspace.br
INSERT INTO _content_guard SELECT READFILE('/tmp/adfa5088-prefs-workdir/adfa5088-prefs-editor-smartbackspace.br');
INSERT INTO Content (path, languageId, contentTypeId, content) VALUES ('i/prefs/editor/smartbackspace', 1, 12, READFILE('/tmp/adfa5088-prefs-workdir/adfa5088-prefs-editor-smartbackspace.br')) ON CONFLICT (path) DO UPDATE SET content = excluded.content;

.system rm -f /tmp/adfa5088-prefs-workdir/adfa5088-prefs-editor-stickyscroll.br
.system echo "<p>Sticky scroll keeps the header line of the code block you are inside, such as a class or method declaration, visible at the top of the editor while you scroll down through its contents.</p><p>This helps you keep track of context in long files.</p>" | brotli -Z > /tmp/adfa5088-prefs-workdir/adfa5088-prefs-editor-stickyscroll.br
INSERT INTO _content_guard SELECT READFILE('/tmp/adfa5088-prefs-workdir/adfa5088-prefs-editor-stickyscroll.br');
INSERT INTO Content (path, languageId, contentTypeId, content) VALUES ('i/prefs/editor/stickyscroll', 1, 12, READFILE('/tmp/adfa5088-prefs-workdir/adfa5088-prefs-editor-stickyscroll.br')) ON CONFLICT (path) DO UPDATE SET content = excluded.content;

.system rm -f /tmp/adfa5088-prefs-workdir/adfa5088-prefs-editor-pinlines.br
.system echo "<p>Pin line numbers keeps the line-number column fixed on the left side of the editor.</p><p>Without this, scrolling a long line horizontally can move the line numbers out of view along with the code.</p>" | brotli -Z > /tmp/adfa5088-prefs-workdir/adfa5088-prefs-editor-pinlines.br
INSERT INTO _content_guard SELECT READFILE('/tmp/adfa5088-prefs-workdir/adfa5088-prefs-editor-pinlines.br');
INSERT INTO Content (path, languageId, contentTypeId, content) VALUES ('i/prefs/editor/pinlines', 1, 12, READFILE('/tmp/adfa5088-prefs-workdir/adfa5088-prefs-editor-pinlines.br')) ON CONFLICT (path) DO UPDATE SET content = excluded.content;

.system rm -f /tmp/adfa5088-prefs-workdir/adfa5088-prefs-editor-googlestyle.br
.system echo "<p>Use Google Java Style code formatting applies Google's published conventions for Java source code, such as indentation, spacing, and line-wrapping rules, when you format code.</p><p>Turn this on if your project follows Google's Java style guide.</p>" | brotli -Z > /tmp/adfa5088-prefs-workdir/adfa5088-prefs-editor-googlestyle.br
INSERT INTO _content_guard SELECT READFILE('/tmp/adfa5088-prefs-workdir/adfa5088-prefs-editor-googlestyle.br');
INSERT INTO Content (path, languageId, contentTypeId, content) VALUES ('i/prefs/editor/googlestyle', 1, 12, READFILE('/tmp/adfa5088-prefs-workdir/adfa5088-prefs-editor-googlestyle.br')) ON CONFLICT (path) DO UPDATE SET content = excluded.content;

.system rm -f /tmp/adfa5088-prefs-workdir/adfa5088-prefs-editor-xml.br
.system echo "<p>XML formatting options opens a sub-screen of settings that control how Code on the Go formats XML files, separate from the general editor settings.</p>" | brotli -Z > /tmp/adfa5088-prefs-workdir/adfa5088-prefs-editor-xml.br
INSERT INTO _content_guard SELECT READFILE('/tmp/adfa5088-prefs-workdir/adfa5088-prefs-editor-xml.br');
INSERT INTO Content (path, languageId, contentTypeId, content) VALUES ('i/prefs/editor/xml', 1, 12, READFILE('/tmp/adfa5088-prefs-workdir/adfa5088-prefs-editor-xml.br')) ON CONFLICT (path) DO UPDATE SET content = excluded.content;

.system rm -f /tmp/adfa5088-prefs-workdir/adfa5088-prefs-xml-trimfinalnewline.br
.system echo "<p>Trim final new line removes empty lines at the very end of an XML file when the formatter runs.</p><p>This is off by default.</p>" | brotli -Z > /tmp/adfa5088-prefs-workdir/adfa5088-prefs-xml-trimfinalnewline.br
INSERT INTO _content_guard SELECT READFILE('/tmp/adfa5088-prefs-workdir/adfa5088-prefs-xml-trimfinalnewline.br');
INSERT INTO Content (path, languageId, contentTypeId, content) VALUES ('i/prefs/xml/trimfinalnewline', 1, 12, READFILE('/tmp/adfa5088-prefs-workdir/adfa5088-prefs-xml-trimfinalnewline.br')) ON CONFLICT (path) DO UPDATE SET content = excluded.content;

.system rm -f /tmp/adfa5088-prefs-workdir/adfa5088-prefs-xml-insertfinalnewline.br
.system echo "<p>Insert final new line adds a single newline character at the end of an XML file if one is not already there.</p><p>Many tools expect files to end this way.</p>" | brotli -Z > /tmp/adfa5088-prefs-workdir/adfa5088-prefs-xml-insertfinalnewline.br
INSERT INTO _content_guard SELECT READFILE('/tmp/adfa5088-prefs-workdir/adfa5088-prefs-xml-insertfinalnewline.br');
INSERT INTO Content (path, languageId, contentTypeId, content) VALUES ('i/prefs/xml/insertfinalnewline', 1, 12, READFILE('/tmp/adfa5088-prefs-workdir/adfa5088-prefs-xml-insertfinalnewline.br')) ON CONFLICT (path) DO UPDATE SET content = excluded.content;

.system rm -f /tmp/adfa5088-prefs-workdir/adfa5088-prefs-xml-splitattributes.br
.system echo "<p>Split attributes places each attribute of an XML tag on its own line when the formatter runs.</p><p>This makes tags with many attributes easier to read and to compare in version control.</p>" | brotli -Z > /tmp/adfa5088-prefs-workdir/adfa5088-prefs-xml-splitattributes.br
INSERT INTO _content_guard SELECT READFILE('/tmp/adfa5088-prefs-workdir/adfa5088-prefs-xml-splitattributes.br');
INSERT INTO Content (path, languageId, contentTypeId, content) VALUES ('i/prefs/xml/splitattributes', 1, 12, READFILE('/tmp/adfa5088-prefs-workdir/adfa5088-prefs-xml-splitattributes.br')) ON CONFLICT (path) DO UPDATE SET content = excluded.content;

.system rm -f /tmp/adfa5088-prefs-workdir/adfa5088-prefs-xml-joincdatalines.br
.system echo "<p>Join CDATA lines combines the lines of a CDATA section into a single line when the formatter runs.</p><p>CDATA sections hold raw, unescaped text or markup inside an XML file.</p>" | brotli -Z > /tmp/adfa5088-prefs-workdir/adfa5088-prefs-xml-joincdatalines.br
INSERT INTO _content_guard SELECT READFILE('/tmp/adfa5088-prefs-workdir/adfa5088-prefs-xml-joincdatalines.br');
INSERT INTO Content (path, languageId, contentTypeId, content) VALUES ('i/prefs/xml/joincdatalines', 1, 12, READFILE('/tmp/adfa5088-prefs-workdir/adfa5088-prefs-xml-joincdatalines.br')) ON CONFLICT (path) DO UPDATE SET content = excluded.content;

.system rm -f /tmp/adfa5088-prefs-workdir/adfa5088-prefs-xml-joincommentlines.br
.system echo "<p>Join comment lines combines adjacent single-line XML comments into one line when the formatter runs.</p>" | brotli -Z > /tmp/adfa5088-prefs-workdir/adfa5088-prefs-xml-joincommentlines.br
INSERT INTO _content_guard SELECT READFILE('/tmp/adfa5088-prefs-workdir/adfa5088-prefs-xml-joincommentlines.br');
INSERT INTO Content (path, languageId, contentTypeId, content) VALUES ('i/prefs/xml/joincommentlines', 1, 12, READFILE('/tmp/adfa5088-prefs-workdir/adfa5088-prefs-xml-joincommentlines.br')) ON CONFLICT (path) DO UPDATE SET content = excluded.content;

.system rm -f /tmp/adfa5088-prefs-workdir/adfa5088-prefs-xml-joincontentlines.br
.system echo "<p>Join content lines combines multiple lines of text inside a single XML element into one line when the formatter runs.</p>" | brotli -Z > /tmp/adfa5088-prefs-workdir/adfa5088-prefs-xml-joincontentlines.br
INSERT INTO _content_guard SELECT READFILE('/tmp/adfa5088-prefs-workdir/adfa5088-prefs-xml-joincontentlines.br');
INSERT INTO Content (path, languageId, contentTypeId, content) VALUES ('i/prefs/xml/joincontentlines', 1, 12, READFILE('/tmp/adfa5088-prefs-workdir/adfa5088-prefs-xml-joincontentlines.br')) ON CONFLICT (path) DO UPDATE SET content = excluded.content;

.system rm -f /tmp/adfa5088-prefs-workdir/adfa5088-prefs-xml-spacebeforeclose.br
.system echo "<p>Space before empty close tag adds one space before the closing /&gt; of a self-closing XML tag, so the formatter produces &lt;foo /&gt; instead of &lt;foo/&gt;.</p>" | brotli -Z > /tmp/adfa5088-prefs-workdir/adfa5088-prefs-xml-spacebeforeclose.br
INSERT INTO _content_guard SELECT READFILE('/tmp/adfa5088-prefs-workdir/adfa5088-prefs-xml-spacebeforeclose.br');
INSERT INTO Content (path, languageId, contentTypeId, content) VALUES ('i/prefs/xml/spacebeforeclose', 1, 12, READFILE('/tmp/adfa5088-prefs-workdir/adfa5088-prefs-xml-spacebeforeclose.br')) ON CONFLICT (path) DO UPDATE SET content = excluded.content;

.system rm -f /tmp/adfa5088-prefs-workdir/adfa5088-prefs-xml-preserveemptycontent.br
.system echo "<p>Preserve empty content keeps an XML element that appears alone on an otherwise empty line as it is, instead of collapsing or moving it during formatting.</p>" | brotli -Z > /tmp/adfa5088-prefs-workdir/adfa5088-prefs-xml-preserveemptycontent.br
INSERT INTO _content_guard SELECT READFILE('/tmp/adfa5088-prefs-workdir/adfa5088-prefs-xml-preserveemptycontent.br');
INSERT INTO Content (path, languageId, contentTypeId, content) VALUES ('i/prefs/xml/preserveemptycontent', 1, 12, READFILE('/tmp/adfa5088-prefs-workdir/adfa5088-prefs-xml-preserveemptycontent.br')) ON CONFLICT (path) DO UPDATE SET content = excluded.content;

.system rm -f /tmp/adfa5088-prefs-workdir/adfa5088-prefs-xml-preserveattributes.br
.system echo "<p>Preserve attribute line breaks keeps attributes on the separate lines you already placed them on, instead of collapsing them onto a single line during formatting.</p>" | brotli -Z > /tmp/adfa5088-prefs-workdir/adfa5088-prefs-xml-preserveattributes.br
INSERT INTO _content_guard SELECT READFILE('/tmp/adfa5088-prefs-workdir/adfa5088-prefs-xml-preserveattributes.br');
INSERT INTO Content (path, languageId, contentTypeId, content) VALUES ('i/prefs/xml/preserveattributes', 1, 12, READFILE('/tmp/adfa5088-prefs-workdir/adfa5088-prefs-xml-preserveattributes.br')) ON CONFLICT (path) DO UPDATE SET content = excluded.content;

.system rm -f /tmp/adfa5088-prefs-workdir/adfa5088-prefs-xml-closebracket.br
.system echo "<p>Closing bracket on new line places the final closing bracket of a tag, including self-closing tags, on its own new line after the last attribute.</p>" | brotli -Z > /tmp/adfa5088-prefs-workdir/adfa5088-prefs-xml-closebracket.br
INSERT INTO _content_guard SELECT READFILE('/tmp/adfa5088-prefs-workdir/adfa5088-prefs-xml-closebracket.br');
INSERT INTO Content (path, languageId, contentTypeId, content) VALUES ('i/prefs/xml/closebracket', 1, 12, READFILE('/tmp/adfa5088-prefs-workdir/adfa5088-prefs-xml-closebracket.br')) ON CONFLICT (path) DO UPDATE SET content = excluded.content;

.system rm -f /tmp/adfa5088-prefs-workdir/adfa5088-prefs-xml-trimwhitespace.br
.system echo "<p>Trim trailing whitespace removes any spaces or tabs left at the end of each line when the formatter runs.</p>" | brotli -Z > /tmp/adfa5088-prefs-workdir/adfa5088-prefs-xml-trimwhitespace.br
INSERT INTO _content_guard SELECT READFILE('/tmp/adfa5088-prefs-workdir/adfa5088-prefs-xml-trimwhitespace.br');
INSERT INTO Content (path, languageId, contentTypeId, content) VALUES ('i/prefs/xml/trimwhitespace', 1, 12, READFILE('/tmp/adfa5088-prefs-workdir/adfa5088-prefs-xml-trimwhitespace.br')) ON CONFLICT (path) DO UPDATE SET content = excluded.content;

.system rm -f /tmp/adfa5088-prefs-workdir/adfa5088-prefs-xml-maxlinewidth.br
.system echo "<p>Maximum line width sets the number of characters allowed on a single line before the formatter wraps it onto additional lines.</p><p>The default value is 80 characters.</p>" | brotli -Z > /tmp/adfa5088-prefs-workdir/adfa5088-prefs-xml-maxlinewidth.br
INSERT INTO _content_guard SELECT READFILE('/tmp/adfa5088-prefs-workdir/adfa5088-prefs-xml-maxlinewidth.br');
INSERT INTO Content (path, languageId, contentTypeId, content) VALUES ('i/prefs/xml/maxlinewidth', 1, 12, READFILE('/tmp/adfa5088-prefs-workdir/adfa5088-prefs-xml-maxlinewidth.br')) ON CONFLICT (path) DO UPDATE SET content = excluded.content;

.system rm -f /tmp/adfa5088-prefs-workdir/adfa5088-prefs-xml-preservenewlines.br
.system echo "<p>Preserve new lines sets the maximum number of consecutive blank lines the formatter keeps between XML elements.</p><p>Any blank lines beyond this limit are removed. The default is 2.</p>" | brotli -Z > /tmp/adfa5088-prefs-workdir/adfa5088-prefs-xml-preservenewlines.br
INSERT INTO _content_guard SELECT READFILE('/tmp/adfa5088-prefs-workdir/adfa5088-prefs-xml-preservenewlines.br');
INSERT INTO Content (path, languageId, contentTypeId, content) VALUES ('i/prefs/xml/preservenewlines', 1, 12, READFILE('/tmp/adfa5088-prefs-workdir/adfa5088-prefs-xml-preservenewlines.br')) ON CONFLICT (path) DO UPDATE SET content = excluded.content;

.system rm -f /tmp/adfa5088-prefs-workdir/adfa5088-prefs-xml-splitattribindent.br
.system echo "<p>Split attributes indent size sets the number of spaces used to indent an attribute placed on its own line by the formatter.</p><p>By default, this is half the editor's tab size.</p>" | brotli -Z > /tmp/adfa5088-prefs-workdir/adfa5088-prefs-xml-splitattribindent.br
INSERT INTO _content_guard SELECT READFILE('/tmp/adfa5088-prefs-workdir/adfa5088-prefs-xml-splitattribindent.br');
INSERT INTO Content (path, languageId, contentTypeId, content) VALUES ('i/prefs/xml/splitattribindent', 1, 12, READFILE('/tmp/adfa5088-prefs-workdir/adfa5088-prefs-xml-splitattribindent.br')) ON CONFLICT (path) DO UPDATE SET content = excluded.content;

.system rm -f /tmp/adfa5088-prefs-workdir/adfa5088-prefs-xml-emptyelements.br
.system echo "<p>Empty elements behavior controls how the formatter writes an XML element that has no content.</p><ul><li><b>Expand</b>: write a separate open and close tag, for example &lt;foo&gt;&lt;/foo&gt;.</li><li><b>Collapse</b>: write a single self-closing tag, for example &lt;foo/&gt;.</li><li><b>Ignore</b>: leave the element as it is in the source file.</li></ul><p>The default is Collapse.</p>" | brotli -Z > /tmp/adfa5088-prefs-workdir/adfa5088-prefs-xml-emptyelements.br
INSERT INTO _content_guard SELECT READFILE('/tmp/adfa5088-prefs-workdir/adfa5088-prefs-xml-emptyelements.br');
INSERT INTO Content (path, languageId, contentTypeId, content) VALUES ('i/prefs/xml/emptyelements', 1, 12, READFILE('/tmp/adfa5088-prefs-workdir/adfa5088-prefs-xml-emptyelements.br')) ON CONFLICT (path) DO UPDATE SET content = excluded.content;

.system rm -f /tmp/adfa5088-prefs-workdir/adfa5088-prefs-build.br
.system echo "<p>The Build &amp; Run screen holds settings for the Gradle build and for running your app: additional Gradle command-line flags, and whether to launch the app automatically after installation.</p>" | brotli -Z > /tmp/adfa5088-prefs-workdir/adfa5088-prefs-build.br
INSERT INTO _content_guard SELECT READFILE('/tmp/adfa5088-prefs-workdir/adfa5088-prefs-build.br');
INSERT INTO Content (path, languageId, contentTypeId, content) VALUES ('i/prefs/build', 1, 12, READFILE('/tmp/adfa5088-prefs-workdir/adfa5088-prefs-build.br')) ON CONFLICT (path) DO UPDATE SET content = excluded.content;

.system rm -f /tmp/adfa5088-prefs-workdir/adfa5088-prefs-build-autolaunch.br
.system echo "<p>Launch app after installation controls whether Code on the Go opens your app automatically once a run finishes installing it on the device.</p><p>When off, you need to launch the app yourself.</p>" | brotli -Z > /tmp/adfa5088-prefs-workdir/adfa5088-prefs-build-autolaunch.br
INSERT INTO _content_guard SELECT READFILE('/tmp/adfa5088-prefs-workdir/adfa5088-prefs-build-autolaunch.br');
INSERT INTO Content (path, languageId, contentTypeId, content) VALUES ('i/prefs/build/autolaunch', 1, 12, READFILE('/tmp/adfa5088-prefs-workdir/adfa5088-prefs-build-autolaunch.br')) ON CONFLICT (path) DO UPDATE SET content = excluded.content;

.system rm -f /tmp/adfa5088-prefs-workdir/adfa5088-prefs-build-flags.br
.system echo "<p>Additional Gradle flags lets you turn on extra command-line flags that Code on the Go adds to every Gradle task it runs, such as build and sync.</p><p>Use this to get more detailed logs, use a build cache, or work offline, without typing the flags yourself each time.</p>" | brotli -Z > /tmp/adfa5088-prefs-workdir/adfa5088-prefs-build-flags.br
INSERT INTO _content_guard SELECT READFILE('/tmp/adfa5088-prefs-workdir/adfa5088-prefs-build-flags.br');
INSERT INTO Content (path, languageId, contentTypeId, content) VALUES ('i/prefs/build/flags', 1, 12, READFILE('/tmp/adfa5088-prefs-workdir/adfa5088-prefs-build-flags.br')) ON CONFLICT (path) DO UPDATE SET content = excluded.content;

.system rm -f /tmp/adfa5088-prefs-workdir/adfa5088-prefs-build-flags-stacktrace.br
.system echo "<p>Sets the Gradle --stacktrace flag. When a build fails, Gradle prints the full stack trace of the failure, which can help diagnose the exact cause.</p>" | brotli -Z > /tmp/adfa5088-prefs-workdir/adfa5088-prefs-build-flags-stacktrace.br
INSERT INTO _content_guard SELECT READFILE('/tmp/adfa5088-prefs-workdir/adfa5088-prefs-build-flags-stacktrace.br');
INSERT INTO Content (path, languageId, contentTypeId, content) VALUES ('i/prefs/build/flags/--stacktrace', 1, 12, READFILE('/tmp/adfa5088-prefs-workdir/adfa5088-prefs-build-flags-stacktrace.br')) ON CONFLICT (path) DO UPDATE SET content = excluded.content;

.system rm -f /tmp/adfa5088-prefs-workdir/adfa5088-prefs-build-flags-info.br
.system echo "<p>Sets the Gradle --info flag to produce more detailed log messages as each task runs, rather than just a summary line for each task.</p>" | brotli -Z > /tmp/adfa5088-prefs-workdir/adfa5088-prefs-build-flags-info.br
INSERT INTO _content_guard SELECT READFILE('/tmp/adfa5088-prefs-workdir/adfa5088-prefs-build-flags-info.br');
INSERT INTO Content (path, languageId, contentTypeId, content) VALUES ('i/prefs/build/flags/--info', 1, 12, READFILE('/tmp/adfa5088-prefs-workdir/adfa5088-prefs-build-flags-info.br')) ON CONFLICT (path) DO UPDATE SET content = excluded.content;

.system rm -f /tmp/adfa5088-prefs-workdir/adfa5088-prefs-build-flags-debug.br
.system echo "<p>Sets the Gradle --debug flag. Gradle prints its most detailed log level for every task.</p><p>This produces a lot of output and can make the build noticeably slower. Use it only when you need to diagnose a specific problem.</p>" | brotli -Z > /tmp/adfa5088-prefs-workdir/adfa5088-prefs-build-flags-debug.br
INSERT INTO _content_guard SELECT READFILE('/tmp/adfa5088-prefs-workdir/adfa5088-prefs-build-flags-debug.br');
INSERT INTO Content (path, languageId, contentTypeId, content) VALUES ('i/prefs/build/flags/--debug', 1, 12, READFILE('/tmp/adfa5088-prefs-workdir/adfa5088-prefs-build-flags-debug.br')) ON CONFLICT (path) DO UPDATE SET content = excluded.content;

.system rm -f /tmp/adfa5088-prefs-workdir/adfa5088-prefs-build-flags-scan.br
.system echo "<p>Sets the Gradle --scan flag. After the build finishes, Gradle uploads data about the build and gives you a link to a Build Scan report.</p><p>The report shows detailed information about tasks, dependencies, and timing. This requires network access.</p>" | brotli -Z > /tmp/adfa5088-prefs-workdir/adfa5088-prefs-build-flags-scan.br
INSERT INTO _content_guard SELECT READFILE('/tmp/adfa5088-prefs-workdir/adfa5088-prefs-build-flags-scan.br');
INSERT INTO Content (path, languageId, contentTypeId, content) VALUES ('i/prefs/build/flags/--scan', 1, 12, READFILE('/tmp/adfa5088-prefs-workdir/adfa5088-prefs-build-flags-scan.br')) ON CONFLICT (path) DO UPDATE SET content = excluded.content;

.system rm -f /tmp/adfa5088-prefs-workdir/adfa5088-prefs-build-flags-warningmodeall.br
.system echo "<p>Sets the Gradle --warning-mode all flag. Gradle prints every individual deprecation warning during the build, instead of only a summary count at the end.</p>" | brotli -Z > /tmp/adfa5088-prefs-workdir/adfa5088-prefs-build-flags-warningmodeall.br
INSERT INTO _content_guard SELECT READFILE('/tmp/adfa5088-prefs-workdir/adfa5088-prefs-build-flags-warningmodeall.br');
INSERT INTO Content (path, languageId, contentTypeId, content) VALUES ('i/prefs/build/flags/--warning-mode-all', 1, 12, READFILE('/tmp/adfa5088-prefs-workdir/adfa5088-prefs-build-flags-warningmodeall.br')) ON CONFLICT (path) DO UPDATE SET content = excluded.content;

.system rm -f /tmp/adfa5088-prefs-workdir/adfa5088-prefs-build-flags-buildcache.br
.system echo "<p>Sets the Gradle --build-cache flag. Gradle reuses outputs from a previous build for any task whose inputs have not changed, instead of running that task again.</p><p>This can noticeably speed up repeated builds.</p>" | brotli -Z > /tmp/adfa5088-prefs-workdir/adfa5088-prefs-build-flags-buildcache.br
INSERT INTO _content_guard SELECT READFILE('/tmp/adfa5088-prefs-workdir/adfa5088-prefs-build-flags-buildcache.br');
INSERT INTO Content (path, languageId, contentTypeId, content) VALUES ('i/prefs/build/flags/--build-cache', 1, 12, READFILE('/tmp/adfa5088-prefs-workdir/adfa5088-prefs-build-flags-buildcache.br')) ON CONFLICT (path) DO UPDATE SET content = excluded.content;

.system rm -f /tmp/adfa5088-prefs-workdir/adfa5088-prefs-build-flags-offline.br
.system echo "<p>Sets the Gradle --offline flag. Gradle uses only the dependencies already downloaded to your device and does not attempt any network access.</p><p>The build fails if a required dependency has not been downloaded yet.</p>" | brotli -Z > /tmp/adfa5088-prefs-workdir/adfa5088-prefs-build-flags-offline.br
INSERT INTO _content_guard SELECT READFILE('/tmp/adfa5088-prefs-workdir/adfa5088-prefs-build-flags-offline.br');
INSERT INTO Content (path, languageId, contentTypeId, content) VALUES ('i/prefs/build/flags/--offline', 1, 12, READFILE('/tmp/adfa5088-prefs-workdir/adfa5088-prefs-build-flags-offline.br')) ON CONFLICT (path) DO UPDATE SET content = excluded.content;

.system rm -f /tmp/adfa5088-prefs-workdir/adfa5088-prefs-termux.br
.system echo "<p>The Terminal screen holds settings for the built-in terminal emulator: internal logging, keyboard behavior, crash notifications, and screen margin.</p>" | brotli -Z > /tmp/adfa5088-prefs-workdir/adfa5088-prefs-termux.br
INSERT INTO _content_guard SELECT READFILE('/tmp/adfa5088-prefs-workdir/adfa5088-prefs-termux.br');
INSERT INTO Content (path, languageId, contentTypeId, content) VALUES ('i/prefs/termux', 1, 12, READFILE('/tmp/adfa5088-prefs-workdir/adfa5088-prefs-termux.br')) ON CONFLICT (path) DO UPDATE SET content = excluded.content;

.system rm -f /tmp/adfa5088-prefs-workdir/adfa5088-prefs-termux-loglevel.br
.system echo "<p>Log Level sets how much detail the terminal emulator writes to its own internal log, separate from your app logs.</p><p>Use a higher level only when troubleshooting a terminal problem, since it produces more log output.</p>" | brotli -Z > /tmp/adfa5088-prefs-workdir/adfa5088-prefs-termux-loglevel.br
INSERT INTO _content_guard SELECT READFILE('/tmp/adfa5088-prefs-workdir/adfa5088-prefs-termux-loglevel.br');
INSERT INTO Content (path, languageId, contentTypeId, content) VALUES ('i/prefs/termux/loglevel', 1, 12, READFILE('/tmp/adfa5088-prefs-workdir/adfa5088-prefs-termux-loglevel.br')) ON CONFLICT (path) DO UPDATE SET content = excluded.content;

.system rm -f /tmp/adfa5088-prefs-workdir/adfa5088-prefs-termux-keylogging.br
.system echo "<p>Terminal View Key Logging records each key you press in the terminal to the system log.</p><p>This produces a large amount of log output and can cause performance problems, so it is off by default. Turn it on only to debug a keyboard-related issue.</p>" | brotli -Z > /tmp/adfa5088-prefs-workdir/adfa5088-prefs-termux-keylogging.br
INSERT INTO _content_guard SELECT READFILE('/tmp/adfa5088-prefs-workdir/adfa5088-prefs-termux-keylogging.br');
INSERT INTO Content (path, languageId, contentTypeId, content) VALUES ('i/prefs/termux/keylogging', 1, 12, READFILE('/tmp/adfa5088-prefs-workdir/adfa5088-prefs-termux-keylogging.br')) ON CONFLICT (path) DO UPDATE SET content = excluded.content;

.system rm -f /tmp/adfa5088-prefs-workdir/adfa5088-prefs-termux-crashreports.br
.system echo "<p>Crash Report Notifications controls whether Code on the Go shows a notification after the terminal process crashes.</p><p>The notification lets you view or share a report about the crash. This is on by default.</p>" | brotli -Z > /tmp/adfa5088-prefs-workdir/adfa5088-prefs-termux-crashreports.br
INSERT INTO _content_guard SELECT READFILE('/tmp/adfa5088-prefs-workdir/adfa5088-prefs-termux-crashreports.br');
INSERT INTO Content (path, languageId, contentTypeId, content) VALUES ('i/prefs/termux/crashreports', 1, 12, READFILE('/tmp/adfa5088-prefs-workdir/adfa5088-prefs-termux-crashreports.br')) ON CONFLICT (path) DO UPDATE SET content = excluded.content;

.system rm -f /tmp/adfa5088-prefs-workdir/adfa5088-prefs-termux-softkeyboard.br
.system echo "<p>Soft Keyboard Enabled turns on the terminal's own on-screen keyboard, which includes keys not found on a standard keyboard, such as Ctrl and Esc.</p>" | brotli -Z > /tmp/adfa5088-prefs-workdir/adfa5088-prefs-termux-softkeyboard.br
INSERT INTO _content_guard SELECT READFILE('/tmp/adfa5088-prefs-workdir/adfa5088-prefs-termux-softkeyboard.br');
INSERT INTO Content (path, languageId, contentTypeId, content) VALUES ('i/prefs/termux/softkeyboard', 1, 12, READFILE('/tmp/adfa5088-prefs-workdir/adfa5088-prefs-termux-softkeyboard.br')) ON CONFLICT (path) DO UPDATE SET content = excluded.content;

.system rm -f /tmp/adfa5088-prefs-workdir/adfa5088-prefs-termux-nohardkeyboard.br
.system echo "<p>Soft Keyboard Only If No Hardware shows the on-screen keyboard only when no physical keyboard is connected to your device.</p><p>This is off by default, meaning the on-screen keyboard can show even with a hardware keyboard connected.</p>" | brotli -Z > /tmp/adfa5088-prefs-workdir/adfa5088-prefs-termux-nohardkeyboard.br
INSERT INTO _content_guard SELECT READFILE('/tmp/adfa5088-prefs-workdir/adfa5088-prefs-termux-nohardkeyboard.br');
INSERT INTO Content (path, languageId, contentTypeId, content) VALUES ('i/prefs/termux/nohardkeyboard', 1, 12, READFILE('/tmp/adfa5088-prefs-workdir/adfa5088-prefs-termux-nohardkeyboard.br')) ON CONFLICT (path) DO UPDATE SET content = excluded.content;

.system rm -f /tmp/adfa5088-prefs-workdir/adfa5088-prefs-termux-margin.br
.system echo "<p>Terminal Margin Adjustment changes the terminal view margin to try to prevent the on-screen keyboard from covering part of the terminal or its extra keys row.</p><p>This is on by default. If it causes screen flickering on your device, turn it off.</p>" | brotli -Z > /tmp/adfa5088-prefs-workdir/adfa5088-prefs-termux-margin.br
INSERT INTO _content_guard SELECT READFILE('/tmp/adfa5088-prefs-workdir/adfa5088-prefs-termux-margin.br');
INSERT INTO Content (path, languageId, contentTypeId, content) VALUES ('i/prefs/termux/margin', 1, 12, READFILE('/tmp/adfa5088-prefs-workdir/adfa5088-prefs-termux-margin.br')) ON CONFLICT (path) DO UPDATE SET content = excluded.content;

.system rm -f /tmp/adfa5088-prefs-workdir/adfa5088-prefs-git.br
.system echo "<p>The Git screen sets your author identity for Git: the name and email address recorded on every commit you make from Code on the Go.</p>" | brotli -Z > /tmp/adfa5088-prefs-workdir/adfa5088-prefs-git.br
INSERT INTO _content_guard SELECT READFILE('/tmp/adfa5088-prefs-workdir/adfa5088-prefs-git.br');
INSERT INTO Content (path, languageId, contentTypeId, content) VALUES ('i/prefs/git', 1, 12, READFILE('/tmp/adfa5088-prefs-workdir/adfa5088-prefs-git.br')) ON CONFLICT (path) DO UPDATE SET content = excluded.content;

.system rm -f /tmp/adfa5088-prefs-workdir/adfa5088-prefs-git-username.br
.system echo "<p>User name sets the name recorded as the author on every Git commit you make from Code on the Go.</p>" | brotli -Z > /tmp/adfa5088-prefs-workdir/adfa5088-prefs-git-username.br
INSERT INTO _content_guard SELECT READFILE('/tmp/adfa5088-prefs-workdir/adfa5088-prefs-git-username.br');
INSERT INTO Content (path, languageId, contentTypeId, content) VALUES ('i/prefs/git/username', 1, 12, READFILE('/tmp/adfa5088-prefs-workdir/adfa5088-prefs-git-username.br')) ON CONFLICT (path) DO UPDATE SET content = excluded.content;

.system rm -f /tmp/adfa5088-prefs-workdir/adfa5088-prefs-git-useremail.br
.system echo "<p>User email sets the email address recorded as the author on every Git commit you make from Code on the Go.</p>" | brotli -Z > /tmp/adfa5088-prefs-workdir/adfa5088-prefs-git-useremail.br
INSERT INTO _content_guard SELECT READFILE('/tmp/adfa5088-prefs-workdir/adfa5088-prefs-git-useremail.br');
INSERT INTO Content (path, languageId, contentTypeId, content) VALUES ('i/prefs/git/useremail', 1, 12, READFILE('/tmp/adfa5088-prefs-workdir/adfa5088-prefs-git-useremail.br')) ON CONFLICT (path) DO UPDATE SET content = excluded.content;

.system rm -f /tmp/adfa5088-prefs-workdir/adfa5088-prefs-pluginmanager.br
.system echo "<p>Plugin Manager opens a screen where you can browse, install, remove, and configure plugins that extend Code on the Go with extra features.</p>" | brotli -Z > /tmp/adfa5088-prefs-workdir/adfa5088-prefs-pluginmanager.br
INSERT INTO _content_guard SELECT READFILE('/tmp/adfa5088-prefs-workdir/adfa5088-prefs-pluginmanager.br');
INSERT INTO Content (path, languageId, contentTypeId, content) VALUES ('i/prefs/pluginmanager', 1, 12, READFILE('/tmp/adfa5088-prefs-workdir/adfa5088-prefs-pluginmanager.br')) ON CONFLICT (path) DO UPDATE SET content = excluded.content;

.system rm -f /tmp/adfa5088-prefs-workdir/adfa5088-prefs-about.br
.system echo "<p>About Code on the Go opens a screen with details about the app, such as its version number and credits.</p>" | brotli -Z > /tmp/adfa5088-prefs-workdir/adfa5088-prefs-about.br
INSERT INTO _content_guard SELECT READFILE('/tmp/adfa5088-prefs-workdir/adfa5088-prefs-about.br');
INSERT INTO Content (path, languageId, contentTypeId, content) VALUES ('i/prefs/about', 1, 12, READFILE('/tmp/adfa5088-prefs-workdir/adfa5088-prefs-about.br')) ON CONFLICT (path) DO UPDATE SET content = excluded.content;

.system rm -f /tmp/adfa5088-prefs-workdir/adfa5088-prefs-devoptions.br
.system echo "<p>Developer Options holds experimental and debugging settings for Code on the Go, such as log dumping and log sending controls.</p><p>These settings are meant for troubleshooting, not everyday use.</p>" | brotli -Z > /tmp/adfa5088-prefs-workdir/adfa5088-prefs-devoptions.br
INSERT INTO _content_guard SELECT READFILE('/tmp/adfa5088-prefs-workdir/adfa5088-prefs-devoptions.br');
INSERT INTO Content (path, languageId, contentTypeId, content) VALUES ('i/prefs/devoptions', 1, 12, READFILE('/tmp/adfa5088-prefs-workdir/adfa5088-prefs-devoptions.br')) ON CONFLICT (path) DO UPDATE SET content = excluded.content;

.system rm -f /tmp/adfa5088-prefs-workdir/adfa5088-prefs-devoptions-dumplogs.br
.system echo "<p>Dump logs writes the Code on the Go internal logs to a file at ~/.cg/logs, inside your home directory.</p><p>Turn this on when you need to collect logs to diagnose a problem.</p>" | brotli -Z > /tmp/adfa5088-prefs-workdir/adfa5088-prefs-devoptions-dumplogs.br
INSERT INTO _content_guard SELECT READFILE('/tmp/adfa5088-prefs-workdir/adfa5088-prefs-devoptions-dumplogs.br');
INSERT INTO Content (path, languageId, contentTypeId, content) VALUES ('i/prefs/devoptions/dumplogs', 1, 12, READFILE('/tmp/adfa5088-prefs-workdir/adfa5088-prefs-devoptions-dumplogs.br')) ON CONFLICT (path) DO UPDATE SET content = excluded.content;

.system rm -f /tmp/adfa5088-prefs-workdir/adfa5088-prefs-devoptions-logsender.br
.system echo "<p>Enable LogSender controls whether Code on the Go shows log output from the apps you run, inside its own log viewer.</p><p>This is on by default. Turn it off if you do not want to see app logs inside the IDE.</p>" | brotli -Z > /tmp/adfa5088-prefs-workdir/adfa5088-prefs-devoptions-logsender.br
INSERT INTO _content_guard SELECT READFILE('/tmp/adfa5088-prefs-workdir/adfa5088-prefs-devoptions-logsender.br');
INSERT INTO Content (path, languageId, contentTypeId, content) VALUES ('i/prefs/devoptions/logsender', 1, 12, READFILE('/tmp/adfa5088-prefs-workdir/adfa5088-prefs-devoptions-logsender.br')) ON CONFLICT (path) DO UPDATE SET content = excluded.content;

-- Tier 3 links: without a TooltipButtons row, a tooltip's popup has no way to
-- surface its Content page - Tier 1 (summary) and Tier 2 (detail) still work
-- from the Tooltips row alone, but the richer Content page above is otherwise
-- unreachable. buttonNumberId 1 matches the existing single-button convention
-- (see e.g. the debugger-panel tooltip). Idempotent: delete then insert, since
-- TooltipButtons has no unique constraint to upsert against.

DELETE FROM TooltipButtons WHERE tooltipId = (SELECT id FROM Tooltips WHERE tag = 'prefs.general.uimode' AND categoryId = 1) AND uri = 'i/prefs/general/uimode';
INSERT INTO TooltipButtons (tooltipId, buttonNumberId, description, uri) VALUES ((SELECT id FROM Tooltips WHERE tag = 'prefs.general.uimode' AND categoryId = 1), 1, 'Learn more', 'i/prefs/general/uimode');

DELETE FROM TooltipButtons WHERE tooltipId = (SELECT id FROM Tooltips WHERE tag = 'prefs.general.language' AND categoryId = 1) AND uri = 'i/prefs/general/language';
INSERT INTO TooltipButtons (tooltipId, buttonNumberId, description, uri) VALUES ((SELECT id FROM Tooltips WHERE tag = 'prefs.general.language' AND categoryId = 1), 1, 'Learn more', 'i/prefs/general/language');

DELETE FROM TooltipButtons WHERE tooltipId = (SELECT id FROM Tooltips WHERE tag = 'prefs.general.openlast' AND categoryId = 1) AND uri = 'i/prefs/general/openlast';
INSERT INTO TooltipButtons (tooltipId, buttonNumberId, description, uri) VALUES ((SELECT id FROM Tooltips WHERE tag = 'prefs.general.openlast' AND categoryId = 1), 1, 'Learn more', 'i/prefs/general/openlast');

DELETE FROM TooltipButtons WHERE tooltipId = (SELECT id FROM Tooltips WHERE tag = 'prefs.general.confirmopen' AND categoryId = 1) AND uri = 'i/prefs/general/confirmopen';
INSERT INTO TooltipButtons (tooltipId, buttonNumberId, description, uri) VALUES ((SELECT id FROM Tooltips WHERE tag = 'prefs.general.confirmopen' AND categoryId = 1), 1, 'Learn more', 'i/prefs/general/confirmopen');

DELETE FROM TooltipButtons WHERE tooltipId = (SELECT id FROM Tooltips WHERE tag = 'prefs.editor.fontsize' AND categoryId = 1) AND uri = 'i/prefs/editor/fontsize';
INSERT INTO TooltipButtons (tooltipId, buttonNumberId, description, uri) VALUES ((SELECT id FROM Tooltips WHERE tag = 'prefs.editor.fontsize' AND categoryId = 1), 1, 'Learn more', 'i/prefs/editor/fontsize');

DELETE FROM TooltipButtons WHERE tooltipId = (SELECT id FROM Tooltips WHERE tag = 'prefs.editor.tabsize' AND categoryId = 1) AND uri = 'i/prefs/editor/tabsize';
INSERT INTO TooltipButtons (tooltipId, buttonNumberId, description, uri) VALUES ((SELECT id FROM Tooltips WHERE tag = 'prefs.editor.tabsize' AND categoryId = 1), 1, 'Learn more', 'i/prefs/editor/tabsize');

DELETE FROM TooltipButtons WHERE tooltipId = (SELECT id FROM Tooltips WHERE tag = 'prefs.editor.nonprinting' AND categoryId = 1) AND uri = 'i/prefs/editor/nonprinting';
INSERT INTO TooltipButtons (tooltipId, buttonNumberId, description, uri) VALUES ((SELECT id FROM Tooltips WHERE tag = 'prefs.editor.nonprinting' AND categoryId = 1), 1, 'Learn more', 'i/prefs/editor/nonprinting');

DELETE FROM TooltipButtons WHERE tooltipId = (SELECT id FROM Tooltips WHERE tag = 'prefs.editor.softtab' AND categoryId = 1) AND uri = 'i/prefs/editor/softtab';
INSERT INTO TooltipButtons (tooltipId, buttonNumberId, description, uri) VALUES ((SELECT id FROM Tooltips WHERE tag = 'prefs.editor.softtab' AND categoryId = 1), 1, 'Learn more', 'i/prefs/editor/softtab');

DELETE FROM TooltipButtons WHERE tooltipId = (SELECT id FROM Tooltips WHERE tag = 'prefs.editor.wordwrap' AND categoryId = 1) AND uri = 'i/prefs/editor/wordwrap';
INSERT INTO TooltipButtons (tooltipId, buttonNumberId, description, uri) VALUES ((SELECT id FROM Tooltips WHERE tag = 'prefs.editor.wordwrap' AND categoryId = 1), 1, 'Learn more', 'i/prefs/editor/wordwrap');

DELETE FROM TooltipButtons WHERE tooltipId = (SELECT id FROM Tooltips WHERE tag = 'prefs.editor.magnifier' AND categoryId = 1) AND uri = 'i/prefs/editor/magnifier';
INSERT INTO TooltipButtons (tooltipId, buttonNumberId, description, uri) VALUES ((SELECT id FROM Tooltips WHERE tag = 'prefs.editor.magnifier' AND categoryId = 1), 1, 'Learn more', 'i/prefs/editor/magnifier');

DELETE FROM TooltipButtons WHERE tooltipId = (SELECT id FROM Tooltips WHERE tag = 'prefs.editor.wordboundaries' AND categoryId = 1) AND uri = 'i/prefs/editor/wordboundaries';
INSERT INTO TooltipButtons (tooltipId, buttonNumberId, description, uri) VALUES ((SELECT id FROM Tooltips WHERE tag = 'prefs.editor.wordboundaries' AND categoryId = 1), 1, 'Learn more', 'i/prefs/editor/wordboundaries');

DELETE FROM TooltipButtons WHERE tooltipId = (SELECT id FROM Tooltips WHERE tag = 'prefs.editor.matchcase' AND categoryId = 1) AND uri = 'i/prefs/editor/matchcase';
INSERT INTO TooltipButtons (tooltipId, buttonNumberId, description, uri) VALUES ((SELECT id FROM Tooltips WHERE tag = 'prefs.editor.matchcase' AND categoryId = 1), 1, 'Learn more', 'i/prefs/editor/matchcase');

DELETE FROM TooltipButtons WHERE tooltipId = (SELECT id FROM Tooltips WHERE tag = 'prefs.editor.deletelines' AND categoryId = 1) AND uri = 'i/prefs/editor/deletelines';
INSERT INTO TooltipButtons (tooltipId, buttonNumberId, description, uri) VALUES ((SELECT id FROM Tooltips WHERE tag = 'prefs.editor.deletelines' AND categoryId = 1), 1, 'Learn more', 'i/prefs/editor/deletelines');

DELETE FROM TooltipButtons WHERE tooltipId = (SELECT id FROM Tooltips WHERE tag = 'prefs.editor.smartbackspace' AND categoryId = 1) AND uri = 'i/prefs/editor/smartbackspace';
INSERT INTO TooltipButtons (tooltipId, buttonNumberId, description, uri) VALUES ((SELECT id FROM Tooltips WHERE tag = 'prefs.editor.smartbackspace' AND categoryId = 1), 1, 'Learn more', 'i/prefs/editor/smartbackspace');

DELETE FROM TooltipButtons WHERE tooltipId = (SELECT id FROM Tooltips WHERE tag = 'prefs.editor.stickyscroll' AND categoryId = 1) AND uri = 'i/prefs/editor/stickyscroll';
INSERT INTO TooltipButtons (tooltipId, buttonNumberId, description, uri) VALUES ((SELECT id FROM Tooltips WHERE tag = 'prefs.editor.stickyscroll' AND categoryId = 1), 1, 'Learn more', 'i/prefs/editor/stickyscroll');

DELETE FROM TooltipButtons WHERE tooltipId = (SELECT id FROM Tooltips WHERE tag = 'prefs.editor.pinlines' AND categoryId = 1) AND uri = 'i/prefs/editor/pinlines';
INSERT INTO TooltipButtons (tooltipId, buttonNumberId, description, uri) VALUES ((SELECT id FROM Tooltips WHERE tag = 'prefs.editor.pinlines' AND categoryId = 1), 1, 'Learn more', 'i/prefs/editor/pinlines');

DELETE FROM TooltipButtons WHERE tooltipId = (SELECT id FROM Tooltips WHERE tag = 'prefs.editor.googlestyle' AND categoryId = 1) AND uri = 'i/prefs/editor/googlestyle';
INSERT INTO TooltipButtons (tooltipId, buttonNumberId, description, uri) VALUES ((SELECT id FROM Tooltips WHERE tag = 'prefs.editor.googlestyle' AND categoryId = 1), 1, 'Learn more', 'i/prefs/editor/googlestyle');

DELETE FROM TooltipButtons WHERE tooltipId = (SELECT id FROM Tooltips WHERE tag = 'prefs.buildrun' AND categoryId = 1) AND uri = 'i/prefs/build';
INSERT INTO TooltipButtons (tooltipId, buttonNumberId, description, uri) VALUES ((SELECT id FROM Tooltips WHERE tag = 'prefs.buildrun' AND categoryId = 1), 1, 'Learn more', 'i/prefs/build');

DELETE FROM TooltipButtons WHERE tooltipId = (SELECT id FROM Tooltips WHERE tag = 'prefs.buildrun.autolaunch' AND categoryId = 1) AND uri = 'i/prefs/build/autolaunch';
INSERT INTO TooltipButtons (tooltipId, buttonNumberId, description, uri) VALUES ((SELECT id FROM Tooltips WHERE tag = 'prefs.buildrun.autolaunch' AND categoryId = 1), 1, 'Learn more', 'i/prefs/build/autolaunch');

DELETE FROM TooltipButtons WHERE tooltipId = (SELECT id FROM Tooltips WHERE tag = 'prefs.buildrun.flags' AND categoryId = 1) AND uri = 'i/prefs/build/flags';
INSERT INTO TooltipButtons (tooltipId, buttonNumberId, description, uri) VALUES ((SELECT id FROM Tooltips WHERE tag = 'prefs.buildrun.flags' AND categoryId = 1), 1, 'Learn more', 'i/prefs/build/flags');

DELETE FROM TooltipButtons WHERE tooltipId = (SELECT id FROM Tooltips WHERE tag = 'prefs.termux.loglevel' AND categoryId = 1) AND uri = 'i/prefs/termux/loglevel';
INSERT INTO TooltipButtons (tooltipId, buttonNumberId, description, uri) VALUES ((SELECT id FROM Tooltips WHERE tag = 'prefs.termux.loglevel' AND categoryId = 1), 1, 'Learn more', 'i/prefs/termux/loglevel');

DELETE FROM TooltipButtons WHERE tooltipId = (SELECT id FROM Tooltips WHERE tag = 'prefs.termux.keylogging' AND categoryId = 1) AND uri = 'i/prefs/termux/keylogging';
INSERT INTO TooltipButtons (tooltipId, buttonNumberId, description, uri) VALUES ((SELECT id FROM Tooltips WHERE tag = 'prefs.termux.keylogging' AND categoryId = 1), 1, 'Learn more', 'i/prefs/termux/keylogging');

DELETE FROM TooltipButtons WHERE tooltipId = (SELECT id FROM Tooltips WHERE tag = 'prefs.termux.margin' AND categoryId = 1) AND uri = 'i/prefs/termux/margin';
INSERT INTO TooltipButtons (tooltipId, buttonNumberId, description, uri) VALUES ((SELECT id FROM Tooltips WHERE tag = 'prefs.termux.margin' AND categoryId = 1), 1, 'Learn more', 'i/prefs/termux/margin');

DELETE FROM TooltipButtons WHERE tooltipId = (SELECT id FROM Tooltips WHERE tag = 'prefs.termux.nohardkeyboard' AND categoryId = 1) AND uri = 'i/prefs/termux/nohardkeyboard';
INSERT INTO TooltipButtons (tooltipId, buttonNumberId, description, uri) VALUES ((SELECT id FROM Tooltips WHERE tag = 'prefs.termux.nohardkeyboard' AND categoryId = 1), 1, 'Learn more', 'i/prefs/termux/nohardkeyboard');

DELETE FROM TooltipButtons WHERE tooltipId = (SELECT id FROM Tooltips WHERE tag = 'prefs.termux.softkeyboard' AND categoryId = 1) AND uri = 'i/prefs/termux/softkeyboard';
INSERT INTO TooltipButtons (tooltipId, buttonNumberId, description, uri) VALUES ((SELECT id FROM Tooltips WHERE tag = 'prefs.termux.softkeyboard' AND categoryId = 1), 1, 'Learn more', 'i/prefs/termux/softkeyboard');

DELETE FROM TooltipButtons WHERE tooltipId = (SELECT id FROM Tooltips WHERE tag = 'prefs.xml.closebracket' AND categoryId = 1) AND uri = 'i/prefs/xml/closebracket';
INSERT INTO TooltipButtons (tooltipId, buttonNumberId, description, uri) VALUES ((SELECT id FROM Tooltips WHERE tag = 'prefs.xml.closebracket' AND categoryId = 1), 1, 'Learn more', 'i/prefs/xml/closebracket');

DELETE FROM TooltipButtons WHERE tooltipId = (SELECT id FROM Tooltips WHERE tag = 'prefs.xml.emptyelements' AND categoryId = 1) AND uri = 'i/prefs/xml/emptyelements';
INSERT INTO TooltipButtons (tooltipId, buttonNumberId, description, uri) VALUES ((SELECT id FROM Tooltips WHERE tag = 'prefs.xml.emptyelements' AND categoryId = 1), 1, 'Learn more', 'i/prefs/xml/emptyelements');

DELETE FROM TooltipButtons WHERE tooltipId = (SELECT id FROM Tooltips WHERE tag = 'prefs.xml.maxlinewidth' AND categoryId = 1) AND uri = 'i/prefs/xml/maxlinewidth';
INSERT INTO TooltipButtons (tooltipId, buttonNumberId, description, uri) VALUES ((SELECT id FROM Tooltips WHERE tag = 'prefs.xml.maxlinewidth' AND categoryId = 1), 1, 'Learn more', 'i/prefs/xml/maxlinewidth');

DELETE FROM TooltipButtons WHERE tooltipId = (SELECT id FROM Tooltips WHERE tag = 'prefs.xml.preserveattributes' AND categoryId = 1) AND uri = 'i/prefs/xml/preserveattributes';
INSERT INTO TooltipButtons (tooltipId, buttonNumberId, description, uri) VALUES ((SELECT id FROM Tooltips WHERE tag = 'prefs.xml.preserveattributes' AND categoryId = 1), 1, 'Learn more', 'i/prefs/xml/preserveattributes');

DELETE FROM TooltipButtons WHERE tooltipId = (SELECT id FROM Tooltips WHERE tag = 'prefs.xml.preservenewlines' AND categoryId = 1) AND uri = 'i/prefs/xml/preservenewlines';
INSERT INTO TooltipButtons (tooltipId, buttonNumberId, description, uri) VALUES ((SELECT id FROM Tooltips WHERE tag = 'prefs.xml.preservenewlines' AND categoryId = 1), 1, 'Learn more', 'i/prefs/xml/preservenewlines');

DELETE FROM TooltipButtons WHERE tooltipId = (SELECT id FROM Tooltips WHERE tag = 'prefs.xml.spacebeforeclose' AND categoryId = 1) AND uri = 'i/prefs/xml/spacebeforeclose';
INSERT INTO TooltipButtons (tooltipId, buttonNumberId, description, uri) VALUES ((SELECT id FROM Tooltips WHERE tag = 'prefs.xml.spacebeforeclose' AND categoryId = 1), 1, 'Learn more', 'i/prefs/xml/spacebeforeclose');

DELETE FROM TooltipButtons WHERE tooltipId = (SELECT id FROM Tooltips WHERE tag = 'prefs.xml.splitattribindent' AND categoryId = 1) AND uri = 'i/prefs/xml/splitattribindent';
INSERT INTO TooltipButtons (tooltipId, buttonNumberId, description, uri) VALUES ((SELECT id FROM Tooltips WHERE tag = 'prefs.xml.splitattribindent' AND categoryId = 1), 1, 'Learn more', 'i/prefs/xml/splitattribindent');

DELETE FROM TooltipButtons WHERE tooltipId = (SELECT id FROM Tooltips WHERE tag = 'prefs.xml.trimwhitespace' AND categoryId = 1) AND uri = 'i/prefs/xml/trimwhitespace';
INSERT INTO TooltipButtons (tooltipId, buttonNumberId, description, uri) VALUES ((SELECT id FROM Tooltips WHERE tag = 'prefs.xml.trimwhitespace' AND categoryId = 1), 1, 'Learn more', 'i/prefs/xml/trimwhitespace');

DELETE FROM TooltipButtons WHERE tooltipId = (SELECT id FROM Tooltips WHERE tag = 'prefs.devoptions' AND categoryId = 1) AND uri = 'i/prefs/devoptions';
INSERT INTO TooltipButtons (tooltipId, buttonNumberId, description, uri) VALUES ((SELECT id FROM Tooltips WHERE tag = 'prefs.devoptions' AND categoryId = 1), 1, 'Learn more', 'i/prefs/devoptions');

DELETE FROM TooltipButtons WHERE tooltipId = (SELECT id FROM Tooltips WHERE tag = 'prefs.devoptions.dumplogs' AND categoryId = 1) AND uri = 'i/prefs/devoptions/dumplogs';
INSERT INTO TooltipButtons (tooltipId, buttonNumberId, description, uri) VALUES ((SELECT id FROM Tooltips WHERE tag = 'prefs.devoptions.dumplogs' AND categoryId = 1), 1, 'Learn more', 'i/prefs/devoptions/dumplogs');

DELETE FROM TooltipButtons WHERE tooltipId = (SELECT id FROM Tooltips WHERE tag = 'prefs.devoptions.logsender' AND categoryId = 1) AND uri = 'i/prefs/devoptions/logsender';
INSERT INTO TooltipButtons (tooltipId, buttonNumberId, description, uri) VALUES ((SELECT id FROM Tooltips WHERE tag = 'prefs.devoptions.logsender' AND categoryId = 1), 1, 'Learn more', 'i/prefs/devoptions/logsender');

DELETE FROM TooltipButtons WHERE tooltipId = (SELECT id FROM Tooltips WHERE tag = 'prefs.editor.nonprinting.leading' AND categoryId = 1) AND uri = 'i/prefs/editor/nonprinting/leading';
INSERT INTO TooltipButtons (tooltipId, buttonNumberId, description, uri) VALUES ((SELECT id FROM Tooltips WHERE tag = 'prefs.editor.nonprinting.leading' AND categoryId = 1), 1, 'Learn more', 'i/prefs/editor/nonprinting/leading');

DELETE FROM TooltipButtons WHERE tooltipId = (SELECT id FROM Tooltips WHERE tag = 'prefs.editor.nonprinting.trailing' AND categoryId = 1) AND uri = 'i/prefs/editor/nonprinting/trailing';
INSERT INTO TooltipButtons (tooltipId, buttonNumberId, description, uri) VALUES ((SELECT id FROM Tooltips WHERE tag = 'prefs.editor.nonprinting.trailing' AND categoryId = 1), 1, 'Learn more', 'i/prefs/editor/nonprinting/trailing');

DELETE FROM TooltipButtons WHERE tooltipId = (SELECT id FROM Tooltips WHERE tag = 'prefs.editor.nonprinting.inner' AND categoryId = 1) AND uri = 'i/prefs/editor/nonprinting/inner';
INSERT INTO TooltipButtons (tooltipId, buttonNumberId, description, uri) VALUES ((SELECT id FROM Tooltips WHERE tag = 'prefs.editor.nonprinting.inner' AND categoryId = 1), 1, 'Learn more', 'i/prefs/editor/nonprinting/inner');

DELETE FROM TooltipButtons WHERE tooltipId = (SELECT id FROM Tooltips WHERE tag = 'prefs.editor.nonprinting.emptylines' AND categoryId = 1) AND uri = 'i/prefs/editor/nonprinting/emptylines';
INSERT INTO TooltipButtons (tooltipId, buttonNumberId, description, uri) VALUES ((SELECT id FROM Tooltips WHERE tag = 'prefs.editor.nonprinting.emptylines' AND categoryId = 1), 1, 'Learn more', 'i/prefs/editor/nonprinting/emptylines');

DELETE FROM TooltipButtons WHERE tooltipId = (SELECT id FROM Tooltips WHERE tag = 'prefs.editor.nonprinting.linebreaks' AND categoryId = 1) AND uri = 'i/prefs/editor/nonprinting/linebreaks';
INSERT INTO TooltipButtons (tooltipId, buttonNumberId, description, uri) VALUES ((SELECT id FROM Tooltips WHERE tag = 'prefs.editor.nonprinting.linebreaks' AND categoryId = 1), 1, 'Learn more', 'i/prefs/editor/nonprinting/linebreaks');

DELETE FROM TooltipButtons WHERE tooltipId = (SELECT id FROM Tooltips WHERE tag = 'prefs.xml.trimfinalnewline' AND categoryId = 1) AND uri = 'i/prefs/xml/trimfinalnewline';
INSERT INTO TooltipButtons (tooltipId, buttonNumberId, description, uri) VALUES ((SELECT id FROM Tooltips WHERE tag = 'prefs.xml.trimfinalnewline' AND categoryId = 1), 1, 'Learn more', 'i/prefs/xml/trimfinalnewline');

DELETE FROM TooltipButtons WHERE tooltipId = (SELECT id FROM Tooltips WHERE tag = 'prefs.xml.insertfinalnewline' AND categoryId = 1) AND uri = 'i/prefs/xml/insertfinalnewline';
INSERT INTO TooltipButtons (tooltipId, buttonNumberId, description, uri) VALUES ((SELECT id FROM Tooltips WHERE tag = 'prefs.xml.insertfinalnewline' AND categoryId = 1), 1, 'Learn more', 'i/prefs/xml/insertfinalnewline');

DELETE FROM TooltipButtons WHERE tooltipId = (SELECT id FROM Tooltips WHERE tag = 'prefs.xml.splitattributes' AND categoryId = 1) AND uri = 'i/prefs/xml/splitattributes';
INSERT INTO TooltipButtons (tooltipId, buttonNumberId, description, uri) VALUES ((SELECT id FROM Tooltips WHERE tag = 'prefs.xml.splitattributes' AND categoryId = 1), 1, 'Learn more', 'i/prefs/xml/splitattributes');

DELETE FROM TooltipButtons WHERE tooltipId = (SELECT id FROM Tooltips WHERE tag = 'prefs.xml.joincdatalines' AND categoryId = 1) AND uri = 'i/prefs/xml/joincdatalines';
INSERT INTO TooltipButtons (tooltipId, buttonNumberId, description, uri) VALUES ((SELECT id FROM Tooltips WHERE tag = 'prefs.xml.joincdatalines' AND categoryId = 1), 1, 'Learn more', 'i/prefs/xml/joincdatalines');

DELETE FROM TooltipButtons WHERE tooltipId = (SELECT id FROM Tooltips WHERE tag = 'prefs.xml.joincommentlines' AND categoryId = 1) AND uri = 'i/prefs/xml/joincommentlines';
INSERT INTO TooltipButtons (tooltipId, buttonNumberId, description, uri) VALUES ((SELECT id FROM Tooltips WHERE tag = 'prefs.xml.joincommentlines' AND categoryId = 1), 1, 'Learn more', 'i/prefs/xml/joincommentlines');

DELETE FROM TooltipButtons WHERE tooltipId = (SELECT id FROM Tooltips WHERE tag = 'prefs.xml.joincontentlines' AND categoryId = 1) AND uri = 'i/prefs/xml/joincontentlines';
INSERT INTO TooltipButtons (tooltipId, buttonNumberId, description, uri) VALUES ((SELECT id FROM Tooltips WHERE tag = 'prefs.xml.joincontentlines' AND categoryId = 1), 1, 'Learn more', 'i/prefs/xml/joincontentlines');

DELETE FROM TooltipButtons WHERE tooltipId = (SELECT id FROM Tooltips WHERE tag = 'prefs.xml.preserveemptycontent' AND categoryId = 1) AND uri = 'i/prefs/xml/preserveemptycontent';
INSERT INTO TooltipButtons (tooltipId, buttonNumberId, description, uri) VALUES ((SELECT id FROM Tooltips WHERE tag = 'prefs.xml.preserveemptycontent' AND categoryId = 1), 1, 'Learn more', 'i/prefs/xml/preserveemptycontent');

DELETE FROM TooltipButtons WHERE tooltipId = (SELECT id FROM Tooltips WHERE tag = 'prefs.buildrun.flags.stacktrace' AND categoryId = 1) AND uri = 'i/prefs/build/flags/--stacktrace';
INSERT INTO TooltipButtons (tooltipId, buttonNumberId, description, uri) VALUES ((SELECT id FROM Tooltips WHERE tag = 'prefs.buildrun.flags.stacktrace' AND categoryId = 1), 1, 'Learn more', 'i/prefs/build/flags/--stacktrace');

DELETE FROM TooltipButtons WHERE tooltipId = (SELECT id FROM Tooltips WHERE tag = 'prefs.buildrun.flags.info' AND categoryId = 1) AND uri = 'i/prefs/build/flags/--info';
INSERT INTO TooltipButtons (tooltipId, buttonNumberId, description, uri) VALUES ((SELECT id FROM Tooltips WHERE tag = 'prefs.buildrun.flags.info' AND categoryId = 1), 1, 'Learn more', 'i/prefs/build/flags/--info');

DELETE FROM TooltipButtons WHERE tooltipId = (SELECT id FROM Tooltips WHERE tag = 'prefs.buildrun.flags.debug' AND categoryId = 1) AND uri = 'i/prefs/build/flags/--debug';
INSERT INTO TooltipButtons (tooltipId, buttonNumberId, description, uri) VALUES ((SELECT id FROM Tooltips WHERE tag = 'prefs.buildrun.flags.debug' AND categoryId = 1), 1, 'Learn more', 'i/prefs/build/flags/--debug');

DELETE FROM TooltipButtons WHERE tooltipId = (SELECT id FROM Tooltips WHERE tag = 'prefs.buildrun.flags.scan' AND categoryId = 1) AND uri = 'i/prefs/build/flags/--scan';
INSERT INTO TooltipButtons (tooltipId, buttonNumberId, description, uri) VALUES ((SELECT id FROM Tooltips WHERE tag = 'prefs.buildrun.flags.scan' AND categoryId = 1), 1, 'Learn more', 'i/prefs/build/flags/--scan');

DELETE FROM TooltipButtons WHERE tooltipId = (SELECT id FROM Tooltips WHERE tag = 'prefs.buildrun.flags.warningmodeall' AND categoryId = 1) AND uri = 'i/prefs/build/flags/--warning-mode-all';
INSERT INTO TooltipButtons (tooltipId, buttonNumberId, description, uri) VALUES ((SELECT id FROM Tooltips WHERE tag = 'prefs.buildrun.flags.warningmodeall' AND categoryId = 1), 1, 'Learn more', 'i/prefs/build/flags/--warning-mode-all');

DELETE FROM TooltipButtons WHERE tooltipId = (SELECT id FROM Tooltips WHERE tag = 'prefs.buildrun.flags.buildcache' AND categoryId = 1) AND uri = 'i/prefs/build/flags/--build-cache';
INSERT INTO TooltipButtons (tooltipId, buttonNumberId, description, uri) VALUES ((SELECT id FROM Tooltips WHERE tag = 'prefs.buildrun.flags.buildcache' AND categoryId = 1), 1, 'Learn more', 'i/prefs/build/flags/--build-cache');

DELETE FROM TooltipButtons WHERE tooltipId = (SELECT id FROM Tooltips WHERE tag = 'prefs.buildrun.flags.offline' AND categoryId = 1) AND uri = 'i/prefs/build/flags/--offline';
INSERT INTO TooltipButtons (tooltipId, buttonNumberId, description, uri) VALUES ((SELECT id FROM Tooltips WHERE tag = 'prefs.buildrun.flags.offline' AND categoryId = 1), 1, 'Learn more', 'i/prefs/build/flags/--offline');

DELETE FROM TooltipButtons WHERE tooltipId = (SELECT id FROM Tooltips WHERE tag = 'prefs.termux.crashreports' AND categoryId = 1) AND uri = 'i/prefs/termux/crashreports';
INSERT INTO TooltipButtons (tooltipId, buttonNumberId, description, uri) VALUES ((SELECT id FROM Tooltips WHERE tag = 'prefs.termux.crashreports' AND categoryId = 1), 1, 'Learn more', 'i/prefs/termux/crashreports');

DELETE FROM TooltipButtons WHERE tooltipId = (SELECT id FROM Tooltips WHERE tag = 'prefs.git.username' AND categoryId = 1) AND uri = 'i/prefs/git/username';
INSERT INTO TooltipButtons (tooltipId, buttonNumberId, description, uri) VALUES ((SELECT id FROM Tooltips WHERE tag = 'prefs.git.username' AND categoryId = 1), 1, 'Learn more', 'i/prefs/git/username');

DELETE FROM TooltipButtons WHERE tooltipId = (SELECT id FROM Tooltips WHERE tag = 'prefs.git.useremail' AND categoryId = 1) AND uri = 'i/prefs/git/useremail';
INSERT INTO TooltipButtons (tooltipId, buttonNumberId, description, uri) VALUES ((SELECT id FROM Tooltips WHERE tag = 'prefs.git.useremail' AND categoryId = 1), 1, 'Learn more', 'i/prefs/git/useremail');

DELETE FROM TooltipButtons WHERE tooltipId = (SELECT id FROM Tooltips WHERE tag = 'prefs.pluginmanager' AND categoryId = 1) AND uri = 'i/prefs/pluginmanager';
INSERT INTO TooltipButtons (tooltipId, buttonNumberId, description, uri) VALUES ((SELECT id FROM Tooltips WHERE tag = 'prefs.pluginmanager' AND categoryId = 1), 1, 'Learn more', 'i/prefs/pluginmanager');

DELETE FROM TooltipButtons WHERE tooltipId = (SELECT id FROM Tooltips WHERE tag = 'prefs.about' AND categoryId = 1) AND uri = 'i/prefs/about';
INSERT INTO TooltipButtons (tooltipId, buttonNumberId, description, uri) VALUES ((SELECT id FROM Tooltips WHERE tag = 'prefs.about' AND categoryId = 1), 1, 'Learn more', 'i/prefs/about');

DELETE FROM TooltipButtons WHERE tooltipId = (SELECT id FROM Tooltips WHERE tag = 'prefs.general' AND categoryId = 1) AND uri = 'i/prefs/general';
INSERT INTO TooltipButtons (tooltipId, buttonNumberId, description, uri) VALUES ((SELECT id FROM Tooltips WHERE tag = 'prefs.general' AND categoryId = 1), 1, 'Learn more', 'i/prefs/general');

DELETE FROM TooltipButtons WHERE tooltipId = (SELECT id FROM Tooltips WHERE tag = 'prefs.editor' AND categoryId = 1) AND uri = 'i/prefs/editor';
INSERT INTO TooltipButtons (tooltipId, buttonNumberId, description, uri) VALUES ((SELECT id FROM Tooltips WHERE tag = 'prefs.editor' AND categoryId = 1), 1, 'Learn more', 'i/prefs/editor');

DELETE FROM TooltipButtons WHERE tooltipId = (SELECT id FROM Tooltips WHERE tag = 'prefs.editor.xml' AND categoryId = 1) AND uri = 'i/prefs/editor/xml';
INSERT INTO TooltipButtons (tooltipId, buttonNumberId, description, uri) VALUES ((SELECT id FROM Tooltips WHERE tag = 'prefs.editor.xml' AND categoryId = 1), 1, 'Learn more', 'i/prefs/editor/xml');

DELETE FROM TooltipButtons WHERE tooltipId = (SELECT id FROM Tooltips WHERE tag = 'prefs.termux' AND categoryId = 1) AND uri = 'i/prefs/termux';
INSERT INTO TooltipButtons (tooltipId, buttonNumberId, description, uri) VALUES ((SELECT id FROM Tooltips WHERE tag = 'prefs.termux' AND categoryId = 1), 1, 'Learn more', 'i/prefs/termux');

DELETE FROM TooltipButtons WHERE tooltipId = (SELECT id FROM Tooltips WHERE tag = 'prefs.git' AND categoryId = 1) AND uri = 'i/prefs/git';
INSERT INTO TooltipButtons (tooltipId, buttonNumberId, description, uri) VALUES ((SELECT id FROM Tooltips WHERE tag = 'prefs.git' AND categoryId = 1), 1, 'Learn more', 'i/prefs/git');
.system rm -rf /tmp/adfa5088-prefs-workdir
COMMIT;
