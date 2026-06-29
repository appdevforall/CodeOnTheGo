# Test Suite — CodeOnTheGo

## Unit tests

Written for the ADFA-4484 branch. All run with:

```bash
JAVA_HOME=/home/yaturner/.jdk17 PATH=/home/yaturner/.jdk17/bin:$PATH ./gradlew \
  :git-core:testV8DebugUnitTest \
  :common:testV8DebugUnitTest \
  :app:testV8DebugUnitTest \
  :lsp:indexing:testV8DebugUnitTest
```

## Instrumentation / UI tests

Require a connected device or emulator. Run with:

```bash
JAVA_HOME=/home/yaturner/.jdk17 PATH=/home/yaturner/.jdk17/bin:$PATH ./gradlew \
  :app:connectedV8DebugAndroidTest \
  :common:connectedV8DebugAndroidTest \
  :agent:connectedV8DebugAndroidTest
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

---

---

## Instrumentation Tests

---

## `app` — End-to-End Full Automation Test

**File:** `app/src/androidTest/kotlin/com/itsaky/androidide/AutomationEndToEndTest.kt`

A single continuous Kaspresso test that drives the app from cold first launch through onboarding, permissions, IDE setup, and then creates and initiates a build for every supported project template in both default and Kotlin language variants. Each phase is a named Kaspresso `step()` so failures report the exact stage that broke.

| Step / Assertion | What it checks |
|------------------|----------------|
| Launch app | `SplashActivity` is launched and settles |
| Verify welcome screen | Greeting title, subtitle, and Next button are all visible and clickable |
| Advance past welcome screen | Next button tap advances to permissions screen |
| Verify privacy disclosure dialog | Dialog title, Accept, and Learn More buttons all appear within timeout |
| Accept privacy disclosure | Tapping Accept dismisses the dialog |
| Verify privacy dialog does not reappear | Dialog is gone after acceptance |
| Verify all permission items | RecyclerView shows one row per required permission with title, description, and Grant button |
| Grant all permissions through onboarding UI | Each Grant button is tapped to grant its permission |
| Confirm all permissions granted | `PermissionsHelper.areAllPermissionsGranted()` returns true |
| Confirm all grant buttons disabled | All Grant buttons are disabled after permissions are granted |
| Tap Finish installation | Accessibility click on "Finish installation" |
| Wait for IDE setup to complete | Waits up to 60 s for the home or editor screen |
| Create+build (×15 templates) | For each of 8 default templates and 7 Kotlin-language variants: navigates to home, opens project creation, selects template, optionally switches to Kotlin, sets project name, creates, and initiates the build |

---

## `app` — End-to-End Test (Short Form)

**File:** `app/src/androidTest/kotlin/com/itsaky/androidide/EndToEndTest.kt`

A shorter variant of the E2E test. Covers the same onboarding flow (launch → welcome → privacy dialog → permissions → IDE setup) but only creates and initiates builds for the first 3 templates (No Activity, Empty Activity, Basic Activity) using default language settings.

| Step / Assertion | What it checks |
|------------------|----------------|
| Launch app | `SplashActivity` is launched |
| Verify welcome screen | Onboarding screen elements are visible |
| Advance past welcome screen | Next button advances the flow |
| Verify privacy disclosure dialog | Dialog appears with correct title and buttons |
| Accept privacy disclosure | Acceptance dismisses the dialog |
| Verify privacy dialog does not reappear | Dialog absent after acceptance |
| Verify all permission items | Permission list is correctly populated |
| Grant all permissions through onboarding UI | Grants each permission via the UI |
| Confirm all permissions granted | All permissions are granted |
| Confirm all grant buttons disabled | Grant buttons are disabled after granting |
| Tap Finish installation | Taps the finish button |
| Wait for IDE setup (up to 60 s) | Waits for home or editor screen |
| Create+build No Activity | Creates and initiates build for the No Activity template |
| Create+build Empty Activity | Creates and initiates build for the Empty Activity template |
| Create+build Basic Activity | Creates and initiates build for the Basic Activity template |

---

## `app` — Project Build Tests with Groovy Gradle

**File:** `app/src/androidTest/kotlin/com/itsaky/androidide/ProjectBuildTestWithGroovyGradle.kt`

Exercises project creation with Groovy DSL (`build.gradle`) scripts unchecking the Kotlin Script option. Each test navigates to the home screen, selects a template, picks the language, unchecks Kotlin Script, and verifies that the project initialises and a build can be kicked off and cancelled.

| Test | Template | Language |
|------|----------|----------|
| test_projectBuild_emptyProject_java_groovyGradle | Empty Activity | Java |
| test_projectBuild_emptyProject_kotlin_groovyGradle | Empty Activity | Kotlin |
| test_projectBuild_baseProject_java_groovyGradle | Basic Activity | Java |
| test_projectBuild_baseProject_kotlin_groovyGradle | Basic Activity | Kotlin |
| test_projectBuild_navigationDrawerProject_java_groovyGradle | Navigation Drawer | Java |
| test_projectBuild_navigationDrawerProject_kotlin_groovyGradle | Navigation Drawer | Kotlin |
| test_projectBuild_bottomNavigationProject_java_groovyGradle | Bottom Navigation | Java |
| test_projectBuild_bottomNavigationProject_kotlin_groovyGradle | Bottom Navigation | Kotlin |
| test_projectBuild_tabbedActivityProject_java_groovyGradle | Tabbed Activity | Java |
| test_projectBuild_tabbedActivityProject_kotlin_groovyGradle | Tabbed Activity | Kotlin |
| test_projectBuild_noAndroidXProject_java_groovyGradle | No AndroidX | Java |
| test_projectBuild_noAndroidXProject_kotlin_groovyGradle | No AndroidX | Kotlin |

---

## `app` — Project Build Tests with KTS Gradle

**File:** `app/src/androidTest/kotlin/com/itsaky/androidide/ProjectBuildTestWithKtsGradle.kt`

Same template matrix as the Groovy tests but uses the default Kotlin Script DSL (`build.gradle.kts`). The Basic Activity / Kotlin variant uses explicit `flakySafely` wrappers with extended timeouts for extra stability. The Compose Activity variant is commented out pending a template fix.

| Test | Template | Language |
|------|----------|----------|
| test_projectBuild_emptyProject_java | Empty Activity | Java |
| test_projectBuild_emptyProject_kotlin | Empty Activity | Kotlin |
| test_projectBuild_baseProject_java | Basic Activity | Java |
| test_projectBuild_baseProject_kotlin | Basic Activity | Kotlin (with `flakySafely` wrappers) |
| test_projectBuild_navigationDrawerProject_java | Navigation Drawer | Java |
| test_projectBuild_navigationDrawerProject_kotlin | Navigation Drawer | Kotlin |
| test_projectBuild_bottomNavigationProject_java | Bottom Navigation | Java |
| test_projectBuild_bottomNavigationProject_kotlin | Bottom Navigation | Kotlin |
| test_projectBuild_tabbedActivityProject_java | Tabbed Activity | Java |
| test_projectBuild_tabbedActivityProject_kotlin | Tabbed Activity | Kotlin |
| test_projectBuild_noAnd2roidXProject_java | No AndroidX | Java |
| test_projectBuild_noAndroidXProject_kotlin | No AndroidX | Kotlin |

---

## `app` — IDE Editor Copy Behaviour

**File:** `app/src/androidTest/kotlin/com/itsaky/androidide/editor/ui/IDEEditorTest.kt`

Tests `IDEEditor.copyText()` on the main thread using `InstrumentationRegistry`. A `spyk` intercepts the internal `doCopy()` call to capture what would have been placed on the clipboard.

| Test | What it checks |
|------|----------------|
| test_includeDebugInfoFlagOnCopy_ifEnabled | When `includeDebugInfoOnCopy = true`, the copied string is prefixed with `"CodeOnTheGo (version)\n"` before the editor text |
| test_includeDebugInfoFlagOnCopy_ifDisabled | When `includeDebugInfoOnCopy = false`, the copied string is exactly the editor text with no prefix |

---

## `app` — Cleanup Test

**File:** `app/src/androidTest/kotlin/com/itsaky/androidide/CleanupTest.kt`

A utility instrumentation test that runs a shell command to delete the `CodeOnTheGoProjects` directory from external storage. Intended to be run after other tests to leave the device in a clean state. Failures are suppressed — cleanup is best-effort only.

| Test | What it checks |
|------|----------------|
| performCleanup | Executes `rm -rf /sdcard/CodeOnTheGoProjects` via `UiAutomation.executeShellCommand()` |

---

## `app` — Gradle Cache Export

**File:** `app/src/androidTest/kotlin/com/itsaky/androidide/ExportCacheDirectoryTest.kt`

Utility instrumentation tests for exporting Gradle caches and generated JARs to external storage after connected tests run, so the artifacts can be pulled off the device for inspection or caching.

| Test | What it checks |
|------|----------------|
| exportGradleModuleCacheBeforeConnectedTestCleanup | Copies the Gradle module cache directory from `filesDir` to a path under `CodeOnTheGoProjects/` on external storage. Skipped if the source directory does not exist. |
| exportGeneratedGradleApiJarWhenRequested | Copies a specific `gradle-api-<version>.jar` to external storage. Skipped unless the `androidide.exportGradleApi.version` test argument is provided and the JAR exists. |

---

## `app` — StrictMode Frame Matcher

**File:** `app/src/androidTest/kotlin/com/itsaky/androidide/app/strictmode/FrameMatcherTest.kt`

Tests `FrameMatcher`, a set of predicates that match individual stack-trace frames by class name and/or method name. Each matcher variant is tested for both positive and negative cases.

| Test | What it checks |
|------|----------------|
| classEquals_matches_exact_class_name | Exact class name match → true |
| classEquals_fails_on_different_class_name | Different class name → false |
| classStartsWith_matches_prefix | Class name starts with prefix → true |
| classStartsWith_fails_on_wrong_prefix | Wrong prefix → false |
| classContains_matches_token | Class name contains substring → true |
| classContains_fails_when_token_absent | Absent substring → false |
| methodEquals_matches_exact_method_name | Exact method name match → true |
| methodEquals_fails_on_different_method_name | Different method name → false |
| methodStartsWith_matches_prefix | Method name starts with prefix → true |
| methodStartsWith_fails_on_wrong_prefix | Wrong prefix → false |
| methodContains_matches_token | Method name contains substring → true |
| methodContains_fails_when_token_absent | Absent substring → false |
| classAndMethod_matches_when_both_match | Both class and method match → true |
| classAndMethod_fails_when_class_mismatches | Class mismatch → false |
| classStartsWithAndMethod_matches_prefix_and_exact_method | Class prefix + exact method match → true |
| classStartsWithAndMethod_fails_when_method_differs | Method mismatch → false |
| anyOf_matches_when_any_matcher_matches | At least one sub-matcher matches → true |
| anyOf_fails_when_no_matcher_matches | No sub-matchers match → false |
| allOf_matches_when_all_matchers_match | All sub-matchers match → true |
| allOf_fails_when_any_matcher_fails | Any sub-matcher fails → false |

---

## `app` — StrictMode Stack Matcher

**File:** `app/src/androidTest/kotlin/com/itsaky/androidide/app/strictmode/StackMatcherTest.kt`

Tests `StackMatcher`, which evaluates a sequence of frame matchers against a full stack trace. Three matching strategies are tested: `Adjacent` (frames must appear consecutively in order), `InOrder` (frames must appear in order but may have gaps), and `AdjacentInOrder` (multiple adjacent groups must appear in order relative to each other).

| Test | What it checks |
|------|----------------|
| adjacent_matches_when_frames_are_adjacent_and_in_order | Consecutive matching frames in the correct order → true |
| adjacent_does_not_match_when_order_is_wrong | Consecutive frames in reversed order → false |
| adjacent_does_not_match_when_not_adjacent | Matching frames separated by a gap frame → false |
| inOrder_matches_when_frames_exist_in_sequence | Matching frames in order with gaps allowed → true |
| inOrder_does_not_match_when_order_is_violated | Matching frames present but in the wrong order → false |
| adjacentInOrder_matches_multiple_groups_in_sequence | Two adjacent groups both present in order → true |
| adjacentInOrder_fails_when_any_group_is_missing | One group's frames missing from the trace → false |

---

## `app` — StrictMode Whitelist Engine

**File:** `app/src/androidTest/kotlin/com/itsaky/androidide/app/strictmode/WhitelistEngineTest.kt`

Tests `WhitelistEngine.evaluate()`, which decides whether a StrictMode violation should be allowed or crash the app. Uses MockK to swap the engine's whitelist rules and `StrictModeManager.install()` to put the engine in Crash-by-default mode.

| Test | What it checks |
|------|----------------|
| evaluateReturnsAllowForWhitelistedViolation | A violation whose stack matches a whitelist rule returns `Decision.Allow` |
| evaluateReturnsCrashForNonWhitelistedViolation | A violation with an empty whitelist returns `Decision.Crash` |
| evaluateReturnsAllowForOplusUIFirstDiskReadViolation | A real-world Oplus `DiskReadViolation` stack trace matches the built-in Oplus whitelist rule; `allow.reason` contains "Oplus" |

---

## `app` — StrictMode Whitelist Rules

**File:** `app/src/androidTest/kotlin/com/itsaky/androidide/app/strictmode/WhitelistRulesTest.kt`

Regression tests for every built-in StrictMode whitelist rule. Each test supplies a representative stack trace for a known OEM or library violation and asserts that `WhitelistEngine.evaluate()` returns `Allow`.

| Test | Violation being whitelisted |
|------|-----------------------------|
| allow_DiskRead_on_FirebaseUserUnlockRecvSharedPrefAccess | Firebase `UserUnlockReceiver` accessing SharedPreferences during data-collection init |
| allow_DiskRead_on_MiuiMultiLangHelperTextViewDraw | MIUI `MultiLangHelper` disk read during `TextView.onDraw` |
| allow_DiskRead_on_MtkScnModuleIsGameAppCheck | MediaTek `ScnModule.isGameApp()` file-size check |
| allow_DiskRead_on_Firebase_DataCollection | Firebase `DataCollectionConfigStorage` reading SharedPreferences |
| allow_DiskRead_on_ServiceLoader_JdkDistributionProvider | App's own `ServiceLoader.parse()` called from `IJdkDistributionProvider` lazy init |
| allow_DiskRead_on_OnboardingActivity_ToolsCheck | `OnboardingActivity.checkToolsIsInstalled()` doing a `File.exists()` check |
| allow_DiskRead_on_MiuiEpFrameworkFactoryIsEnterpriseJarExists | MIUI enterprise framework JAR existence check |
| allow_DiskRead_on_MtkBoostFwkIsGameApp | MediaTek `BoostFwk` game-app check via `File.exists()` |
| allow_DiskRead_on_MtkAsyncDrawableCachePutCacheList | MediaTek `AsyncDrawableCache.putCacheList()` calling `SharedPreferencesImpl.writeToFile()` (File.exists variant) |
| allow_DiskRead_on_MtkAsyncDrawableCachePutCacheList_OsStatVariant | Same path but triggered via `Os.stat()` |
| allow_DiskWrite_on_MtkAsyncDrawableCachePutCacheList | Write variant of the MediaTek drawable cache commit (IoBridge.open) |
| allow_DiskWrite_on_MtkAsyncDrawableCache_FileOutputStreamWrite | MediaTek cache write via `FileOutputStream.write` → XML serialiser |
| allow_DiskWrite_on_MtkAsyncDrawableCache_FileDelete | MediaTek cache commit calling `File.delete()` |
| allow_DiskWrite_on_MtkAsyncDrawableCache_OsChmod | MediaTek cache commit calling `Os.chmod()` |

---

## `common` — Database Version Resolver

**File:** `common/src/androidTest/java/com/itsaky/androidide/utils/DatabaseVersionResolverTest.kt`

Tests `DatabaseVersionResolver.resolveDatabaseVersion()` using a real in-memory `SQLiteDatabase`. The resolver reads version metadata from a `LastChange` table and returns a human-readable version string.

| Test | What it checks |
|------|----------------|
| returnsWholedbRow_whenPresent | Row with `documentationSet = "wholedb"` → `"timestamp who"` |
| fallsBackToLatestRow_whenWholedbMissing | No "wholedb" row → falls back to the row with the latest `changeTime`, formatted as `"timestamp (set) who"` |
| returnsVersionUnknown_whenTableEmpty | Table exists but has no rows → `VERSION_UNKNOWN` |
| returnsVersionUnknown_whenTableMissing | Table does not exist → `VERSION_UNKNOWN` |
| handlesNullWho_onWholedbRow | `who` is null on the "wholedb" row → `"timestamp"` (no trailing name) |
| handlesNullWho_onFallbackRow | `who` is null on the fallback row → `"timestamp (set)"` (no trailing name) |

---

## `common` — Flash Bar Show Utilities

**File:** `common/src/androidTest/java/com/itsaky/androidide/utils/FlashActivityUtilsKtTest.kt`

Tests `flashbarBuilder().showOnUiThread()`, a utility that safely shows a Flashbar (in-app notification banner) regardless of which thread calls it.

| Test | What it checks |
|------|----------------|
| showFlashbarFromUiThread_doesNotCrash | Calling `showOnUiThread()` from the UI thread does not throw |
| showFlashbarFromBackgroundThread_doesNotCrash | Calling `showOnUiThread()` from a background thread does not throw and completes within 5 seconds |

---

## `agent` — Local LLM Integration Tests

**File:** `agent/src/androidTest/java/com/itsaky/androidide/agent/repository/LocalLlmIntegrationTest.kt`

End-to-end integration tests for the on-device LLM inference engine (`LlmInferenceEngine` / `LocalAgenticRunner`). The Llama AAR is installed from test assets; tests are skipped if no `.gguf` model file is found on the device. All tests have generous timeouts (1–4 minutes).

| Test | What it checks |
|------|----------------|
| testEngineInitialization | `LlmInferenceEngine.initialize()` returns true |
| testModelLoading | `initModelFromFile()` loads the GGUF model; `isModelLoaded` becomes true and `loadedModelName` is non-null |
| testSimpleInference | Running inference with "What is 2 + 2?" returns a non-blank response containing "4" or "four" |
| testLocalAgenticRunnerSimplifiedWorkflow | `LocalAgenticRunner.generateASimpleResponse()` produces at least one agent message and does not reach a "max steps" error state |
| testToolCallParsing | Asking "What time is it?" results in at least one agent message (either a tool call or a direct answer) |
| testNoMaxStepsErrorWithComplexQuery | A complex multi-step prompt does not produce a "max steps exceeded" error state |
| testAllToolsAreInvokedFromPrompt | For each of 13 registered tools, injecting a pre-formed `<tool_call>` response causes the runner to transition through a `Thinking` state that names the tool |
| testLocalLlmBenchmarkSuite | Runs 15 benchmark prompts (13 tool-triggering + 2 conversational) and writes a CSV of tool detection accuracy, time-to-first-token, and total latency to the device's external files directory |
