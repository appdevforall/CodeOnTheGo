-- ADFA-5088: Tooltips + Content rows for every Preferences menu item.
--
-- Deliverable 1 (already merged) tags every Preferences row with its own
-- idetooltips TooltipTag.PREFS_* constant. This script fills in the
-- documentation database rows those tags look up: a Tooltips row per tag
-- (Tier 1 `summary` + Tier 2 `detail`) and a matching Content row (Tier 3
-- HTML page) at a new, item-granular `i/prefs/...` path.
--
-- Some `prefs.*` tags already exist in Tooltips as empty stub rows
-- (summary = '', detail = ''); those are UPDATEd in place, since
-- Tooltips.(categoryId, tag) is UNIQUE and a second INSERT would violate it.
-- All other tags are brand new and get a plain INSERT. Content rows are
-- always new INSERTs -- no per-item Content rows existed before this script.
--
-- All rows use categoryId = 1 ("ide"), languageId = 1 ("EN-us"),
-- contentTypeId = 12 ("text/html", brotli-compressed).
--
-- Apply against the real documentation.db:
--   sqlite3 documentation.db < ADFA-5088-preference-tooltips.sql
--
-- The Content section uses `.system echo "<html>" | brotli -Z > /tmp/x.br`
-- immediately before each `INSERT ... READFILE('/tmp/x.br')` so the
-- uncompressed HTML is visible in this script. `.system` and `READFILE()`
-- require the sqlite3 CLI (not a library binding). If `.system` is disabled
-- in your sqlite3 build, run the `echo ... | brotli -Z > file` line yourself
-- via a shell first, then run just the INSERT statements.

-- ---------------------------------------------------------------------
-- Tooltips: UPDATEs (existing empty stub rows)
-- ---------------------------------------------------------------------

UPDATE Tooltips SET
  summary = 'Change general settings for the IDE.',
  detail = 'Set the app theme, language, and how Code on the Go opens projects. These settings apply across the whole app.'
WHERE tag = 'prefs.general' AND categoryId = 1;

UPDATE Tooltips SET
  summary = 'Choose light mode, dark mode, or match your device''s system setting.',
  detail = 'This setting controls the color theme of the app. Pick Light, Dark, or Follow system. Follow system switches automatically when your device''s theme changes.'
WHERE tag = 'prefs.general.uimode' AND categoryId = 1;

UPDATE Tooltips SET
  summary = 'Choose the display language for Code on the Go.',
  detail = 'This setting changes the language of the app menus and text. Pick System Default to use your device language, or choose a specific language from the list.'
WHERE tag = 'prefs.general.language' AND categoryId = 1;

UPDATE Tooltips SET
  summary = 'Reopen your last project automatically when the app starts.',
  detail = 'When on, Code on the Go opens the last project you worked on as soon as it starts. When off, the app shows the project list instead.'
WHERE tag = 'prefs.general.openlast' AND categoryId = 1;

UPDATE Tooltips SET
  summary = 'Ask for confirmation before opening your last project.',
  detail = 'When on, Code on the Go shows a confirmation prompt before it reopens your last project. This gives you a chance to go to the project list instead.'
WHERE tag = 'prefs.general.confirmopen' AND categoryId = 1;

UPDATE Tooltips SET
  summary = 'Change how the code editor looks and behaves.',
  detail = 'Set font size, tab size, whitespace display, and other editor behavior. XML-specific formatting has its own sub-screen.'
WHERE tag = 'prefs.editor' AND categoryId = 1;

UPDATE Tooltips SET
  summary = 'Set the editor text size.',
  detail = 'This changes the size of code text in the editor, measured in sp (scale-independent pixels). Use a slider to pick a value between 6 and 32.'
WHERE tag = 'prefs.editor.fontsize' AND categoryId = 1;

UPDATE Tooltips SET
  summary = 'Set how many spaces one tab indents.',
  detail = 'This sets the number of spaces the editor uses for each indent level. Choose from 2, 4, 6, or 8 spaces.'
WHERE tag = 'prefs.editor.tabsize' AND categoryId = 1;

UPDATE Tooltips SET
  summary = 'Choose which whitespace and line-break marks the editor shows.',
  detail = 'This opens a dialog with checkboxes for leading, trailing, inner, and empty-line whitespace, plus line-break marks. Turn on any you want the editor to display.'
WHERE tag = 'prefs.editor.nonprinting' AND categoryId = 1;

UPDATE Tooltips SET
  summary = 'Insert spaces instead of a tab character when you press Tab.',
  detail = 'When on, pressing the Tab key inserts spaces. When off, it inserts a tab character.'
WHERE tag = 'prefs.editor.softtab' AND categoryId = 1;

UPDATE Tooltips SET
  summary = 'Break long lines so they fit on the screen.',
  detail = 'When on, the editor wraps long lines onto multiple visual lines instead of scrolling sideways. This does not change the file content.'
WHERE tag = 'prefs.editor.wordwrap' AND categoryId = 1;

UPDATE Tooltips SET
  summary = 'Show a magnified view of text while you select it.',
  detail = 'When on, pressing and holding on text shows a zoomed-in view of the area around your finger. This makes it easier to place the cursor precisely.'
WHERE tag = 'prefs.editor.magnifier' AND categoryId = 1;

UPDATE Tooltips SET
  summary = 'Use spaces to define word boundaries when you double-tap to select.',
  detail = 'When on, double-tapping a word selects text up to the nearest space. When off, the editor also uses punctuation to decide where a word ends.'
WHERE tag = 'prefs.editor.wordboundaries' AND categoryId = 1;

UPDATE Tooltips SET
  summary = 'Match code suggestions even when letter case differs.',
  detail = 'When on, autocomplete suggestions match class and member names regardless of whether you type upper or lower case letters.'
WHERE tag = 'prefs.editor.matchcase' AND categoryId = 1;

UPDATE Tooltips SET
  summary = 'Delete a whole blank line with one backspace.',
  detail = 'When on, pressing backspace on a line with no visible text removes the entire line at once.'
WHERE tag = 'prefs.editor.deletelines' AND categoryId = 1;

UPDATE Tooltips SET
  summary = 'Delete a full indent level with one backspace.',
  detail = 'When on, pressing backspace at the start of an indented line removes the whole indent level at once, instead of one character at a time.'
WHERE tag = 'prefs.editor.smartbackspace' AND categoryId = 1;

