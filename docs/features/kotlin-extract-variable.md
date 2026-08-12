# Kotlin extract variable (K2 LSP)

- **Ticket:** ADFA-4826 (subtask of ADFA-3317; split out of the closed ADFA-3324 "Refactoring"). Extract method was originally part of this subtask and is now ADFA-5080.
- **Status:** Implemented in `lsp/kotlin/utils/refactor/` and `lsp/kotlin/refactor/ui/`, pending on-device QA. Still to land in this PR: the `ExtractionPlan` -> `ExtractVariablePlan` rename (the sealed `RefactoringPlan` supertype it will sit under has landed).
- **Module:** `lsp/kotlin`

Bind the expression at the cursor, or the selected one, to a new local `val`, and replace the occurrences of that expression with the new name.

This is the first *interactive* Kotlin code action: the user chooses an expression, a name, a target scope and whether to replace other occurrences, so it needs a real UI surface rather than a fire-and-forget edit. Where that UI lives is [ADR 0012](../adr/0012-refactoring-ui-lives-in-the-owning-lsp-module.md); what it refuses to do is the decline-rather-than-rewrite principle, recorded as ADR 0013 alongside extract method (ADFA-5080).

## Language

This section is the glossary for the whole refactoring family - extract variable, extract method (ADFA-5080), inline variable (ADFA-4827), rename (ADFA-4825). Prefer these terms over ad-hoc synonyms in code, tests, docs and review comments.

