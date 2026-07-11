# 0009. Build new UI in Jetpack Compose, not XML Views

- **Status:** Proposed
- **Date:** 2026-06-19
- **Deciders:** Code On The Go team

## Context

Historically the IDE's own UI is **View-based** — XML layouts, `Fragment`s, and `RecyclerView` with Material Components (see [ADR 0006](0006-koin-dependency-injection.md) context and ARCHITECTURE.md). Newer surfaces already moved to a Unidirectional Data Flow (UDF) architecture — `ViewModel` + `StateFlow`, sealed UI-state/effect types, repositories, Koin DI — but still render through XML and `findViewById`/binding.

That split has a cost: two ways to build a screen, manual view-state wiring, boilerplate binding code, and UI logic that's awkward to unit-test. Jetpack Compose collapses the view layer into Kotlin, binds naturally to `StateFlow` via `collectAsState()`, and fits the UDF pattern the team already follows. Unlike most ADRs here, this one is **forward-looking** — it sets direction for new work rather than documenting an existing decision.

## Decision

**All new UI for the IDE is built in Jetpack Compose. New XML View-based screens are not permitted.**

- New screens, panels, dialogs, and reusable UI components are composables. Reviewers should reject a new XML layout / `Fragment`-rendered screen for the IDE's own UI.
- **The rest of the stack is unchanged.** Compose replaces only the view layer. UDF still holds: `ViewModel` + `StateFlow<UiState>`, sealed `UiEvent`/`UiEffect`, and repositories for data. Composables collect state with `collectAsState()` and send events up to the ViewModel.
- **DI is still Koin** ([ADR 0006](0006-koin-dependency-injection.md)) — ViewModels are resolved through Koin, not a new mechanism.
- **Existing XML/View screens stay as-is.** This is not a migration mandate; legacy surfaces are rewritten in Compose only when they're being substantially reworked anyway.
- This does **not** apply to the *user's* app or the visual design tooling (`layouteditor`, `uidesigner`, `xml-inflater`), which manipulate XML by definition.

## Consequences

**Positive**
- One way to build new screens; less boilerplate (no binding/`findViewById`), state-driven rendering that maps cleanly onto the existing `StateFlow` UDF.
- UI logic is easier to test and preview (`compose-preview` already exists in the tree).

**Negative / costs**
- A **mixed codebase** for the foreseeable future — Compose and Views coexist; contributors must know both, and interop (`ComposeView` / `AndroidView`) is needed at the seams.
- New Compose dependencies and compiler plugin; some learning curve and added build surface.
- **Cross-cutting UI rules written for XML need Compose equivalents** — notably accessibility (`contentDescription` → `Modifier.semantics`/`contentDescription`, decorative views → `null` semantics) and the long-press 3-tier help affordance (see REVIEW.md §8–§9). The help affordance has no Compose entry point yet — building the bridge is tracked as a prerequisite in **ADFA-4381**; sequence it before the first Compose feature screen.

## Alternatives considered

- **Stay View-based** — rejected: perpetuates two ways to build a screen and the binding boilerplate; doesn't leverage the `StateFlow` UDF the team already standardized on.
- **Compose, and mandate migrating existing screens** — rejected: a forced migration of all legacy UI is high-risk churn with little user-facing value. Migrate opportunistically instead.

## Related

- [ARCHITECTURE.md](../../ARCHITECTURE.md) — technology stack (UI), state management & UDF.
- [ADR 0006](0006-koin-dependency-injection.md) — Koin DI (unchanged by this decision).
- [REVIEW.md](../../REVIEW.md) — §8 Accessibility, §9 Contextual help (rules that need Compose equivalents).
