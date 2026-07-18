# Architecture

> Audience: engineers working in this repository. This describes how the code is *actually* organized today, including where the codebase is mid-migration. Patterns that are the agreed target for new code are called out explicitly.

## Overview

Code On The Go (CoGo) is a full Android IDE that runs **on the device** — it edits, builds, and deploys real Android apps offline, embedding a Termux toolchain and running an actual Gradle build in a separate process via the `tooling-api`. It is the maintained successor to AndroidIDE, so the codebase namespace is still `com.itsaky.androidide`.

There is **no single architectural philosophy** across the whole app. This large, layered application is still **predominantly View-based**: newer feature surfaces (plugin manager, AI agent, git, project list) follow a deliberate **Unidirectional Data Flow (UDF)** with Koin DI, `ViewModel` + `StateFlow`, sealed UI-state/effect types, and repositories, while older surfaces still use `LiveData` and talk to GreenRobot EventBus directly. New work follows the UDF pattern documented below, and new UI is built in **Jetpack Compose** ([ADR 0009](docs/adr/0009-jetpack-compose-for-new-ui.md)) — Compose replaces the view layer only; the UDF stack (ViewModel + `StateFlow`, Koin, repositories) is unchanged. Existing XML/View screens remain until substantially reworked.

## Core Architecture & Data Flow

Feature code layers as **UI → ViewModel → Repository → data source**, with state flowing up and events/intents flowing down. Koin provides dependencies (`coreModule`, `pluginModule`), constructor-injected into ViewModels.

- **Data sources** — Room (`RecentProjectRoomDatabase` + DAO, `suspend` functions), raw SQLite (`SQLiteOpenHelper`, e.g. `localWebServer/WebServer`), the filesystem/preferences, the embedded `tooling-api` (on-device Gradle), and external clients (Gemini via the Google GenAI SDK, on-device llama.cpp, JGit). Most are exposed through `suspend` functions.
- **Repositories** — e.g. `agent/repository/GeminiRepository`, `repositories/PluginRepository`, `repositories/BreakpointRepository`. They wrap data sources and hide threading/IO from the ViewModel.
- **ViewModels** — run work in `viewModelScope` on `Dispatchers.IO`, hold a private `MutableStateFlow`/`MutableSharedFlow`, and expose read-only `StateFlow`/`SharedFlow`. One-shot effects (toasts, navigation, dialogs) go through a separate `SharedFlow` of a sealed `*UiEffect` type.
- **UI (Fragments / Activities / Views)** — collect state in a lifecycle-aware coroutine and render it; user actions return to the ViewModel as method calls or sealed `*UiEvent` intents. The existing UI is **Android Views + Fragments + RecyclerView adapters**; new UI is Jetpack Compose ([ADR 0009](docs/adr/0009-jetpack-compose-for-new-ui.md)). (`compose-preview` previews the *user's* Compose code, not CoGo's own.)

```
                ┌─────────────────────────────────────────────┐
   intents ↓    │                  UI LAYER                    │   ↑ state
 (UiEvent /     │   Activity / Fragment / RecyclerView.Adapter │   (StateFlow)
  method call)  │   collects StateFlow, renders; emits intents │   ↑ effects
                └───────────────┬─────────────────────────────┘   (UiEffect SharedFlow)
                                │
                ┌───────────────▼─────────────────────────────┐
                │               VIEWMODEL LAYER                │
                │  viewModelScope + Dispatchers.IO             │
                │  private MutableStateFlow ──► StateFlow      │
                │  private MutableSharedFlow ─► UiEffect flow  │
                │  sealed UiState / UiEvent / UiEffect / State │
                └───────────────┬─────────────────────────────┘
                                │ suspend calls
                ┌───────────────▼─────────────────────────────┐
                │               REPOSITORY LAYER               │
                │  GeminiRepository / PluginRepository / ...   │
                └───────────────┬─────────────────────────────┘
                                │
        ┌───────────────────────┼────────────────────────────────────┐
        ▼                       ▼                                      ▼
  ┌───────────┐         ┌───────────────┐                    ┌──────────────────┐
  │  Room /   │         │ tooling-api   │                    │ External clients │
  │  SQLite / │         │ (on-device    │                    │ Gemini · llama   │
  │  FS / prefs│        │  Gradle build)│                    │ · JGit           │
  └───────────┘         └───────────────┘                    └──────────────────┘

  Cross-cutting: GreenRobot EventBus carries decoupled, app-wide events
  (build progress, install results, editor signals) outside the UDF spine.
```

