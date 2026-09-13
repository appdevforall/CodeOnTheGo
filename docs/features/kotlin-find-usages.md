# Kotlin find usages (K2 LSP)

- **Ticket:** ADFA-4824 (subtask of ADFA-3317; split out of the closed ADFA-3321 "Navigation")
- **Status:** Implemented in `lsp/kotlin/navigation/`, pending on-device QA
- **Module:** `lsp/kotlin`

From a Kotlin declaration - or from a reference to one - list every place in the workspace that uses it, across three scopes: same file, another file in the same module, another module in the workspace.

`KotlinLanguageServer.findReferences` already exists as a stub that answers empty; this feature fills it in. Everything downstream of it (`ReferenceResult`, `IDEEditor.onFindReferencesResult`, the search-results panel) already existed for the Java server.

The sibling feature [go-to-definition](kotlin-goto-definition.md) answers the *opposite* question and shares this feature's caret handling, symbol-to-location conversion, and test fixture. Read its Language section first; the terms below extend it rather than replace it.

## Language

**Usage**:
A reference that resolves into the match set. This is the unit the feature reports.
_Avoid_: reference (that is the PSI element, per go-to-definition's glossary), occurrence, hit, match.

**Target**:
The declaration whose usages are being searched for. Derived from the caret either directly (the caret is on the declaration's own name) or by resolving the reference under the caret.
_Avoid_: symbol, subject, source, declaration (reserve that for the PSI element a reference resolves to).

**Match set**:
The target plus every declaration a call to the target may legitimately have been written against: its **workspace-source** supers, and - when the target is a classifier - its constructors. A reference is a usage if and only if it resolves into this set.
_Avoid_: hierarchy, family, candidates (go-to-definition uses "candidate" for a resolved declaration).

**Search scope**:
The set of modules a usage could possibly live in, derived from the target's visibility. Distinct from go-to-definition's **resolution scope** (same-file / inter-file / inter-module), which describes coverage rather than a bound. These two are easy to conflate and are deliberately named apart.
_Avoid_: scope (unqualified), visibility scope, module scope.

**Candidate file**:
A file that survived the text prefilter and is therefore worth parsing and resolving. Most candidate files contain no usage at all - the prefilter is a cheap over-approximation.
_Avoid_: match, result, hit.

**Workspace boundary**:
The line between declarations with source PSI in a source module and everything else (the stdlib, the framework, library jars). The match set stops at it, and so does the reportable result set.
_Avoid_: project boundary, library edge.

## Scope

### In scope

Any reference, in any of the three resolution scopes, that resolves into the match set - where both the reference and the target's declaration are workspace sources.

The **target** may be a Java-source declaration. A caret on a Kotlin reference to a workspace `.java` class or method resolves to it (go-to-definition's AC5 already covers that direction), and its Kotlin usages are found like any other target's.

**Convention references are valid entry points.** A caret on `a + b`, on `by`, on `[`, on a `for` loop's `in`, or on a destructuring entry resolves through to `plus` / `getValue` / `get` / `iterator` / `componentN`, and the feature then searches for *named* usages of that function. This costs nothing beyond what go-to-definition already does.

### Out of scope

- **Implicit call sites as results.** A usage search on `operator fun plus` finds explicit `a.plus(b)` calls, not `a + b`. Discovering implicit sites would mean resolving every operator, index, call, delegate and loop expression in every file in scope, because the text of `a + b` contains no name to prefilter on. Java's find-references reports no implicit usages either.
- **`.java` files as search targets.** Kotlin declarations *are* visible to Java PSI as light classes here (`symbol-light-classes.xml` registers `KotlinAsJavaSupport`, and `JavaElementFinder` is registered), but nothing in the repo exercises Java PSI *resolution*, and the Java server has its own find-references. Kotlin call sites of a Java declaration work; Java call sites of a Kotlin declaration are not searched.
- **Usages reachable only through a subclass.** See R3 and Non-goals.
- **Binary symbols.** As with go-to-definition: no decompiler, and `showLocations` can only open a real file. A search from a reference to `listOf` finds nothing.
- **Test sources.** Not a choice made here - `AndroidModule.getSourceDirectories()` returns `mainSourceSet` only, so `src/test/**` and `src/androidTest/**` are not content roots for *any* Kotlin LSP feature.

## Requirements

**R1 - Trigger.** A "Find references" item appears in the Kotlin code-actions menu, mirroring Java's. `FindReferencesAction` extends `BaseKotlinCodeAction`, id `ide.editor.lsp.kt.findReferences`, reuses `R.string.action_find_references`, and delegates to `ILspEditor.findReferences()`. Registered in `KotlinCodeActionsMenu` immediately after `GoToDefinitionAction`, matching Java's ordering.

It carries its own tooltip tag, `EDITOR_CODE_ACTIONS_KT_FIND_REFS = "editor.codeactions.kotlin.findrefs"`, not Java's `EDITOR_CODE_ACTIONS_FIND_REFS` - the same split go-to-definition made, so Kotlin and Java can carry different tooltip text. The tooltips database is not in this repo, so the tag shows no text until a row exists for it; that row is a hand-off item, not code.

The item is **always visible** for `.kt`/`.kts` and never conditioned on what the caret is sitting on: deciding "is there a target here" needs PSI and the project lock, and `prepare()` runs on the UI thread. A caret on whitespace therefore flashes "No references found". A `.kts` file shows the item and it does nothing, because a script has no `CompilationEnvironment` - identical to go-to-definition.

**R2 - Target at caret.** The caret maps to a target declaration by trying, in order:

1. **The caret is on a declaration's own name** - the leaf is the `nameIdentifier` of a `KtNamedDeclaration`. That declaration is the target.
2. **The caret is on a reference** - delegate to go-to-definition's `referenceAtCaret`, then resolve it to its declaration, which becomes the target.

Order matters, and it makes the two features answer differently from one identical caret. For `val (x, y) = p` with the caret on `x`, go-to-definition navigates to `component1`; find usages targets the local `x`. That is deliberate: `x` is both a declaration and a convention reference, and each feature wants the reading that is useful to it.

`referenceAtCaret` cannot be reused for step 1. It is built so that a caret on a declaration's own name resolves nothing - go-to-definition's no-self-jump rule - which is precisely the caret position find usages is normally invoked from. Step 1 is therefore a new, separate check; the token accept-list and the `offset - 1` retry are shared.

**R3 - Match set.** Assembled once, in the caret's analysis session:

- The target symbol, normalised through `fakeOverrideOriginal`. A call `derived.foo()` where `Derived` does not redeclare `foo` resolves to a substituted fake override, not to `Base.foo`, so both sides of every comparison are normalised.
- Its supers, via `allOverriddenSymbols`, **stopping at the workspace boundary**. So a call dispatched through a workspace `Base.foo` counts as a usage of `Derived.foo`, wherever `Base` lives - which is why R4's scope unions the supers' modules too. Library supers are excluded: including them would make a usage search on an overridden `toString` match every `.toString()` call in the workspace, and a library super can never yield a reportable result anyway.
- When the target is a classifier, its **constructors**. Otherwise `Foo()` - which resolves to the constructor, not the class (go-to-definition's R4) - would not count as a usage of `class Foo`, and the feature would miss every instantiation. The reverse expansion is not applied: a target that *is* a specific constructor stays that constructor, because asking for usages of one overload is a deliberate act.

The walk goes **up** only. Usages reachable solely through a subclass (`Base.foo` searched, `derived.foo()` written) are not found - that needs a workspace inheritor search, and `DirectInheritorsProvider.computeIndex()` rebuilds its entire index on every call.

**Import directives count as usages.** `import a.b.Foo` resolves to `Foo`, so it is one by construction. The panel has no categories to separate them into, and the noise is bounded at one hit per importing file.

**R4 - Search scope.** Derived from the target's visibility, which is an exact bound rather than a heuristic:

| Target | Scope |
|---|---|
| local val/var, parameter, local fun, local class, loop variable | containing file |
| `private` top-level declaration | containing file (Kotlin private top-level is file-private) |
| `private` class/object member | containing file |
| `internal` | the target's module |
| `protected`, `public`, default | every match-set member's module + its transitive dependents (`KotlinModuleDependentsProvider.getTransitiveDependents`) |

The ticket's three resolution scopes fall out of this one code path rather than being three implementations. Cheap cases stay cheap: a search on a local variable never leaves the open file.

The last row unions **every match-set member's** module, not just the target's, because R3's up-walk and R4's dependents pull in opposite directions. With `Base` in `lib` and `Derived` in `app`, a `base.paint()` call written in `lib` is a usage of `Derived.paint` - but `lib` is a *dependency* of `app`, not a dependent, so the target's own module and dependents would never look at it. Library supers are already out of the match set, so the union cannot escape the workspace. The first three rows need no union: `private` cannot override, and this project model has no friend modules, so `internal` cannot be overridden across one.

The first three rows need the declaration's path. The file the user is editing is a live `KtFile` built from the editor buffer, whose `virtualFile` is a non-physical `LightVirtualFile`, so the path comes from `backingFilePath` first and the VFS only as a fallback - go-to-definition's derivation. A target that still has no path cannot be confined to one file, but it is still unreferenceable outside its own module, so it falls back to the `internal` row rather than to the last one.

`internal` needs no widening for test sources. There is no test module to widen to - `collectKtModules` builds one `KtSourceModule` per Gradle module from `mainSourceSet` only, and `directFriendDependencies` is empty everywhere.

**R5 - Candidate discovery.** Two tiers, because find usages is run *while* editing and unsaved text must not be invisible:

| File | Prefilter text | PSI |
|---|---|---|
| open in the editor | the live buffer | `ktSymbolIndex.getCurrentKtFile(path).await()`, awaited **outside** `project.read` |
| everything else | disk | `ktSymbolIndex.getKtFile(path)` |

A module's files include `.java`, which is a non-goal to search, so candidates are filtered on the extension *before* the read - otherwise a Java-heavy workspace spends most of the prefilter reading files whose result is already known.

The prefilter is `mentionsName`, word-boundary exact on the target's simple name, reading line by line through `FileManager.getReader(path)` - which returns the live document when the file is open and the file itself otherwise, so the two tiers above need no branch of their own. Its errors are one-directional: a file that mentions the name but contains no usage is parsed and discarded (wasted work, correct result), while a file that does not mention the name cannot contain a named usage. An unreadable file drops out of the scan with a log rather than failing the search.

Open documents are tab-count many, so the live tier is free. Without it, a usage the user just typed would be missed entirely - the prefilter would never select the file, so it would never be parsed.

Deliberately **not** `StringSearch.containsWord`, the equivalent helper the Java server prefilters with. It reads only the first 1 MB of a file, so a usage below the mark would be silently dropped; it reads through one process-global `ByteBuffer` that the Java server mutates concurrently from its own threads; and it rethrows an unreadable file as a `RuntimeException`, which here would abort the whole search. A name cannot span a line break, so matching per line loses nothing.

Only `KtSimpleNameExpression`s are examined. That is what makes the name filter cheap - it runs on PSI alone, so it runs *before the analysis session is opened*, and a text-prefilter hit whose only mention is a comment or a string literal never costs an analysis-lock acquisition, a FIR session or a match-set restore. It is also what implements "convention references are not results": `a + b` contains no `plus` token, so it is never a candidate. The cost is that a **KDoc `[link]`** to the target is not reported, even though go-to-definition navigates from one; a documented gap rather than a decision worth its own machinery in v1.

**R6 - Identity.** A reference is a usage if its resolved symbol is in the match set. Deciding that across files needs care, because `KaSymbol` is session-scoped and the same declaration exists as two PSI instances - the on-disk `KtFile` cached in the index, and the dangling `KtFile` built from the editor buffer for an open file.

Matching therefore uses `KaSymbolPointer`: `createPointer()` for each match-set member in the caret's session, then `restoreSymbol(session)` **once per candidate session**, then `==` against each resolved candidate symbol inside that session. This is the platform's cross-session identity mechanism, with structural implementations per symbol kind, and it is the direct analogue of the Java server re-deriving its target `Element` inside each compile task.

Locals pay almost none of it: R4 confines them to one file, so there is a single candidate session and the pointers restore once.

A pointer that fails to restore drops that session's candidates, with a log. That under-reports rather than reporting something false, which is the safe direction, and it is tested.

Neither a PSI identity check nor a (file, offset) key works here. Both break exactly when the target's own file has unsaved edits: the live PSI and the on-disk PSI disagree about offsets, so every cross-file usage would be silently missed - and editing-then-searching is the common case.

**R7 - Results.** Each usage becomes a `Location` whose range covers the reference's **name identifier** (`foo` in `a.b.foo()`, `Foo` in `Foo()`), matching go-to-definition's R6. Deduplicated by file plus range, ordered by file path then start offset.

`includeDeclaration` is **ignored**, and the target's own declaration is never emitted. Java's provider ignores it too. Honouring it would also create a trap: a declaration with no usages would return exactly one location in the current file, which `onFindReferencesResult` turns into a silent `setSelection` on the declaration the caret is already on - indistinguishable from a broken no-op. Returning empty flashes "No references found", which is true.

There is **no result cap**. See R10 for why one is not needed.

**R8 - Result handling.** The server returns `ReferenceResult(locations)`; `IDEEditor.onFindReferencesResult` applies unchanged:

- empty -> flash `msg_no_references`
- one location in the current file -> `setSelection`
- otherwise -> `languageClient.showLocations`, the grouped search-results panel

**R9 - Scheduling.** The request runs at the new `AnalysisPriority.COMMAND` ([ADR 0011](../adr/0011-command-analysis-priority.md)), behind the editor's existing cancellable progress flashbar (`msg_finding_references`).

Granularity is per candidate file, and it is load-bearing:

- **One analysis session per candidate file.** A preemption by completion costs one file's work, which is retried once - `findDefinitionAt`'s pattern. The target-resolution phase is retried twice over, because losing *it* loses the whole search rather than one file (R12). One session for the whole search would let a single keystroke discard a whole-workspace scan. A file preempted *twice* is dropped like any other failed candidate (R12), not rethrown: keystroke-driven work winning the lock must not turn a search with plenty of hits into "no references".
- **`project.read` per candidate file, never once for the search.** A whole-workspace search holding the read lock start to finish would block every `project.write`, which is what index refresh needs.
- **The live-document await stays outside `project.read`.** The refresh it waits on needs `project.write`; awaiting it under the read lock deadlocks. Go-to-definition's R10 records the same constraint.
- `params.cancelChecker` is honoured per prefiltered file, between candidate files **and** between references within a file. The prefilter checks it per file rather than once for the pass: a whole-workspace scan is seconds of I/O, and cancelling has to stop it rather than let it finish and discard the result.

The prefilter pass runs first, before any analysis, and takes no *analysis* lock at all (`computeFiles` takes `project.read` per file to resolve one path to a `VirtualFile`, but nothing is held across the pass). No progress count is shown - `launchCancellableAsyncWithProgress` takes a fixed `@StringRes`, and threading a live count through it would change a shared editor API for a cosmetic gain. No timeout and no file budget: the search finishes or the user cancels.

**R10 - Panel cost.** `IDELanguageClientImpl.showLocations` used to read each result file **in full, once per hit, on the main thread** (`FileIOUtils.readFile2String` inside the per-location loop, plus an `exists()` stat per hit). That is a main-thread I/O violation and O(hits) file reads; Java's find-references had it too and simply rarely produced enough hits to hurt.

Rewritten to: group locations by file, then one sequential `BufferedReader` pass per file pulling only the lines its ranges touch, retaining nothing before moving on. The disk pass runs **off** the main thread through `TaskExecutor`, which posts its callback back to the UI thread.

A file with an **open editor** is still resolved **on** the UI thread. Its `Content` is live UI state that a background thread must not touch, and pulling a few lines out of it is substring work with no I/O. That is also what keeps unsaved edits reflected in the panel.

Reads drop from O(hits) to O(files), peak memory is one line rather than one file (deliberately *not* a per-file content cache - holding every result file's text at once is the wrong trade on a phone), and the main thread does no I/O. This removes the need for a result cap, which would otherwise silently truncate.

Because the publish is now asynchronous, it is also guarded: `showLocations` claims the panel with a request counter and captures `EditorViewModel.currentSearchGeneration`, and the callback publishes only if both still hold. Otherwise a slow request that started first would land last and overwrite the newer search the user is looking at. Panel visibility is committed *with* the rows for the same reason - a publish that never happens (superseded, or the activity recreated mid-read) must not leave the panel open with the "no results" placeholder hidden over the previous query's rows.

Two behaviour changes, both improvements: a hit whose line no longer exists is dropped rather than yielding whatever `Content` returned, and a file whose every hit is stale is omitted rather than contributing an empty group. The grouping and line extraction live in `SearchResultGrouping` so they can be unit-tested; the activity call is a thin shell.

**R11 - Not ready.** No `CompilationEnvironment` for the file (a script, a file outside the content roots), or no analysis session yet, answers empty and logs. There is no "still indexing" signal; that gap is cross-cutting across every LSP feature and is not solved here.

**R12 - Failure isolation.** A resolution failure on one candidate file drops that file and continues - one unparseable file must not lose the whole result. So does an unreadable one in the prefilter pass, and one preempted past `retryingOnPreemption`'s single retry. A failure in the target-resolution phase returns empty.

Preemption is not cancellation, and the two must not share a handler. Both unwind as a `CancellationException`, but a preempted request is still *wanted* - the user is watching the flashbar - so reporting empty for it is a wrong answer, while reporting empty for a cancelled one is invisible (the cancelled coroutine never reaches `onFindReferencesResult`). Hence a candidate file preempted past `retryingOnPreemption`'s single retry drops like any other failed candidate, and the target-resolution phase runs `planAt` twice - up to four underlying attempts - before giving up with a warning. Genuine cancellation short-circuits to empty at any depth. Nothing propagates an exception to the editor or leaves the progress flashbar up.

## Non-goals

- **Rename / safe-delete**, or anything that edits the usages found.
- **Usages via subclasses** (the down-walk). Blocked on `DirectInheritorsProvider.computeIndex()` being cached; filed separately.
- **Searching `.java` files** for usages of a Kotlin declaration. Filed separately.
- **Usages in test source sets.** Filed separately, as an LSP-wide content-root gap.
- **Implicit call sites as results** (see Scope).
- **KDoc `[link]`s as results.** Only `KtSimpleNameExpression`s are examined (R5). Go-to-definition navigates *from* a KDoc link, so this is an asymmetry, but a bounded one.
- **Library-source usages**, via decompilation or `-sources.jar`.
- **Categorising results** (imports vs calls vs type references) - the panel has no grouping beyond file.
- **A partiality signal.** `ReferenceResult` is shared with the Java and XML servers and has no field for it, and `showLocations` has no header slot; the same caveat already applies silently to test sources.
- **A gesture trigger.** Editor-wide UX change that would apply to Java too.

## Acceptance criteria

1. "Find references" appears in a Kotlin file's code-actions menu and is absent in a non-Kotlin file.
2. Same-file: a local function's call sites are listed.
3. Inter-file: usages of a class in a sibling file of the same module are listed.
4. Inter-module: usages in a dependent module are listed.
5. Invoked from a **reference** rather than a declaration, the result is the same set.
6. A `private` top-level declaration reports no usages from another file, even when that file contains a same-named unrelated declaration.
7. An `internal` declaration reports usages within its module only.
8. A local variable's usages are confined to its file.
9. `Foo()` is reported as a usage of `class Foo`.
10. An `import` of the target is reported as a usage.
11. A call dispatched via a workspace `Base.foo` is reported as a usage of `Derived.foo`, including when `Base` lives in a module `Derived`'s depends on.
12. A usage search on an override of `toString` does **not** report unrelated `.toString()` calls.
13. A usage typed into an open, unsaved file is reported.
14. A target with no usages flashes "No references found".
15. The target's own declaration never appears in the results.
16. Cancelling the progress flashbar mid-search leaves the editor responsive and unchanged.
17. Typing during a search does not discard it.
18. A search from a reference to a stdlib or framework symbol flashes "No references found".
19. A caret on whitespace, in a comment, or on a non-navigable keyword produces no search.
20. Invoking before the project finishes loading flashes "No references found" and does not crash or hang.
21. A result set spanning many files opens the panel without a main-thread stall.

## Design

Resolution goes through the Analysis API and PSI only; the symbol indexes are never consulted - see [ADR 0010](../adr/0010-navigation-resolves-via-analysis-api.md). That decision is load-bearing here for a second reason: there is no reference-search infrastructure to fall back on. `analysis-api-standalone-embeddable-for-ide` ships no `ReferencesSearch`, no `PsiSearchHelper` and no word index, and `KtFileMetadata` records declarations only. The search is built here.

```text
FindReferencesAction.execAction                   lsp/kotlin/actions
  -> ILspEditor.findReferences()                  editor (unchanged: progress flashbar + cancel checker)
    -> KotlinLanguageServer.findReferences(params)
         guards: settings.referencesEnabled(), DocumentUtils.isKotlinFile
         compilationEnvironmentFor(params.file) ?: empty                        [R11]
         -> context(env) { findUsagesAt(params) }        navigation/FindUsages.kt
              planWithRetry -> planAt(params):           (twice, each retrying a preemption once) [R12]
                ktFile = env.ktSymbolIndex.getCurrentKtFile(file).await() ?: empty [R5, R11]
                env.project.read {
                  target = targetAtCaret(ktFile, offset) navigation/TargetAtCaret.kt [R2]
                  analyzeMaybeDangling(ktFile, COMMAND, cancelChecker) {
                    planFor(target) -> simpleName, matchSet pointers, scope       [R3, R4, R6]
                  }
                }
              candidateFiles(plan, cancelChecker)                               [R5]
              per candidate file:                        (retried once if preempted) [R9]
                await live PSI if open                  (outside project.read)
                env.project.read {
                  walk name references (PSI only); no hit -> skip the file       [R5]
                  analyzeMaybeDangling(file, COMMAND, cancelChecker) {
                    restore pointers once, compare                              [R6]
                  }
                } -> locations                                                  [R7]
    <- ReferenceResult(locations)                                               [R8]
```

New components:

- **`navigation/TargetAtCaret.kt`** - `targetAtCaret(file: KtFile, offset: Int): CaretTarget?`, returning either a `Declaration` or a `Reference` so the resolution step does not re-derive which case it is looking at. Pure PSI, no analysis session, so R2's caret rules are testable without one. Shares `ReferenceAtCaret.kt`'s token accept-list, which becomes `internal`. It checks the leaf at the offset **and** the one before it, because `referenceAtCaret`'s single retry is not enough here: a caret just past `fun target` lands on `(`, which is navigable in its own right, so checking only that leaf made a caret one character past a declaration's name find nothing.
- **`navigation/FindUsages.kt`** - `planAt` (target, match set, scope) and the per-file resolve loop, reusing go-to-definition's `symbolsAt` and range helper. `planAt`, `SearchPlan` and `candidateFiles` are `internal` rather than private so the visibility ladder is directly assertable: it is *not* observable from a result set, since symbol matching means a same-named decoy can never be a false positive whatever the scope.
- **`SearchResultGrouping`** (in `app/`) - R10's grouping and line extraction.

An **ambiguous** reference at the caret (overloads, broken code) searches for its first resolved candidate and logs. The alternative is a chooser the panel cannot host, and refusing to search would be worse.

Touched existing components:

- **`KotlinLanguageServer.findReferences`** - the stub's guards stay; it now delegates inside the file's `CompilationEnvironment`, matching how `findDefinition` and `signatureHelp` dispatch.
- **`navigation/ReferenceAtCaret.kt`** - visibility loosened for reuse. Behaviour unchanged, and its existing tests are kept as the proof of that.
- **`AnalysisPriority` / `AnalysisScheduler`** - the new `COMMAND` tier, plus `retryingOnPreemption`, which holds the two invariants every command's retry depends on: a fresh `ScheduledCancelChecker` per attempt (`preempt()` latches), and re-fetching the `KtFile` inside the attempt ([ADR 0011](../adr/0011-command-analysis-priority.md)).
- **`GoToDefinitionAction`, `OrganizeImportsAction`, `ImplementMembersAction`** - migrated to `COMMAND`; the latter two gain the retry they never had, and take the delegate `ICancelChecker` rather than a pre-wrapped one since wrapping is now per attempt.
- **`GoToDefinition.symbolsAt`** - `internal`, so the reference-at-caret resolution is shared rather than duplicated.
- **`services/ModuleDependentsProvider`** - its direct- and refinement-dependents maps are now accumulated across all modules instead of built per module and merged with `Map + Map`, which *replaced* a shared dependency's dependent set. R4's last row reads that map, so a module used by more than one other silently lost every dependent but the last.
- **`IDELanguageClientImpl.showLocations`** - R10's grouped streaming rewrite, plus its staleness guard.
- **`TooltipTag`** - one new constant (R1).

Unchanged: `ReferenceParams`/`ReferenceResult`, `ILanguageServer`, `IDEEditor`, and every string resource.

## Verification

`flox activate -d flox/local -- ./gradlew :lsp:kotlin:testV7DebugUnitTest` and `:app:testV7DebugUnitTest`, split to match the helpers:

- **`TargetAtCaretTest`** (13) - PSI only, no session. Caret on a function's / class's / property's / parameter's own name; caret on a reference rather than the enclosing declaration; one past a declaration's name; a local declaration inside a lambda; a destructuring entry targeting the local rather than `componentN`; an operator; whitespace / comment / non-navigable keyword. One case asserts the contrast directly: the same caret that `referenceAtCaret` rejects still yields a target.
- **`ReferenceAtCaretTest`** - kept as-is, as the regression proof that loosening visibility changed no behaviour.
- **`FindUsagesTest`** (20) - the `lib` + `app(dependsOn = lib)` fixture from ADFA-4823: the three resolution scopes; each row of R4's visibility ladder, asserted on the plan's scope rather than the result set; R3's super-walk, workspace-boundary cutoff and constructor expansion; imports; a Java-source target; a same-named decoy in another package; ordering; property reads and writes; a stdlib reference; a caret that names nothing; and a pre-cancelled request.
- **`FindUsagesLiveDocumentTest`** (2) - R5's live tier, which needs `enableParserEventSystem`: a usage that exists only in an unsaved buffer is found, and one deleted in the buffer but still on disk is not.
- **`AnalysisSerializationTest`** (+5) - `COMMAND`'s three ordering properties, plus `retryingOnPreemption`'s one-retry-with-a-fresh-checker contract and its refusal to loop.
- **`KotlinCodeActionTooltipTagTest`** - the new tag row.
- **`SearchResultGroupingTest`** (10, in `:app`) - single-line and multi-line hits, a hit on a line that no longer exists, a column past its line's end, only-the-wanted-lines collection, a short file, an unreadable file, and several hits in one file from one read.

Not unit-testable, so covered by on-device QA via the "Steps to QA" field on ADFA-4824: the menu item and its tooltip tag, the panel with a large result set, cancelling mid-search, and typing during a search without losing it.

## Related

- [docs/features/kotlin-goto-definition.md](kotlin-goto-definition.md) - the sibling feature whose helpers and fixture this reuses
- [ADR 0010](../adr/0010-navigation-resolves-via-analysis-api.md) - navigation resolves via the Analysis API, not the symbol index
- [ADR 0011](../adr/0011-command-analysis-priority.md) - user-invoked commands get their own analysis priority
- [ARCHITECTURE.md](../../ARCHITECTURE.md)
