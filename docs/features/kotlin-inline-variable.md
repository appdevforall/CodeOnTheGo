# Kotlin inline variable (K2 LSP)

- **Ticket:** ADFA-4827 (subtask of ADFA-3317; split out of the closed ADFA-3324 "Refactoring")
- **Status:** Implemented. Fourth link in the refactoring stack, based on extract method (ADFA-5080).
- **Module:** `lsp/kotlin`

Replace the references to a local variable with its initializer, and delete the declaration once nothing needs it.

The inverse of extract variable (ADFA-4826), and the third interactive Kotlin refactoring. It differs from both extracts in one way that shapes the whole design: it is **subtractive**. Extract adds a declaration next to code the user is looking at; inline rewrites references that are usually *off-screen* and then deletes the line under their finger. Where the UI lives is [ADR 0013](../adr/0013-refactoring-ui-lives-in-the-owning-lsp-module.md); what it refuses to do, and what it does only partially, is [ADR 0014](../adr/0014-refactorings-decline-rather-than-rewrite.md).

## Language

The family glossary lives in [kotlin-extract-variable.md](kotlin-extract-variable.md#language) - *selection*, *text span*, *refactoring plan*, *rewrite span* are defined there and used here unchanged. This feature adds:

**Target declaration**:
The local variable being inlined - a `KtProperty` with `isLocal` and an initializer. Parameters, loop variables and destructuring entries are not `KtProperty` at all, so they are excluded by construction rather than by a check.
_Avoid_: variable (ambiguous with the references to it), local, symbol.

**Reference**:
One read of the target declaration inside the enclosing declaration. Deliberately *not* called an occurrence: an **occurrence** in this codebase already means a site structurally equal to an expression (extract variable's `findOccurrences`), which is a different question resolved a different way. Inline matches by *symbol identity*, never by structure.
_Avoid_: occurrence, usage, use site.

**Cutoff**:
The first offset after the target declaration where the inlined value stops being the value the declaration produced - either a write to the target itself, or a write to a mutable its initializer reads. References before the cutoff are sound; the one exception is a reference inside a body that runs later than its own text position, which is judged separately regardless of where it sits (R6).
_Avoid_: barrier, invalidation point, write boundary.

**Inlinable reference**:
A reference that may be rewritten: before the cutoff, not deferred, not shadowed, not receiver-shifted, not smart-cast, not unsafe in callee position, and not a write target. Every other reference is left exactly as it is.
_Avoid_: safe reference, valid usage, eligible reference.

**Partial inline**:
Rewriting the inlinable references while leaving the rest, which necessarily keeps the declaration. The third designed outcome of a refactoring, alongside applying and refusing - see the [ADR 0014](../adr/0014-refactorings-decline-rather-than-rewrite.md) amendment.
_Avoid_: partial success, best-effort, incomplete inline.

**Substitution text**:
The initializer's source text as it lands at one reference - parenthesised per R10, or wrapped as `${...}` when the reference is a string-template entry. One initializer, but not necessarily one substitution text.
_Avoid_: replacement text, inlined value, expansion.

## Scope

### In scope

A local `val` or `var` with an initializer, declared in any executable body - a function body, an accessor, an `init` block, a constructor, or a lambda - in a Kotlin file. Invoked with the cursor on the declaration's name or on any reference to it.

### Out of scope

Member properties, top-level properties and anything whose references can leave the file (R2). Inlining those is a multi-file refactoring: the plan model here is one file's text plus one document version, and a `public val`'s callers are not all reachable, let alone editable.

## Requirements

**R1 - Trigger.** An "Inline variable" item (`action_inline_variable`) in the editor code-actions menu for Kotlin files, id `ide.editor.lsp.kt.inlineVariable`, tooltip tag `EDITOR_CODE_ACTIONS_KT_INLINE_VARIABLE = "editor.codeactions.kotlin.inlinevariable"` - a new constant in `TooltipTag.kt`, and the tag string is fixed by the out-of-repo tooltips database rather than chosen here.

As with both extracts: **no `prepare()` visibility gate** - deciding whether the cursor is on an inlinable local needs an analysis session, which `prepare()` runs on the UI thread and must not do - and `requiresUIThread = false` so the cursor is read on a background thread.

**R2 - Target.** The cursor resolves to exactly one target declaration, from either of two positions:

- **on the declaration's name** - anywhere in the `total` of `val total = a + b`;
- **on any reference** - the `total` of `println(total)`, resolved to its declaration with `mainReference.resolveToSymbols()`, then required to be a source `KtProperty` in the same file.

The target must be a `KtProperty` with `isLocal` **and** an initializer. This excludes, without a single dedicated check, function parameters, lambda parameters, `it`, loop variables, `catch` parameters and destructuring entries - none of which is a `KtProperty`. It leaves three refusals to make explicitly, because each is a position a user can reasonably put the cursor in and deserves to be told about: a member or top-level property (`NotALocalVariable`), a local `val` with no initializer (`NoInitializer`, the `val x: Int` then `x = 1` shape, which Kotlin permits), and a destructuring declaration or one of its entries (`DestructuringDeclaration`).

The destructuring refusal covers the entries and the syntax around them, not the whole node: the initializer is part of it, so `val (p, q) = split(total)` with the caret on `total` resolves `total` normally.

A caret resting immediately *after* a use - `val y = x| + 1`, `foo(x|)` - is a routine editor position, and there the leaf at the offset is the whitespace or the `)`. Resolution retries one character back, but only when the first attempt refused with `NotAVariable`: every other refusal already names something real at the caret. The declaration position needs no retry, because trailing whitespace is a child of the `KtProperty` and its name-range test still matches.

Both positions converge on the same target immediately, so there is one analysis pass and one plan shape. The *position* is still recorded on the plan, because R9's mode availability depends on it.

**R3 - Live offsets and the version guard.** Identical to both extracts: analysis runs against `getCurrentKtFile(path)` fetched *before* entering `project.read`, the plan records the document version on the `RefactoringPlan` supertype, and the action re-reads the live version before emitting edits, refusing on a mismatch.

**R4 - References.** Every read of the target within the **enclosing declaration** - the named function, accessor, `init` block or constructor whose body contains the declaration, via `enclosingExecutableBody`. A local's scope cannot leave that body, so this search root is complete rather than merely convenient: no index, no other file, no visibility reasoning.

A reference matches by **symbol identity**, following `Occurrences.kt`'s existing rule - compare the resolved symbol's source PSI to the target `KtProperty` - never by name text, which would match a shadowing declaration's name in a nested scope.

A reference that is a **write target** (`x = 5`, `x += 1`, `x++`, detected with the existing `isWriteTarget`) is never a reference to inline. It is a *cause* of the cutoff (R5), not a candidate for substitution.

**Zero references refuses** (`NeverUsed`, naming the variable). With R9's single mode the declaration would otherwise be deleted with nothing inlined, which is a delete-unused-variable action wearing inline's hat, on a line the user may have been about to use.

**R5 - Cutoff.** The first offset after the declaration where the value changes, from either cause:

- **a write to the target** - only possible for a `var`;
- **a write to a mutable the initializer reads** - the `val bound = limit + 1` case, where `limit` is reassigned between two references and the two sites no longer hold the same value even though the text is identical.

Both come from the existing `writeOffsetsFor(candidate, searchRoot)`, called with the initializer as the candidate, plus the target's own write references, filtered to offsets after the declaration's end.

This matches IntelliJ, whose documented behaviour is that *"the variable must be initialized at declaration; if the initial value is modified somewhere in the code, only the occurrences before modification will be inlined."* The alternative - refusing the whole inline - was rejected: it discards a sound refactoring of the references before the write, and a partial result is what a user coming from a desktop IDE already expects here.

The cutoff is a purely textual position, and cannot judge a reference whose execution does not follow the text. Two shapes break that correspondence: a body that runs *later* - a lambda, a local function, or an anonymous object (`button.setOnClickListener { show(label) }` before a later `index = 1`, where `label` reads `index`) - and a loop body, which runs *again* after everything textually below it, so a reference before the write executes after it on every iteration but the first. Once any write exists at all, such a reference is excluded outright (`DeferredExecution`, R6) rather than judged by where its text falls relative to the cutoff.

**Known limitation:** the shared `writeOffsetsFor` primitive tests a simple name, so a write through a qualified access - `config.limit = 5` - is not detected as a write at all. A variable whose initializer reads a qualified mutable therefore gets no cutoff. Fixing the shared primitive is out of scope here; extract variable also depends on its current behaviour.

**R6 - Per-site exclusions.** Five ways a reference is individually unsound. Each **excludes that reference**, leaving it untouched; none refuses the inline, because each is a property of one site rather than of the target.

- **Out of textual order.** The reference sits inside a body that does not run once, in order, at the offset where its text sits, so it does not read the value the cutoff (R5) would credit it with. Two shapes: a body that runs later - a lambda, a local function, or an anonymous object, as in `button.setOnClickListener { show(label) }` followed by a later `index = 1` where `label`'s initializer read `index`; and a **loop body**, where `val step = i + 1; while (i < 10) { println(step); i += 2 }` puts the reference textually before the write but executes it after the write on every iteration but the first. Once any write exists at all - the cutoff is no longer `Int.MAX_VALUE` - such a reference is excluded outright rather than judged by its textual position relative to the cutoff. A loop that *contains* the declaration is not one of these: the value is recomputed each iteration alongside the reference. Deliberately over-broad: a lambda invoked immediately, `run { show(label) }`, and a reference in a loop whose write sits after the loop are excluded too even though both would have been safe. Over-exclusion is this feature's safe direction.
- **Shadowing.** The initializer reads a name that resolves to something else at the reference. `val a = 1; val x = a + 1; run { val a = 99; f(x) }` would inline to `f(a + 1)` reading the inner `a`. Detected by walking the reference's parents up to the target's own block, checking each intervening scope's declared names - block statements before the site, lambda value parameters and `it`, function parameters, loop and `catch` parameters, destructuring entries, a `when` subject variable, a class or object body - against the set of names the initializer references. The walk also checks the target's own block, restricted to declarations that come after the target's end and before the reference: a declaration before the target is exactly what the initializer legitimately resolves to, which is why the restriction exists. The converse case cannot arise: a local's scope runs to the end of its block, so everything the initializer reads is still in scope at every reference. Only shadowing bites.
- **Receiver shift.** The initializer accesses a member through an implicit receiver, and the reference sits somewhere that introduces a different one. Tested as the conjunction of two cheap questions: does the initializer contain an unqualified reference resolving through an implicit receiver, or a bare `this`; and does anything between the declaration and the reference change the implicit receiver? The bare-`this` half is asked first and separately, because `this` contributes no `KtSimpleNameExpression` - its instance reference is a plain `KtReferenceExpression` - so `val v = this` would leave the simple-name scan with nothing to iterate. The second question has two answers - a receiver-introducing lambda (a `KtFunctionLiteral` whose functional type has a receiver), the `with(other) { ... }` / `apply` / `run` / `buildString` case; and a class or object body, whose own `this` displaces the enclosing one, as in `val label = toString()` referenced inside a later `object : Any() { ... }`. The class-body half is not covered by shadowing: that test compares *declared* names, and an inherited member such as `toString` is declared nowhere. Only both questions together are a problem. This is extract method's `InnerImplicitReceiver` as a per-site exclusion rather than a refusal.
- **Smart cast.** `val b = a.b; if (b != null) b.length` inlines to `a.b.length`, which does not compile - a smart cast needs a stable value, and a property read is not one. Detected with `smartCastInfo` on the reference (`KaDataFlowProvider`); non-null means excluded.
- **Unsafe in callee position.** The reference is the callee of a call and the initializer is anything but a bare name. A lambda or anonymous function - `val f = { n: Int -> n * 2 }` used as `f(3)` - would need `.invoke()` to compile; the rule is not limited to those two shapes, because a callable reference - `val f = ::g` used as `f(3)` - does not parse there at all, and a qualified initializer - `val f = a.b` used as `f(3)` - would silently prefer a member function `b` over `invoke`. Passing the same `f` as an argument (`list.map(f)`) is unaffected and stays inlinable.

**R7 - An explicit type refuses.** A target declaration carrying an explicit type reference - `val x: Long = 1`, `val s: Any = "text"` - **refuses the whole inline** (`DeclaredTypeIsLoadBearing`, naming the type).

The declared type participates in the initializer's inference and in overload resolution at every reference: `foo(x)` becomes `foo(1)`, an `Int`, which does not compile, and `val s: Any = "text"` can silently select a different overload. This refuses on the *presence* of the annotation rather than on a comparison against the initializer's type, because the comparison does not work: in `val x: Long = 1` the expected type propagates, so the initializer's `expressionType` **is** `Long` and a naive equality test would call the case safe. Telling the genuinely safe annotations apart needs the initializer's type computed *without* its expected type, which the Analysis API does not offer.

Deliberately stricter than necessary - `val x: Long = 1L` is refused too - and stated as such per ADR 0014's preference for a stricter rule over a cleverer one. Explicit types on locals are uncommon in idiomatic Kotlin, and the ones that do appear (pinning a supertype, a nullable, a platform type) are usually exactly the load-bearing cases.

**R8 - Partial inline and the declaration.** The inlinable references (R4-R6) are rewritten; every other reference is left alone. **The declaration is deleted only when nothing is left behind**: every reference was inlined, the target has no writes anywhere, *and* the declaration sits directly in a block.

The second clause is not redundant. A `var` whose reads were all inlined can still have a later `x = 5` assigning to it, so the declaration is still needed even though no read survives. Removing that write would be dead-store elimination, which is not this refactoring.

The third clause exists because "every reference was inlined" no longer implies the declaration is safe to remove. A `when` subject variable - `when (val a = compute()) { 1 -> g(a); else -> 0 }` - is a `KtProperty` with `isLocal` true like any other target, so its one reference can be inlined same as any other; but its own text is not a statement, it is the `when`'s subject, so deleting it takes `when (val a = ...)` itself with it and leaves code that does not parse. The fix is a deletion guard, not a refusal: rewriting the reference and keeping the declaration is perfectly sound, so this stays a useful partial-shaped result rather than a decline.

**Nothing inlinable refuses** (`NothingInlinable`, naming the variable): every reference excluded or past the cutoff means there is no edit to make, and reporting that is better than a no-op.

**R9 - Modes.** Two, and which are offered depends on the cursor position (R2) and the inlinable count (R8):

| Cursor on | Offered |
|---|---|
| the declaration's name | all references (no choice - there is no reference at the cursor to single out) |
| a reference, 2+ inlinable | **both** - this reference only, or all references |
| a reference, 1 inlinable | all references (no choice) |
| a reference that is not inlinable | refuses (`ReferenceNotInlinable`) |

The single-inlinable-reference row collapses deliberately. "This reference only" there would produce the same substitution as "all references" plus a declaration nothing reads - a `variable is never used` warning in generated code, which ADR 0014 forbids emitting. So the case has one honest answer.

The last row refuses rather than inlining *other* references: rewriting every site except the one under the user's finger reads as the action having done nothing.

"This reference only" keeps the declaration unconditionally, by definition.

**R10 - Substitution text.** The initializer's text, wrapped in two situations.

**Parentheses** are decided by classifying the **initializer alone**, never the reference site: no parentheses when the initializer is a single atomic or postfix expression - a literal, a name, `this`, a qualified chain, a call, an already-parenthesised expression, a lambda - and parentheses for everything else: binary operators, `as`/`is`, `?:`, unary operators, and `if`/`when`/`try` used as an expression.

Site-sensitive precedence comparison was rejected. It emits marginally cleaner text and has to be right about every parent context - a receiver (`x.length` where `x = a ?: b`), a unary minus over a subtraction, an infix call, a `when` subject - where being wrong emits code that does not compile, and R13 shows no preview on the common path. The cost of the stricter rule is a redundant `return (a + b)`; the cost of the cleverer one is a miscompile the user did not see coming.

**String templates.** A reference inside a template appears as `$x`, and the short form only accepts a simple name, so `val x = a + b` must become `"total: ${a + b}"` - never `"total: $a + b"`, which silently changes the string. The rule: inside a template entry emit `${...}` unless the substitution text is itself a plain identifier, where `$y` stays short. `true`, `false` and `null` read as identifiers but are keywords, so `"$true"` does not parse - they are rejected explicitly and take the braced form. `this` is the one keyword the short form does accept, and stays short. This makes the template flag a per-reference property of the plan, not a property of the target.

**R11 - Deleting the declaration.** Three line shapes, all pure span arithmetic:

- **Alone on its line** - delete from the line start through the line terminator inclusive, leaving no blank line.
- **Sharing its line with real code** - `val x = 1; return g(x)`, or a one-line body `fun f() { val x = 1; g(x) }` - delete the declaration's own span plus a following `;` and one following space if present. Deleting "the line" here would take the `return` or the closing brace with it, which is the defect class extract variable already hit (`ADFA-4826: Decline a block whose statement shares the brace line`).
- **With a trailing comment** - `val total = a + b // running total` - **the comment is preserved** on its own line at the declaration's indentation.

Preserving the comment is the asymmetry argument: a comment left describing nothing is *visible* and removed with one gesture, while a deleted comment is invisible, and nothing in a diff-less phone UI reports that prose went missing. Comments are the one thing here that cannot be regenerated. A comment on its own line above the declaration is untouched, since only the declaration's own line is ever deleted.

**R12 - Edit.** One `DocumentChange` carrying **one `TextEdit` per inlined reference plus, when R8 deletes it, one for the declaration, sorted by descending start offset**.

The ordering is mandatory rather than stylistic, for the reason extract method records: `IDELanguageClientImpl.applyActionEdits` iterates the list in order and applies each edit with **line/column** ranges against the text as it then stands, so an earlier edit must never shift a later one. The declaration precedes every reference, so its deletion always sorts last. Spans never overlap: references are distinct reads, and the declaration's span contains none of them.

Substitutions are emitted as final text; code-action edits bypass the editor's auto-indent and `CMD_FORMAT_CODE` is a no-op for Kotlin. Nothing re-indents, but nothing needs to - a substitution is intra-line and the deletion removes whole lines.

**Known consequence:** as with extract method, nothing on this path calls `beginBatchEdit`, so an inline over N references is **N+1 undo entries** and an intermediate undo state does not compile. **ADFA-5081** fixes this properly by batching `applyActionEdits` for every multi-edit action. Working around it here - collapsing everything into one spanning replacement of the whole enclosing declaration - was rejected: it would hide a real bug behind one feature's implementation, rewrite untouched lines, and leave the next multi-edit action to rediscover the problem.

**R13 - Sheet.** Shown **only when R9 offers a choice**. Every other path applies immediately and reports with `flashInfo` - "Inlined 3 references to `total`", or the partial form naming what was kept.

`InlineVariableSheet` (a `BottomSheetDialogFragment` hosting a `ComposeView`) and a stateless `InlineVariableSheetContent`, reusing `LabelledSection` / `OptionList` from `refactor/ui/SheetComponents.kt` and `IdeTheme`. **No ViewModel and no UiState**: both extracts need one to own an editable name and a selected scope, and inline has no mutable state at all - an immutable plan, two derived labels, and three events (either mode, or dismissed). A ViewModel holding nothing would be ceremony, and its test would assert that a constant is a constant.

Contents: title -> the two mode buttons with their derived labels -> the substitution text as one monospace line -> Cancel.

**The labels are derived by pure functions beside the plan, not composed in the composable**, and unit-tested there - the same discipline as extract method's shared `signatureText`. "Inline all 5 references and delete `total`" versus "Inline 3 of 5 references" is exactly the string that can drift from what the edit does, and R8's deletion rule makes the difference invisible to a reader of the composable.

**R14 - Refusals and reports.** The plan carries a typed `InlineRefusal` and `postExec` maps it to a specific message. Ten reasons, each naming what is in the way, per ADR 0014:

| Reason | Message intent |
|---|---|
| `NotAVariable` | place the cursor on a local variable or one of its uses |
| `NotALocalVariable` | only a local variable can be inlined |
| `NoInitializer` | `<name>` has no value at its declaration |
| `DestructuringDeclaration` | a destructuring declaration cannot be inlined |
| `DeclaredTypeIsLoadBearing` | `<name>` is declared `<type>`, and its uses need that type (R7) |
| `NeverUsed` | `<name>` is never used (R4) |
| `NothingInlinable` | no use of `<name>` can be inlined safely (R8) |
| `ReferenceNotInlinable` | this use of `<name>` cannot be inlined safely (R9) |
| `CouldNotAnalyse` | the analysis could not run - deliberately neutral, since the cursor may have been fine |
| `FileChanged` | the file changed while the sheet was open (R3) |

Plus two success reports: the whole-inline form and the partial form, which must say both counts and that the declaration was kept, because a user who asked to inline everything and got three of five needs to know from the flash rather than by rereading the file.

`CouldNotAnalyse` exists so the other nine stay truthful - a missing compilation environment or a thrown analysis error must not be reported as `NotAVariable`, which blames a cursor nothing ever looked at. New entries in `resources/.../values/strings.xml`, picked up by the next translation batch.

Cancellation is not a refusal: the planner re-throws `CancellationException` (`AnalysisPreemptedException` is one), so a cancelled action ends silently.

**R15 - Responsiveness and failure isolation.** As both extracts: one background pass at `AnalysisPriority.INTERACTIVE` under a cancel checker tied to the action's coroutine builds the whole plan; the sheet does pure string and offset arithmetic and re-enters no analysis on confirm. Anything thrown degrades to `CouldNotAnalyse` plus a log line rather than an uncaught throw, and the sheet's confirm path - outside every guard the action framework provides - wraps its own body in `runCatching`.

## Non-goals

- **Member and top-level properties** (R2). A cross-file refactoring with a different plan model.
- **Inline function, inline parameter, inline property accessor.** Separate refactorings; only a local variable is in scope.
- **A purity or side-effect check.** `val n = queue.removeFirst()` with three references inlines into three `removeFirst()` calls. This is deliberate, not an oversight: Kotlin offers nothing to prove purity with, so any check would be a heuristic, and IntelliJ does not check either. The user reads the expression they are inlining and decides.
- **Dead-store elimination** (R8). A `var`'s later write keeps the declaration alive; the write is not removed.
- **Site-sensitive parenthesisation** (R10).
- **Reformatting the result** (R12). Substitution text is emitted final.
- **Atomic undo** of the N+1 edits (R12) - ADFA-5081.
- **A preview on the no-choice paths** (R13). The sheet appears only where there is a decision to make.
- **Java inline variable.** The sibling in `lsp/java`, unticketed.

## Acceptance criteria

1. "Inline variable" appears in the code-actions menu of a Kotlin file and is absent in a non-Kotlin file.
2. Cursor on `val total = a + b` with three references inlines all three and removes the declaration line, in one action with no sheet.
3. Cursor on one of those three references offers a choice; picking "this reference only" rewrites exactly that reference and keeps the declaration.
4. Cursor on the single reference of a variable inlines it and deletes the declaration, with no choice offered.
5. `val sum = a + b` referenced in `sum * 2` produces `(a + b) * 2`.
6. `val name = user.name` referenced in `f(name)` produces `f(user.name)` with no parentheses.
7. `val sum = a + b` referenced in `"total: $sum"` produces `"total: ${a + b}"`.
8. `val other = name` referenced in `"hi $other"` produces `"hi $name"`, still in short form.
9. `var count = 1` read twice, then reassigned, then read again inlines the first two reads, keeps the declaration, and reports 2 of 3.
10. `val bound = limit + 1` with `limit` reassigned between two references inlines only the first and keeps the declaration.
11. A `when` subject variable's single reference inlines, but its declaration is never deleted - `when (val a = compute()) { 1 -> g(a); else -> 0 }` keeps `val a = compute()` intact inside the `when`.
12. A reference inside `run { val a = 99; f(x) }`, where the initializer reads an outer `a`, is left untouched and the declaration is kept.
13. A reference inside `with(other) { ... }`, where the initializer uses the enclosing receiver's members, is left untouched.
14. `val b = a.b` used as `if (b != null) b.length` leaves the smart-cast reference untouched.
15. `val x: Long = 1` is refused, and the message names the declared type.
16. A member property is refused with "only a local variable can be inlined".
17. `val x: Int` with a later `x = 1` is refused as having no value at its declaration.
18. A cursor on a destructuring entry is refused specifically, not as "not a variable".
19. An unused local is refused as never used, and the declaration is **not** deleted.
20. A cursor on a reference past the cutoff is refused, and no other reference is rewritten.
21. `val x = 1; return g(x)` on one line inlines to `return g(1)` with the rest of the line intact.
22. `val total = a + b // running total` leaves `// running total` on its own line, correctly indented.
23. Editing the file while the sheet is open, then confirming, reports the file-changed message and leaves the file untouched.
24. Undo restores the file; it currently takes **N+1** undo steps (R12) and intermediate states do not compile.
25. A space-indented file receives space-indented output; a CRLF file keeps CRLF.

## Design

Same shape as both extracts, and the same data boundary from [ADR 0013](../adr/0013-refactoring-ui-lives-in-the-owning-lsp-module.md): one background pass produces a plain-data plan, the UI holds no PSI.

```text
InlineVariableAction.execAction (background)                lsp/kotlin/actions
  server.compilationEnvironmentFor(path) ?: refusal
  -> buildInlineVariablePlan(...)                           utils/refactor/InlineVariablePlanner.kt
       ktFile = env.ktSymbolIndex.getCurrentKtFile(path).get()         [R3: before project.read]
       env.project.read {
         analyzeMaybeDangling(INTERACTIVE, cancelChecker) {                                  [R15]
           resolveTarget(ktFile, offset)                                                   [R2, R7]
           references(target, enclosingExecutableBody)                                       [R4]
           cutoffAfter(target)                                                               [R5]
           exclude per site: deferred / shadow / receiver / smartcast / callee                [R6]
           -> InlineVariablePlan | InlineRefusal                                         [R8, R14]
         }
       }
  <- InlineVariablePlan (plain data, no PSI)

InlineVariableAction.postExec (UI thread)
  refusal -> flashInfo(message for reason)                  [R14]
  no choice -> apply immediately + flashInfo(report)         [R9, R13]
  choice    -> InlineVariableSheet.show                     refactor/ui                     [R13]
  on apply -> version re-read; mismatch -> refuse            [R3]
    buildInlineVariableRewrites -> N+1 RewriteSpans         utils/refactor/InlineVariableEdit.kt
    client.performCodeAction(one DocumentChange, descending) [R12]
```

New files, all in `lsp/kotlin`:

- **`utils/refactor/InlineVariablePlan.kt`** - `InlineVariablePlan` (a `RefactoringPlan` subtype), `InlineReference` (span, template flag, inlinable-or-why), `InlineMode`, `InlineRefusal`, and the derived label/report functions (R13).
- **`utils/refactor/InlineVariablePlanner.kt`** - the single background pass: target resolution, references, cutoff, per-site exclusions (R2-R8, R15). The only analysis-dependent part.
- **`utils/refactor/InlineVariableEdit.kt`** - substitution text, parenthesisation, template wrapping, the three deletion shapes, and the descending edit list (R10-R12). Pure text and offsets.
- **`refactor/ui/InlineVariableSheet.kt`**, **`InlineVariableSheetContent.kt`** - sheet and content only, no ViewModel (R13).
- **`actions/InlineVariableAction.kt`** - registered in `KotlinCodeActionsMenu`; the only class touching the editor, the document version or the language client.
- **`TooltipTag.EDITOR_CODE_ACTIONS_KT_INLINE_VARIABLE`** - one new constant (R1).

Reused unchanged: `TextSpan`, `RewriteSpan` + `toTextEdit`, `positionAt`, `lineStartOffset`, `leadingIndentAt`, `detectNewline`, `enclosingExecutableBody`, `isWriteTarget`, `writeOffsetsFor`, `collapseForLabel`, `SheetComponents.kt`, `IdeTheme`, and `Occurrences.kt`'s symbol-identity comparison.

Deliberately **not** reused: `findOccurrences`, `excludeUnsoundOccurrences`, `CandidateExpression`, `ScopeOption` and `AnchorForm`. Inline has no candidate list, no scope chain and no insertion anchor, and its references are found by symbol identity rather than structural equality - the opposite direction from `findOccurrences`. The two families share primitives, not aggregates. `excludeUnsoundOccurrences` is close in spirit to R5 and still not reusable: it grows a contiguous run *outward* from a candidate in both directions, where the cutoff runs *forward* from a fixed declaration.

Nothing outside `lsp/kotlin` changes except `TooltipTag.kt` and `values/strings.xml`. No new module, no new dependency.

## Verification

Unit tests in `:lsp:kotlin` (`flox activate -d flox/local -- ./gradlew :lsp:kotlin:testV7DebugUnitTest`), split so a failure localises to one layer:

- **`InlineVariablePlanEndToEndTest`** - analysis-backed, one case per rule: both cursor positions (R2), the reference set (R4), the cutoff from each cause (R5), one case per per-site exclusion (R6), the explicit-type refusal (R7), the deletion rule including the `var`-with-a-later-write case and the `when`-subject-variable case (R8), mode availability per row of R9's table, and **one case per refusal reason** (R14).
- **`InlineVariableEditTest`** - pure text: parenthesisation per initializer class, both template forms, the three deletion line shapes, comment preservation on both a tab- and a space-indented fixture, descending edit order, and CRLF preservation (R10-R12).
- **`InlineVariablePlanTest`** - the pure derivations: R9's mode table and R13's labels and reports, against hand-built plans.
- **`RefactorPrimitivesTest`** - extended for whatever R6's scope walk factors out syntactically.
- **`KotlinCodeActionTooltipTagTest`** - one new row (R1).

Every new scope shape added to R6's shadowing walk needs its own end-to-end case in `InlineVariablePlanEndToEndTest` - that walk is where three separate defects have been found in review (a `when` subject variable, a class or object body, and a redeclaration in the target's own block).

There is no `ViewModelTest`, because there is no ViewModel (R13); the label and report derivations are tested in `InlineVariablePlanTest` against hand-built plans instead.

The sheet, `prepare()`/`ActionData`, the N+1 undo and the new tooltip row are not unit-testable; they are covered by on-device QA from the acceptance criteria above, recorded in ADFA-4827's "Steps to QA" field.

## Related

- [ADR 0014](../adr/0014-refactorings-decline-rather-than-rewrite.md) - refactorings decline rather than rewrite; amended by this feature to add partial application as a third outcome (R8)
- [ADR 0013](../adr/0013-refactoring-ui-lives-in-the-owning-lsp-module.md) - refactoring UI lives in the owning LSP module
- [kotlin-extract-variable.md](kotlin-extract-variable.md) - ADFA-4826; owns the family Language section and most primitives reused here
- [kotlin-extract-method.md](kotlin-extract-method.md) - ADFA-5080; the branch this one is based on, and the precedent for R12's edit ordering
- ADFA-5081 - code action edits should be a single undo step (fixes R12's consequence)
- ADFA-4825 - semantic rename, the remaining member of the family
- [ARCHITECTURE.md](../../ARCHITECTURE.md)
