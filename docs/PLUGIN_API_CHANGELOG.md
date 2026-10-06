# Plugin API Changelog

A version-mapped history of the Code on the Go **plugin API** — which release week
first shipped each plugin capability. It exists so a plugin author can choose a
correct `plugin.min_ide_version`.

- **Audience:** plugin developers (and maintainers changing the API).
- **Companions:** [PLUGIN_AUTHORING.md](PLUGIN_AUTHORING.md) (how to write a plugin),
  [plugin-api.md](plugin-api.md) (what counts as the API + compatibility policy).

## How to use this

Set `plugin.min_ide_version` to the **highest** version below among the
capabilities your plugin actually uses. Only `min` is enforced at install today;
`max` is parsed but advisory.

```xml
<!-- src/main/AndroidManifest.xml -->
<meta-data android:name="plugin.min_ide_version" android:value="26.30" />
<meta-data android:name="plugin.max_ide_version" android:value="99.0.0" />
```

Versions are bare `YY.WW` — two-digit ISO year, two-digit ISO week (`26.30` =
2026, week 30). Not the R1 / R2 marketing names, and there is no separate integer
"API level" — the `YY.WW` string is the whole contract.

## Changelog

Newest first. Most changes are **additive**; the ones that are not carry a
`breaking` row saying what breaks and what to do about it. Read the `breaking`
rows at or below your `min_ide_version` before you bump it.

Legend: `added` = new capability, safe to adopt · `breaking` = existing plugins
need a source change, a recompile, or both · `tooling` = API-stability
milestone. **[verified]** = read from the checked-in ABI dump. **[reconstructed]**
= diffed from `plugin-api/src` history (predates the dump; symbol-accurate).

### 26.41 — unreleased
- **added — Terminal readiness and visible terminal commands** _(ADFA-6373)_ **[verified]**
  `IdeTerminalService.isTerminalReady()` reports whether the terminal environment is installed
  and bash runs; it needs no permission. `runInTerminal(command, workingDirectory)` opens a new
  session in the visible Terminal, runs the command with bash and suspends until it exits,
  returning `TerminalCommandResult.Completed(exitCode, output)` with the session transcript, or
  `NotStarted(reason)` (environment missing, IDE not in the foreground). The session stays open
  so the user sees what ran; cancelling the caller kills the command. Needs `system.commands`;
  the working directory must lie inside the project. Floor `plugin.min_ide_version` at `26.41`:
  an older IDE has no `IdeTerminalService` class, so referencing it fails to load.
- **added — Run Gradle tasks with arguments, get a structured result, cancel** _(ADFA-6373)_ **[verified]**
  `IdeBuildService.executeTasks(tasks: List<String>, arguments: List<String>)` runs on the
  IDE's tooling server, so `--tests`, `-P` and `--info` work and the output reaches the Build
  Output pane (read it with `getBuildOutput()`). It completes with a `GradleTaskResult`:
  `Success`, `Failed(reason)`, `Refused(reason)` when the build never started (another build
  running, tooling server down), or `Cancelled`. `IdeBuildService.cancelBuild()` cancels the
  running build, whoever started it. `executeTasks(vararg String)` is unchanged. Floor
  `plugin.min_ide_version` at `26.41`: an older IDE has neither method.
- **breaking — `CommandSpec.GradleTask` runs on the tooling server** _(ADFA-6373)_
  It used to start `./gradlew` as a separate process: a second Gradle daemon on the device,
  with output that never reached the Build Output pane. It now runs like `executeTasks` above.
  Output arrives in one batch of `StdOut` lines when the build ends instead of streaming; the
  exit code is 0 on success and 1 on a failed build; a refused build fails with exit code -1
  and the reason in `CommandResult.Failure.error`. No source change is needed. A plugin that
  ran a Gradle task while another build was running now gets that refusal instead of a
  second build.
- **breaking — `CommandSpec.ShellCommand.workingDirectory` needs an open project** _(ADFA-6373)_
  With no project open, a `workingDirectory` used to be accepted unchecked; `executeCommand` now
  throws `SecurityException`, as it does for one outside the project. Pass null to run in the
  default directory.
- **added — Plugin languages: tree-sitter highlighting and a language server** _(ADFA-4851)_ **[verified]**
  A plugin implementing `LanguageExtension` returns `LanguageDefinition`s, each claiming file
  extensions and optionally carrying a `TreeSitterGrammar` and a `LanguageServerDefinition`.
  The grammar is `lib/<abi>/libtree-sitter-<name>.so` exporting `tree_sitter_<name>`, built
  at tree-sitter language ABI 13 or 14, with `highlights.scm` (and optionally `locals`,
  `blocks`, `brackets`, `indents`) under `queriesAssetPath` in the plugin's assets. Captures
  use the standard names (`keyword`, `string`, `function`, `type`, ...); every colour scheme
  maps them through its `generic.json`. The server is any stdio LSP process: a bare command
  name resolves against the Termux `bin` directory, and it runs with the Termux environment
  plus `environment`. A grammar needs `native.code`; a server needs `system.commands`.
  Extensions the IDE already handles (`java`, `kt`, `kts`, `xml`, `json`, `log`, `gradle`,
  C/C++) cannot be claimed. Purely additive. Floor `plugin.min_ide_version` at `26.41`: an
  older IDE cannot load a plugin class that implements `LanguageExtension`.