UPDATE Tooltips SET
  summary = 'Keep the current code block header visible while scrolling.',
  detail = 'When on, the editor pins the header line of the current class or method at the top of the screen while you scroll through its body.'
WHERE tag = 'prefs.editor.stickyscroll' AND categoryId = 1;

UPDATE Tooltips SET
  summary = 'Keep line numbers visible when scrolling sideways.',
  detail = 'When on, line numbers stay in place on the left side of the screen even when you scroll a long line horizontally.'
WHERE tag = 'prefs.editor.pinlines' AND categoryId = 1;

UPDATE Tooltips SET
  summary = 'Format Java code using Google''s style rules.',
  detail = 'When on, the code formatter applies Google''s Java style conventions, such as its indentation and spacing rules, instead of the default style.'
WHERE tag = 'prefs.editor.googlestyle' AND categoryId = 1;

UPDATE Tooltips SET
  summary = 'Open XML-specific formatting settings.',
  detail = 'This opens a sub-screen with formatting options that apply only to XML files, such as attribute placement and line width.'
WHERE tag = 'prefs.editor.xml' AND categoryId = 1;

UPDATE Tooltips SET
  summary = 'Change settings for building and running your app.',
  detail = 'Set additional Gradle flags and control whether the app launches automatically after a run installs it.'
WHERE tag = 'prefs.buildrun' AND categoryId = 1;

UPDATE Tooltips SET
  summary = 'Launch the app automatically after a successful run installs it.',
  detail = 'When on, Code on the Go opens your app right after installing it, with no extra confirmation step.'
WHERE tag = 'prefs.buildrun.autolaunch' AND categoryId = 1;

UPDATE Tooltips SET
  summary = 'Choose extra Gradle flags to add to every build.',
  detail = 'This opens a dialog with checkboxes for common Gradle command-line flags, such as --info or --offline. Any flag you turn on is added to every Gradle task the IDE runs.'
WHERE tag = 'prefs.buildrun.flags' AND categoryId = 1;

UPDATE Tooltips SET
  summary = 'Change settings for the built-in terminal.',
  detail = 'Set the terminal logging level, keyboard behavior, and screen margin.'
WHERE tag = 'prefs.termux' AND categoryId = 1;

UPDATE Tooltips SET
  summary = 'Set how much detail the terminal writes to its internal log.',
  detail = 'This controls the terminal own internal logging level, used for troubleshooting the terminal itself. Higher levels record more detail.'
WHERE tag = 'prefs.termux.loglevel' AND categoryId = 1;

UPDATE Tooltips SET
  summary = 'Log every key you press in the terminal, for debugging.',
  detail = 'When on, the terminal records each key press to the system log. This is very verbose and can slow the app down, so leave it off unless you are debugging a keyboard issue.'
WHERE tag = 'prefs.termux.keylogging' AND categoryId = 1;

UPDATE Tooltips SET
  summary = 'Adjust the terminal margin so the on-screen keyboard does not cover it.',
  detail = 'When on, the terminal adjusts its margin to avoid being covered by the on-screen keyboard. If you notice screen flickering, turn this off.'
WHERE tag = 'prefs.termux.margin' AND categoryId = 1;

UPDATE Tooltips SET
  summary = 'Show the on-screen keyboard only when no physical keyboard is connected.',
  detail = 'When on, the terminal hides its on-screen keyboard while a hardware keyboard is connected, and shows it again once the hardware keyboard is disconnected.'
WHERE tag = 'prefs.termux.nohardkeyboard' AND categoryId = 1;

UPDATE Tooltips SET
  summary = 'Show the on-screen keyboard in the terminal.',
  detail = 'When on, the terminal shows its own on-screen keyboard for typing commands. This is on by default.'
WHERE tag = 'prefs.termux.softkeyboard' AND categoryId = 1;

UPDATE Tooltips SET
  summary = 'Put a tag closing bracket on its own new line.',
  detail = 'When on, the formatter places the final ">" or "/>" on a new line after the last attribute. This is off by default.'
WHERE tag = 'prefs.xml.closebracket' AND categoryId = 1;

UPDATE Tooltips SET
  summary = 'Choose how the formatter writes empty XML elements.',
  detail = 'Pick Expand to write empty elements as an open and a close tag, Collapse to write them as a single self-closing tag, or Ignore to leave them as they are.'
WHERE tag = 'prefs.xml.emptyelements' AND categoryId = 1;

UPDATE Tooltips SET
  summary = 'Set the maximum characters allowed on one line.',
  detail = 'This sets the line-width limit, in characters, before the formatter wraps a line onto more than one line. The default is 80.'
WHERE tag = 'prefs.xml.maxlinewidth' AND categoryId = 1;

UPDATE Tooltips SET
  summary = 'Keep existing line breaks between attributes.',
  detail = 'When on, the formatter leaves attributes on the lines you already put them on, instead of joining them onto one line. This is on by default.'
WHERE tag = 'prefs.xml.preserveattributes' AND categoryId = 1;

UPDATE Tooltips SET
  summary = 'Set how many blank lines to keep between elements.',
  detail = 'This sets the maximum number of blank lines the formatter keeps between elements when it reformats a file. Extra blank lines beyond this number are removed. The default is 2.'
WHERE tag = 'prefs.xml.preservenewlines' AND categoryId = 1;

UPDATE Tooltips SET
  summary = 'Add a space before a tag self-closing slash.',
  detail = 'When on, the formatter writes a space before "/>", producing "<foo />" instead of "<foo/>". This is on by default.'
WHERE tag = 'prefs.xml.spacebeforeclose' AND categoryId = 1;

UPDATE Tooltips SET
  summary = 'Set the indent size for attributes on split lines.',
  detail = 'This sets how many spaces the formatter uses to indent an attribute placed on its own line. By default it is half the editor tab size.'
WHERE tag = 'prefs.xml.splitattribindent' AND categoryId = 1;

UPDATE Tooltips SET
  summary = 'Remove extra spaces at the end of lines.',
  detail = 'When on, the formatter deletes any whitespace left at the end of each line. This is on by default.'
WHERE tag = 'prefs.xml.trimwhitespace' AND categoryId = 1;

UPDATE Tooltips SET
  summary = 'Change experimental and debugging settings.',
  detail = 'These settings are for troubleshooting Code on the Go itself, not for normal project configuration. Only change them if you are diagnosing a problem.'
WHERE tag = 'prefs.devoptions' AND categoryId = 1;

