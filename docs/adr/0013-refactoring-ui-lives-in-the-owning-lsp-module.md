# 0013. Refactoring UI lives in the owning LSP module

- **Status:** Proposed
- **Date:** 2026-08-03
- **Deciders:** Code On The Go team

## Context

The K2 Kotlin LSP is gaining interactive refactorings: extract variable and extract method (ADFA-4826), inline variable (ADFA-4827), semantic rename (ADFA-4825). Unlike every existing Kotlin code action, these cannot be a single fire-and-forget edit — the user has to choose an expression, a name, a target scope, and whether to replace other occurrences. That is a real UI surface, not a `DialogUtils` one-liner.

[ADR 0009](0009-jetpack-compose-for-new-ui.md) settles *what* that UI is built with (Compose, UDF, `ViewModel` + `StateFlow`). It says nothing about *where* language-specific UI lives, and the module graph makes that a genuine question:

- `editor` depends on `lsp/kotlin` (`editor/build.gradle.kts`), so the dependency flows **LSP -> editor**. An LSP module cannot reach the editor or `app`.
- `ActionData` carries only a `Context` and the editor; there is no service-lookup mechanism for an LSP module to call *up* into a UI layer.
- `lsp/java` already owns UI code today — `AutoFixImportsAction` builds and shows a `DialogUtils` chooser directly.

So a refactoring in `lsp/kotlin` either renders its own UI, or a new inversion mechanism has to be invented for it.

## Decision

**A language server module owns the UI for its own refactorings.** `lsp/kotlin` enables Compose and hosts the refactoring bottom sheets; the same applies to any future `lsp/*` module that grows an interactive refactoring.

- Compose is enabled per-module exactly as `flamegraph`, `floating-window` and `profiler` do it: the `kotlin-compose` plugin, `compose = true`, and the Compose BOM with `ui`/`foundation`/`material3`.
- The UI is a `BottomSheetDialogFragment` hosting a `ComposeView`. The hosting `FragmentActivity` is found by walking `ContextWrapper.baseContext` up from `ActionData`'s `Context` — no new `ActionData` key, no change to the `editor` module.
- **The analysis/UI split is enforced by data, not by module boundaries.** The action's background pass produces a plain-data plan (candidate expressions, scope chains, occurrence ranges, suggested name, document version); the sheet performs no analysis and holds no PSI. All refactoring logic lives in pure functions, unit-testable without an editor, an activity, or Compose.
- ADR 0009 otherwise applies unchanged: `ViewModel` + `StateFlow<UiState>`, sealed `UiEvent`, `collectAsStateWithLifecycle()`.

## Consequences

**Positive**
- No new indirection: one module, one PR per refactoring, no interface to register or resolve.
- Consistent with `lsp/java` already owning its dialogs, so there is one rule for LSP-owned UI rather than two.
- The plain-data plan boundary keeps the valuable logic testable regardless of where the UI sits, so the placement decision does not compromise test coverage.

**Negative / costs**
- A language server module gains a UI surface, which is a layering smell: `lsp/kotlin` is no longer purely a language service.
- Compose and `lifecycle-viewmodel` are added to a module that previously had neither, growing its build surface and bringing ktlint's compose-rules ruleset to bear on it.
- Walking the `ContextWrapper` chain for a `FragmentActivity` is an implicit dependency on how the editor is hosted; a future change to that hosting breaks it at runtime rather than at compile time.
- If three or more `lsp/*` modules end up with Compose UI, extracting a shared UI module becomes worthwhile and this decision will need revisiting.

## Alternatives considered

- **Render in `editor`, invert via an interface.** Declare a refactoring-UI interface in `editorApi` or `lsp/models`, implement it in `editor`, have `lsp/kotlin` call up through it. Cleanest layering. Rejected: nothing registers such an implementation today, so it means inventing a service-lookup mechanism for one sheet, and the interface would be guessed from a single client.
- **Render in `app`.** `app` is the integration point and already hosts `BottomSheetDialogFragment`s and `ILanguageClient`. Rejected: same inversion problem, and it puts Kotlin-specific refactoring UI in the module where nothing else language-specific lives.
- **A new `lsp/kotlin-ui` module.** Keeps Compose out of `lsp/kotlin` without inverting. Rejected for now: a new Gradle module in a ~80-module build is disproportionate for one sheet. Reconsider once extract-method and inline-variable have landed and the UI surface is known.

## Related

- [ADR 0009](0009-jetpack-compose-for-new-ui.md) — Compose for new UI; this ADR answers *where*, not *what*.
- [ADR 0006](0006-koin-dependency-injection.md) — Koin DI, unchanged.
- [ADR 0010](0010-navigation-resolves-via-analysis-api.md) — the K2 Analysis API as the Kotlin semantic source of truth.
- [ARCHITECTURE.md](../../ARCHITECTURE.md) — module map, layering, UDF.