- **added — No cap on sidebar items** _(ADFA-4977)_
  The sidebar held 12 items: the IDE's seven plus the slots plugins declared with
  `plugin.sidebar_items`. A plugin declaring more than the free slots failed to load, and
  plugins loaded before the editor counted its own items could overfill the sidebar and
  crash the IDE at launch. The sidebar now scrolls, so every declared item is shown.
  `IdeSidebarService.getMaxSidebarItems()` and `getAvailableSidebarSlots()` return
  `Int.MAX_VALUE`, and `canAddSidebarItems()` returns `true`. A plugin still returns no
  more items than it declares. Floor `plugin.min_ide_version` at `26.41` if the plugin
  declares more than 5 items, the slots an older IDE leaves free.
- **added — Tool-source groups and health, backend model names, and change listeners** _(ADFA-6278)_ **[verified]**
  A consumer such as the agent's chat screen could not tell which tools the agent has, whether
  they work, or which model will answer, and was never told when any of that changed.
  `ToolSourceRegistry.CONTRACT_VERSION` is now `2`.
  - `ToolSourceRegistry.GroupedToolSource` (new interface, extends `ToolSource`):
    `getToolGroups()` splits one source into `ToolSourceRegistry.ToolGroup`s (`getId`,
    `getDisplayName`, `getToolNames`, `getStatus`, `getStatusMessage`) — one per MCP server,
    say. A group may name no tools, which is how an unreachable server keeps its place.
  - `ToolSourceRegistry.StatusReportingToolSource` (new interface, extends `ToolSource`):
    `getStatus()` and `getStatusMessage()` report health as a `CapabilityStatus`:
    `AVAILABLE`, `CONNECTING` or `DEGRADED`. Both must be cheap and non-blocking, because
    consumers call them on the UI thread. A provider reports a change with
    `ToolSourceRegistry.notifyToolSourceStatusChanged`. The enum may grow; handle an unknown
    constant as `DEGRADED`. A source that does not implement it reads as `AVAILABLE`.
  - `ToolSourceRegistry.addToolSourceListener` / `removeToolSourceListener` take a
    `ToolSourceListener`. `onToolSourcesChanged` is told the provider id on register,
    unregister and `notifyToolsChanged`; `onToolSourceStatusChanged` on
    `notifyToolSourceStatusChanged`, so a status change need not re-read the tool list. It
    defaults to `onToolSourcesChanged`.
  - `LlmInferenceService.ActiveModelReportingBackend` (new interface, extends `LlmBackend`):
    `getActiveModelName()` names the model a backend will answer with. It is not called
    `getModelName` because the Gemini and OpenAI backends already declare a private
    `getModelName()`. A backend that does not implement it names no model.
  - `LlmInferenceService.StatusReportingBackend` (new interface, extends `LlmBackend`):
    `getStatus()` and `getStatusMessage()` report whether the backend can answer right now as
    the same `CapabilityStatus`. A backend
    that does not implement it reads as `AVAILABLE`. `isAvailable()` still means only "set up"; a
    configured OpenAI-compatible server that is not running is `DEGRADED`. Same cheap,
    non-blocking rule and unknown-constant reading as for a tool source.
  - `com.itsaky.androidide.plugins.services.CapabilityStatus` (new top-level enum) is the one
    status both contracts return, so a consumer showing backends and tool sources side by side
    maps one vocabulary, not two identical enums.
  - `com.itsaky.androidide.plugins.ai.LlmBackendRegistration` keeps a backend plugin's
    `LlmBackend` registered with the router: `start(backend)` from `activate`, `stop()` from
    `deactivate` and `dispose`. It re-registers when AI Core restarts, and calls
    `notifyBackendChanged` when one of the `watchedKeys` in the plugin's settings changes, so
    backend plugins stop carrying their own copy of that wiring.
  - `LlmInferenceService.addBackendChangeListener` / `removeBackendChangeListener` take a
    `BackendChangeListener`, which is told the backend id on register, unregister, a change
    in the user's selection, and `LlmInferenceService.notifyBackendChanged`. A backend calls
    that when its availability or model changes.
  - `LlmInferenceService.EmbeddingModelSelectable` (new interface, extends `EmbeddingBackend`)
    lets a screen outside the backend's plugin list (`listEmbeddingModels()`) and change
    (`setEmbeddingModelId`) the embedding model; the backend still stores the choice and calls
    `notifyBackendChanged`. `EmbeddingBackend.getEmbeddingDimensions()` is now constant per
    model rather than per backend instance.

  Listeners are called synchronously on the changing thread, outside the implementation's
  lock. An exception a listener throws is contained.

  Status, model name and groups are new interfaces rather than defaults on `ToolSource` and
  `LlmBackend`, per the "prefer a new interface" rule in [plugin-api.md](plugin-api.md): a
  default named `getStatusMessage`, `getActiveModelName` or `getToolGroups` could be taken for
  an unrelated method a plugin already declares. `LlmBackend` and `ToolSource` gain no
  members, and the ABI dump diff is additions only, so a plugin built against 26.40 loads
  unchanged.

  The registry and service defaults do nothing, and ai-core supplies the real behaviour. On an
  ai-core built before this change, listeners are accepted and never called. Floor
  `plugin.min_ide_version` at `26.41` to rely on the new members.