UPDATE Tooltips SET
  summary = 'Save the IDE internal logs to a file.',
  detail = 'When on, Code on the Go writes its internal logs to a file at $HOME/.cg/logs, for troubleshooting.'
WHERE tag = 'prefs.devoptions.dumplogs' AND categoryId = 1;

UPDATE Tooltips SET
  summary = 'Show logs from your running app inside Code on the Go.',
  detail = 'When on, Code on the Go displays log output from apps you run. Turn this off to stop showing those logs.'
WHERE tag = 'prefs.devoptions.logsender' AND categoryId = 1;

-- ---------------------------------------------------------------------
-- Tooltips: INSERTs (brand new tags)
-- ---------------------------------------------------------------------

INSERT INTO Tooltips (categoryId, tag, summary, detail) VALUES (1, 'prefs.editor.nonprinting.leading',
  'Show whitespace at the start of each line.',
  'When on, the editor marks spaces and tabs that come before the first visible character on a line.');

INSERT INTO Tooltips (categoryId, tag, summary, detail) VALUES (1, 'prefs.editor.nonprinting.trailing',
  'Show whitespace at the end of each line.',
  'When on, the editor marks spaces and tabs that come after the last visible character on a line.');

INSERT INTO Tooltips (categoryId, tag, summary, detail) VALUES (1, 'prefs.editor.nonprinting.inner',
  'Show whitespace between words.',
  'When on, the editor marks spaces and tabs that appear between two visible characters on a line.');

INSERT INTO Tooltips (categoryId, tag, summary, detail) VALUES (1, 'prefs.editor.nonprinting.emptylines',
  'Show whitespace on lines with no visible text.',
  'When on, the editor marks spaces and tabs on lines that contain only whitespace.');

INSERT INTO Tooltips (categoryId, tag, summary, detail) VALUES (1, 'prefs.editor.nonprinting.linebreaks',
  'Show a mark at the end of each line.',
  'When on, the editor draws a small symbol where each line ends, so line breaks are visible.');

INSERT INTO Tooltips (categoryId, tag, summary, detail) VALUES (1, 'prefs.xml.trimfinalnewline',
  'Remove blank lines at the end of an XML file.',
  'When on, the formatter deletes empty lines at the end of the file, leaving no trailing blank lines. This is off by default.');

INSERT INTO Tooltips (categoryId, tag, summary, detail) VALUES (1, 'prefs.xml.insertfinalnewline',
  'Add one newline at the end of an XML file.',
  'When on, the formatter makes sure the file ends with exactly one newline character. This is on by default.');

INSERT INTO Tooltips (categoryId, tag, summary, detail) VALUES (1, 'prefs.xml.splitattributes',
  'Put each XML attribute on its own line.',
  'When on, the formatter places every attribute of a tag on a separate line instead of one line. This is on by default.');

INSERT INTO Tooltips (categoryId, tag, summary, detail) VALUES (1, 'prefs.xml.joincdatalines',
  'Combine multiple CDATA lines into one.',
  'When on, the formatter merges the lines of a multi-line CDATA section into a single line. This is off by default.');

INSERT INTO Tooltips (categoryId, tag, summary, detail) VALUES (1, 'prefs.xml.joincommentlines',
  'Combine multiple single-line comments into one.',
  'When on, the formatter merges adjacent single-line XML comments into a single line. This is on by default.');

INSERT INTO Tooltips (categoryId, tag, summary, detail) VALUES (1, 'prefs.xml.joincontentlines',
  'Combine multiple lines of element text into one.',
  'When on, the formatter merges multiple lines of text inside an XML element into a single line. This is off by default.');

INSERT INTO Tooltips (categoryId, tag, summary, detail) VALUES (1, 'prefs.xml.preserveemptycontent',
  'Keep tags that stand alone on an empty line as they are.',
  'When on, the formatter leaves an element alone on its own blank line unchanged, instead of collapsing it. This is on by default.');

INSERT INTO Tooltips (categoryId, tag, summary, detail) VALUES (1, 'prefs.buildrun.flags.stacktrace',
  'Show a full stack trace when a build fails.',
  'Sets the Gradle --stacktrace flag. When a build fails, Gradle prints the full stack trace of the failure instead of a short summary.');

INSERT INTO Tooltips (categoryId, tag, summary, detail) VALUES (1, 'prefs.buildrun.flags.info',
  'Show detailed log messages for each build task.',
  'Sets the Gradle --info flag. Gradle prints detailed log messages as each task runs, instead of just a summary line for each task.');

INSERT INTO Tooltips (categoryId, tag, summary, detail) VALUES (1, 'prefs.buildrun.flags.debug',
  'Show maximum detail in build logs.',
  'Sets the Gradle --debug flag. Gradle prints very detailed, low-level log messages for every task. This produces a large amount of output and can slow the build down.');

INSERT INTO Tooltips (categoryId, tag, summary, detail) VALUES (1, 'prefs.buildrun.flags.scan',
  'Publish a Gradle Build Scan report link.',
  'Sets the Gradle --scan flag. After the build, Gradle uploads build data and gives you a link to a Build Scan report with detailed information.');

INSERT INTO Tooltips (categoryId, tag, summary, detail) VALUES (1, 'prefs.buildrun.flags.warningmodeall',
  'Show every deprecation warning during the build.',
  'Sets the Gradle --warning-mode all flag. Gradle lists every individual deprecation warning instead of just a count at the end of the build.');

INSERT INTO Tooltips (categoryId, tag, summary, detail) VALUES (1, 'prefs.buildrun.flags.buildcache',
  'Reuse outputs from a previous build to speed up this one.',
  'Sets the Gradle --build-cache flag. Gradle reuses task outputs from an earlier build when the inputs have not changed, which can make the build faster.');

INSERT INTO Tooltips (categoryId, tag, summary, detail) VALUES (1, 'prefs.buildrun.flags.offline',
  'Build without any network access.',
  'Sets the Gradle --offline flag. Gradle uses only dependencies already downloaded to your device and does not try to reach the network.');

INSERT INTO Tooltips (categoryId, tag, summary, detail) VALUES (1, 'prefs.termux.crashreports',
  'Show a notification if the terminal crashes.',
  'When on, Code on the Go shows a notification with a crash report after the terminal process crashes. This is on by default.');

INSERT INTO Tooltips (categoryId, tag, summary, detail) VALUES (1, 'prefs.git',
  'Set your Git author identity.',
  'This screen sets the name and email address recorded as the author of your Git commits.');

