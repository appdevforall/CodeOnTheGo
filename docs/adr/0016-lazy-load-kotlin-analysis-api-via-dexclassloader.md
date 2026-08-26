# 0016. Lazy-load the Kotlin Analysis API via a carrier APK + DexClassLoader

- **Status:** Proposed
- **Date:** 2026-08-04
- **Deciders:** Code On The Go team

## Context

Per ADFA-4549's release-build DEX analysis, the embedded Kotlin Analysis API accounted for the largest single share of the app's DEX: tens of MiB and tens of thousands of classes, more than everything else in the app combined. It was always resident: merged into `app`'s `classes*.dex` by D8 at build time, and loaded/verified on every app run whether or not the user ever opened a Kotlin file.

What's actually embedded is not a build-time compiler. Full Gradle builds run real Gradle out-of-process via the Tooling API ([ADR 0002](0002-on-device-builds-via-gradle-tooling-api.md)) and never touch this dependency. What's embedded is the Kotlin Analysis API (FIR-based, `analysis-api-standalone-embeddable-for-ide`), used purely for in-process live-editing: completion, diagnostics, navigation ([ADR 0010](0010-navigation-resolves-via-analysis-api.md)), and signature help in the Kotlin language server. It was also constructed eagerly — `KotlinLanguageServer.setupWithProject()` built a full `Compiler`/`CompilationEnvironment` on every project open, even for pure-Java projects.

**Three independently-versioned Kotlin artifacts exist in this codebase — do not conflate them:**
1. This repo's own Kotlin Gradle plugin (`2.3.0`, `gradle/libs.versions.toml`), used to compile CodeOnTheGo's own source.
2. The embedded Analysis API fork isolated by this decision (`2.3.255`, `analysis-api-standalone-embeddable-for-ide`, downloaded from `appdevforall/kotlin-android`).
3. The on-device Gradle build toolchain's own Kotlin compiler (`1.9.x`), used when the app builds a *user's* project on-device, out-of-process, per ADR 0002. Unrelated to both of the above; further confirmation that on-device builds never touch the Analysis API.

Separately: `app/proguard-rules.pro`'s `-dontoptimize`/`-dontobfuscate` (ADFA-3604) is caused by the app's *own* first-party source compiled by the repo's 2.3.0 plugin (the cited corruption example, `utils.TestModeUtilsKt.isTestMode`, is app code, not a class from the Analysis API jar) — isolating the jar does not remove the need for that workaround, and it stays.

## Decision

**Isolate the Analysis API and everything that directly references it behind a `DexClassLoader` boundary, loaded lazily on first Kotlin-file interaction, mirroring the app's existing plugin-loading pattern.**

Any class that directly imports Analysis API types must live in the *same* dex/classloader as the Analysis API itself — a resident class in the main dex cannot statically reference a type that only exists in a lazily-loaded child dex. So isolating just the downloaded jar isn't enough; the whole `lsp/kotlin` compiler-facing surface has to move with it, behind a bridge interface the always-resident editor code calls through.

- **`lsp/kotlin-api`** — the bridge: `IKotlinCompilerSession`, `IKotlinCompilationEnvironment`, `IKotlinCompilerSessionFactory`, plus small shared value types (`DiagnosticAction`, `KotlinDiagnosticExtra`). Resident. Depended on by both sides.
- **`lsp/kotlin-compiler-impl`** — the isolated payload: `compiler/`, `completion/`, `diagnostic/`, `navigation/`, `signaturehelp/`, `utils/`, and the four diagnostic-driven code actions (Add Import, Organize Imports, Null Safety, Implement Members) moved out of `lsp/kotlin`. Implements the bridge interfaces against the real `Compiler`/`CompilationEnvironment`. Depends `implementation` on `subprojects/kotlin-analysis-api` (the only dependency actually bundled here); everything resident it needs (`lsp/kotlin`, `lsp/api`, `lsp/models`, `lsp/jvm-symbol-index`, etc.) is `compileOnly` — those types are already loaded by the parent (main app) classloader by the time the carrier dex runs, so `DexClassLoader`'s normal parent-first delegation resolves them there.
- **`subprojects/kotlin-compiler-carrier`** — a `com.android.application` module (`isMinifyEnabled = false`, matching the existing "ship intact, don't shrink" call for this dependency graph) whose only purpose is to produce a real, D8-dexed APK from `lsp/kotlin-compiler-impl`. Never installed or launched; `app`'s build copies its output straight into `app/src/main/assets/data/common/kotlin-compiler-carrier.apk` (`copyKotlinCompilerCarrierToAssets`, wired into `preBuild`) rather than through the brotli-compressed installer-zip pipeline used for the JDK/Android SDK — this is a few MB, not hundreds, and belongs in the base APK unconditionally.
- **`lsp/kotlin`** (resident) — `KotlinLanguageServer` becomes a thin wrapper. `KotlinCompilerLoader` extracts the carrier APK from assets to a stable private file on first use and loads it via `DexClassLoader(apkPath, optimizedDir, null, parent)`, the same construction `PluginLoader` already uses for optional plugin APKs. The trigger point moves from `setupWithProject()` (every project open, Kotlin or not) to the first call actually gated on a `.kt` file — `onDocumentOpen`, `complete()`, `analyze()`, `findDefinition()`, `signatureHelp()` — which also fixes the pre-existing bug where every project open paid the Analysis API bootstrap cost even for pure-Java projects.

