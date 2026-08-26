# 0010. Kotlin navigation resolves via the Analysis API, not the symbol index

- **Status:** Proposed
- **Date:** 2026-07-27
- **Deciders:** Code On The Go team

## Context

The K2 Kotlin LSP carries a substantial symbol-indexing stack: `JvmSymbolIndex` over library jars, `KtFileMetadataIndex` over source files, and `KtSymbolIndex` tying them together, all SQLite-backed and refreshed by background workers. Completion and add-import lean on it heavily, and it is the cheap way to answer "what symbols named X exist in this workspace".

Navigation features - go-to-definition (ADFA-4823) and find usages (ADFA-4824) - look superficially similar: given a name, find where it lives. A reader who has just read the completion code will reasonably expect navigation to query the same index.

It cannot. The index stores names, kinds, visibility, and containing-class metadata, but **no source offsets** - `JvmSymbol`/`JvmSymbolInfo` have nowhere to put a declaration's position, and `KtFileMetadata` records only a file path plus its symbol keys. An index hit narrows the answer to a file at best; something still has to parse that file to find where in it the declaration sits. Worse, the index answers by *name*, while navigation must answer by *resolution* - which of the seven overloads of `foo`, through this module's dependency graph and content scopes, does this call site actually bind to.

## Decision

**Kotlin navigation resolves through the Analysis API and PSI only. The symbol indexes are not consulted.**

- Given a caret offset, find the reference, then `analyze(ktFile) { reference.mainReference.resolveToSymbols() }`, falling back to `resolveToCall()` for convention references (`a + b`, `a[i]`, `by lazy`, destructuring, `for` loops) that have no name reference to resolve.
- Convert each resolved symbol to its PSI declaration, and the declaration to a file plus a name-identifier range.
- Correctness of scoping - module dependencies, content scopes, visibility - is delegated to the analysis session rather than reimplemented over index rows.

## Consequences

**Positive**

- Results are *resolved*, not name-matched: the right overload, the right receiver, the right module.
- Module dependency graphs and content scopes are respected by construction. No second, divergent notion of "which module can see what" to keep in sync with `ProjectStructureProvider`.
- One code path for all three resolution scopes (same-file, inter-file, inter-module), so the test matrix covers behaviour rather than plumbing.

**Negative / costs**

- **Navigation requires a live analysis session.** Before one exists, go-to-definition returns nothing; it cannot degrade to an index-only answer. The user-facing gap - no way to say "still indexing" rather than "not found" - is cross-cutting across every LSP feature and remains unsolved.
- Resolving a cross-module target means building PSI for the target file, which is more work than an index row lookup. Acceptable for a user-initiated, cancellable, one-at-a-time request; it would not be acceptable for a per-keystroke feature.
- Symbols with no source PSI - stdlib, framework, any library jar - are simply unreachable. Library navigation would need decompilation or source-jar extraction, neither of which exists in the tree.

## Alternatives considered

- **Index-first, analysis fallback** - rejected: the index has no declaration offsets, so it can only narrow to a file and a second pass must parse it anyway. Extra machinery, no saved work, and a name-matched shortlist that can disagree with what the call site actually binds to.
- **Index-only for cross-module targets** - rejected: two divergent code paths for the same user action, and cross-module results would be position-less, so they could not be selected in the editor.
- **Extend the index to store declaration offsets** - rejected for now: offsets go stale on every edit, so the index would need write-through invalidation on document change to stay trustworthy for navigation, and it still would not answer overload resolution. Revisit only if navigation latency becomes a measured problem.

## Related

- [docs/features/kotlin-goto-definition.md](../features/kotlin-goto-definition.md) - the first feature built on this decision
- [docs/features/kotlin-find-usages.md](../features/kotlin-find-usages.md) - the second, which additionally has no reference-search infrastructure to fall back on: the bundled Analysis API ships no `ReferencesSearch`, no `PsiSearchHelper` and no word index
- [ADR 0011](0011-command-analysis-priority.md) - the analysis priority those features run at
- [ADR 0001](0001-prefer-room-for-persistence.md) - persistence choices for the indexes this ADR declines to use