INSERT INTO Tooltips (categoryId, tag, summary, detail) VALUES (1, 'prefs.git.username',
  'Set the name used as the author of your commits.',
  'This name is recorded on every Git commit you make from Code on the Go.');

INSERT INTO Tooltips (categoryId, tag, summary, detail) VALUES (1, 'prefs.git.useremail',
  'Set the email address used as the author of your commits.',
  'This email address is recorded on every Git commit you make from Code on the Go.');

INSERT INTO Tooltips (categoryId, tag, summary, detail) VALUES (1, 'prefs.pluginmanager',
  'Manage IDE plugins and extensions.',
  'This opens Plugin Manager, where you can install, remove, and configure plugins that add features to Code on the Go.');

INSERT INTO Tooltips (categoryId, tag, summary, detail) VALUES (1, 'prefs.about',
  'See app version and other information about Code on the Go.',
  'This opens the About screen, with details such as the app version and credits.');

-- ---------------------------------------------------------------------
-- Content: Tier 3 HTML pages, one INSERT per Tooltips row above.
-- Each pair shows the uncompressed HTML via `.system echo ... | brotli -Z`
-- then inserts the resulting compressed file with READFILE().
-- ---------------------------------------------------------------------

.system echo "<p>The General screen holds settings that are not specific to the editor or to a single project. Use it to set the app theme, choose a display language, and control how Code on the Go opens the last project on launch.</p><p>Changes here apply immediately and affect the whole app.</p>" | brotli -Z > /tmp/adfa5088-prefs-general.br
INSERT INTO Content (path, languageId, contentTypeId, content) VALUES ('i/prefs/general', 1, 12, READFILE('/tmp/adfa5088-prefs-general.br'));

.system echo "<p>UI mode sets the color theme for Code on the Go.</p><ul><li><b>Light</b>: always use light colors.</li><li><b>Dark</b>: always use dark colors.</li><li><b>Follow system</b>: match your device's current theme, and switch automatically when it changes.</li></ul>" | brotli -Z > /tmp/adfa5088-prefs-general-uimode.br
INSERT INTO Content (path, languageId, contentTypeId, content) VALUES ('i/prefs/general/uimode', 1, 12, READFILE('/tmp/adfa5088-prefs-general-uimode.br'));

.system echo "<p>Language sets the display language for the Code on the Go interface.</p><p>Choose System Default to use your device language setting, or pick one of the supported languages directly. The app restarts the affected screens to apply the change.</p>" | brotli -Z > /tmp/adfa5088-prefs-general-language.br
INSERT INTO Content (path, languageId, contentTypeId, content) VALUES ('i/prefs/general/language', 1, 12, READFILE('/tmp/adfa5088-prefs-general-language.br'));

.system echo "<p>Open last project controls what happens when you start Code on the Go.</p><p>Turn it on to skip the project list and go straight into your most recent project. Turn it off to see the project list every time.</p>" | brotli -Z > /tmp/adfa5088-prefs-general-openlast.br
INSERT INTO Content (path, languageId, contentTypeId, content) VALUES ('i/prefs/general/openlast', 1, 12, READFILE('/tmp/adfa5088-prefs-general-openlast.br'));

.system echo "<p>Confirm project opening adds a confirmation step before Code on the Go reopens your last project automatically.</p><p>Use this if you often want to switch to a different project instead of continuing the last one.</p>" | brotli -Z > /tmp/adfa5088-prefs-general-confirmopen.br
INSERT INTO Content (path, languageId, contentTypeId, content) VALUES ('i/prefs/general/confirmopen', 1, 12, READFILE('/tmp/adfa5088-prefs-general-confirmopen.br'));

.system echo "<p>The Editor screen holds settings for the code editor: font size, tab size, word wrap, whitespace display, and other editing behavior.</p><p>Formatting options specific to XML files are on a separate sub-screen.</p>" | brotli -Z > /tmp/adfa5088-prefs-editor.br
INSERT INTO Content (path, languageId, contentTypeId, content) VALUES ('i/prefs/editor', 1, 12, READFILE('/tmp/adfa5088-prefs-editor.br'));

.system echo "<p>Font size sets the text size used in the code editor, in sp units.</p><p>Choose a larger value to make code easier to read, or a smaller value to fit more code on the screen. The allowed range is 6 to 32.</p>" | brotli -Z > /tmp/adfa5088-prefs-editor-fontsize.br
INSERT INTO Content (path, languageId, contentTypeId, content) VALUES ('i/prefs/editor/fontsize', 1, 12, READFILE('/tmp/adfa5088-prefs-editor-fontsize.br'));

.system echo "<p>Tab size sets the number of spaces that one tab character represents in the editor.</p><p>Pick a value that matches your project code style: 2, 4, 6, or 8 spaces.</p>" | brotli -Z > /tmp/adfa5088-prefs-editor-tabsize.br
INSERT INTO Content (path, languageId, contentTypeId, content) VALUES ('i/prefs/editor/tabsize', 1, 12, READFILE('/tmp/adfa5088-prefs-editor-tabsize.br'));

.system echo "<p>Show non-printing characters controls which invisible characters, such as spaces, tabs, and line breaks, the editor marks visibly.</p><p>Open this setting to choose which kinds to show: leading whitespace, trailing whitespace, whitespace between words, whitespace on empty lines, and line-break marks.</p>" | brotli -Z > /tmp/adfa5088-prefs-editor-nonprinting.br
INSERT INTO Content (path, languageId, contentTypeId, content) VALUES ('i/prefs/editor/nonprinting', 1, 12, READFILE('/tmp/adfa5088-prefs-editor-nonprinting.br'));

.system echo "<p>Leading marks whitespace characters, such as spaces and tabs, that appear before the first visible character on a line.</p><p>Turn this on to see indentation whitespace clearly.</p>" | brotli -Z > /tmp/adfa5088-prefs-editor-nonprinting-leading.br
INSERT INTO Content (path, languageId, contentTypeId, content) VALUES ('i/prefs/editor/nonprinting/leading', 1, 12, READFILE('/tmp/adfa5088-prefs-editor-nonprinting-leading.br'));

.system echo "<p>Trailing marks whitespace characters that appear after the last visible character on a line.</p><p>Turn this on to spot stray trailing spaces or tabs.</p>" | brotli -Z > /tmp/adfa5088-prefs-editor-nonprinting-trailing.br
INSERT INTO Content (path, languageId, contentTypeId, content) VALUES ('i/prefs/editor/nonprinting/trailing', 1, 12, READFILE('/tmp/adfa5088-prefs-editor-nonprinting-trailing.br'));