**Selection**:
The user's raw offsets from the editor caret, before any processing. A cursor is the degenerate selection where start equals end. Trimmed and snapped before it becomes an extraction region, so it is *not* interchangeable with one.
_Avoid_: range (that's `Range`, the LSP line/column type), region.

**Extraction region**:
The contiguous text an extraction reads its body from. For extract variable it is always an expression candidate; extract method adds statement ranges.
_Avoid_: target (overloaded with go-to-definition's target and with the insertion site), extent, fragment.

**Expression candidate**:
A `KtExpression` at the selection that is a legal extraction target. Ordered innermost-first, at most `MAX_CANDIDATES` (3) of them, so the chooser stays scannable on a phone.
_Avoid_: candidate expression when naming code (the type is `CandidateExpression`, but the term is "expression candidate"), match, option.

**Text span**:
A half-open offset range `[start, end)` into the analysed file's text - the type `TextSpan`. Purely positional; it carries no meaning about what it covers.
_Avoid_: range, offset pair.

**Legal scope chain**:
The ordered anchors available for the new declaration, innermost first: outward from the candidate's own statement through enclosing blocks, crossing a lambda boundary only when nothing lambda-scoped is referenced, and stopping at the enclosing named function, accessor or `init` body.
_Avoid_: scope list, parent chain.

**Anchor scope**:
The chain member the user picked. The `val` is declared inside it.

**Anchor form**:
How the declaration is woven into an anchor scope, since not all Kotlin scopes are blocks: `ExistingBlock`, `WrapInBraces`, or `ConvertExpressionBody`.

**Anchor point**:
The exact insertion offset - the start of the line holding the first statement *within the anchor
scope* that contains a replaced occurrence. Recorded per rung in the plan (`ExistingBlock`'s
`statementSpans`), because it is the only thing that makes an outer rung differ from an inner one.

**Occurrence**:
A site inside the anchor scope that is structurally equal to the candidate *and* whose every name reference resolves to the same declaration. Sites made unsound by an intervening write are excluded, so an occurrence set is always safe to replace wholesale.
_Avoid_: duplicate, match, usage.

**Refactoring plan**:
The complete result of the background analysis pass - the sealed `RefactoringPlan`, carrying the analysed `fileText` and its `documentVersion`. Plain data: no PSI, no symbols, no session. `ExtractVariablePlan` is this refactoring's subtype.
_Avoid_: model, result, context.

**Rewrite span**:
The single text replacement an extraction performs - a `TextSpan` plus its replacement text (`RewriteSpan`), converted to one `TextEdit` at the boundary.

## Scope

### In scope

An expression inside any executable body: a function body, a property accessor, an `init` block, a constructor, or a lambda. Both a bare cursor and a selection, since a cursor is just the selection where start equals end.

### Out of scope

Positions where no `val` can precede the expression, all rejected up front by `isExtractionPosition`:

- **Annotation arguments** - must be compile-time constants.
- **Default parameter values** - evaluated per call, and a hoisted local would not be in scope.
- **Super-constructor delegation arguments** - nothing can precede them.
- **Anything outside an executable body**, notably a class-body property initializer. Converting one to a getter would turn compute-once into compute-per-access, so it is declined rather than silently changing evaluation semantics.

## Requirements

**R1 - Trigger.** An "Extract variable" item (`action_extract_variable`) appears in the editor code-actions menu for Kotlin files, id `ide.editor.lsp.kt.extractVariable`, tooltip tag `EDITOR_CODE_ACTIONS_KT_EXTRACT_VARIABLE = "editor.codeactions.kotlin.extractvariable"`. Tooltip *content* is keyed by tag in the out-of-repo tooltips database, so the tag shows no text until a row exists for it - a hand-off item, not code.

There is deliberately **no `prepare()` visibility gate**. Deciding whether anything is extractable needs a K2 analysis session, which is far too costly for `prepare()` (UI thread, per menu item). The action stays visible on any Kotlin file and reports "nothing to extract" instead, matching `OrganizeImportsAction` and `ImplementMembersAction`. `requiresUIThread = false`, so the selection is read on a background thread; a torn read while the user is mid-edit can only produce a plan the version guard (R3) then refuses.

**R2 - Region.** The selection is whitespace-trimmed first, because a touch-screen selection routinely carries a leading or trailing space; a whitespace-only selection yields nothing. For a cursor, the element is looked up at the offset and then at `offset - 1`, so a caret resting just past a token still resolves.

From the innermost element the parent chain is walked outwards, collecting legal targets and stopping at the enclosing declaration. Illegal nodes along the way are **skipped rather than terminating the walk**, so `if (c) a else b` is still offered from inside one of its branches. At most 3 candidates, innermost first, deduplicated by range.

An expression is not a legal target when it is: a block, a loop, `return`/`throw`/`break`/`continue`, an operation reference, `super`, a lambda (the `{ ... }` expression and the literal inside it -- outside its call site the parameter types are gone, so `val v = { it.length + 1 }` does not compile), the selector of a qualified expression (`b` in `a.b`), a call's callee (`foo` in `foo(x)`), the left side of an assignment, or a **bare literal**. Excluding bare literals removes the only case where omitting the type annotation could change meaning - an `Int` literal where a `Long` is expected, or a bare `null` inferring `Nothing?`.

When the trimmed selection exactly equals the innermost candidate's range, the user has already said which expression they mean and the chooser is not shown (`selectionMatchedCandidate`).

**R3 - Live offsets and the version guard.** Analysis runs against `ktSymbolIndex.getCurrentKtFile(path)`, PSI refreshed to the open document's current version - an offset resolved against stale text points at the wrong element. The `KtFile` is fetched *before* entering `project.read`: the refresh needs `project.write`, and awaiting it under the read lock deadlocks.

The plan records the document version it was computed against. On confirm, the version is re-read and the edit is **refused** if it has moved on (`msg_extract_variable_file_changed`) - the editor stays reachable while the sheet is open, and applying spans computed against older text would corrupt the file. Refusing is always safe; the user can invoke the action again.

**R4 - Value filter.** A candidate whose type is `Unit` or `Nothing` is dropped: `val u = println(x)` compiles but is pointless. A candidate whose legal scope chain is empty is dropped too - a candidate with no legal anchor is not a candidate.

**R5 - Scope chain.** Anchors are enumerated outward from the candidate's own statement, each one of three anchor forms:

| Anchor form | When | Emitted as |
|---|---|---|
| `ExistingBlock` | the scope already has a `{ ... }` body | a new statement line |
| `WrapInBraces` | a braceless statement position: `if (c) foo()`, a `when` entry, a braceless loop body | the statement is replaced by a braced block holding the declaration and the original statement |
| `ConvertExpressionBody` | an expression-bodied function or accessor, `fun area(r: Int) = r * r` | `=` and the body become a block body; `return` is added unless the declaration returns `Unit`; the return type is written into the signature when the declaration does not spell one out, because a block body with no declared type returns `Unit` |

A written-out return type is rendered fully qualified and then shortened to its simple name only where
that name already resolves in the file -- an exact import, a star import of its package, or a
default-imported package such as `kotlin.collections`. Everything else stays qualified: verbose, but it
compiles, and this refactoring adds no imports. When the type cannot be written as source at all
(anonymous, intersection, an unresolved type, or a platform type the renderer cannot reduce) the rung
is declined rather than emitting a block body that does not compile.

Each rung is labelled with the construct that owns it -- `fun name`, `getter`, `setter`, `init block`,
`lambda`, `if block`, `else block`, `for loop`, `while loop`, `do-while loop`, `when branch` -- so the
`Declare in` list reads as a place rather than as a nesting level. A braced control-structure body is
wrapped in a container node, so the owner is the block's grandparent, not its parent.

The walk stops after the enclosing named function, accessor or `init` body. A class body or file is never an anchor. Lambda boundaries are crossed during the syntactic walk, then **truncated afterwards** by the innermost scope holding a declaration the candidate references - so a candidate using `it` or a lambda parameter can never be hoisted out of that lambda. `it` needs its own case: it has no source PSI, so a value-parameter symbol with no PSI referenced by the name `it` is taken to be the innermost enclosing lambda's implicit parameter. That is a property of the language, not a guess about the text.

Braceless control-structure bodies are wrapped in a container node, so the `if`/loop is the grandparent; without unwrapping, no braceless body is ever detected and the declaration silently hoists to the enclosing block instead of braces being added.

**R6 - Occurrences.** Two sites are the same expression when they are structurally identical (whitespace and comments ignored) *and* every name reference in them resolves to the same declaration. The symbol check is the point: text or structure alone would match `config.timeout` inside a nested lambda where `config` is a different `config`. ADFA-3324 states the standard outright - text-based matching breaks things.

Source declarations are compared by PSI identity, which is exactly the question being asked ("the same `val`?"); symbols without source PSI fall back to symbol equality. A resolution failure reads as "not the same" rather than propagating.

Matches must themselves be legal targets - in `a.a`, a candidate of `a` matches the selector too, and rewriting it would produce `v.v`. Overlapping matches are dropped so no site is rewritten twice.

An occurrence set is then restricted to a contiguous run around the candidate that **no write to a referenced mutable interrupts**:

```kotlin
var limit = 1
foo(limit + 1)   // occurrence
limit = 5
foo(limit + 1)   // same expression, different value
```

Unsound sites are excluded rather than warned about, so "Replace all N occurrences" can never produce wrong code and N is always achievable. The walk grows outward from the candidate - never dropping the site the user selected - and stops in each direction at the first write it would cross. Writes counted: plain assignment, the augmented forms, and `++`/`--`, against any `var` the candidate reads.

Occurrence sets are ascending by offset and always contain the candidate's own span, so `occurrences.size` is the count shown in "Replace all N occurrences". Narrowing to an inner scope can only shrink the set, never grow it.

**R7 - Name.** The suggestion is derived from the expression's shape first (`items.size` -> `size`, `getFoo()` -> `foo`, an interpolated string -> `text`), then its rendered type (`List<Foo>` -> `list`), then `"value"`; shape beats type because `size`, `count` and `name` are far better names than `int` and `string`. It is then uniquified with a numeric suffix.

Validation returns a `NameProblem` - `Blank`, `NotAnIdentifier`, `Keyword`, `AlreadyTaken` - rather than throwing, since the input is a text field. Only Kotlin's **hard** keywords are rejected; soft and modifier keywords (`by`, `data`, `it`) are legal names. Backtick-quoted names are rejected: legal Kotlin, but a poor generated local, and accepting them would mean validating the quoted form too.

Taken names are every declaration name in the file - deliberately conservative rather than scope-exact. Being over-broad costs a `size1` where `size` would have done; being under-broad generates code that shadows something. It is also purely syntactic, so it needs no analysis and is unit-testable.

**R8 - Sheet.** One surface holding every choice, with no navigation between steps: expression chooser, name field, scope chooser, replace-all checkbox, Cancel/Extract. The four are interdependent - a different expression changes the scope list and the occurrence count - so they are shown together where that relationship is visible, rather than across sequential dialogs the user would have to back out of to explore.

Each chooser is hidden when it has nothing to ask: the expression chooser when there is one candidate or the selection already matched one, the scope chooser when the chain has one rung, the replace-all checkbox at an occurrence count of one. Changing the expression re-suggests the name, because the old one described the old expression.

**R9 - Edit.** Exactly **one** `TextEdit`, built as a `RewriteSpan` covering one contiguous span. `IDELanguageClientImpl.applyActionEdits` applies each edit in its own `runOnUiThread` with no `beginBatchEdit`, and every range is interpreted against the *current* text - so a list of N edits would be applied against positions already shifted by its predecessors and would cost N undo steps with a typing window between each. Occurrences are substituted right-to-left within the span so an earlier substitution cannot shift a later offset.

The span is anchored on the chosen rung's statement, not on the occurrence: for an outer rung the
declaration goes above the whole enclosing statement, at that statement's indentation.

A block written on one line -- `items.map { it.length + 1 }`, `fun f(n: Int): Int { return n * 2 }`,
a one-line `if` body -- is expanded instead: the content between the braces moves onto its own line
with the declaration above it and the closing brace below. Anchoring on the statement's line start
there would place the declaration *before* the `{`, outside the scope the value belongs to, which
leaves a lambda's `it` unresolved. The braces themselves and a lambda's `param ->` header are left
where they are.

Whether a block counts as "one line" takes two conditions, not one. A single check against where the
block's content starts is not enough: a lambda body's block does not own its braces, so its content
span sits at the body's first token even when that token starts its own line, and comparing that alone
against the line start would wrongly expand an ordinary multi-line lambda. Both must hold: something
other than indentation already precedes the statement on its line (the brace, a header, or a prior
semicolon-separated statement), *and* the block's own content contains no newline (so re-emitting it
as a single line loses nothing). A multi-line lambda fails the first and keeps its shape; a multi-line
block with two semicolon-separated statements on one line satisfies the first but fails the second, so
it also keeps its shape, with the declaration hoisted above the whole line instead.

A block that fails *both* conditions -- something besides indentation precedes the statement on its
line, but the block's own content spans more than one line, as in `items.forEach { log(x)\n\tlog(y) }`
-- is **declined** rather than hoisted. Hoisting would anchor before the block's own opening delimiter,
outside the scope the user picked, which is unsound whenever anything inside that scope (a lambda's
`it`, say) is not visible there.

The emitted text is **fully indented**: code-action edits bypass the editor's auto-indent (raw `Content.replace`), and `CMD_FORMAT_CODE` is a no-op for Kotlin. The indent unit is inferred from the file's own lines (a tab if any line is tab-indented, else the smallest positive run of leading spaces, defaulting to a tab), mirroring `ImplementMembersAction`; CRLF is used only when the file already contains it, so the edit never mixes line endings.

**R10 - Responsiveness.** One background analysis pass produces the plan for *all* candidates at once; the sheet then performs pure string and offset arithmetic on it. Nothing re-enters analysis on confirm, which keeps PSI off the UI thread, removes the stale-PSI window, and makes the whole derivation unit-testable without an editor, an activity or Compose. Analysis runs at `AnalysisPriority.INTERACTIVE` under a cancel checker tied to the action's coroutine, so cancelling the action aborts the analysis.

**R11 - Failure isolation.** Anything thrown in the analysis pipeline degrades to an empty plan and a log line. The action framework catches only `IllegalArgumentException` and this runs on a scope with no exception handler, so an uncaught throw would crash the app; reporting "nothing to extract" is always safe. A missing `FragmentActivity` or fragment manager logs and flashes `msg_cannot_perform_fix` rather than failing silently.

## Non-goals

- **Extract to a `val` outside an executable body** - a class property or a top-level `val`. That is a different refactoring with different scope rules.
- **Extract `var`, `lateinit`, or a property with accessors.** Always a `val`.
- **An explicit type annotation** on the generated declaration. Bare literals are excluded (R2) precisely so inference cannot change meaning.
- **Occurrences outside the anchor scope**, or across files.
- **Renaming the declaration in place after the edit** - ADFA-4825.
- **Formatting the result.** `CMD_FORMAT_CODE` is a no-op for Kotlin; R9 emits indented text instead.
- **Extract method** - ADFA-5080, which shares this vocabulary and these primitives.

## Acceptance criteria

1. "Extract variable" appears in the code-actions menu of a Kotlin file and is absent in a non-Kotlin file.
2. A cursor inside `a + b * c` offers the innermost-first candidates and extracting the selected one produces `val <name> = ...` on its own line above, correctly indented.
3. A selection that exactly matches an expression skips the expression chooser.
4. A caret immediately after an identifier resolves the same as one inside it.
5. A cursor on a bare literal, on whitespace, in a comment, or in an annotation argument reports "No expression to extract here".
6. An expression appearing three times in the same block reports "Replace all 3 occurrences" and rewrites all three.
7. The same expression with an intervening reassignment of a `var` it reads offers only the contiguous sound run.
8. An expression using `it` inside a lambda offers no anchor outside that lambda.
9. Extracting from `if (c) foo(x + 1)` wraps the branch in braces with the declaration inside.
9a. With a candidate inside a braced `if` inside a function, picking `fun name` in `Declare in` puts the declaration above the `if`, and picking `if block` puts it inside the branch.
9b. Extracting from `return items.map { it.length + 1 }` puts the declaration inside the lambda and expands the block over three lines; the same holds for a one-line function body.
10. Extracting from `fun area(r: Int): Int = r * r` converts it to a block body with `return`, leaving the declared type alone; extracting from `fun area(r: Int) = r * r` converts it *and* writes `: Int` into the signature.
11. Extracting from a `Unit`-returning expression-bodied function converts it without adding `return` and without writing a type.
12. A name that is blank, not an identifier, a hard keyword, or already used disables Extract and shows the matching message.
13. Editing the file while the sheet is open, then confirming, reports "The file changed. Try extracting again." and leaves the file untouched.
14. One undo restores the file exactly.
15. A file indented with spaces receives space-indented output; a CRLF file keeps CRLF.

## Design

Per [ADR 0012](../adr/0012-refactoring-ui-lives-in-the-owning-lsp-module.md), `lsp/kotlin` owns its refactoring UI, and the analysis/UI split is enforced **by data rather than by module boundaries**: the background pass produces a plain-data plan, and the sheet holds no PSI and performs no analysis.

```
ExtractVariableAction.execAction (background)              lsp/kotlin/actions
  server.compilationEnvironmentFor(path) ?: empty plan
  cursor -> [selectionStart, selectionEnd)
  -> buildExtractionPlan(...)                              utils/refactor/ExtractVariablePlanner.kt
       ktFile = env.ktSymbolIndex.getCurrentKtFile(path).get()   [R3: before project.read]
       env.project.read {
         candidateExpressionsAt(ktFile, start, end)         utils/refactor/CandidateExpressions.kt  [R2]
         analyzeMaybeDangling(INTERACTIVE, cancelChecker) {  [R10]
           per candidate: type filter                       [R4]
                          enclosingScopeFrames + truncateAtCeiling   ScopeChain.kt / Occurrences.kt  [R5]
                          findOccurrences + excludeUnsoundOccurrences                Occurrences.kt  [R6]
                          suggestVariableName + visibleNamesAt        NameSuggestion.kt / Occurrences.kt  [R7]
         }
       }
  <- ExtractVariablePlan (plain data, no PSI)

ExtractVariableAction.postExec (UI thread)
  empty -> flashInfo("No expression to extract here")       [R11]
  findFragmentActivity() -> ExtractVariableSheet.show       refactor/ui  [R8]
    ExtractVariableViewModel: StateFlow<ExtractVariableUiState>, sealed UiEvent
  on confirm -> ExtractionChoice
    version re-read; mismatch -> refuse                     [R3]
    buildExtractVariableRewrite -> RewriteSpan -> toTextEdit  utils/refactor/ExtractVariableEdit.kt  [R9]
    client.performCodeAction(one DocumentChange, one TextEdit)
```

Components:

- **`utils/refactor/ExtractionPlan.kt`** - `TextSpan`, `AnchorForm`, `ScopeOption`, `CandidateExpression`, the plan, `collapseForLabel`. To be renamed to `ExtractVariablePlan` under a sealed `RefactoringPlan` carrying `fileText`, `documentVersion` and the shared version guard, so ADFA-5080 adds a subtype rather than renaming this one. Both refactorings share these *primitives*, not the aggregate: extract method has no scope chain, so `ScopeOption`/`AnchorForm`/`CandidateExpression` are not shared.
- **`CandidateExpressions.kt`** - purely syntactic, no analysis session, hence unit-testable on its own (R2).
- **`ScopeChain.kt`** - the syntactic chain and the three anchor forms (R5); indentation and newline detection shared with the edit builder.
- **`Occurrences.kt`** - symbol-aware structural equality, the occurrence search, the unsoundness filter, the referenced-declaration ceiling, and `visibleNamesAt` (R5, R6, R7).
- **`NameSuggestion.kt`** - suggestion and validation, no analysis session (R7).
- **`ExtractVariableEdit.kt`** - `RewriteSpan`, the three anchor-form rewrites, `toTextEdit` (R9). Pure text and offsets.
- **`refactor/ui/`** - `ExtractVariableSheet` (a `BottomSheetDialogFragment` hosting a `ComposeView`), stateless `ExtractVariableSheetContent`, `ExtractVariableViewModel` + `ExtractVariableUiState` + sealed `ExtractVariableUiEvent`. `LabelledSection` and `OptionList` become shared with ADFA-5080. The ViewModel uses a plain `ViewModelProvider.Factory` rather than a Koin definition: it is sheet-scoped, injects nothing, and takes the plan as a runtime argument.
- **`ExtractVariableAction`** extending `BaseKotlinCodeAction`, registered in `KotlinCodeActionsMenu`; the only class that touches the editor, the document version or the language client.
- **`common-compose`** - `IdeTheme`/`IdeColorScheme`, shared with `profiler` and `floating-window` so the sheet matches the IDE's theme.

## Verification

Unit tests in `:lsp:kotlin` (`flox activate -d flox/local -- ./gradlew :lsp:kotlin:testV7DebugUnitTest`), split so a failure localises to one layer:

- **`RefactorPrimitivesTest`** - no analysis session: selection trimming, candidate collection and the legal-target rules (R2), indent/newline detection, name suggestion and validation (R7), the unsoundness filter as a pure function (R6).
- **`ExtractVariablePlanEndToEndTest`** - analysis-backed: the value filter (R4), scope chains and the lambda ceiling (R5), occurrence sets including the `it` and same-name-different-symbol cases (R6).
- **`ExtractVariableEditTest`** - pure text: the three anchor forms, right-to-left substitution, indentation and CRLF (R9).
- **`ExtractVariableViewModelTest`** - state derivation: chooser visibility, candidate switching re-suggesting the name, replace-all clamping, `choice()` refusing an invalid name (R8).
- **`KotlinCodeActionTooltipTagTest`** - every action carries a tooltip tag (R1).

`prepare()`/`ActionData` and the sheet itself are not unit-testable, consistent with the other Kotlin code actions. They are covered by on-device QA from the acceptance criteria, recorded in ADFA-4826's "Steps to QA" field.

## Related

- [ADR 0012](../adr/0012-refactoring-ui-lives-in-the-owning-lsp-module.md) - refactoring UI lives in the owning LSP module
- ADR 0013 - refactorings decline rather than rewrite unselected code (lands with extract method, ADFA-5080)
- [ADR 0009](../adr/0009-jetpack-compose-for-new-ui.md) - Compose, UDF, `ViewModel` + `StateFlow`
- [ADR 0010](../adr/0010-navigation-resolves-via-analysis-api.md) - the K2 Analysis API as the Kotlin semantic source of truth
- ADFA-5080 - extract method, the sibling refactoring; it reuses this vocabulary and these primitives
- [ARCHITECTURE.md](../../ARCHITECTURE.md)
