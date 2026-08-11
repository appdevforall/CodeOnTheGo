# Kotlin extract method (K2 LSP)

- **Ticket:** ADFA-5080 (subtask of ADFA-3317; split out of ADFA-4826, which now covers extract variable only)
- **Status:** Requirements only - not implemented
- **Module:** `lsp/kotlin`
- **Vocabulary:** the term is **method**, matching the ticket and the already-fixed tooltip tag `editor.codeactions.kotlin.extractmethod`, even though the refactoring's output is a Kotlin `fun`.

Move the expression at the cursor, or a selected range of statements, into a new function, and replace it with a call to that function.

Ships as the top of a three-PR stack: `common-compose` theming, then extract variable (ADFA-4826), then this. It reuses that PR's primitives - offsets, naming, indentation, edit emission - and adds no new module, no new dependency and no new UI mechanism.

The governing principle is [ADR 0013](../adr/0013-refactorings-decline-rather-than-rewrite.md): this refactoring **moves** code, it never edits the interior of what it moved, and where it cannot do that faithfully it **declines with a specific reason** rather than guessing. Most of the requirements below are that principle applied to one case each.

## Language

Shared vocabulary - *selection*, *extraction region*, *expression candidate*, *text span*, *occurrence*, *refactoring plan*, *rewrite span* - is defined once in [kotlin-extract-variable.md](kotlin-extract-variable.md#language). This feature adds:

**Statement range**:
One or more *sibling* statements inside a single `KtBlockExpression`, snapped outward from the selection to whole statement boundaries. The second kind of extraction region; the first is an expression candidate.
_Avoid_: statement list, block, selection.

**Enclosing declaration**:
The named function, property accessor or `init` block whose body contains the extraction region. It is both the boundary that decides what becomes a parameter and the sibling anchor the new function is inserted after.
_Avoid_: parent function, host, owner.

**Captured declaration**:
A declaration the region references whose PSI lies *inside* the enclosing declaration - a local, a function or lambda parameter, `it`, a destructuring entry, a loop variable. Each becomes a **parameter**. Anything else (class members, top-level declarations, imports) resolves unchanged from the new function body and needs no parameter.
_Avoid_: free variable, capture, dependency.

**Output**:
The single value that flows out of the region and is still needed after it - a local declared inside the region and read after it. Zero outputs means the extracted function returns `Unit`; two or more is declined.
_Avoid_: result, return value (that's the extracted function's `return`, which an output is only one cause of).

**Exit**:
A `return`, `break`, `continue` or non-local return inside the region whose target lies outside it. Declined, except the tail return (R8).
_Avoid_: jump, control flow, early return.

**Refusal**:
A typed reason (`ExtractionRefusal`) the region could not be extracted, carried on the plan and rendered as a specific message. A refusal is a designed outcome, not an error.
_Avoid_: failure, error, invalid.

## Scope

### In scope

An expression, or a range of sibling statements, inside any executable body - a function body, an accessor, an `init` block, a constructor, or a lambda - in a Kotlin file.

### Out of scope

The positions extract variable already rejects, for the same reasons and via the same `isExtractionPosition` check: annotation arguments, default parameter values, super-constructor delegation arguments, and anything outside an executable body (notably a class-body property initializer).

## Requirements

**R1 - Trigger.** An "Extract method" item (`action_extract_method`) in the editor code-actions menu for Kotlin files, id `ide.editor.lsp.kt.extractMethod`, tooltip tag `EDITOR_CODE_ACTIONS_KT_EXTRACT_METHOD = "editor.codeactions.kotlin.extractmethod"` - a new constant in `TooltipTag.kt`. The tag string is fixed: tooltip *content* lives in the out-of-repo tooltips database keyed by tag, so it cannot be renamed here.

As with extract variable: **no `prepare()` visibility gate** (deciding extractability needs an analysis session, far too costly for the UI thread), and `requiresUIThread = false` so the selection is read on a background thread.

**R2 - Region.** The selection resolves to exactly one extraction region, of one of two kinds.

*Expression candidate* - reuses `candidateExpressionsAt` unchanged, including whitespace trimming, the `offset - 1` cursor retry, the innermost-first walk, `MAX_CANDIDATES = 3`, the legal-target rules and `selectionMatchedCandidate`. A bare cursor always takes this path.

*Statement range* - a non-empty selection that spans statement boundaries snaps **outward** to whole statements: a touch selection will not land on a boundary. The result must be 1..N statements that are **siblings in one `KtBlockExpression`**. A selection spanning two different blocks, or partially covering a statement that cannot be snapped, is declined (`NotASingleRegion`).

Restricting to siblings in one block excludes every hard case - a selection covering half an `if` and half its `else`, a range straddling a lambda boundary - by construction rather than by later filtering, exactly as `isLegalExtractionTarget` excludes expression fragments today.

**R3 - Live offsets and the version guard.** Identical to extract variable: analysis runs against `getCurrentKtFile(path)` fetched *before* entering `project.read`, the plan records the document version, and the version is re-read on confirm with a mismatch refusing the edit. Shared via the `RefactoringPlan` supertype.

**R4 - Target.** One uniform rule, no target picker: **the new function is inserted as a sibling of the enclosing declaration**, immediately after it. That one rule produces the conventional answer in every context:

| The region sits in | The new function becomes |
|---|---|
| a member function, accessor or `init` of a class | a `private fun` member of that class |
| a top-level function or property | a `private` top-level `fun` |
| a lambda inside either of the above | still a sibling of the enclosing *named* declaration; the lambda's captures become parameters |
| a local `fun` or local class | a local `fun` in the enclosing block, since the sibling *is* a statement there |
| a companion object body | a member of the companion |

Unlike extract variable there is no scope chain and no ceiling, because anything not visible at the insertion site becomes a parameter instead of constraining the anchor.

**R5 - Parameters.** A referenced declaration needs a parameter exactly when it is a captured declaration - its PSI lies inside the enclosing declaration. Members of the enclosing class need nothing, because the new function is a member of that same class.

- **Order** - first textual appearance in the region, so the signature reads in the order the body uses it.
- **Names** - the original identifier, unchanged. `it` becomes a parameter literally named `it`, which is legal Kotlin, and the call site passes `it`.
- **Types** - the resolved type rendered with the existing `renderName(KaType)`. A type that cannot be rendered - an anonymous or intersection type, or a resolution failure - **declines the extraction** (`UnrenderableType`) rather than emitting uncompilable text.
- **Not editable.** The derived signature is shown read-only (R11). Renaming, reordering or excluding parameters is a desktop-sized dialog; a wrong parameter *name* is fixable afterwards with rename (ADFA-4825), and a wrong parameter *set* is not something the user could correct by hand anyway.

**R6 - Return type and call-site form.** Determined by the region kind and its output:

| Case | Extracted body | Call site |
|---|---|---|
| expression candidate | `return <expr>` | `extracted(args)` in the expression's place |
| statement range, no output | the statements; returns `Unit` | `extracted(args)` as a statement |
| statement range, one output `x` | the statements, then `return x` | `val x = extracted(args)` |
| statement range, tail return (R8) | the statements including the `return` | `return extracted(args)` |

A region that always throws still declares `Unit`; the exception propagates and the call site behaves identically, so `throw` needs no rule of its own.

**R7 - Outputs.** An output is a local declared inside the region and read after it. Exactly one is supported; **two or more declines** (`MultipleOutputs`, naming them).

A `var` declared outside the region and **reassigned inside it declines** (`ReassignsOuterVar`, naming the variable), because Kotlin has no `out` parameters and the faithful emission - a parameter plus `var x = x` at the top of the body - carries a name-shadowing warning into generated code. This is deliberately stricter than dataflow requires: a reassignment whose result is never read afterwards is still refused, because proving that needs real liveness analysis. ADFA-5082 tracks supporting it.

The refused case is the accumulator loop, which is a genuinely common extraction, so its message must name the variable and read as a limitation rather than a malfunction.

**R8 - Exits.** Every exit declines (`ExitsRegion`), with one syntactic exception.

**Tail return:** when the region's *last* statement is a `return`, the region contains no other `return`, `break` or `continue`, and there is no other output, the extracted function takes the enclosing function's return type, keeps the `return`, and the call site becomes `return extracted(args)`. "Extract the rest of this function into a helper" is one of the most common real extractions and the enabling check is purely syntactic - last-child kind plus a recursive absence check - so it costs a predicate and one call-site form, not an analysis.

Declined: a `return` anywhere but the tail position, a `break`/`continue` whose target loop is outside the region, a labelled `return@` whose target is outside it, and a non-local return from an inlined lambda. Each would silently change meaning, since a `return` in the extracted body returns from *it*.

**R9 - Receivers.**

- **Class dispatch receiver** - nothing to do; the new function is a member of the same class.
- **The enclosing declaration's extension receiver** - the new function is generated as an extension on the **same receiver type**, copied syntactically from the enclosing declaration's receiver type reference. The call site needs no change at all: inside `fun Foo.original()`, `this` is a `Foo`, so `extracted(args)` resolves to `private fun Foo.extracted(args)`.
- **An implicit receiver introduced inside the enclosing declaration** - the `with(x) { ... }` / `apply` / `run` / `buildString` case - **declines** (`InnerImplicitReceiver`). Turning that receiver into a parameter would require qualifying every unqualified member access inside the extracted body, which is editing the interior of the moved code. Android code uses these scoping functions heavily, so this refusal will be common and its message must say which construct is in the way.

**R10 - Modifiers.** Copy nothing from the enclosing declaration; add only what the body needs in order to compile in its new home.

- **Visibility** - always `private`, whether a class member or top-level. Never `internal`, never `open`, no annotations copied, no KDoc generated.
- **`suspend`** - added when any call in the region resolves to a suspend function, or the region references `coroutineContext`. The call site is necessarily already a suspend context.
- **`@Composable`** - added when any call in the region resolves to a `@Composable`-annotated function. This is not polish: CoGo users write Compose apps on the device, and an extracted composable without the annotation does not compile.
- **Function-level type parameters** - a region referencing a type parameter declared on the *enclosing function* **declines** (`UsesTypeParameter`, naming it). Class-level type parameters need no rule; they stay in scope for a member. A filtered copy of the enclosing type-parameter list with its bounds would mean deciding "is `T` referenced" from rendered type text, which is fragile.

`suspend` and `@Composable` are the two cases where omitting a modifier produces non-compiling code, which is why they are requirements while everything else is left off.

**R11 - Sheet.** A sibling of the extract-variable sheet, not a generalisation of it: `ExtractMethodSheet` (a `BottomSheetDialogFragment` hosting a `ComposeView`), a stateless `ExtractMethodSheetContent`, `ExtractMethodViewModel` + `ExtractMethodUiState` + a sealed `ExtractMethodUiEvent`. `LabelledSection` and `OptionList` are promoted to a shared internal file in `refactor/ui/`.

Contents, top to bottom: title -> expression chooser (only for an expression region with more than one candidate and no exact selection match) -> name field with its `NameProblem` message -> signature preview -> Cancel/Extract. There is **no scope chooser** (R4) and **no replace-all checkbox** (R13).

The preview is **one monospace line: the signature exactly as it will be emitted** - modifiers, receiver, parameters, return type, e.g. `private suspend fun loadUser(id: String): User`. It wraps rather than truncating. No body preview: the body is the code the user selected and can see behind the sheet, so it moves verbatim and previewing it says nothing new, while the signature is the one derived artefact and the one place the derivation can surprise them.

ADR 0012 defers the shared-UI question until the extract-method surface is known; a single generalised sheet would need a state class where half the fields are meaningless to either caller, so that question stays open rather than being settled from one data point.

**R12 - Name.** Suggestion: for an expression region, the existing shape/type derivation unchanged; for a statement range, the constant `extracted`, since there is no expression to read a name from and inventing a verb from statement shapes is guesswork. Uniquified as today.

Validation reuses `validateVariableName` and `NameProblem` unchanged - so no new error strings - with taken names being **every callable name visible in the insertion container, including inherited members** (the container's `memberScope`, not just its declared members) for a class target; every top-level declaration name in the file for a top-level target; enclosing-block declarations for a local target.

Including inherited names is a correctness requirement, not a nicety: a private function accidentally matching a supertype member is an accidental-override compile error. Rejecting *any* name match rather than only a signature match also means the refactoring never creates an overload the user did not ask for.

**R13 - One call site.** The region is the only site rewritten. No duplicate detection, no replace-all toggle: exact-duplicate matching would almost never fire, and near-duplicate matching needs anti-unification plus a per-site parameter mapping - a feature in its own right. `Occurrences.kt` is expression-granular by construction.

**R14 - Refusals.** The plan carries a typed `ExtractionRefusal` rather than merely being empty, and `postExec` maps it to a specific message:

| Reason | Message intent |
|---|---|
| `NotASingleRegion` | select an expression, or whole statements inside one block |
| `MultipleOutputs` | the selection produces more than one value |
| `ReassignsOuterVar` | the selection assigns to `<name>`, declared outside it |
| `ExitsRegion` | the selection jumps out of itself (`return`/`break`/`continue`) |
| `InnerImplicitReceiver` | the selection uses members of an enclosing `with`/`apply` receiver |
| `UsesTypeParameter` | the selection uses type parameter `<T>` |
| `UnrenderableType` | a type in the selection cannot be written out |

Five of the seven are actionable - they tell the user what to change - and two of them (`ReassignsOuterVar`, `InnerImplicitReceiver`) are common enough that a generic message would read as the feature being broken. Given how much of this design is "decline cleanly", the refusal text is a first-class part of the feature. New entries in `resources/.../values/strings.xml`, picked up by the next translation batch.

The refusal lives on `ExtractMethodPlan` only; extract variable keeps its single "nothing to extract" behaviour unchanged.

**R15 - Edit.** Two regions change - the region becomes a call, and the new function appears after the enclosing declaration - emitted as **two `TextEdit`s in one `DocumentChange`, ordered new-function-first (descending document order)**.

The ordering is mandatory, not stylistic. `IDELanguageClientImpl.applyActionEdits` iterates the edit list in order and `editInEditor` applies each with **line/column** ranges against whatever the text is at that moment (the `index` in `Position` is ignored). Emitting the call site first would shift the insertion point and corrupt the file.

**Known consequence:** nothing on that path calls `beginBatchEdit`, so this is **two undo entries**, and a single undo leaves a half-refactored, non-compiling file. This knowingly diverges from `RewriteSpan`'s single-replacement rule, which extract variable relies on. **ADFA-5081** fixes it properly by batching the edit loop in `applyActionEdits`, which benefits every multi-edit action; until it lands, the two-step undo is a stated limitation to be covered in QA.

The new function is emitted **fully indented** at the enclosing declaration's own indentation, separated by one blank line, reusing `detectIndentUnit`, `detectNewline`, `leadingIndentAt` and `positionAt`. Code-action edits bypass the editor's auto-indent and `CMD_FORMAT_CODE` is a no-op for Kotlin.

**R16 - Responsiveness and failure isolation.** As extract variable: one background pass at `AnalysisPriority.INTERACTIVE` under a cancel checker tied to the action's coroutine produces the whole plan; the sheet does pure string and offset arithmetic and re-enters no analysis on confirm. Anything thrown in the pipeline degrades to a refusal plus a log line, never an uncaught throw - the action framework catches only `IllegalArgumentException` and this runs on a scope with no exception handler.

## Non-goals

- **Duplicate or near-duplicate call sites** (R13).
- **An editable parameter list** - rename, reorder or exclude (R5).
- **Two or more outputs, and a reassigned outer `var`** (R7). The latter is ADFA-5082.
- **Mid-region `return`/`break`/`continue`** (R8).
- **Inner `with`/`apply`/`run` receivers** (R9).
- **Function-level type parameters** (R10).
- **Choosing a different target** - another class, another file, a local `fun` when a member is possible, or a property instead of a function (R4). Moving a declaration elsewhere is a move refactoring.
- **Extraction from a property initializer or annotation argument** - inherited from `isExtractionPosition`.
- **Generated KDoc** for the new function.
- **Post-extract inline rename** of the new name in the editor - ADFA-4825.
- **Atomic undo** of the two edits - ADFA-5081.
- **Formatting the result.** R15 emits indented text instead.
- **Java extract method** - ADFA-5048.

## Acceptance criteria

1. "Extract method" appears in the code-actions menu of a Kotlin file and is absent in a non-Kotlin file.
2. A cursor inside an expression offers the innermost-first candidates; extracting one replaces it with a call and adds a `private fun` returning that expression, directly below the enclosing function.
3. Selecting two adjacent statements that use two locals produces a function with those two locals as parameters, in first-use order, and a call passing them.
4. A selection with ragged boundaries snaps outward to whole statements before extracting.
5. A selection spanning two different blocks reports "select an expression, or whole statements inside one block".
6. A range declaring a local that is read afterwards produces `val x = extracted(...)` at the call site.
7. A range declaring two locals that are both read afterwards is declined as producing more than one value.
8. Selecting a loop that accumulates into an outer `var` is declined, and the message names that variable.
9. Selecting the tail of a function ending in `return x` produces `return extracted(...)` and a function with the enclosing return type.
10. Selecting a range containing a `return` in the middle is declined.
11. Selecting a range with a `break` targeting a loop outside it is declined.
12. Extracting from inside `fun Foo.bar()` when the region touches `Foo`'s members produces `private fun Foo.extracted(...)`, and the call site is unchanged.
13. Extracting from inside a `with(x) { ... }` block whose region uses `x`'s members is declined, and the message names the construct.
14. A region calling a suspend function produces a `suspend fun`.
15. A region calling a `@Composable` produces a `@Composable` function that compiles.
16. A region using a type parameter of the enclosing function is declined, naming the parameter.
17. A name matching an existing member - including an inherited one - is rejected with "That name is already used".
18. The signature preview matches the emitted declaration exactly, including modifiers and receiver.
19. Editing the file while the sheet is open, then confirming, reports the file-changed message and leaves the file untouched.
20. Undo restores the file; it currently takes **two** undo steps (R15), and the intermediate state is non-compiling.
21. A space-indented file receives space-indented output; a CRLF file keeps CRLF.

## Design

Same shape as extract variable, and the same data boundary from [ADR 0012](../adr/0012-refactoring-ui-lives-in-the-owning-lsp-module.md): one background pass produces a plain-data plan, the sheet holds no PSI.

```
ExtractMethodAction.execAction (background)                lsp/kotlin/actions
  server.compilationEnvironmentFor(path) ?: refusal
  -> buildExtractMethodPlan(...)                           utils/refactor/ExtractMethodPlanner.kt
       ktFile = env.ktSymbolIndex.getCurrentKtFile(path).get()          [R3: before project.read]
       env.project.read {
         resolveRegion(ktFile, start, end)                 utils/refactor/ExtractionRegion.kt      [R2]
           expression -> candidateExpressionsAt(...)       (reused unchanged)
           statements -> snap outward, sibling check
         analyzeMaybeDangling(INTERACTIVE, cancelChecker) {                                        [R16]
           captured declarations -> parameters             utils/refactor/MethodSignature.kt       [R5]
           outputs / exits / receivers / modifiers                                          [R6-R10]
           -> ExtractMethodPlan | ExtractionRefusal                                               [R14]
         }
       }
  <- ExtractMethodPlan (plain data, no PSI)

ExtractMethodAction.postExec (UI thread)
  refusal -> flashInfo(message for reason)                 [R14]
  ExtractMethodSheet.show                                  refactor/ui                            [R11]
  on confirm -> version re-read; mismatch -> refuse        [R3]
    buildExtractMethodRewrite -> two RewriteSpans          utils/refactor/ExtractMethodEdit.kt     [R15]
    client.performCodeAction(one DocumentChange, two TextEdits, descending)
```

New files, all in `lsp/kotlin`:

- **`utils/refactor/ExtractionRegion.kt`** - the region model and its resolution (R2). Purely syntactic, so unit-testable with no analysis session, exactly as `CandidateExpressions.kt` is.
- **`utils/refactor/MethodSignature.kt`** - captured declarations to parameters, outputs, exits, receivers, modifiers, and the rendered signature string (R5-R10). The only analysis-dependent part.
- **`utils/refactor/ExtractMethodPlan.kt`** - `ExtractMethodPlan` (a `RefactoringPlan` subtype) and `ExtractionRefusal`.
- **`utils/refactor/ExtractMethodPlanner.kt`** - the single background pass (R3, R16).
- **`utils/refactor/ExtractMethodEdit.kt`** - the two rewrites and their ordering (R15). Pure text and offsets.
- **`refactor/ui/ExtractMethod*.kt`** - sheet, content, ViewModel, state, events (R11).
- **`actions/ExtractMethodAction.kt`** - registered in `KotlinCodeActionsMenu`; the only class touching the editor, the document version or the language client.
- **`TooltipTag.EDITOR_CODE_ACTIONS_KT_EXTRACT_METHOD`** - one new constant (R1).

Reused from extract variable unchanged: `TextSpan`, `collapseForLabel`, `candidateExpressionsAt` / `CandidateSyntax`, `isExtractionPosition`, `enclosingExecutableBody`, `NameProblem` + `validateVariableName`, `suggestVariableName`, `detectIndentUnit`, `detectNewline`, `leadingIndentAt`, `lineStartOffset`, `RewriteSpan` + `toTextEdit`, `positionAt`, `renderName`.

Deliberately **not** reused: `ScopeOption`, `AnchorForm` and `CandidateExpression`. Each is shaped by the legal scope chain, which this refactoring does not have (R4) - so the two refactorings share primitives, not the aggregate. What they do share is hoisted into the sealed `RefactoringPlan` (`fileText`, `documentVersion`, the version guard), introduced in the extract-variable PR so this one is purely additive.

Nothing outside `lsp/kotlin` changes except `TooltipTag.kt` and `values/strings.xml`. No new module, no new dependency.

## Verification

Unit tests in `:lsp:kotlin` (`flox activate -d flox/local -- ./gradlew :lsp:kotlin:testV7DebugUnitTest`), mirroring the extract-variable split so a failure localises to one layer:

- **`ExtractMethodRegionTest`** - no analysis session, PSI only: outward snapping to whole statements, the sibling-in-one-block rule, cross-block rejection, and the expression path (R2).
- **`ExtractMethodPlanEndToEndTest`** - analysis-backed, one case per rule: the parameter set, order and types (R5), the single output and the `Unit` case (R6, R7), the tail return (R8), the extension receiver (R9), `suspend` and `@Composable` (R10), and **one case per refusal reason** (R14).
- **`ExtractMethodEditTest`** - pure text: the two edits and their descending order, the three call-site forms, indentation, the blank-line separation, and CRLF preservation (R15).
- **`ExtractMethodViewModelTest`** - state derivation: chooser visibility, name validation against inherited names, and the rendered signature preview (R11, R12).

`lsp/kotlin` has **no `androidTest`** source set, and none is added: `@Composable` detection is tested by declaring `package androidx.compose.runtime; annotation class Composable` in a test source module, and `suspend` is a language modifier, so both need **no new dependency** (`KtLspTestEnvironment` supports `extraLibraryJars`, but not for this).

The sheet, `prepare()`/`ActionData`, the two-step undo and the new tooltip row are not unit-testable; they are covered by on-device QA from the acceptance criteria above, recorded in ADFA-5080's "Steps to QA" field.

## Related

- [ADR 0013](../adr/0013-refactorings-decline-rather-than-rewrite.md) - refactorings decline rather than rewrite unselected code; the principle behind R7-R10
- [ADR 0012](../adr/0012-refactoring-ui-lives-in-the-owning-lsp-module.md) - refactoring UI lives in the owning LSP module
- [kotlin-extract-variable.md](kotlin-extract-variable.md) - ADFA-4826; owns the shared Language section and every primitive reused here
- ADFA-5081 - code action edits should be a single undo step (fixes R15's consequence)
- ADFA-5082 - support a reassigned outer `var` as the single output (lifts R7's refusal)
- ADFA-5048 - Java extract method, the sibling in `lsp/java`
- [ARCHITECTURE.md](../../ARCHITECTURE.md)
