# 0018. Android Studio plugins are ported, not run

- **Status:** Proposed
- **Date:** 2026-10-13
- **Deciders:** Code On The Go team

## Context

ADFA-95 asks whether CoGo can run existing Android Studio plugins, so the ecosystem built for the desktop IDE works here too.

An Android Studio plugin is an IntelliJ Platform plugin: a JAR, or a ZIP of JARs, whose `META-INF/plugin.xml` registers classes against the platform's extension points - actions, tool windows, settings pages, inspections, file editors, services. It is compiled against `com.intellij.*`, and an Android-specific plugin also against the Android plugin that Android Studio bundles. It declares the platform builds it supports with `since-build`/`until-build`, because that API changes between releases. Its UI is Swing.

Loading the bytecode is not the obstacle. CoGo already loads plugin DEX through `DexClassLoader` (`PluginLoader`), and every on-device build already runs d8, so a JAR could be dexed on install.

The obstacle is what the bytecode calls:

- **Swing and AWT do not exist on Android.** `android.jar` (API 36) has no `javax.swing`, and of `java.awt` only `java.awt.font.TextAttribute` and `NumericShaper`. A plugin with a tool window, dialog or settings page fails on its first UI call.
- **The IDE layer that plugins extend is absent.** CoGo already runs IntelliJ's headless core on device, inside the Kotlin Analysis API jar (`subprojects/kotlin-analysis-api`): PSI, VFS, `Application`, the extension area, `PluginManagerCore`. All of it is relocated to `org.jetbrains.kotlin.com.intellij.*` - the APK defines no class under `com.intellij.*` - so a plugin compiled against `com.intellij.*` does not link to it. It also has none of `AnAction`, `ToolWindowFactory`, `Configurable`, `FileEditorProvider` or `LocalInspectionTool`, and nothing of the Android plugin.
- **The target moves.** A compatibility layer would track a platform API that changes between releases, on a schedule CoGo does not control.
- **Distribution.** Plugins come from JetBrains Marketplace, which is online, under per-plugin licenses. Repackaging one for offline use needs its author's permission.

## Decision

**CoGo does not run Android Studio or IntelliJ plugins.** A plugin's behavior reaches CoGo by being ported to the CoGo plugin API - see [Porting an Android Studio plugin](../PLUGIN_AUTHORING.md#porting-an-android-studio-plugin) - or, where the plugin wraps an open standard, by using that standard directly:

- **Language support** through a language server, which CoGo hosts via `LanguageExtension` and `lsp:external`.
- **Gradle plugins** already run, because CoGo builds with real Gradle ([ADR 0002](0002-on-device-builds-via-gradle-tooling-api.md)).

Data a plugin bundles - IntelliJ live templates, `.icls` colour schemes - can be imported rather than executed, each as its own ticket.

## Consequences

**Positive**

- No second plugin runtime to build, secure, or keep in step with JetBrains releases.
- The CoGo plugin API stays the one contract plugin authors target, with its permission model intact.

**Negative**

- No Android Studio plugin works unmodified. Each needs a port, and for a UI-heavy plugin that means rewriting its UI.
- Users arriving from Android Studio will find their plugins missing. ADFA-428 surveys the plugins Android Studio bundles, to find the ones worth rebuilding natively.
- Android Lint checks are Gradle-side too, but CoGo does not show Lint results in the editor, and whether the `lint` task completes on device has not been checked.

## Alternatives considered

- **IntelliJ compatibility layer** - dex plugin JARs on install and implement `com.intellij.*` over CoGo's services. Rejected: the surface to implement is the platform's whole IDE layer plus Swing, it shifts between platform releases, and a plugin fails at its first call outside whatever subset was built.
- **Reuse the bundled IntelliJ core** - rejected: relocated, headless, and missing the extension points plugins implement (see Context).
- **Run the IntelliJ Platform itself in the bundled JDK** ([ADR 0004](0004-embedded-termux-runtime.md)) - rejected: CoGo has no display server for Swing, and the result would be a second desktop IDE beside CoGo, not plugins inside it.

## Related

- [`docs/plugin-api.md`](../plugin-api.md) - the plugin API contract a port targets.
- [ADR 0019](0019-vscode-extensions-are-ported-not-hosted.md) - the same decision for VS Code extensions (ADFA-96).
- ADFA-428 - survey of the plugins Android Studio bundles.