.system echo "<p>Inner marks whitespace characters that appear between words or tokens within a line, rather than at the start or end.</p><p>Turn this on to check for irregular spacing inside a line.</p>" | brotli -Z > /tmp/adfa5088-prefs-editor-nonprinting-inner.br
INSERT INTO Content (path, languageId, contentTypeId, content) VALUES ('i/prefs/editor/nonprinting/inner', 1, 12, READFILE('/tmp/adfa5088-prefs-editor-nonprinting-inner.br'));

.system echo "<p>Empty lines marks whitespace characters on lines that have no visible text, only spaces or tabs.</p><p>Turn this on to spot stray whitespace on otherwise blank lines.</p>" | brotli -Z > /tmp/adfa5088-prefs-editor-nonprinting-emptylines.br
INSERT INTO Content (path, languageId, contentTypeId, content) VALUES ('i/prefs/editor/nonprinting/emptylines', 1, 12, READFILE('/tmp/adfa5088-prefs-editor-nonprinting-emptylines.br'));

.system echo "<p>Line breaks draws a small marker at the end of each line to show where the line break occurs.</p><p>Turn this on to see line endings clearly, for example when comparing files with different line-ending styles.</p>" | brotli -Z > /tmp/adfa5088-prefs-editor-nonprinting-linebreaks.br
INSERT INTO Content (path, languageId, contentTypeId, content) VALUES ('i/prefs/editor/nonprinting/linebreaks', 1, 12, READFILE('/tmp/adfa5088-prefs-editor-nonprinting-linebreaks.br'));

.system echo "<p>Use soft tab controls what the editor inserts when you press the Tab key.</p><p>Turn this on to insert spaces instead of a tab character. This can help keep code consistent across editors that render tabs differently.</p>" | brotli -Z > /tmp/adfa5088-prefs-editor-softtab.br
INSERT INTO Content (path, languageId, contentTypeId, content) VALUES ('i/prefs/editor/softtab', 1, 12, READFILE('/tmp/adfa5088-prefs-editor-softtab.br'));

.system echo "<p>Word wrap breaks long lines of code into multiple visual lines so they fit within the visible width of the editor.</p><p>The underlying file is not changed; only the way it is displayed changes.</p>" | brotli -Z > /tmp/adfa5088-prefs-editor-wordwrap.br
INSERT INTO Content (path, languageId, contentTypeId, content) VALUES ('i/prefs/editor/wordwrap', 1, 12, READFILE('/tmp/adfa5088-prefs-editor-wordwrap.br'));

.system echo "<p>Enable magnifier shows a zoomed-in view of the text around your finger when you press and hold to select text.</p><p>This makes it easier to position the cursor precisely on a small screen.</p>" | brotli -Z > /tmp/adfa5088-prefs-editor-magnifier.br
INSERT INTO Content (path, languageId, contentTypeId, content) VALUES ('i/prefs/editor/magnifier', 1, 12, READFILE('/tmp/adfa5088-prefs-editor-magnifier.br'));

.system echo "<p>Use spaces as word boundaries changes how the editor decides what counts as one word when you double-tap to select text.</p><p>When on, only blank space marks the edge of a word. When off, the editor also treats punctuation, such as periods and underscores, as word boundaries.</p>" | brotli -Z > /tmp/adfa5088-prefs-editor-wordboundaries.br
INSERT INTO Content (path, languageId, contentTypeId, content) VALUES ('i/prefs/editor/wordboundaries', 1, 12, READFILE('/tmp/adfa5088-prefs-editor-wordboundaries.br'));

.system echo "<p>Match completions in lower case controls whether the code editor autocomplete feature is case-sensitive.</p><p>When on, typing in lower case still matches suggestions that use upper case letters, such as class names.</p>" | brotli -Z > /tmp/adfa5088-prefs-editor-matchcase.br
INSERT INTO Content (path, languageId, contentTypeId, content) VALUES ('i/prefs/editor/matchcase', 1, 12, READFILE('/tmp/adfa5088-prefs-editor-matchcase.br'));

.system echo "<p>Delete empty lines on backspace changes what happens when you press backspace on a line with no visible text.</p><p>When on, the whole empty line is removed in one step, instead of removing one whitespace character at a time.</p>" | brotli -Z > /tmp/adfa5088-prefs-editor-deletelines.br
INSERT INTO Content (path, languageId, contentTypeId, content) VALUES ('i/prefs/editor/deletelines', 1, 12, READFILE('/tmp/adfa5088-prefs-editor-deletelines.br'));

.system echo "<p>Smart backspace indent changes what one backspace press removes when the cursor is inside leading indentation.</p><p>When on, it removes a full indent level at once. When off, it removes one character at a time.</p>" | brotli -Z > /tmp/adfa5088-prefs-editor-smartbackspace.br
INSERT INTO Content (path, languageId, contentTypeId, content) VALUES ('i/prefs/editor/smartbackspace', 1, 12, READFILE('/tmp/adfa5088-prefs-editor-smartbackspace.br'));

.system echo "<p>Sticky scroll keeps the header line of the code block you are inside, such as a class or method declaration, visible at the top of the editor while you scroll down through its contents.</p><p>This helps you keep track of context in long files.</p>" | brotli -Z > /tmp/adfa5088-prefs-editor-stickyscroll.br
INSERT INTO Content (path, languageId, contentTypeId, content) VALUES ('i/prefs/editor/stickyscroll', 1, 12, READFILE('/tmp/adfa5088-prefs-editor-stickyscroll.br'));

.system echo "<p>Pin line numbers keeps the line-number column fixed on the left side of the editor.</p><p>Without this, scrolling a long line horizontally can move the line numbers out of view along with the code.</p>" | brotli -Z > /tmp/adfa5088-prefs-editor-pinlines.br
INSERT INTO Content (path, languageId, contentTypeId, content) VALUES ('i/prefs/editor/pinlines', 1, 12, READFILE('/tmp/adfa5088-prefs-editor-pinlines.br'));

.system echo "<p>Use Google Java Style code formatting applies Google's published conventions for Java source code, such as indentation, spacing, and line-wrapping rules, when you format code.</p><p>Turn this on if your project follows Google's Java style guide.</p>" | brotli -Z > /tmp/adfa5088-prefs-editor-googlestyle.br
INSERT INTO Content (path, languageId, contentTypeId, content) VALUES ('i/prefs/editor/googlestyle', 1, 12, READFILE('/tmp/adfa5088-prefs-editor-googlestyle.br'));

