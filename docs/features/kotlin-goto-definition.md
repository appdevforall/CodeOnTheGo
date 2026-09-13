# Kotlin go-to-definition (K2 LSP)

- **Ticket:** ADFA-4823 (subtask of ADFA-3317; split out of the closed ADFA-3321 "Navigation")
- **Status:** Implemented in `lsp/kotlin/navigation/`, pending on-device QA
- **Module:** `lsp/kotlin`

Jump from a Kotlin symbol reference to the declaration it resolves to, across three scopes: same file, another file in the same module, another module in the workspace.

`KotlinLanguageServer.findDefinition` dispatches into `navigation/`; everything downstream of it (the editor's multi-result panel, `DefinitionResult`, `IDEEditor`) already existed.

## Language

**Reference**:
A Kotlin PSI element that names something declared elsewhere - an identifier in a call, a type position, an import, an annotation, a named argument.
_Avoid_: usage, symbol reference, occurrence.

**Declaration**:
The PSI element a reference resolves to. This is what the feature navigates to, even though the feature is called "go-to-definition".
_Avoid_: definition (reserve that for the user-facing verb), target, decl.

**Candidate**:
One declaration a reference resolved to. A reference normally has exactly one; ambiguous overloads and broken code can yield several.
_Avoid_: match, result, hit.

**Location**:
The existing `com.itsaky.androidide.models.Location` (file + range) that a candidate is converted into for transport to the editor. A candidate has a location only if its declaration lives in a workspace source.
_Avoid_: position, target, site.

**Workspace source**:
A `.kt` or `.java` file inside a source module's content roots, including generated sources under `build/generated/**`. Contrast with a **binary symbol**, which lives in a jar or the JDK and has no source file on device.
_Avoid_: project file, local file, user code.

**Resolution scope**:
Where the declaration lives relative to the reference: **same-file**, **inter-file** (same module), or **inter-module**. These are the three scopes the ticket enumerates; they are a way to talk about coverage, not three code paths.

**Convention reference**:
A reference with no name to point at, where the compiler picks a declaration by convention: `a + b` -> `plus`, `a[i]` -> `get`, `val (x, y) = p` -> `component1`, `by lazy` -> `getValue`, a `for` loop -> `iterator`. It resolves through a resolved-call lookup rather than a name reference, but navigates to a declaration like any other.
_Avoid_: implicit call, synthetic reference.

## Scope

### In scope

Any reference whose declaration is a workspace source, in all three resolution scopes. Kotlin references into workspace `.java` sources count - Java files are part of a Kotlin source module's content scope (`AbstractSourceModule.computeBaseContentScope`), so a Kotlin call into a Java class in the same project navigates.

Convention references are in scope too. They need a second resolution path on top of `mainReference` - a resolved-call lookup - but that path is a single expression (`resolveToCall()?.successfulFunctionCallOrNull()?.symbol`) that `utils/ImportUsageCollector.kt` already uses, and everything after it (symbol -> PSI -> `Location`) is shared with the name-reference path. KDoc `[links]` need no second path at all: `KtElement.mainReference` already has a `KDocName` branch.

### Out of scope

Binary symbols - the Kotlin stdlib, the Android framework, and every library jar. There is no decompiler and no source-jar handling anywhere in the repo, and `IDELanguageClientImpl.showDocument` only opens a real, existing, UTF-8 file on disk. A jump onto `listOf` or `Activity` reports "Definition not found", the same as a genuine resolution failure. Distinguishing the two would mean a new field on `DefinitionResult`, which is shared with the Java and XML servers.

## Requirements

**R1 - Trigger.** A "Go to definition" item appears in the editor code-actions menu for `.kt`/`.kts` files, mirroring Java's. It reuses `R.string.action_goto_definition` and delegates to `ILspEditor.findDefinition()`. It is invisible for non-Kotlin files.

It carries its **own** tooltip tag, `EDITOR_CODE_ACTIONS_KT_GOTO_DEF = "editor.codeactions.kotlin.gotodef"` - a new constant in `TooltipTag.kt`, not Java's `EDITOR_CODE_ACTIONS_GOTO_DEF` - so Kotlin and Java go-to-definition can carry different tooltip text. This follows the existing split for fix-imports (`editor.codeactions.kotlin.fiximports`). Tooltip *content* is keyed by tag in the tooltips database, which is not in this repo; `ToolTipManager.getTooltip` logs and returns null on a miss, so the new tag shows no tooltip text until a row exists for it. That row is a hand-off item, not code.

**R2 - Caret mapping.** Resolution starts from the reference at the caret offset. If there is no reference there, retry at `offset - 1`, so a caret resting just past an identifier still works - touch caret placement is imprecise.

A caret position is navigable only if its token is one of: an identifier; `this` or `super`; `in` (for-loop convention) or `by` (property delegate); `(` (`invoke`) or `[` (`get`/`set`); an operator token; a KDoc name. A caret on whitespace, a comment, a string body, a brace, a literal, or any other keyword yields no candidates.

A caret on a declaration's **own** name also yields no candidates; there is no self-jump, which would read as a broken no-op. The one exception is a destructuring entry - `x` in `val (x, y) = p` is both a declaration and a convention reference, and it navigates to `component1`.

Both rules are enforced by construction rather than by filtering afterwards: an accept-list of caret tokens, and a walk from that token up **at most two PSI levels** to find the reference. The cap is what prevents a caret on the name of a local `fun foo` declared inside `run { ... }` from climbing out of the declaration into the enclosing call and navigating to `run`.

**R3 - Live offsets.** The caret offset is interpreted against the live document contents, not an async-lagged PSI snapshot - an offset resolved against stale text points at the wrong element. In practice that means `KtSymbolIndex.getCurrentKtFile(path)`, which refreshes PSI to the open document's current version, rather than a cached or on-disk `KtFile`.

**R4 - Coverage.** Two resolution paths, tried in order: `KtElement.mainReference.resolveToSymbols()`, then - when there is no `mainReference` or it yields nothing - `resolveToCall()?.successfulFunctionCallOrNull()?.symbol`. Everything after that point is shared, so the paths differ only in how the symbols are obtained.

| Reference | Declaration navigated to |
|---|---|
| local variable, parameter | its declaration |
| property read/write | the property (or its Java field) |
| function call, extension call | the function |
| infix / operator-named call written as a call | the function |
| class, object, interface, enum entry | the classifier |
| type reference, generic argument | the classifier |
| constructor call | the invoked constructor; the class when there is no explicit one |
| import directive | the imported declaration |
| package reference | nothing (no candidates) |
| annotation | the annotation class |
| typealias reference | the typealias declaration, not its expansion |
| companion / object reference | the companion or object |
| named argument | the corresponding parameter |
| `super<T>`, super constructor delegation | the supertype's member or constructor |
| label (`return@foo`) | the labelled expression |
| KDoc `[link]` | the linked declaration |
| operator (`a + b`, `a[i]`, `f()`) | `plus`, `get`/`set`, `invoke` |
| destructuring entry (`val (x, y) = p`) | that entry's `componentN` |
| property delegate (caret on `by`) | `getValue`/`setValue` |
| for-loop (caret on `in`) | `iterator`, `hasNext`, `next` - three candidates, so the multi-result panel |

**R5 - Candidates.** All resolved symbols are considered, not just the first. Candidates whose declaration has no workspace source PSI are dropped - the test is symbol **origin** (`sourcePsiSafe()`, non-null only for `SOURCE` and `JAVA_SOURCE`), not a null check on the PSI, because a library symbol has a non-null PSI pointing into a class file. Survivors are deduplicated by file plus range and ordered by file path then start offset.

**R6 - Location range.** A candidate's range covers the declaration's **name identifier** (`PsiNameIdentifierOwner.nameIdentifier`), so the editor highlights just the name. When there is no name token - an anonymous object, an `init` block, an implicit primary constructor - the range collapses to the declaration's start offset. A property accessor resolves to its enclosing property; a constructor with no PSI of its own resolves to its class, which is what makes R4's "the class when there is no explicit constructor" fall out.

**R7 - Result handling.** The server returns `DefinitionResult(locations)`; the editor's existing logic in `IDEEditor.onFindDefinitionResult` applies unchanged:

- empty -> flash `msg_no_definition`
- one location in the current file -> `setSelection`
- one location elsewhere -> `showDocument`, which opens the file and selects the range
- more than one -> `languageClient.showLocations`, the search-results panel

**R8 - Generated sources.** Declarations under `build/generated/**` are valid targets; they are already in a module's content roots (`AndroidModule.getSourceDirectories`). Two documented caveats: the target reflects the last build, and `R` normally comes from `R.jar`, so `R.string.foo` reports "Definition not found". The generated file opens read-write, which is pre-existing IDE-wide behaviour.

**R9 - Not ready.** When there is no analysis session yet, or the file maps to no module (a script, a file outside the content roots), the server logs and returns an empty result. There is no dedicated "still indexing" signal; that gap is cross-cutting across every LSP feature and is not solved here.

**R10 - Responsiveness.** The request runs off the main thread through the editor's existing cancellable progress flashbar (`msg_finding_definition`). No `project.read` on the UI thread - the project lock is a plain `ReentrantReadWriteLock` and a UI-thread read can block behind a background index write. The action's `prepare()`, which *does* run on the UI thread, adds no lock, index or analysis work of its own; it reads only `ActionData`. It is **not** filesystem-free, because `BaseKotlinCodeAction.prepare` calls `DocumentUtils.isKotlinFile`, which stats the file (`Files.exists` plus `Files.isDirectory`). That is pre-existing and shared by every Java and Kotlin code action, so it is not fixed here, but it does mean the menu still does two stat calls per action on the UI thread - worth a separate ticket against `DocumentUtils`. The live-document await (R3) happens **outside** `project.read`, because the refresh it waits on needs `project.write` and awaiting it under the read lock would deadlock (`KotlinSignatureHelp.kt` records the same constraint). `params.cancelChecker` is honoured between candidate resolutions and before returning. No numeric latency budget: the repo has no LSP benchmark harness, so a number would be unverifiable.

**R11 - Failure isolation.** A resolution failure returns an empty result and logs; it never propagates an exception to the editor and never leaves the progress flashbar up.

## Non-goals

- **Find usages** - ADFA-4824, the sibling subtask; see [kotlin-find-usages.md](kotlin-find-usages.md).
- **Go-to-implementation.** A call through an interface or abstract member resolves to the declaring member only. Walking down to overriding implementations needs an inheritance search over the workspace.
- **Go-to-super.**
- **Library-source navigation**, via decompilation, generated stubs, or `-sources.jar` extraction.
- **A gesture trigger** (long-press or double-tap to navigate). That is an editor-wide UX change that would apply to Java too.
- **Read-only buffers for generated files.**

## Acceptance criteria

1. Go to definition appears in the code-actions menu of a Kotlin file and is absent in a non-Kotlin file.
2. Same-file: a call to a top-level function declared above it selects that function's name.
3. Inter-file: a reference to a class declared in a sibling file opens that file with the class name selected.
4. Inter-module: a reference to a class in a dependency module opens that module's file.
5. A reference to a workspace Java class from Kotlin navigates.
6. A reference to a stdlib or framework symbol flashes "Definition not found".
7. An ambiguous reference lists its candidates in the search-results panel.
8. The caret placed immediately after an identifier resolves the same as inside it.
9. The caret on a declaration's own name produces no navigation.
10. Cancelling the progress flashbar mid-request leaves the editor responsive and unchanged.
11. Invoking before the project finishes loading flashes "Definition not found" and does not crash or hang.
12. The caret on an operator between two workspace types navigates to that type's operator function.
13. The caret on `by` in a delegated property whose delegate is a workspace class navigates to its `getValue`.
14. The caret on a destructuring entry navigates to the matching `componentN`.
15. The caret on a KDoc `[link]` navigates to the linked declaration.
16. The caret on whitespace, inside a comment, or on a non-navigable keyword produces no navigation.

## Design

Resolution goes through the Analysis API and PSI only; the symbol indexes are never consulted - see [ADR 0010](../adr/0010-navigation-resolves-via-analysis-api.md).

```
GoToDefinitionAction.execAction                   lsp/kotlin/actions
  -> ILspEditor.findDefinition()                  editor (unchanged: progress flashbar + cancel checker)
    -> KotlinLanguageServer.findDefinition(params)
         guards: settings.definitionsEnabled(), DocumentUtils.isKotlinFile
         compilationEnvironmentFor(params.file) ?: empty                        [R9]
         -> context(env) { findDefinitionAt(params) }    navigation/GoToDefinition.kt
              ktFile = env.ktSymbolIndex.getCurrentKtFile(file).await() ?: empty [R3, R9]
              env.project.read {                                                [R10]
                element = referenceAtCaret(ktFile, offset)  navigation/ReferenceAtCaret.kt [R2]
                analyzeMaybeDangling(ktFile) { symbolsAt(element) }              [R4]
              } -> locations                                                    [R5, R6]
    <- DefinitionResult(locations)                                              [R7]
```

The dispatch mirrors `signatureHelp` line for line, which is what buys R3 and R10 - `getCurrentKtFile(...).await()` returns PSI refreshed to the live document's version, and awaiting it outside `project.read` avoids deadlocking against the refresh's `project.write`.

Touched components:

- **`KotlinLanguageServer.findDefinition`** - guards stay (`definitionsEnabled()`, `isKotlinFile`), then delegates inside the file's `CompilationEnvironment`, matching how `signatureHelp` and `analyze` already dispatch. A `.kts` has no environment, so the lookup returns null there and the request answers empty.
- **`navigation/ReferenceAtCaret.kt`** - `referenceAtCaret(file: KtFile, offset: Int): KtElement?`. Pure PSI, no analysis session: the caret-token accept-list, the `offset - 1` retry, and the two-level climb (R2). ADFA-4824 reuses its accept-list and retry, but not the function: this deliberately resolves nothing when the caret is on a declaration's own name, which is exactly where find usages is invoked from. See [kotlin-find-usages.md](kotlin-find-usages.md) R2.
- **`navigation/GoToDefinition.kt`** - `findDefinitionAt(params)` under `context(env: CompilationEnvironment)`. The two symbol paths (R4), then symbol -> source PSI -> name-identifier range -> `Location`, with dedup, ordering, cancellation and failure isolation (R5, R6, R10, R11).
- **`GoToDefinitionAction` in `lsp/kotlin/actions`** extending `BaseKotlinCodeAction`, id `ide.editor.lsp.kt.gotoDefinition` (the prefix every other Kotlin action uses), `requiresUIThread = true` like Java's, registered in `KotlinCodeActionsMenu` after the comment actions - the same slot Java uses.
- **`TooltipTag.EDITOR_CODE_ACTIONS_KT_GOTO_DEF`** - one new constant (R1).
- **`KtLspTestEnvironment` / `KtLspTestRule`** - accept a `List<TestSourceModuleSpec>` (`name`, `dirName`, `dependsOn`), defaulting to today's single `src` module so no existing test changes. Today every test source module depends only on the JDK, stdlib and extra jars, so inter-module resolution cannot be exercised at all.

Splitting caret-from-resolution is the one structural decision here: it makes the R2 caret rules testable with no analysis session (as `CallAtCursorFinderTest` already does for signature help), and gives ADFA-4824 a helper it can use unchanged. A single `resolveDeclarationsAt(file, offset)` entry point would force both features through a full session; a shared `ResolvedReference` value type cannot work, because `KaSymbol` is session-scoped and must not escape the `analyze` block.

Unchanged: `DefinitionParams`/`DefinitionResult`, `ILanguageServer`, `IDEEditor`, `IDELanguageClientImpl`, and every string resource. R8 needs no code - `build/generated/**` is already inside module content roots.

## Verification

Unit tests in `:lsp:kotlin` (`flox activate -d flox/local -- ./gradlew :lsp:kotlin:testV7DebugUnitTest`), split to match the two helpers:

- **`ReferenceAtCaretTest`** - no analysis session, PSI only. The R2 rules: inside vs one-past an identifier, whitespace/comment/non-navigable keyword, a declaration's own name, the climb cap (`run { fun foo() {} }` must not navigate to `run`), and each navigable convention token.
- **`GoToDefinitionTest`** - one case per R4 row, all three resolution scopes (inter-module via a two-spec fixture: `lib`, and `app` with `dependsOn = listOf("lib")`), a workspace Java target, a stdlib reference yielding nothing, multi-candidate dedup and ordering (R5), the range rules (R6), and a pre-cancelled `cancelChecker` returning empty without resolving (R10).

The action's `prepare()`/`ActionData` path is not unit-testable, consistent with the other Kotlin code actions. It is covered by on-device QA, along with the multi-result panel, cancellation, and the new tooltip tag, via the "Steps to QA" field on ADFA-4823.

## Related

- [ADR 0010](../adr/0010-navigation-resolves-via-analysis-api.md) - navigation resolves via the Analysis API, not the symbol index
- [ARCHITECTURE.md](../../ARCHITECTURE.md)