- **added — The AI prompt config engine and settings-pane helpers** _(ADFA-6281)_ **[verified]**
  Every AI plugin carried its own copy of the code that reads and renders its prompt
  config, and the credential screens their own copy of the reveal toggle and pane
  styling, so a fix had to be repeated per plugin and a missed copy made the plugins
  drift. The host now ships one copy.
  `com.itsaky.androidide.plugins.ai.prompt`: `PromptTemplateEngine` and `PromptText`
  (the `{{NAME}}` / `{{#NAME}}` / `{{^NAME}}` renderer; names may be in any case and hold
  dots, e.g. `{{fileName}}` or `{{item.name}}`, `{{{{` writes a literal `{{`, and config text keeps its whitespace as YAML
  parsed it); `PromptConfigLoader.load(source,
  parser)`, which reads `agent.yml` and its `include` list off the main thread;
  `PromptConfigDocument` and `PromptConfigObject`, the strict key-by-key reader a parser
  maps the merged YAML through; `PromptConfigSource` / `AssetPromptConfigSource`;
  `PromptConfigException`; and `PromptConfigStore<T>`, the per-activation cache, behind
  `PromptConfigProvider<T>`. Loader and store are generic over the plugin's config type:
  a plugin supplies only a `PromptConfigParser<T>` and keeps one store, e.g.
  `val shared = PromptConfigStore(MyParser)`, and calls `shared.reload(source, onLoaded, onFailed)`
  from `activate()` and `shared.clear()` from `deactivate()`. The YAML library (snakeyaml-engine 2.10)
  is on the host side, so a plugin using the loader no longer bundles it.
  `com.itsaky.androidide.plugins.ai.ui`: `SecretRevealController`, whose two states are
  each a `RevealToggle` (icon and content description), and
  `View.applyPaneStyling(PaneStyle, outlinedButtonIds)`, with `PaneStyle` grouping a
  `ButtonColors` per emphasis and a `FieldColors`. Both take the plugin's own resource ids
  rather than shipping any: they resolve against the view's context, which carries the
  plugin's resources, not the host's.
  Additive to the ABI (185 added lines in the dump, none removed), but no longer unused:
  AI-Core and the Gemini, Local and OpenAI agents now load and render their prompt config
  through `ai.prompt` and drop their private copies, and the Gemini, OpenAI and MCP
  settings screens use `ai.ui`. `LlmInferenceService.WebSearchBackend` (`canSearchWeb()`)
  lets a backend say whether a `web_search` request would be searched now; ai-core forces
  and offers its `web_search` tool only when it does. The `extraParams` keys both sides
  read are defined once, as `WebSearchBackend.EXTRA_PARAM_WEB_SEARCH` and
  `ToolCallingBackend.EXTRA_PARAM_REQUIRED_TOOL`. Floor
  `plugin.min_ide_version` at `26.41` to use any of it; an older IDE has none of these
  classes, and the plugin fails with `NoClassDefFoundError` on first use.
- **added — Read-only App Logs and IDE Logs** _(ADFA-6267)_ **[verified]**
  Plugins could read build output (`IdeBuildService.getBuildOutput()`) but not the App Logs
  or IDE Logs tabs, so an agent diagnosing a runtime crash had to ask the user to paste them.
  `IdeLogService.readLogs(LogSource, LogQuery): LogReadResult` returns the newest lines of
  either tab (`LogSource.APP` / `LogSource.IDE`), oldest first, as `LogEntry(level, text)`.
  `LogQuery` filters as the tab's filter bar does: `levels` (empty = all; a line with no
  known level always passes), `text` (case-insensitive substring of the rendered line, tag
  included), and `maxLines` (default 200, clamped to `1..1000`). A result is also capped at
  `LogQuery.MAX_CHARS` (131072 UTF-16 chars of line content, terminators not counted),
  dropping the oldest lines first; `truncated` says whether either bound left matching
  lines out, or cut the text of a single line longer than the cap. With no editor open or no log yet, the read returns
  `LogReadResult.EMPTY`, never a throw. The service has no clear or write method, and needs
  no permission: plugins run in-process under the IDE's uid, so a gate would disclose log
  access, not enforce it. Purely additive (the ABI dump diff is additions only). Floor
  `plugin.min_ide_version` at `26.41` to use it; an older IDE has no such service.