.system echo "<p>XML formatting options opens a sub-screen of settings that control how Code on the Go formats XML files, separate from the general editor settings.</p>" | brotli -Z > /tmp/adfa5088-prefs-editor-xml.br
INSERT INTO Content (path, languageId, contentTypeId, content) VALUES ('i/prefs/editor/xml', 1, 12, READFILE('/tmp/adfa5088-prefs-editor-xml.br'));

.system echo "<p>Trim final new line removes empty lines at the very end of an XML file when the formatter runs.</p><p>This is off by default.</p>" | brotli -Z > /tmp/adfa5088-prefs-xml-trimfinalnewline.br
INSERT INTO Content (path, languageId, contentTypeId, content) VALUES ('i/prefs/xml/trimfinalnewline', 1, 12, READFILE('/tmp/adfa5088-prefs-xml-trimfinalnewline.br'));

.system echo "<p>Insert final new line adds a single newline character at the end of an XML file if one is not already there.</p><p>Many tools expect files to end this way.</p>" | brotli -Z > /tmp/adfa5088-prefs-xml-insertfinalnewline.br
INSERT INTO Content (path, languageId, contentTypeId, content) VALUES ('i/prefs/xml/insertfinalnewline', 1, 12, READFILE('/tmp/adfa5088-prefs-xml-insertfinalnewline.br'));

.system echo "<p>Split attributes places each attribute of an XML tag on its own line when the formatter runs.</p><p>This makes tags with many attributes easier to read and to compare in version control.</p>" | brotli -Z > /tmp/adfa5088-prefs-xml-splitattributes.br
INSERT INTO Content (path, languageId, contentTypeId, content) VALUES ('i/prefs/xml/splitattributes', 1, 12, READFILE('/tmp/adfa5088-prefs-xml-splitattributes.br'));

.system echo "<p>Join CDATA lines combines the lines of a CDATA section into a single line when the formatter runs.</p><p>CDATA sections hold raw, unescaped text or markup inside an XML file.</p>" | brotli -Z > /tmp/adfa5088-prefs-xml-joincdatalines.br
INSERT INTO Content (path, languageId, contentTypeId, content) VALUES ('i/prefs/xml/joincdatalines', 1, 12, READFILE('/tmp/adfa5088-prefs-xml-joincdatalines.br'));

.system echo "<p>Join comment lines combines adjacent single-line XML comments into one line when the formatter runs.</p>" | brotli -Z > /tmp/adfa5088-prefs-xml-joincommentlines.br
INSERT INTO Content (path, languageId, contentTypeId, content) VALUES ('i/prefs/xml/joincommentlines', 1, 12, READFILE('/tmp/adfa5088-prefs-xml-joincommentlines.br'));

.system echo "<p>Join content lines combines multiple lines of text inside a single XML element into one line when the formatter runs.</p>" | brotli -Z > /tmp/adfa5088-prefs-xml-joincontentlines.br
INSERT INTO Content (path, languageId, contentTypeId, content) VALUES ('i/prefs/xml/joincontentlines', 1, 12, READFILE('/tmp/adfa5088-prefs-xml-joincontentlines.br'));

.system echo "<p>Space before empty close tag adds one space before the closing /&gt; of a self-closing XML tag, so the formatter produces &lt;foo /&gt; instead of &lt;foo/&gt;.</p>" | brotli -Z > /tmp/adfa5088-prefs-xml-spacebeforeclose.br
INSERT INTO Content (path, languageId, contentTypeId, content) VALUES ('i/prefs/xml/spacebeforeclose', 1, 12, READFILE('/tmp/adfa5088-prefs-xml-spacebeforeclose.br'));

.system echo "<p>Preserve empty content keeps an XML element that appears alone on an otherwise empty line as it is, instead of collapsing or moving it during formatting.</p>" | brotli -Z > /tmp/adfa5088-prefs-xml-preserveemptycontent.br
INSERT INTO Content (path, languageId, contentTypeId, content) VALUES ('i/prefs/xml/preserveemptycontent', 1, 12, READFILE('/tmp/adfa5088-prefs-xml-preserveemptycontent.br'));

.system echo "<p>Preserve attribute line breaks keeps attributes on the separate lines you already placed them on, instead of collapsing them onto a single line during formatting.</p>" | brotli -Z > /tmp/adfa5088-prefs-xml-preserveattributes.br
INSERT INTO Content (path, languageId, contentTypeId, content) VALUES ('i/prefs/xml/preserveattributes', 1, 12, READFILE('/tmp/adfa5088-prefs-xml-preserveattributes.br'));

.system echo "<p>Closing bracket on new line places the final closing bracket of a tag, including self-closing tags, on its own new line after the last attribute.</p>" | brotli -Z > /tmp/adfa5088-prefs-xml-closebracket.br
INSERT INTO Content (path, languageId, contentTypeId, content) VALUES ('i/prefs/xml/closebracket', 1, 12, READFILE('/tmp/adfa5088-prefs-xml-closebracket.br'));

.system echo "<p>Trim trailing whitespace removes any spaces or tabs left at the end of each line when the formatter runs.</p>" | brotli -Z > /tmp/adfa5088-prefs-xml-trimwhitespace.br
INSERT INTO Content (path, languageId, contentTypeId, content) VALUES ('i/prefs/xml/trimwhitespace', 1, 12, READFILE('/tmp/adfa5088-prefs-xml-trimwhitespace.br'));

.system echo "<p>Maximum line width sets the number of characters allowed on a single line before the formatter wraps it onto additional lines.</p><p>The default value is 80 characters.</p>" | brotli -Z > /tmp/adfa5088-prefs-xml-maxlinewidth.br
INSERT INTO Content (path, languageId, contentTypeId, content) VALUES ('i/prefs/xml/maxlinewidth', 1, 12, READFILE('/tmp/adfa5088-prefs-xml-maxlinewidth.br'));

.system echo "<p>Preserve new lines sets the maximum number of consecutive blank lines the formatter keeps between XML elements.</p><p>Any blank lines beyond this limit are removed. The default is 2.</p>" | brotli -Z > /tmp/adfa5088-prefs-xml-preservenewlines.br
INSERT INTO Content (path, languageId, contentTypeId, content) VALUES ('i/prefs/xml/preservenewlines', 1, 12, READFILE('/tmp/adfa5088-prefs-xml-preservenewlines.br'));

