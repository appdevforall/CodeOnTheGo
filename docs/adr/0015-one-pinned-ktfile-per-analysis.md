# 0015. One pinned live KtFile per analysis, enforced by the type system

- **Status:** Proposed
- **Date:** 2026-08-25
- **Deciders:** Code On The Go team

## Context

The K2 Kotlin LSP relies on one live `KtFile` instance per open path. `DeclarationProvider.ktFilesForPackage`
(`lsp/kotlin/src/main/java/com/itsaky/androidide/lsp/kotlin/compiler/services/DeclarationsProvider.kt`) resolves a
path through `KtSymbolIndex.getKtFile` for anything an analysis session needs to see beyond the file it started on.
If that lookup can answer with a *different* instance than the one the analysis is holding, FIR sees every
top-level declaration twice - once as the analysis's own PSI, once through the provider - and reports the file as
conflicting with itself. That is what reaches the editor as "Redeclaration" / "Conflicting overloads" underlines
on every declaration.

This is not a new failure. ADFA-4165 established the one-instance invariant: `CompilationEnvironment.onFileContentChanged`
captured the `KtFile` being replaced, then atomically invalidated its FIR session and installed the replacement
under `project.write`, and a companion fix to `KeyedDebouncingAction` stopped two refreshes for the same key from
running concurrently and installing out of order (commit `975d23fdfc`). ADFA-3322 (`Signature help for Kotlin LSP`,
PR #1484) replaced that file-handling path with a per-version `currentFiles` cache
(`KtSymbolIndex.getCurrentVersionedKtFile`) that mints a fresh `KtFile` every time the open document's version
changes, and neither the atomic install nor the serialization carried forward. The regression this ADR fixes is
that gap: `getCurrentVersionedKtFile` and `getKtFile` could each answer a lookup for the same path with a different
instance if a refresh landed between them, and an analysis rooted at the older one saw its own declarations doubled
through the provider. `StaleKtFileInstanceDiagnosticsTest`
(`lsp/kotlin/src/test/java/com/itsaky/androidide/lsp/kotlin/compiler/index/StaleKtFileInstanceDiagnosticsTest.kt`)
reproduces it directly.

The history is the argument for the decision below: a runtime mechanism enforced the invariant once, tied to code
that the next refactor replaced wholesale without carrying the discipline forward. A property that has to be
remembered gets lost the next time someone who does not know the history touches the code. The fix has to be
something the next refactor cannot drop without the code failing to compile.

## Decision

**A `KtFile` for an open path may only be obtained as a pinned handle, and only one instance is pinned to a path
at a time.** `LiveKtFile` (`lsp/kotlin/src/main/java/com/itsaky/androidide/lsp/kotlin/compiler/index/LiveKtFile.kt`)
is an `internal sealed interface` whose only implementation, `KtSymbolIndex.PinnedKtFile`, is `private`. The only
way to obtain one is `KtSymbolIndex.withLiveKtFile` / `withLiveKtFileAsync`
(`lsp/kotlin/src/main/java/com/itsaky/androidide/lsp/kotlin/compiler/index/KtSymbolIndex.kt`), which:

1. Acquire the path's `Pin` - join one already open (`joinExistingPin`, reference-counted), or resolve the current
   instance and install a new one (`acquirePin` / `acquirePinAsync`, `installPin`).
2. While the pin is open, every door resolves to the pinned instance: `getCurrentVersionedKtFile` returns it
   without minting a new one even if the document has moved on, and `getKtFile` - the resolution-side door
   `DeclarationProvider.ktFilesForPackage` calls - checks `pins[path]` first. The two doors this bug came from can
   no longer disagree.
3. A version bump observed while the pin is open is recorded (`Pin.refreshOwed`) rather than acted on, and applied
   once the last scope releases (`releasePin`), so the pin defers the refresh instead of losing it.

`getCurrentKtFile`, `getCurrentVersionedKtFile` and `getCurrentKtFileIfPresent` are `private`; `getKtFile` is
`internal`, documented as the resolution-side door for the Analysis API service providers, not a general
accessor. `LiveKtFile` never exposes the `KtFile` as a value - `read` and `analyzing` take a lambda instead of
returning the file - so a caller cannot hold a reference past the scope that pinned it. `analyzing` routes through
`analyzeMaybeDangling`, which is `withAnalysisLock` under the hood
(`lsp/kotlin/src/main/java/com/itsaky/androidide/lsp/kotlin/compiler/modules/KtFileExts.kt`), so pinning also
closes the last direct route to `analyze`/`analyzeCopy` that its doc comment could previously only ask callers not
to take. For an open path, using the shared serialization lock is no longer just a convention - it is the only way
to reach a live `KtFile` at all.

**One escape hatch:** `KtSymbolIndex.peekLiveKtFile`, gated behind `@RequiresOptIn(ERROR)` `UnpinnedKtFileAccess`.
Its one production caller is `AdvancedKotlinEditHandler`
(`lsp/kotlin/src/main/java/com/itsaky/androidide/lsp/kotlin/completion/AdvancedKotlinEditHandler.kt`), which runs
on the UI thread after completion has already returned, does PSI-only work, and opens no analysis session.
Pinning there would block the UI thread on a refresh that a background analysis might be holding up.

## Consequences

**Positive**

- The invariant is now enforced by the compiler: code that reaches for a live `KtFile` outside `withLiveKtFile` /
  `withLiveKtFileAsync` does not compile. The class of bug ADFA-4165 fixed and ADFA-3322 silently reintroduced
  cannot come back from a refactor that simply forgets the discipline the old fix depended on.
- The pin makes explicit what was previously only inferred from two call sites happening to agree: an analysis and
  the declaration provider see the same PSI for the whole scope, by construction.

**Negative / costs**

- **A pin is process-wide, not per-caller.** A second request for a pinned path joins the pin and sees that
  scope's text, which can already be older than the buffer. Pin duration is a cross-request staleness window for
  everyone, not just the request that opened it.
- Callers that consult `LiveKtFile.isStale` fall into three buckets, not two. Sites whose output is an edit refuse
  rather than compute offsets against frozen text: `ExtractVariablePlanner`, `ExtractMethodPlanner`,
  `KotlinCompletions`, `OrganizeImportsAction`, `ImplementMembersAction`, `AddImportAction`, `NullSafetyAction`. A
  refusal is recoverable; a wrong edit to the user's source is not. `KotlinDiagnosticProvider.doAnalyze` discards
  and reschedules instead: it has nothing safe to hand the user in the moment, so it drops the computed diagnostics
  and re-queues the file through `env.fileAnalyzer.schedule` rather than paint the editor with squiggles for text
  the user has already replaced. Navigation and info sites - go-to-definition, find usages, signature help -
  deliberately tolerate being one edit behind (see the comment at `GoToDefinition.kt:215`) and do not check
  `isStale` at all, because their failure mode is a wrong jump, not a corrupted file or a dropped result.
- **Known parked consequence:** while background diagnostics hold a pin and the user keeps typing, a completion
  request joins the stale pin and returns no items until the next keystroke closes it. Fixing this needs
  acquisition to be priority-aware - an interactive request preempting a lower-priority holder instead of joining
  it - which `Pin` cannot do yet: it has no notion of *which* acquirer holds it, and `AnalysisScheduler`'s
  `preempt()` (ADR 0011) latches onto whichever scope is active, so signalling "the holder" from here would fire an
  `AnalysisPreemptedException` into a nested outer scope that never asked to be cancelled. `Pin` becoming a
  per-holder registry is a prerequisite, not scheduled here.
- The escape guard is partial. `PinnedKtFile.guarded` rejects returning the pinned file *directly* from a `read` /
  `analyzing` block, but returning it wrapped - inside a collection, or as one of its child PSI elements - escapes
  the check undetected and is equally unsafe.
- A narrow window remains between resolving an instance and installing its pin (documented on `withLiveKtFile`):
  a request arriving in that window sees no pin yet and can launch a refresh that completes inside the scope,
  firing a FIR modification event under it. Instance identity still holds through every door - the pin is stamped
  with the resolved instance's own version, so the bump is not lost, only deferred. Closing the window fully would
  mean publishing a pin before its file exists, making joiners wait on an unresolved entry in the one path every
  caller depends on; that deadlock risk was judged worse than the window.

## Alternatives considered

- **A runtime mechanism that keeps the invariant true without a type gate** - what ADFA-4165 did: atomically
  invalidate the superseded FIR session and install the replacement under `project.write`, serialized so two
  refreshes for the same key cannot race. It worked, until ADFA-3322 replaced the code path it lived in without
  carrying the same discipline forward. That is precisely how this regression happened.
- **One mutable `KtFile` per open path, reparsed in place instead of minting a new instance per version** -
  strictly the deeper fix: it removes the multiple-identities problem instead of gating access to it. Not taken.
  In-place reparse (`BlockSupport.reparseRange` against a `LightVirtualFile`) is unproven in this standalone/mock
  Analysis API environment, which has no real `PsiDocumentManager` behind it - real feasibility risk to carry on a
  regression fix. It would also still be a construction property a later refactor could quietly undo, rather than
  something the compiler holds; the team chose the type gate instead and did not schedule in-place reparse as a
  follow-up.
- **A custom lint/detekt rule banning the raw accessors** - the build has no detekt; Spotless's ktlint integration
  only formats, it does not carry custom semantic rules, so there is no rule seat to put this in.

## Related

- ADFA-4165 - established the one-live-KtFile-per-path invariant, once enforced by an atomic install-and-invalidate
  under `project.write` rather than by the type system.
- ADFA-3322 (PR #1484) - introduced the per-version `currentFiles` cache that reintroduced the bug.
- [ADR 0010](0010-navigation-resolves-via-analysis-api.md) - why navigation resolves through the Analysis API,
  the pipeline this pin protects.
- [ADR 0011](0011-command-analysis-priority.md) - `AnalysisScheduler` priorities and `preempt()`, referenced above
  as the reason acquisition cannot yet be made priority-aware.
- `lsp/kotlin/src/main/java/com/itsaky/androidide/lsp/kotlin/compiler/index/LiveKtFile.kt` - the pinned handle.
- `lsp/kotlin/src/main/java/com/itsaky/androidide/lsp/kotlin/compiler/index/KtSymbolIndex.kt` - `pins`, `Pin`,
  `withLiveKtFile`, `withLiveKtFileAsync`, `getKtFile`.
- `lsp/kotlin/src/test/java/com/itsaky/androidide/lsp/kotlin/compiler/index/StaleKtFileInstanceDiagnosticsTest.kt` -
  reproduces the regression this ADR documents the fix for.