### 26.40 — 2026-09-29
- **added — An embedding capability a backend can declare** _(ADFA-6053)_ **[verified]**
  A backend that has an embedding model can now say so. The only embedding entry point
  before this was `LlmInferenceService.getEmbeddings(String, String)`, which addresses a
  backend by id and hands back a bare `float[]`: the caller learns neither which model
  produced the vector nor how long it is, and pays one round trip per text. Indexing a
  project is thousands of chunks, so that is thousands of requests, and a stored vector
  carries no provenance — swapping the model behind a backend silently degrades every
  vector already on disk instead of invalidating it.
  `LlmInferenceService.EmbeddingBackend extends LlmBackend` is an optional capability
  interface, like `ToolCallingBackend` and `HistoryCapableBackend`: implement it and the
  consumer finds it with `instanceof`, there is no flag to set. It declares
  `embed(List<String>)` returning `CompletableFuture<List<float[]>>` index-aligned with
  the input, `getEmbeddingDimensions()`, and `getEmbeddingModelId()` — the model's
  identity, not the backend's, because two models of equal width are mutually
  incomparable and a width check alone cannot detect a swap.
  The batch either completes whole or fails whole; it never yields a short list, a list
  padded with nulls, or a placeholder vector, so a caller can never store a partially-real
  batch. `embed` must not block the calling thread and must be safe for concurrent calls
  (indexing and a user's query can be in flight at once), and it snapshots the caller's
  list before returning, so a caller may reuse or clear its own list as soon as the call
  comes back; the caller owns the returned list and arrays outright. It reports every failure by
  completing the future exceptionally and throws synchronously only for a caller's own
  mistake — `NullPointerException` for a null argument or element, `IllegalArgumentException`
  for an empty list. The static `EmbeddingBackend.requireValidBatch(List<String>)` does
  those checks and returns the snapshot, so a backend calls it first in `embed`.
  Purely additive: a new interface with three new methods and one static helper, nothing
  existing changed (the ABI dump diff is seven added lines and no removals), so an already-built `.cgp` keeps
  loading and running against the refreshed jar. `getEmbeddings(String, String)` stays —
  the Vector-Search plugin is a live caller. Floor `plugin.min_ide_version` at `26.40` if
  you implement or consume `EmbeddingBackend`; an older IDE has no such type, and a
  consumer's `instanceof` against it there fails to resolve the class.
- **added — `SnippetContribution.language` accepts `kotlin`** _(ADFA-6189)_
  The host keys Kotlin snippets under `kt`, so a contribution declaring `kotlin` registered
  under a language nothing looks up and never appeared in a `.kt` file. The id is now
  lower-cased and `kotlin` is aliased to `kt`; `java`, `kt` and `xml` are unchanged.
  Accepted ids are `java`, `kt` (or `kotlin`) and `xml` — anything else registers but is
  never queried.

### 26.37 — 2026-09-08
- **added — Build provenance in every `.cgp`** _(ADFA-5394)_ **[verified]**
  A plugin artifact now records the commit it was built from, so a crash report or a
  support question can be traced back to source. Nothing in the pipeline carried a git
  revision before, and the autogenerated version was stamped from the wall clock, so two
  builds of one commit produced two different artifacts.
  Two new manifest placeholders, `${pluginVcsRevision}` and `${pluginBuildTimestamp}`,
  feed the `plugin.vcs_revision` and `plugin.build_timestamp` `meta-data` entries; the
  builder also writes `assets/cgp-build.properties` into the archive before signing, and
  the IDE shows both in the plugin details dialog and in the `active_plugins` context of
  every crash report. `PluginMetadata` gains `vcsRevision` and `buildTimestamp`, both
  nullable — a `.cgp` built before this change carries neither.
  Adding the two `meta-data` entries requires a builder jar from 26.36 or later: a
  manifest that references a placeholder the builder does not define fails the manifest
  merger outright. Existing manifests are unaffected, and both placeholders are always
  populated (`unknown` when nothing resolved), so referencing them can never fail on a
  new-enough builder.
  The autogenerated version keeps its shape; the revision is deliberately not folded into
  it, because the plugin list truncates a version past its third dot-segment. Opt in with
  `pluginBuilder { includeRevisionInVersion = true }` if you want it there anyway. See
  [PLUGIN_AUTHORING.md](PLUGIN_AUTHORING.md#provenance) for `revision_source`, the
  `+dirty` marker, and how to state a revision for a source-archive distribution.

- **breaking — `PluginMetadata.copy` gained two parameters** _(ADFA-5394)_ **[verified]**
  The two new fields are trailing and defaulted, so this is *Kotlin*-source-compatible:
  reading `metadata.version` or destructuring keeps working, and nothing needs editing.
  The constructor is safe on the binary ABI as well -- it carries `@JvmOverloads`, so the
  old 10-parameter `<init>` survives and a *Java* caller that constructs a
  `PluginMetadata` needs no source change.
  `copy` is the exception: it gained parameters rather than an overload, 10 to 12, so a
  plugin that *copies* a `PluginMetadata` and was compiled against an older `plugin-api`
  throws `NoSuchMethodError` there until it is recompiled, and a *Java* caller has to pass
  `vcsRevision` and `buildTimestamp` explicitly -- `null` for both reproduces the previous
  behaviour -- which is a source change, not just a recompile. `@JvmOverloads` also does
  not restore the synthetic default-argument constructor, so a *Kotlin* plugin that
  constructed a `PluginMetadata` while omitting a defaulted argument needs the recompile
  too. No plugin in `plugin-examples` is affected either way; plugins receive metadata
  rather than build it.
- **added — Dialogs and toasts from a floating window** _(ADFA-4500)_
  Show a dialog or a toast from a plugin tab the user has undocked into a floating
  window. An undocked fragment runs against a window context created for
  `TYPE_APPLICATION_OVERLAY`, and the platform requires every window added through it
  to carry that same type. A `Dialog` builds a `TYPE_APPLICATION` window and a `Toast`
  a `TYPE_TOAST` one, so both `AlertDialog.Builder(requireContext()).show()` and
  `Toast.makeText(requireContext(), ...)` throw `IllegalArgumentException` once the tab
  is floating — the toast after any work preceding it has already run.
  `PluginWindows.showDialog(Dialog)` applies the window type the dialog's context
  requires and shows it (`prepareDialog` does so without showing; build the dialog with
  `create()` rather than showing it from its builder).
  `PluginWindows.showToast(Context, CharSequence, Int)` posts the toast against the
  application context, which imposes no window type. Both keep the ordinary
  activity-backed behaviour while the plugin is docked, so one call site is correct in
  either state. Neither can be applied by the IDE
  on a plugin's behalf: `Window` has no theme attribute for its type, and a toast is
  posted by the system against whatever context built it.

### 26.36 — 2026-09-01
- **added — File-targeted editor save** _(ADFA-5259)_
  Save a named file's open buffer and find out whether the bytes actually landed.
  `saveCurrentFile` follows whichever tab the user has focused and returns as soon
  as a save is dispatched, so a plugin that edits one file and then persists it can
  save a different file, or read the file back before the write finishes.
  `IdeEditorService.saveFile(File): Boolean` (`suspend`, `default` returning
  `false`) resolves the editor by file, suspends until the write completes, and
  reports the on-disk outcome — `true` also when the buffer was already clean,
  `false` when the file has no open editor or the write failed. Throws
  `SecurityException` on an authorization failure (no `FILESYSTEM_WRITE`, or a path
  outside the plugin's allowed roots), as the other write methods do. Await it from
  a coroutine; a `runBlocking` bridge on the main thread deadlocks.

  Because an older IDE's `IdeEditorService` has no `saveFile` at all, calling it
  there fails with `NoSuchMethodError` at the call site — floor
  `plugin.min_ide_version` at `26.36` if you call it. The Kotlin `default` body is
  not a Java default method: `plugin-api` sets no `-Xjvm-default`, so the compiler
  emits an abstract interface method plus a `DefaultImpls` static (both visible in
  the ABI dump). It therefore rescues neither a caller nor an implementer compiled
  against an older `plugin-api` — the latter gets `AbstractMethodError`. Only the
  host's own implementers benefit, and they are recompiled with it.

- **added — Keystore-backed secret storage for plugins** _(ADFA-5269)_ **[verified]**
  A plugin that stores a credential can encrypt it with AES/GCM under a hardware-backed
  Android Keystore key instead of carrying its own copy of the cipher. Three AI plugins
  had already grown near-identical copies that were starting to diverge (only one zeroed
  its plaintext buffers, only one told "no secret stored" apart from "stored but no longer
  decryptable"), and the host's own `git-core/CryptoManager` is a fourth. Duplicating the
  source into each `.cgp` would not fix that: `compileOnly` against the host means one
  implementation in the process, loaded by the host's class loader.
  `KeystoreSecretStore(alias)` (`encrypt`, `decrypt`, `write`, `readAndMigrate`) and
  `KeystoreSecretStore.Stored` / `.Absent` / `.Value` / `.Unreadable` / `.Unavailable`. The
  `enc:v1:` marker it writes is deliberately **not** part of the surface: the on-disk format
  is the store's business, both formats are handled for the caller, and keeping it private is
  what leaves room for an `enc:v2:` later.
  Additions to the **class** are additive, because a plugin instantiates it rather than
  implementing it. `Stored` is the exception, and the one part of this entry that cannot grow
  quietly: it is a public **sealed** interface, so a fifth case makes every plugin's exhaustive
  `when` fail to compile, and a `.cgp` already built against four throws
  `NoWhenBranchMatchedException` at runtime. Any new `Stored` case needs a `breaking` row —
  including the `enc:v2:` state the paragraph above leaves room for, if it ever surfaces.
  `encrypt` throws `GeneralSecurityException` and nothing else: whatever the Keystore actually
  raises (`IOException` from `KeyStore.load`, the unchecked `ProviderException` from keygen) is
  wrapped in one, so catching the documented type is enough — an unwrapped throwable here
  reaches the host's crash handler as an IDE crash, not your plugin's error path.
  `readAndMigrate` keeps a lost key (`Unreadable` — ask the user for the secret again) apart
  from a Keystore that would not answer (`Unavailable` — retry), so a transient failure does
  not cost the user a credential that is still perfectly readable. That split covers the cipher
  step as well as key acquisition, and it enumerates the *permanent* failures rather than the
  transient ones: a wrong key, an altered payload, a malformed one, an invalidated or
  unrecoverable key are `Unreadable`, and anything else the Keystore surfaces — a dead binder, a
  busy backend — is `Unavailable`. (Asking the platform is not an option here: `BackendBusyException`
  is API 31+ and `KeyStoreException.isTransientFailure()` API 33+, against `minSdk 28`.)
  A blank stored value is no credential, and all three entry points agree about the same bytes on
  disk: `write` forgets one rather than storing it, `readAndMigrate` purges it and reports
  `Absent`, `decrypt` returns null. (`encrypt`/`decrypt` used as a bare codec still round-trip
  `""`; the rule is about what is *stored*.)
  Every one of the four methods does Keystore binder IPC, so **call them off the main thread** —
  `decrypt` and `write` additionally share one alias-scoped lock, and `write` holds it across a
  synchronous flush.
  The `alias` is a constructor parameter and must stay **distinct per
  plugin**: plugins share the host's process, UID and therefore its Keystore, so a shared
  alias would let one plugin's invalidated-key recovery (`deleteEntry`) destroy another's
  stored secret. It must also stay stable across releases, since a secret encrypted under
  one alias cannot be read under another. `readAndMigrate` re-encrypts a legacy plaintext
  value in place, so a plugin adopting this keeps working for users who configured a
  credential before it existed.

### 26.33 — 2026-08-12
- **added — Plugin-contributed agent tools** _(ADFA-2592)_ **[verified]**
  Any `.cgp` can add tools to the AI agent, whose tool set was previously fixed at
  ai-core compile time. The contract has to live in the host: each plugin is loaded
  by its own class loader with the host as parent, so a type packaged in one `.cgp`
  is not resolvable from another — and duplicating it into each plugin compiles
  cleanly, then fails on device with `ClassCastException`. ai-core implements the
  registry and publishes it under `SharedServices`, exactly as it does
  `LlmInferenceService`; a provider registers on `activate()` and unregisters on
  `deactivate()`. Host runtime behaviour, `PluginManager`, the loader and
  `PluginPermission` are unchanged — a provider declares the permissions its own
  work needs.
  `ToolSourceRegistry` (`registerToolSource`, `unregisterToolSource`,
  `getToolSources`, `notifyToolsChanged`, `CONTRACT_VERSION`),
  `ToolSourceRegistry.ToolSource` / `.ToolSpec` / `.ToolInvocation` / `.ToolOutcome`.
  Values crossing this boundary must be JDK types, and the registry hands each
  source a sanitized copy of the argument map rather than its own.
  `unregisterToolSource` takes the `ToolSource` instance, not a provider id, so a
  reused provider id cannot remove another plugin's source — but the registry is
  no trust boundary between plugins: `getToolSources` hands out the registered
  instances and registering under a taken id replaces it. `ToolSpec.requiresApproval()`
  defaults to **true**, inverted relative to the agent's own tools: those are
  contained by its path guard, a contributed tool by nothing.
- **added — Optional LLM backend capabilities** _(ADFA-5095)_ **[verified]**
  An LLM backend declares what it supports by the interfaces it implements, so a
  backend can ship as its own plugin and implement only what it can do. The
  consumer asks with `instanceof` before it calls; a backend that implements none
  of these is still a valid `LlmBackend`.
  `LlmInferenceService.HistoryCapableBackend` (`generateStreamingWithHistory`),
  `ToolCallingBackend` (`generateStreamingWithTools`),
  `CancellableBackend` (`cancelStreaming`),
  `ConfigurableBackend` (`getSettingsFragmentClassName` — the backend's own
  settings `Fragment`, loaded with the backend's classloader).
- **added — Backend-owned prompt and sampling** _(ADFA-5095)_ **[verified]**
  A backend supplies the system prompt and temperature its model needs, instead of
  the consumer hardcoding them per provider. Both are `default` and return null
  for "no preference"; `getDefaultTemperature()` is a boxed `Float`, so null-check
  before assigning it to the primitive `LlmConfig.temperature`.
  `LlmBackend.getSystemPrompt(SystemPromptRequest)`,
  `LlmBackend.getDefaultTemperature()`, `SystemPromptRequest`.
- **breaking — Tool results correlated by call id and tool name** _(ADFA-5095)_ **[verified]**
  A tool's output travels back into the next turn as a message of its own, so a
  turn's several calls are matched by correlator rather than by position. Both
  correlators travel with the result because providers key results differently —
  by call id, or by function name — and a backend can only forward what it was
  given.
  `ChatMessage.toolResult(String, String, String)`, `ChatMessage.toolCallId` /
  `toolName`, `ChatMessage.Role.TOOL`.
  **What breaks:** `Role` gains a fourth constant, so an exhaustive Kotlin `when`
  over it with no `else` stops compiling. A plugin already built against the
  three-constant enum has the worse failure: the `when` throws
  `NoWhenBranchMatchedException` with a null message, which reads as an
  unattributable crash inside the plugin rather than as anything to do with
  `Role`. A `TOOL` message reaches a backend that never calls `toolResult` — the
  consumer builds it and passes it in the history — so handling it is not
  optional for backends. **What to do:** add a `TOOL` branch (routing it as a
  user turn is fine for a backend with no native function calling) and republish;
  a `.cgp` that is only reinstalled, not rebuilt, stays exposed.
- **added — Preferred backend id** _(ADFA-5095)_ **[verified]**
  A backend can ask which backend the user selected, so one that would otherwise
  spend seconds and gigabytes preparing itself knows whether it is about to be
  used — without reading another plugin's preferences.
  `LlmInferenceService.getPreferredBackendId()` (`default`, null when unset).
- **breaking — Nullability annotated across the LLM surface** _(ADFA-5095)_
  Every parameter, return and field on `LlmInferenceService` and the types nested
  in it now carries `@NonNull` or `@Nullable`, so the contract is stated rather
  than inferred.
  **What breaks:** an unannotated Java type reaches Kotlin as a platform type
  (`String!`) that dereferences without a check; annotated `@Nullable` it becomes
  `String?`, and every existing dereference stops compiling with "only safe (?.)
  or non-null asserted (!!.) calls are allowed". This hits **callers**, not just
  implementors — `LlmResponse.text` / `.error`, `ToolCallRequest.args` and
  `ToolDefinition.parametersSchema` are the ones consumers touch, and
  `@NonNull` across `LlmBackend` tightens what an implementor may return.
  Bytecode is unchanged, so an installed `.cgp` keeps running; the break is at
  compile time in the plugin repo. **What to do:** `?.`, `.orEmpty()` or an
  explicit null check at each site — the annotations describe values the API
  could already return.

### 26.31 — 2026-07-29
- **tooling — Plugin API & builder resolvable by Maven coordinate on-device** _(ADFA-4911)_
  The plugin API and the builder Gradle plugin are injected into the on-device
  local Maven repository at onboarding, so a plugin resolves them by coordinate,
  offline, without committing `libs/*.jar`:
  `compileOnly("com.itsaky.androidide:plugin-api:1.0.0")` and
  `plugins { id("com.itsaky.androidide.plugins.build") version "1.0.0" }`.
  `plugin-api:1.0.0` bundles `:plugin-api` + `common` + `eventbus-events` +
  `idetooltips`. (No-`libs/` project detection lands separately in ADFA-4913.)

### 26.30 — 2026-07-20
- **added — Project-search providers** _(ADFA-4723, `88c20f3`)_ **[verified]**
  Plugins contribute their own project-search sources that render as dedicated
  result sections.
  `ProjectSearchExtension.searchProject(ProjectSearchRequest): CompletableFuture`,
  `ProjectSearchRequest`, `ProjectSearchResult`, `ProjectSearchSection`.

### 26.29 — 2026-07-14
- **added — Cross-plugin services & lifecycle** _(ADFA-4584, `875853d`)_ **[verified]**
  Register services other plugins consume, observe plugin lifecycle, query
  whether a plugin is active or its version, read app preferences.
  `PluginContext.registerService` / `unregisterService` / `getPluginService` /
  `getProvidedServices` / `addPluginLifecycleListener` / `isPluginActive` /
  `getPluginVersion` / `getAppSharedPreferences`, `PluginLifecycleListener`,
  `SharedServices`.
- **added — Editor inline suggestions (ghost text)** _(ADFA-4584, `875853d`)_ **[verified]**
  Show/dismiss inline completions and observe editor content changes.
  `IdeEditorService.showInlineSuggestion` / `dismissInlineSuggestion` /
  `addContentChangeListener`, `EditorContentChangeListener`.
- **added — Dynamic toolbar icons** _(ADFA-4584, `875853d`)_ **[verified]**
  `UIExtension.getIconProvider` / `setIconProvider`, `IdeUIService.refreshToolbarActions()`.

### 26.28 — 2026-07-07
- **added — Per-module context & task execution** _(ADFA-4582, `cc2f592`)_ **[verified]**
  Resolve a context for a specific Gradle module and run build tasks against it.
  `IdeProjectService.getModuleContext(String): ModuleContext`,
  `IdeBuildService.executeTasks(vararg String): CompletableFuture`.
- **tooling — API-stability baseline** _(ADFA-3588, `440e7dd`)_ **[verified]**
  First release where the whole public surface is frozen into a checked-in ABI
  dump (`plugin-api/api/plugin-api.api`) guarded by the binary-compatibility
  validator. From here on every API change is a reviewable diff. Adds the
  `@InternalPluginApi` opt-out marker.

### 26.18 — 2026-04-23
- **added — Archive & environment services** _(ADFA-3787, `88d2f4a`)_ **[reconstructed]**
  Extract archives (xz/gzip/tar/zip), locate IDE-managed directories (SDK, NDK,
  home, tmp), write binary/streamed files. Adds the `ide.environment.write`
  permission.
  `IdeArchiveService`, `IdeEnvironmentService`, `ArchiveFormat`, `ExtractResult`,
  `PluginPermission.IDE_ENVIRONMENT_WRITE`,
  `ResourceManager.openPluginResource` / `openPluginAsset`,
  `IdeFileService.writeBinary` / `writeStream` / `delete`.

### 26.17 — 2026-04-21 / 2026-04-17
- **added — Day / night plugin icons** _(ADFA-3694, `e5383d2`)_ **[reconstructed]**
  Ship separate light/dark icons; the manager renders the one matching the theme.
  Manifest keys `plugin.icon_day` / `plugin.icon_night`;
  `PluginMetadata.iconDayPath` / `iconNightPath`.
- **added — Code snippets** _(ADFA-3546, `683b551`)_ **[reconstructed]**
  Contribute reusable snippets in TextMate syntax.
  `SnippetExtension`, `IdeSnippetService`, `SnippetContribution`.

### 26.16 — 2026-04-13
- **added — Build actions & custom commands** _(ADFA-3580, `98b9ba1`)_ **[reconstructed]**
  Contribute actions to the build toolbar; run shell commands or Gradle tasks
  with streamed output; includes the toolbar-action IDs a plugin may hide.
  `BuildActionExtension`, `IdeCommandService`, `CommandExecution`,
  `PluginBuildAction`, `BuildActionCategory`, `ToolbarActionIds`,
  `CommandSpec` (`ShellCommand` / `GradleTask`), `CommandResult`.

### 26.14 — 2026-03-29 / 2026-03-26
- **added — Project-template contribution** _(#1122, `93ae25c`)_ **[reconstructed]**
  Contribute new-project templates in the `.cgt` format.
  `IdeTemplateService`, `CgtTemplateBuilder`.
- **added — Feature-flag access** _(ADFA-2808, `d924652`)_ **[reconstructed]**
  `IdeFeatureFlagService.isExperimentsEnabled()`.

### 26.12 — 2026-03-12
- **added — File-open handling** _(ADFA-3162, `0681d66`)_ **[reconstructed]**
  Intercept file opens and contribute file-tab menu items — the basis for custom
  viewers like the APK viewer.
  `FileOpenExtension` (`canHandleFileOpen` / `handleFileOpen` / `getFileTabMenuItems`),
  `FileTabMenuItem`.

### 26.09 — 2026-02-17
- **added — Material 3 theming** _(ADFA-1718, `a004fc5`)_ **[reconstructed]**
  Read the active theme and react to theme changes.
  `IdeThemeService`, `ThemeChangeListener`.

### 26.02 — genesis (2025-09-24) + sidebar slots (2025-12-01)
- **added — Sidebar slots** _(ADFA-2139, `9fa0f17`)_ **[reconstructed]**
  Declare how many sidebar slots a plugin needs and query availability.
  `IdeSidebarService` (`getAvailableSidebarSlots` / `canAddSidebarItems` /
  `getMaxSidebarItems`), manifest key `plugin.sidebar_items` (Int).
- **added — Plugin system foundation (genesis)** _(#406, `6fdbe8e`)_ **[reconstructed]**
  The plugin system itself: lifecycle, the context + service registry handed to
  every plugin, the first extension points and core IDE services, and the
  manifest contract including `plugin.min_ide_version` / `plugin.max_ide_version`
  (present from day one).
  `IPlugin` (`initialize` / `activate` / `deactivate` / `dispose`),
  `PluginContext`, `ServiceRegistry`, `ResourceManager`, `PluginLogger`,
  `PluginMetadata`, `PluginPermission`, `UIExtension`, `EditorExtension`,
  `EditorTabExtension`, `ProjectExtension`, `DocumentationExtension`,
  `IdeProjectService`, `IdeEditorService`, `IdeUIService`, `IdeBuildService`,
  `IdeFileService`, `IdeEditorTabService`, `IdeTooltipService`,
  and the manifest `<meta-data>` contract.

## Caveats

- **Pre-26.28 has no ABI dump.** The validator arrived in 26.28. Earlier entries
  (`[reconstructed]`) were recovered by diffing each `plugin-api/src` commit — the
  symbol lists are accurate, but read from source rather than a frozen contract.
- **Early weeks collapse onto 26.02.** The oldest release tag is `26.02`, so both
  the September genesis and the December sidebar work report `26.02` as their
  first shipped version — not because they were written that week, but because no
  earlier release was ever tagged. A plugin can safely floor at `26.02` for
  anything in that band.
- **This is a history, not a compatibility guarantee.** The plugin API is
  deliberately still evolving — see [plugin-api.md](plugin-api.md).
- App-side/manager-only symbols (e.g. `IdeNavigationRailView`, `PluginValidation`,
  `.codeonthego/scripts.json`) are intentionally omitted — plugins don't compile
  against them.

## Regenerating this doc

The source of truth is the ABI dump, so each new release's additions can be listed
mechanically. For the window between two release tags:

```bash
git log --oneline 26.29..26.30 -- plugin-api/api/plugin-api.api
git diff 26.29 26.30 -- plugin-api/api/plugin-api.api
```

Map any commit to the release that first shipped it:

```bash
git tag --list --contains <sha> | grep -E '^[0-9]{2}\.[0-9]{2}$' | sort -V | head -1
```

When a release is tagged, replace its bucket's `unreleased` with the tag date and move
any entry the tag does not contain up to the next bucket.