.system echo "<p>Split attributes indent size sets the number of spaces used to indent an attribute placed on its own line by the formatter.</p><p>By default, this is half the editor's tab size.</p>" | brotli -Z > /tmp/adfa5088-prefs-xml-splitattribindent.br
INSERT INTO Content (path, languageId, contentTypeId, content) VALUES ('i/prefs/xml/splitattribindent', 1, 12, READFILE('/tmp/adfa5088-prefs-xml-splitattribindent.br'));

.system echo "<p>Empty elements behavior controls how the formatter writes an XML element that has no content.</p><ul><li><b>Expand</b>: write a separate open and close tag, for example &lt;foo&gt;&lt;/foo&gt;.</li><li><b>Collapse</b>: write a single self-closing tag, for example &lt;foo/&gt;.</li><li><b>Ignore</b>: leave the element as it is in the source file.</li></ul><p>The default is Collapse.</p>" | brotli -Z > /tmp/adfa5088-prefs-xml-emptyelements.br
INSERT INTO Content (path, languageId, contentTypeId, content) VALUES ('i/prefs/xml/emptyelements', 1, 12, READFILE('/tmp/adfa5088-prefs-xml-emptyelements.br'));

.system echo "<p>The Build &amp; Run screen holds settings for the Gradle build and for running your app: additional Gradle command-line flags, and whether to launch the app automatically after installation.</p>" | brotli -Z > /tmp/adfa5088-prefs-build.br
INSERT INTO Content (path, languageId, contentTypeId, content) VALUES ('i/prefs/build', 1, 12, READFILE('/tmp/adfa5088-prefs-build.br'));

.system echo "<p>Launch app after installation controls whether Code on the Go opens your app automatically once a run finishes installing it on the device.</p><p>When off, you need to launch the app yourself.</p>" | brotli -Z > /tmp/adfa5088-prefs-build-autolaunch.br
INSERT INTO Content (path, languageId, contentTypeId, content) VALUES ('i/prefs/build/autolaunch', 1, 12, READFILE('/tmp/adfa5088-prefs-build-autolaunch.br'));

.system echo "<p>Additional Gradle flags lets you turn on extra command-line flags that Code on the Go adds to every Gradle task it runs, such as build and sync.</p><p>Use this to get more detailed logs, use a build cache, or work offline, without typing the flags yourself each time.</p>" | brotli -Z > /tmp/adfa5088-prefs-build-flags.br
INSERT INTO Content (path, languageId, contentTypeId, content) VALUES ('i/prefs/build/flags', 1, 12, READFILE('/tmp/adfa5088-prefs-build-flags.br'));

.system echo "<p>Sets the Gradle --stacktrace flag. When a build fails, Gradle prints the full stack trace of the failure, which can help diagnose the exact cause.</p>" | brotli -Z > /tmp/adfa5088-prefs-build-flags-stacktrace.br
INSERT INTO Content (path, languageId, contentTypeId, content) VALUES ('i/prefs/build/flags/--stacktrace', 1, 12, READFILE('/tmp/adfa5088-prefs-build-flags-stacktrace.br'));

.system echo "Sets the Gradle --info flag to produce more detailed log messages as each task runs, rather than just a summary line for each task." | brotli -Z > /tmp/adfa5088-prefs-build-flags-info.br
INSERT INTO Content (path, languageId, contentTypeId, content) VALUES ('i/prefs/build/flags/--info', 1, 12, READFILE('/tmp/adfa5088-prefs-build-flags-info.br'));

.system echo "<p>Sets the Gradle --debug flag. Gradle prints its most detailed log level for every task.</p><p>This produces a lot of output and can make the build noticeably slower. Use it only when you need to diagnose a specific problem.</p>" | brotli -Z > /tmp/adfa5088-prefs-build-flags-debug.br
INSERT INTO Content (path, languageId, contentTypeId, content) VALUES ('i/prefs/build/flags/--debug', 1, 12, READFILE('/tmp/adfa5088-prefs-build-flags-debug.br'));

.system echo "<p>Sets the Gradle --scan flag. After the build finishes, Gradle uploads data about the build and gives you a link to a Build Scan report.</p><p>The report shows detailed information about tasks, dependencies, and timing. This requires network access.</p>" | brotli -Z > /tmp/adfa5088-prefs-build-flags-scan.br
INSERT INTO Content (path, languageId, contentTypeId, content) VALUES ('i/prefs/build/flags/--scan', 1, 12, READFILE('/tmp/adfa5088-prefs-build-flags-scan.br'));

.system echo "<p>Sets the Gradle --warning-mode all flag. Gradle prints every individual deprecation warning during the build, instead of only a summary count at the end.</p>" | brotli -Z > /tmp/adfa5088-prefs-build-flags-warningmodeall.br
INSERT INTO Content (path, languageId, contentTypeId, content) VALUES ('i/prefs/build/flags/--warning-mode-all', 1, 12, READFILE('/tmp/adfa5088-prefs-build-flags-warningmodeall.br'));

.system echo "<p>Sets the Gradle --build-cache flag. Gradle reuses outputs from a previous build for any task whose inputs have not changed, instead of running that task again.</p><p>This can noticeably speed up repeated builds.</p>" | brotli -Z > /tmp/adfa5088-prefs-build-flags-buildcache.br
INSERT INTO Content (path, languageId, contentTypeId, content) VALUES ('i/prefs/build/flags/--build-cache', 1, 12, READFILE('/tmp/adfa5088-prefs-build-flags-buildcache.br'));

.system echo "<p>Sets the Gradle --offline flag. Gradle uses only the dependencies already downloaded to your device and does not attempt any network access.</p><p>The build fails if a required dependency has not been downloaded yet.</p>" | brotli -Z > /tmp/adfa5088-prefs-build-flags-offline.br
INSERT INTO Content (path, languageId, contentTypeId, content) VALUES ('i/prefs/build/flags/--offline', 1, 12, READFILE('/tmp/adfa5088-prefs-build-flags-offline.br'));

.system echo "<p>The Terminal screen holds settings for the built-in terminal emulator: internal logging, keyboard behavior, crash notifications, and screen margin.</p>" | brotli -Z > /tmp/adfa5088-prefs-termux.br
INSERT INTO Content (path, languageId, contentTypeId, content) VALUES ('i/prefs/termux', 1, 12, READFILE('/tmp/adfa5088-prefs-termux.br'));

