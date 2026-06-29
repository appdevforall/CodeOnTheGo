# Test Suite — CodeOnTheGo

Unit tests written for the ADFA-4484 branch. All run with:

```bash
JAVA_HOME=/home/yaturner/.jdk17 PATH=/home/yaturner/.jdk17/bin:$PATH ./gradlew \
  :git-core:testV8DebugUnitTest \
  :common:testV8DebugUnitTest \
  :app:testV8DebugUnitTest \
  :lsp:indexing:testV8DebugUnitTest
```

---

## `git-core` — Git Repository URL Parsing

**File:** `git-core/src/test/java/com/itsaky/androidide/git/core/GitRepositoryUrlsTest.kt`

Tests `parseGitRepositoryUrl()`, which validates and normalises a string into a Git remote URL or returns `null` if the input is not a recognisable repository address.

| Test | What it checks |
|------|----------------|
| blank string returns null | Empty string → `null` |
| whitespace-only string returns null | String of spaces → `null` |
| newline-only string returns null | `\n\t` → `null` |
| valid HTTPS GitHub URL is accepted | Standard `https://github.com/…` URL is parsed and contains the host |
| valid HTTPS URL with trailing whitespace is trimmed and accepted | Leading/trailing spaces are stripped before parsing |
| HTTPS URL without git suffix is accepted | URL without `.git` suffix is still valid |
| HTTPS URL with port is accepted | Non-standard port (`:8443`) is accepted |
| HTTP URL is accepted | Plain `http://` scheme works |
| SSH URL with scheme is accepted | `ssh://git@github.com/…` format works |
| SCP-style SSH URL is accepted | `git@github.com:user/repo.git` format works |
| SCP-style SSH URL for GitLab is accepted | Same format works for GitLab host |
| SCP-style SSH URL with subdomain is accepted | Bitbucket host with subdomain works |
| git protocol URL is accepted | `git://` scheme works |
| plain word without host or scheme returns null | Single word with no scheme → `null` |
| relative path returns null | `some/relative/path` → `null` |
| random clipboard text returns null | Plain prose → `null` |
| dot-only string returns null | `.` → `null` |
| file scheme URL is accepted | `file:///` local path is accepted |

---

## `git-core` — Git Data Models

**File:** `git-core/src/test/java/com/itsaky/androidide/git/core/GitModelsTest.kt`

Tests the data classes used to represent Git state: `GitStatus`, `FileChange`, `GitBranch`, `GitCommit`, and `CommitHistoryUiState`.

| Test | What it checks |
|------|----------------|
| testGitStatusEmpty | `GitStatus.EMPTY` is clean, has no conflicts, and all lists are empty |
| GitStatus EMPTY is not merging | `isMerging` is false on the empty sentinel |
| GitStatus with staged files is not clean | `isClean = false` when staged list is non-empty |
| GitStatus with conflicts sets both flags | `hasConflicts` and `isMerging` are both true when a conflict exists |
| GitStatus equality is value-based | Two equivalent `GitStatus` instances compare equal |
| testFileChange | Basic path, type, and null `oldPath` on a MODIFIED change |
| FileChange with ADDED type | `ChangeType.ADDED` is set; `oldPath` is null |
| FileChange with DELETED type | `ChangeType.DELETED` is set |
| FileChange with RENAMED type carries oldPath | `oldPath` is populated for a rename |
| FileChange equality is value-based | Two equivalent `FileChange` instances compare equal |
| FileChange with different paths are not equal | Different paths produce unequal instances |
| testGitBranch | Local branch has correct name, `isCurrent`, and is not remote |
| GitBranch remote branch carries remoteName | Remote branch stores `remoteName` |
| GitBranch local branch has null remoteName by default | No `remoteName` on a local branch |
| GitBranch equality is value-based | Two equivalent `GitBranch` instances compare equal |
| testGitCommit | Hash, shortHash, and message are stored correctly |
| GitCommit with parent hashes for merge commit | Merge commit stores two parent hashes and `hasBeenPushed` |
| GitCommit equality is value-based | Two equivalent `GitCommit` instances compare equal |
| GitCommit with different hashes are not equal | Different hashes produce unequal instances |
| CommitHistoryUiState Loading is the loading state | `Loading` singleton is an instance of the Loading subtype |
| CommitHistoryUiState Empty has no commits | `Empty` singleton is an instance of the Empty subtype |
| CommitHistoryUiState Success carries commit list | `Success` holds and returns the provided commit list |
| CommitHistoryUiState Error carries message | `Error` stores the provided error message string |
| CommitHistoryUiState Error with null message | `Error` accepts a null message |
| CommitHistoryUiState Success equality is value-based | Two `Success` instances with the same commits compare equal |

---

## `agent` — LLM Tool Call Parsing

**File:** `agent/src/test/java/com/itsaky/androidide/agent/repository/UtilParseToolCallTest.kt`