**EventBus is a deliberate side-channel.** Long-running, cross-module signals (build/install lifecycle, editor events) are broadcast via GreenRobot EventBus (`@Subscribe(threadMode = ThreadMode.MAIN)`) and the `eventbus-events` module's shared event types. Treat it as the integration bus *between* subsystems; don't use it to replace a ViewModel's own state inside a single screen.

## Module Structure

Strategy: **layer-and-subsystem based**, not feature-by-feature. The Gradle build has ~80 modules (`settings.gradle.kts`) plus three included composite builds. `app` is the integration point; the rest are libraries it composes.

| Group | Modules | Responsibility |
|---|---|---|
| Application | `app` | The IDE itself — activities, fragments, services, DI, agent, web server. Wires everything together. |
| Build engine | `subprojects:tooling-api*`, `gradle-plugin*`, `subprojects:projects`, `subprojects:builder-model-impl` | Runs a real Gradle build of the user's project out-of-process and streams events back. |
| Quick Build (experimental, ADFA-4128) | `quick-build`, `quickbuild-daemon`, `quickbuild-runtime` | Build-engine-adjacent live-reload fast path: session/watcher/deploy service in `quick-build` (CoGo side), warm incremental compile daemon (`quickbuild-daemon`, pure JVM), and the hot-reload runtime AAR in the generated test app (`quickbuild-runtime`, no CoGo deps). Seams into the Build engine: the setup build IS `gradle-plugin`'s `QuickBuildPlugin` (same init-script injection), hand-back listens to `GradleBuildService` build events, and install reuses the Run path. Separate module group while experimental to keep blast radius small. Boundary decision: [ADR 0010](docs/adr/0010-quick-build-fast-path-boundary.md); module-local design decisions: `quick-build/README.md`. |
| Language tooling | `lsp:{api,java,kotlin,xml,indexing,…}`, `lexers`, `editor*`, `editor-treesitter` | Language servers, indexing, the Sora-based editor and highlighting. |
| UI design tooling | `layouteditor`, `uidesigner`, `xml-inflater`, `vectormaster`, `compose-preview` | Visual/XML design surfaces for the *user's* app. |
| Shell | `termux:{termux-app,termux-shared,termux-view,termux-emulator}` | Embedded Termux shell and terminal. |
| Plugin system | `plugin-api`, `plugin-api:plugin-builder`, `plugin-manager` | In-app plugin SDK + manager — `AndroidManifest.xml` `<meta-data>` contract, permissions, extensions. See [plugin-api.md](docs/plugin-api.md) for the API surface & compatibility policy. |
| On-device AI | `llama-api`, `llama-impl` | llama.cpp integration, shipped as a per-flavor native AAR. |
| Cross-cutting | `eventbus`, `eventbus-android`, `eventbus-events`, `common`, `common-ui`, `logger`, `resources`, `preferences`, `shared` | Shared infra and the event bus. |
| Testing | `testing:{android,unit,lsp,tooling,common}` | Shared test harnesses, split by what's under test. |

**Dependency rules (enforced):**
- **`app` depends inward; libraries never depend on `app`.** Subsystems are consumed by `app`, not vice versa.
- **Vendored forks are substituted, not imported ad hoc.** `composite-builds/build-deps` and `build-deps-common` provide forked `javac`/`jdt`/`layoutlib`/etc.; `settings.gradle.kts` substitutes them in for `com.itsaky.androidide.build:*`. Don't add a Maven coordinate for something already substituted.
- **All module config flows through `composite-builds/build-logic`.** Every Android module gets the `v7`/`v8` ABI flavors centrally (`AndroidModuleConf.kt`) — there is no flavorless `assembleDebug`. `:plugin-api` is intentionally excluded from flavors.
- **`RepositoriesMode.FAIL_ON_PROJECT_REPOS`** — repositories are declared once in `settings.gradle.kts`; modules must not declare their own.
- **Avoid new dependencies.** Check `gradle/libs.versions.toml` first; the build almost certainly already has it.