.system echo "<p>Log Level sets how much detail the terminal emulator writes to its own internal log, separate from your app logs.</p><p>Use a higher level only when troubleshooting a terminal problem, since it produces more log output.</p>" | brotli -Z > /tmp/adfa5088-prefs-termux-loglevel.br
INSERT INTO Content (path, languageId, contentTypeId, content) VALUES ('i/prefs/termux/loglevel', 1, 12, READFILE('/tmp/adfa5088-prefs-termux-loglevel.br'));

.system echo "<p>Terminal View Key Logging records each key you press in the terminal to the system log.</p><p>This produces a large amount of log output and can cause performance problems, so it is off by default. Turn it on only to debug a keyboard-related issue.</p>" | brotli -Z > /tmp/adfa5088-prefs-termux-keylogging.br
INSERT INTO Content (path, languageId, contentTypeId, content) VALUES ('i/prefs/termux/keylogging', 1, 12, READFILE('/tmp/adfa5088-prefs-termux-keylogging.br'));

.system echo "<p>Crash Report Notifications controls whether Code on the Go shows a notification after the terminal process crashes.</p><p>The notification lets you view or share a report about the crash. This is on by default.</p>" | brotli -Z > /tmp/adfa5088-prefs-termux-crashreports.br
INSERT INTO Content (path, languageId, contentTypeId, content) VALUES ('i/prefs/termux/crashreports', 1, 12, READFILE('/tmp/adfa5088-prefs-termux-crashreports.br'));

.system echo "<p>Soft Keyboard Enabled turns on the terminal's own on-screen keyboard, which includes keys not found on a standard keyboard, such as Ctrl and Esc.</p>" | brotli -Z > /tmp/adfa5088-prefs-termux-softkeyboard.br
INSERT INTO Content (path, languageId, contentTypeId, content) VALUES ('i/prefs/termux/softkeyboard', 1, 12, READFILE('/tmp/adfa5088-prefs-termux-softkeyboard.br'));

.system echo "<p>Soft Keyboard Only If No Hardware shows the on-screen keyboard only when no physical keyboard is connected to your device.</p><p>This is off by default, meaning the on-screen keyboard can show even with a hardware keyboard connected.</p>" | brotli -Z > /tmp/adfa5088-prefs-termux-nohardkeyboard.br
INSERT INTO Content (path, languageId, contentTypeId, content) VALUES ('i/prefs/termux/nohardkeyboard', 1, 12, READFILE('/tmp/adfa5088-prefs-termux-nohardkeyboard.br'));

.system echo "<p>Terminal Margin Adjustment changes the terminal view margin to try to prevent the on-screen keyboard from covering part of the terminal or its extra keys row.</p><p>This is on by default. If it causes screen flickering on your device, turn it off.</p>" | brotli -Z > /tmp/adfa5088-prefs-termux-margin.br
INSERT INTO Content (path, languageId, contentTypeId, content) VALUES ('i/prefs/termux/margin', 1, 12, READFILE('/tmp/adfa5088-prefs-termux-margin.br'));

.system echo "<p>The Git screen sets your author identity for Git: the name and email address recorded on every commit you make from Code on the Go.</p>" | brotli -Z > /tmp/adfa5088-prefs-git.br
INSERT INTO Content (path, languageId, contentTypeId, content) VALUES ('i/prefs/git', 1, 12, READFILE('/tmp/adfa5088-prefs-git.br'));

.system echo "<p>User name sets the name recorded as the author on every Git commit you make from Code on the Go.</p>" | brotli -Z > /tmp/adfa5088-prefs-git-username.br
INSERT INTO Content (path, languageId, contentTypeId, content) VALUES ('i/prefs/git/username', 1, 12, READFILE('/tmp/adfa5088-prefs-git-username.br'));

.system echo "<p>User email sets the email address recorded as the author on every Git commit you make from Code on the Go.</p>" | brotli -Z > /tmp/adfa5088-prefs-git-useremail.br
INSERT INTO Content (path, languageId, contentTypeId, content) VALUES ('i/prefs/git/useremail', 1, 12, READFILE('/tmp/adfa5088-prefs-git-useremail.br'));

.system echo "<p>Plugin Manager opens a screen where you can browse, install, remove, and configure plugins that extend Code on the Go with extra features.</p>" | brotli -Z > /tmp/adfa5088-prefs-pluginmanager.br
INSERT INTO Content (path, languageId, contentTypeId, content) VALUES ('i/prefs/pluginmanager', 1, 12, READFILE('/tmp/adfa5088-prefs-pluginmanager.br'));

.system echo "<p>About Code on the Go opens a screen with details about the app, such as its version number and credits.</p>" | brotli -Z > /tmp/adfa5088-prefs-about.br
INSERT INTO Content (path, languageId, contentTypeId, content) VALUES ('i/prefs/about', 1, 12, READFILE('/tmp/adfa5088-prefs-about.br'));

.system echo "<p>Developer Options holds experimental and debugging settings for Code on the Go, such as log dumping and log sending controls.</p><p>These settings are meant for troubleshooting, not everyday use.</p>" | brotli -Z > /tmp/adfa5088-prefs-devoptions.br
INSERT INTO Content (path, languageId, contentTypeId, content) VALUES ('i/prefs/devoptions', 1, 12, READFILE('/tmp/adfa5088-prefs-devoptions.br'));

.system echo "<p>Dump logs writes the Code on the Go internal logs to a file at ~/.cg/logs, inside your home directory.</p><p>Turn this on when you need to collect logs to diagnose a problem.</p>" | brotli -Z > /tmp/adfa5088-prefs-devoptions-dumplogs.br
INSERT INTO Content (path, languageId, contentTypeId, content) VALUES ('i/prefs/devoptions/dumplogs', 1, 12, READFILE('/tmp/adfa5088-prefs-devoptions-dumplogs.br'));

.system echo "<p>Enable LogSender controls whether Code on the Go shows log output from the apps you run, inside its own log viewer.</p><p>This is on by default. Turn it off if you do not want to see app logs inside the IDE.</p>" | brotli -Z > /tmp/adfa5088-prefs-devoptions-logsender.br
INSERT INTO Content (path, languageId, contentTypeId, content) VALUES ('i/prefs/devoptions/logsender', 1, 12, READFILE('/tmp/adfa5088-prefs-devoptions-logsender.br'));