Two risks specific to this boundary, both mitigated in `KotlinCompilerLoader`:
- **`intellijPluginRoot`** (the path `Compiler`/`CompilationEnvironment` read `kt-lsp.xml`'s plugin descriptor from) must point at the carrier APK's own extracted path, not `context.applicationInfo.sourceDir` — the descriptor now lives inside the carrier, not the main app APK.
- **Context-classloader mismatch**: the Analysis API/IntelliJ-platform machinery uses context-classloader-default `ServiceLoader.load(...)` for its own internal extension-point wiring. `KotlinCompilerLoader` sets `Thread.currentThread().contextClassLoader` to the `DexClassLoader` around initialization and restores it after — without this, those lookups silently return empty against the wrong (parent) classloader rather than throwing, which is a much harder failure to diagnose than a crash.

## Consequences

**Positive**
- The Analysis API and its transitive graph (IntelliJ platform core, Guava, protobuf, aalto-xml shaded under it) are gone from `app`'s own `classes*.dex` entirely — confirmed via dex inspection of a real release build (zero `Lorg/jetbrains/kotlin/analysis/**` class descriptors remain in `app`'s dex; they moved intact into the carrier's dex).
- Projects that never open a Kotlin file never pay the classload, dex-verification, or `Compiler`/`CompilationEnvironment` construction cost.
- `app/proguard-rules.pro`'s Analysis-API-specific `-keep` rules (the `org.jetbrains.kotlin.**`/Caffeine/kotlin.reflect/kotlin.script/kotlinx.coroutines.internal/streamex/gnu.trove block, and the `compiler.services.**` PicoContainer rule) are removed — nothing in `app`'s own classpath needs them anymore. The carrier module needs no equivalent rules since it doesn't run R8 at all.

**Negative / costs**
- First Kotlin-file interaction in a session now pays a one-time synchronous latency spike: asset extraction (first run only) + `DexClassLoader` construction + `Compiler` bootstrap. Previously this cost was paid unconditionally at project-open time instead.
- Three new Gradle modules and a real classloader boundary to reason about for anyone touching Kotlin LSP code going forward: a change to a bridge interface (`lsp/kotlin-api`) now requires updating both the resident caller and the isolated implementation, and any new resident type crossing the boundary must be added as `compileOnly` on the isolated side, not `implementation`/`api`, to avoid bundling dead-weight duplicate classes into the carrier dex.
- `subprojects/kotlin-analysis-api/consumer-rules.pro`'s ~350 keep rules become dead weight (never exercised, since the carrier never runs R8) but were left in place rather than deleted, since removing them isn't required for correctness and wasn't in this decision's scope.

## Alternatives considered

- **Reflectively patch the app's own `PathClassLoader.pathList`** (the technique the old `androidx.multidex.MultiDex.install()` support library used) — rejected: relies on non-SDK-API internals that Android has progressively locked down across releases, and the ticket's own wording specifies `DexClassLoader` as the mechanism.
- **Android Dynamic Feature modules / Play Feature Delivery** — rejected: this app must install offline/side-loaded (F-Droid, direct APK, GitHub releases), not only via Play Store, and `SplitInstallManager` is a Play-Store-install-only mechanism in production.
- **Ship the carrier jar through the existing brotli-compressed installer-zip pipeline** (`AssetsInstaller`/`BundledAssetsInstaller`, used for the JDK/Android SDK/Gradle distribution) — rejected: that framework exists for hundreds-of-MB optional first-run downloads with progress UI; this is a few MB that belongs in the base APK unconditionally, closer in spirit to how `plugin-api.jar` is already copied straight into assets at build time.

## Related

- [ADR 0002](0002-on-device-builds-via-gradle-tooling-api.md) — confirms on-device builds never touch this dependency.
- [ADR 0003](0003-vendored-forked-desktop-toolchain.md) — the analogous `javac`/`jaxp` in-process, always-resident toolchain; a similar lazy-load treatment has been proposed for it but is out of scope here.
- [ADR 0010](0010-navigation-resolves-via-analysis-api.md) — the navigation feature whose Analysis API dependency is exactly what moves behind this boundary.
- `plugin-manager/.../PluginLoader.kt` — the existing `DexClassLoader` pattern this decision extends.