## Build & Module Configuration

These structural facts shape every module. Day-to-day build *commands* live in `CLAUDE.md`; the rules that produce them live here.

- **Centralized convention logic.** All Android module setup flows through `composite-builds/build-logic` (`conf/AndroidModuleConf.kt`). Modules stay thin and share configuration, so understanding any module's setup starts here.
- **ABI product flavors.** Every Android module *except* `:plugin-api` gets two flavors on the `abi` dimension — `v7` (`armeabi-v7a`) and `v8` (`arm64-v8a`) — defined centrally. There is no flavorless variant; tasks are `assembleV8Debug`, `assembleV7Release`, etc.
- **SDK levels** (`build-logic/.../build/config/BuildConfig.kt`): `COMPILE_SDK=36`, `MIN_SDK=28`, `TARGET_SDK=28`. **`TARGET_SDK` is deliberately pinned at 28:** higher targets enforce W^X (write-xor-execute), which blocks executing code from app-writable files. That is fatal for an on-device IDE that compiles and runs code (Gradle, `javac`, Termux binaries), so it is a hard requirement, not tech debt. `MIN_SDK_FOR_APPS_BUILT_WITH_COGO=16` is the floor for the apps a *user* builds with CoGo — distinct from CoGo's own `MIN_SDK`.
- **Native asset bundling.** The on-device LLM (`llama-impl`) ships as a per-flavor native AAR, wired through the root `build.gradle.kts` (`bundleLlamaV8Assets` / `assembleV8Assets`, …); prebuilt per-flavor assets live under `assets/release/v7/` and `assets/release/v8/`.
- **Native lib compression** (ADFA-2306, ADFA-4729). The app manifest hard-codes `android:extractNativeLibs="true"` (required: the installer must materialize libs in `nativeLibraryDir`, e.g. `libshizuku.so` is an executable the adb shell runs from there). That attribute overrides the `jniLibs.useLegacyPackaging` DSL, so AGP packages `lib/<abi>/*.so` deflate-compressed in **every** APK — ~5.9 MB smaller (`libtree-sitter-kotlin.so` alone is 4.18 MB → 339 kB). The trap is the `recompressApk` post-step (release always, debug in CI only): its no-compress lists in `app/build.gradle.kts` must NOT contain `"so"`, or it silently re-stores the libs and undoes the saving — which is what ADFA-2306 fixed for release and ADFA-4729 for CI debug. Locally built debug APKs (including the e2e farm's) never run that step and were always fine.
- **`app` package layout is by concern, not feature:** `activities`, `fragments`, `services`, `di`, `agent`, `viewmodel(s)`, `repositories`, `roomData`, `localWebServer`, `preferences`, `ui`, `utils`, ….

## Technology Stack

| Concern | Library / Approach |
|---|---|
| UI | **Jetpack Compose for all new UI** ([ADR 0009](docs/adr/0009-jetpack-compose-for-new-ui.md)). The existing majority is still Android Views + Fragments + `RecyclerView` (Material Components); those legacy screens stay until reworked, but new IDE UI is Compose-only. |
| Dependency Injection | **Koin** (`org.koin`) — `coreModule`/`pluginModule`, `startKoin` in `IDEApplication`, plus a `ServiceLocator : KoinComponent` for lazy post-startup access. No Hilt/Dagger. |
| Asynchronous work | **Kotlin Coroutines + Flow** (`StateFlow`/`SharedFlow`, `viewModelScope`, app-scoped `CoroutineScope(SupervisorJob() + Dispatchers.IO)`); **GreenRobot EventBus** for cross-subsystem events. |
| Networking | Offline-first; no general REST layer. External I/O is **Google GenAI SDK** (Gemini), **on-device llama.cpp**, and **JGit** (git). Retrofit is in the catalog but effectively unused in app code. |
| Database / Persistence | **Room** is the default for relational/queryable data; **filesystem + preferences (DataStore)** for non-relational settings. **Raw SQLite** (`SQLiteDatabase` / `SupportSQLiteOpenHelper`) only for justified exceptions (see policy below). |
| Serialization | `kotlinx.serialization` and Gson. |
| Parceling | Kotlin **`@Parcelize`** (`kotlin-parcelize` plugin) for `Parcelable` data classes — never hand-implement `Parcelable`. Do it manually only if `@Parcelize` genuinely can't express it (custom serialization logic, unsupported member types). |
| AI agent | Google GenAI (cloud) + llama (local), behind `GeminiRepository` / `SwitchableGeminiRepository`, with planner/critic/executor agents in `agent/repository`. |

> **Persistence policy (authoritative):** new relational/queryable persistence uses **Room** (`@Entity` + DAO + `RoomDatabase` with explicit migrations, provided via Koin). Non-relational settings use the **filesystem/preferences (DataStore)**. **Raw SQLite is the exception, not the default** — see [ADR 0001](docs/adr/0001-prefer-room-for-persistence.md).
>
> **Recent Projects** is the reference example of the default: `app/src/main/java/com/itsaky/androidide/roomData/recentproject/` (`RecentProjectRoomDatabase`, `@Database version = 4` with migrations 1→4; `RecentProjectDao`; the `RecentProject` `@Entity` → table `recent_project_table`). It's provided via Koin in `di/AppModule.kt` and consumed by `MainViewModel`, `RecentProjectsViewModel`, `MainActivity`, `ProjectInfoBottomSheet`, and `ProjectCreationManager`.
>
> **Raw SQLite is allowed only when** the database is prebuilt and opened read-only, the data is performance/allocation-critical and needs granular schema control, or the schema is shared across a process/component boundary. Current exceptions: symbol indexing (`lsp/indexing/SQLiteIndex.kt`), tooltips (`idetooltips/ToolTipManager.kt`), in-app/plugin help (`plugin-manager/.../documentation/PluginDocumentationManager.kt`), and the local web server (`app/.../localWebServer/WebServer.kt`). `idetooltips` also declares unused Room Gradle deps (remove them), and the `androidx.room:*` strings in `editor`'s `GroovyAutoComplete` are autocomplete suggestions for the *user's* code, not CoGo persistence.

## State Management

- **Make illegal states unrepresentable.** When a screen's states are **mutually exclusive** (loading / content / success / error / installing …), model them as a **sealed interface/class** — *not* a `data class` carrying several `Boolean`s that can contradict each other ("boolean hell", state explosion). A single immutable `data class` is right only when the fields are genuinely **independent**.
- **Either shape** is exposed as a `StateFlow<…UiState>`; the ViewModel mutates a private `MutableStateFlow` — `update { it.copy(...) }` for a data class, or emit the next subtype for a sealed state. Derived flags live as computed properties (data class) or are implied by the subtype (sealed), so the UI stays dumb.
- **One-shot effects** (errors, navigation, dialogs, restart prompts) are modeled as a **sealed `…UiEffect`** emitted through a separate `SharedFlow` — never folded into the persistent state, so they don't replay on rotation.
- **Inbound intents** are a sealed `…UiEvent` (or direct ViewModel method calls on older screens).
- **Process/long-task state** uses dedicated sealed hierarchies — e.g. `BuildState`, `TaskState`, `InstallationState`, `ApkInstallationViewModel.SessionState`, `agent/AgentState`.
- **Legacy screens** still expose `LiveData` (~8 ViewModels) instead of `StateFlow` (~20). When touching one substantially, prefer migrating it to `StateFlow`.

Two shapes, chosen by whether the states are mutually exclusive.

**Mutually-exclusive states → sealed** (real example, `git-core/.../CloneRepoUiState.kt`) — the default when a screen moves through phases:

```kotlin
sealed interface CloneRepoUiState {
    data class Idle(val url: String = "", val localPath: String = "", val isCloneButtonEnabled: Boolean = false) : CloneRepoUiState
    data class Cloning(val url: String, val localPath: String, val cloneProgress: String = "", val clonePercentage: Int = 0) : CloneRepoUiState
    data class Success(val localPath: String) : CloneRepoUiState
    data class Error(val url: String, val errorMessage: String? = null, val canRetry: Boolean = false) : CloneRepoUiState
}
// The UI `when`s over the state — Idle/Cloning/Success/Error can never be true at once.
```

**Independent fields → a single immutable `data class`** (example, `ui/models/PluginManagerUiState.kt`). ⚠️ The booleans below (`isLoading`/`isInstalling`/`isEmpty`) only work because they're *independent*; the moment states become exclusive, switch to a sealed type rather than adding more flags:

```kotlin
// Persistent, immutable UI state — exposed as StateFlow<PluginManagerUiState>
data class PluginManagerUiState(
    val isLoading: Boolean = false,
    val plugins: List<PluginInfo> = emptyList(),
    val isPluginManagerAvailable: Boolean = false,
    val isInstalling: Boolean = false,
) {
    val isEmpty: Boolean get() = plugins.isEmpty() && !isLoading           // derived in state
    val showEmptyState: Boolean get() = isEmpty && isPluginManagerAvailable
}

// Inbound intents from the UI
sealed class PluginManagerUiEvent {
    object LoadPlugins : PluginManagerUiEvent()
    data class EnablePlugin(val pluginId: String) : PluginManagerUiEvent()
    data class InstallPlugin(val uri: Uri, val deleteSourceAfterInstall: Boolean) : PluginManagerUiEvent()
    // ...
}

// One-shot effects — emitted via a SharedFlow, not stored in UiState
sealed class PluginManagerUiEffect {
    data class ShowError(@StringRes val messageResId: Int, val formatArgs: List<Any> = emptyList()) : PluginManagerUiEffect()
    object ShowRestartPrompt : PluginManagerUiEffect()
    // ...
}
```

Typical ViewModel wiring:

```kotlin
private val _uiState = MutableStateFlow(PluginManagerUiState())
val uiState: StateFlow<PluginManagerUiState> = _uiState.asStateFlow()

private val _effects = MutableSharedFlow<PluginManagerUiEffect>()
val effects: SharedFlow<PluginManagerUiEffect> = _effects.asSharedFlow()

fun onEvent(event: PluginManagerUiEvent) = viewModelScope.launch(Dispatchers.IO) {
    when (event) {
        PluginManagerUiEvent.LoadPlugins -> {
            _uiState.update { it.copy(isLoading = true) }
            val plugins = repository.loadPlugins()
            _uiState.update { it.copy(isLoading = false, plugins = plugins) }
        }
        // ...
    }
}
```

## Testing Guidelines

Test code lives both alongside each module and in the shared `testing:{unit,android,lsp,tooling,common}` harnesses. Run with the flox wrapper, e.g. `flox activate -d flox/local -- ./gradlew :testing:unit:test` or a module's `:module:test --tests "…"`.

| Layer | Runner / Tools | What to test |
|---|---|---|
| Unit (pure JVM) | **JUnit Jupiter (5)**, some legacy **JUnit 4**; assertions via **Google Truth**; mocking via **MockK** (primary) and **Mockito-Kotlin** (legacy) | ViewModels (state transitions over a fake repository), repositories, parsers, builder/tooling logic. Keep these off the device. |
| JVM + Android framework | **Robolectric** | Code needing `Context`/resources/`SQLiteOpenHelper` without an emulator. |
| Instrumented / UI | **Espresso** + **AndroidX Test** + **UiAutomator**, run under **Test Orchestrator**; `mockk-android` for on-device mocks | End-to-end IDE flows (create/build/deploy, editor, terminal). |

Preferences and conventions:
- **Assertions: Google Truth** (`assertThat(x).isEqualTo(...)`) over raw JUnit asserts.
- **Mocking: MockK** for new code; relax it deliberately rather than over-stubbing.
- For UDF ViewModels, drive `onEvent(...)`/method calls against a fake or mocked repository and assert the emitted `UiState` sequence (collect the `StateFlow`); assert effects by collecting the effect `SharedFlow`.
- On the M2 emulator, prefer short `flakySafely` timeouts (2–3s) and `AccessibilityNodeInfo.ACTION_CLICK` over raw coordinate taps — the bottom system-bar region swallows coordinate clicks, and tests must never obstruct the two system bars (top status, bottom nav).
```