Tests `Util.parseToolCall()`, which extracts a structured tool call (name + args) from the raw text of an LLM response. The LLM may format the call in several ways.

| Test | What it checks |
|------|----------------|
| parses tool call with name and empty args from tool_call tags | JSON inside `<tool_call>` tags with no args is parsed |
| parses tool call with args from tool_call tags | JSON inside `<tool_call>` tags with a file path arg is parsed |
| parses tool call for list_files with path arg | `list_files` tool with `path` arg is parsed |
| parses tool call for search_project | `search_project` tool with `query` arg is parsed |
| parses bare JSON object with name field | JSON without any wrapping tags is parsed |
| parses JSON wrapped in markdown code fence | ` ```json … ``` ` fence is stripped and parsed |
| parses JSON wrapped in markdown code fence without json label | Plain ` ``` ` fence (no language label) is also handled |
| list_dir is resolved to list_files | Alias `list_dir` maps to the canonical `list_files` tool name |
| accepts tool_name field as alternative to name | `tool_name` key is accepted as a synonym for `name` |
| parses tool-only tag with no args | `<tool_call>get_current_datetime</tool_call>` (no JSON) is parsed |
| returns null when tool name is not in available tools | Unknown tool name → `null` |
| returns null when name field is missing | JSON with no `name` key → `null` |
| returns null when name field is blank | `name: ""` → `null` |
| returns null for empty string | Empty input → `null` |
| returns null for plain prose | Natural language sentence → `null` |
| returns null when no tools are provided and response has a tool call | Empty tool set → always `null` |
| extracts first JSON object and ignores trailing text | Prose after the JSON object is ignored |
| parses multiple args correctly | Two args (`path`, `content`) are both extracted |
| returns null when tool set is empty and input has JSON | Redundant empty-set check |

---

## `agent` — Chat Transcript Export

**File:** `agent/src/test/java/com/itsaky/androidide/agent/utils/ChatTranscriptUtilsTest.kt`

Tests `ChatTranscriptUtils.writeTranscriptToCache()`, which saves a chat session to a timestamped `.txt` file under the app's cache directory.

| Test | What it checks |
|------|----------------|
| writeTranscriptToCache creates file in chat_exports subdirectory | Output file lives inside a `chat_exports/` folder under `cacheDir` |
| writeTranscriptToCache writes content verbatim | File content exactly matches the transcript string passed in |
| writeTranscriptToCache filename starts with chat-transcript | Filename has the expected prefix |
| writeTranscriptToCache filename ends with txt | Filename has the `.txt` extension |
| writeTranscriptToCache two calls produce distinct filenames | Successive calls generate different filenames (timestamp-based) |
| writeTranscriptToCache handles empty transcript | Empty string is written successfully; file is created |
| writeTranscriptToCache handles multiline unicode content | Japanese, Arabic, and Chinese characters round-trip correctly |
| writeTranscriptToCache throws when exports path is a file not a directory | `IOException` with "not a directory" message when `chat_exports` is a file |
| writeTranscriptToCache re-uses existing exports directory | Pre-existing `chat_exports/` directory is reused, not duplicated |

---

## `common` — Keyed Debouncing Action

**File:** `common/src/test/java/com/itsaky/androidide/utils/KeyedDebouncingActionTest.kt`

Tests `KeyedDebouncingAction`, which debounces repeated work by key — rapid schedules for the same key are coalesced into a single action invocation, fired after a quiet period.

| Test | What it checks |
|------|----------------|
| single schedule triggers action exactly once | One `schedule()` call fires the action once after the debounce window |
| rapid schedules are coalesced into a single action | Ten rapid `schedule("k")` calls result in exactly one action execution |
| late reschedule of same key extends debounce window and fires exactly once | A second schedule mid-window resets the timer; action still fires only once |
| different keys are handled independently | Scheduling `"alpha"` and `"beta"` fires both actions independently |
| rapid schedules for different keys each trigger exactly once | Five sends for `"a"` and five for `"b"` each coalesce to one execution |
| cancelPending prevents scheduled action from running | `cancelPending()` before the window expires suppresses the action |
| cancelAll cancels all pending entries | `cancelAll()` with multiple keys pending results in zero executions |
| cancelPending for unknown key is a no-op | Cancelling a key that was never scheduled does not throw or affect other keys |
| can re-schedule a key after its action completes | A key can be scheduled again after its first action finishes |
| action receives a cancel checker that is active while running | The `ICancelChecker` passed to the action reports not-cancelled during execution |

---

## `app` — Diagnostics Formatter

**File:** `app/src/test/java/com/itsaky/androidide/utils/DiagnosticsFormatterTest.kt`

Tests `DiagnosticsFormatter.format()`, which converts a map of `File → List<DiagnosticItem>` into a human-readable diagnostics report string.

| Test | What it checks |
|------|----------------|
| empty map returns no diagnostics message | `emptyMap()` input produces the literal string `"No diagnostics"` |
| non-empty map contains header and summary | Output always includes `=== Diagnostics Report ===` and `=== Summary ===` sections |
| file header shows correct error and warning counts | Per-file header shows `(N errors, M warnings)` |
| file with only warnings shows zero errors | `0 errors` is shown when no errors exist for a file |
| file with only errors shows zero warnings | `0 warnings` is shown when no warnings exist for a file |
| ERROR severity label is written | `[ERROR]` appears in output for error-severity items |
| WARNING severity label is written | `[WARNING]` appears in output |
| INFO severity label is written | `[INFO]` appears in output |
| HINT severity label is written | `[HINT]` appears in output |
| line and column are reported as 1-indexed | 0-based `line=4, col=9` is displayed as `Line 5:10` |
| zero-based line 0 col 0 reports as Line 1 col 1 | First position `(0,0)` displays as `Line 1:1` |
| diagnostic message text is present in output | The diagnostic message string appears verbatim in the report |
| diagnostic code is shown when present | Non-blank `code` field appears as `Code: E001` |
| diagnostic code is omitted when blank | Blank `code` field does not produce a `Code:` line |
| files are sorted alphabetically by name | Files appear in alphabetical order in the report |
| diagnostics within a file are sorted by line number | Items within a file appear in ascending line-number order |
| summary counts aggregate across all files | Summary section totals errors and warnings across all files |
| summary files count only files with items | Files with empty diagnostic lists are excluded from the file count |
| files with empty diagnostic list are not included in body | A file key with an empty list produces no output in the report body |

---

## `lsp:indexing` — Background Indexer

**File:** `lsp/indexing/src/test/kotlin/org/appdevforall/codeonthego/indexing/BackgroundIndexerTest.kt`

Tests `BackgroundIndexer`, which indexes source files into an `InMemoryIndex` asynchronously via coroutine jobs and reports progress via a listener.

| Test | What it checks |
|------|----------------|
| indexSource inserts entries into backing index | Entries from a provider are written to the index after the job completes |
| indexSource skips already-indexed source when skipIfExists is true | Provider is never called when the source is already in the index |
| indexSource re-indexes source when skipIfExists is false | Old entries are removed and new entries are inserted when force-reindexing |
| progress listener receives Started and Completed events | Listener receives `Started` then `Completed` for a normal indexing run |
| progress listener Completed event carries total count | `Completed.totalIndexed` equals the number of entries provided |
| progress listener receives Skipped when source already indexed | Listener receives `Skipped` when `skipIfExists = true` and source exists |
| progress listener receives Failed event on provider exception | Listener receives `Failed` when the provider sequence throws |
| indexSources indexes all provided sources | Multiple sources are all indexed, each queryable by `bySource()` |
| activeJobCount reflects running jobs | Count is positive while a job is in progress and zero after completion |
| awaitAll suspends until all active jobs complete | `awaitAll()` only returns once every active indexing job has finished |

---

## `lsp:indexing` — Index Query Builder

**File:** `lsp/indexing/src/test/kotlin/org/appdevforall/codeonthego/indexing/IndexQueryBuilderTest.kt`

Tests the `indexQuery { }` DSL and `IndexQuery` static factories, which construct query objects used to filter entries in the symbol index.

| Test | What it checks |
|------|----------------|
| empty builder produces ALL-equivalent query | An empty `indexQuery {}` block has no predicates and the default limit of 200 |
| eq adds exact match predicate | `eq("kind", "class")` adds a field/value pair to `exactMatch` |
| multiple eq calls accumulate | Multiple `eq()` calls each add their own entry; none overwrite the others |
| prefix adds prefix match predicate | `prefix("name", "Array")` adds an entry to `prefixMatch` |
| multiple prefix calls accumulate | Multiple `prefix()` calls each add their own entry |
| exists sets presence predicate to true | `exists("field")` adds `field → true` to `presence` |
| notExists sets presence predicate to false | `notExists("field")` adds `field → false` to `presence` |
| sourceId is set via builder property | `sourceId = "my-jar.jar"` is reflected in the built query |
| key is set via builder property | `key = "com.example.Foo"` is reflected in the built query |
| limit can be overridden | `limit = 50` is reflected in the built query |
| limit zero means unlimited | `limit = 0` is reflected as zero (no cap) in the built query |
| combined predicates all appear in built query | All predicate types together produce a single coherent query |
| IndexQuery ALL has no predicates and default limit | The `ALL` factory produces an unconstrained query |
| IndexQuery byKey sets key and limit 1 | `byKey()` factory sets the key and caps results to 1 |
| IndexQuery bySource sets sourceId and unlimited limit | `bySource()` factory sets the source and uses unlimited (0) limit |
| modifying builder after build does not affect built query | Post-build mutation of the builder does not change the already-built query |
| two queries with identical predicates are equal | Data class equality holds for equivalent queries |
| queries with different predicates are not equal | Queries with differing predicate values are not equal |
