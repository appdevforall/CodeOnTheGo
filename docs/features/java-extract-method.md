# Java extract method

- **Ticket:** ADFA-5048 (the Java sibling of ADFA-5080; requested in ADFA-4821's parity table)
- **Status:** Implemented
- **Modules:** `lsp/java`, `lsp/ui`
- **Vocabulary:** the term is **method**, matching the ticket and the tooltip tag `editor.codeactions.extractmethod`.

Move the expression at the cursor, or a selected range of statements, into a new `private` method, and replace it with a call to that method.

Java parity for the Kotlin refactoring specified in [kotlin-extract-method.md](kotlin-extract-method.md), built on the primitives ADFA-5047 already put in `lsp/java/refactor/` and `:lsp:refactor-core`. It adds no new module and no new dependency.

The governing principle is [ADR 0014](../adr/0014-refactorings-decline-rather-than-rewrite.md): this refactoring **moves** code, never edits the interior of what it moved, and where it cannot do that faithfully it **declines with a specific reason** rather than guessing.

## Language

Shared vocabulary - *selection*, *extraction region*, *expression candidate*, *text span*, *occurrence*, *refactoring plan*, *rewrite span* - is defined in [kotlin-extract-variable.md](kotlin-extract-variable.md#language); *statement range*, *captured declaration*, *output*, *exit* and *refusal* in [kotlin-extract-method.md](kotlin-extract-method.md#language). Java replaces one term and adds one.

**Anchor member**:
The nearest ancestor of the region that is a *direct member of a `ClassTree`* - a method, a constructor, an initializer block, or a field. It is both the boundary that decides what becomes a parameter and the sibling the new method is inserted after. Replaces Kotlin's *enclosing declaration*, which had no field case.
_Avoid_: enclosing method, parent, owner.

**Thrown checked type**:
A checked exception type the region can throw and does not itself catch. Each one becomes an entry in the new method's `throws` clause. Java's only analogue of Kotlin's `suspend`/`@Composable` rules: the one derived modifier whose omission produces code that does not compile.
_Avoid_: exception, throws clause.

## Scope

### In scope

An expression, or a range of sibling statements, inside any executable body in a Java file: a method or constructor body, an initializer block, a lambda body, or a method of a local or anonymous class.

### Out of scope

The positions extract variable already rejects for reasons that hold here too, via the same `isExtractionPosition` check: annotation arguments, `this(...)`/`super(...)` delegation arguments, `case` labels, and anything outside an executable body. The positions it rejects only because *it hoists* are in scope here (R2).

## Requirements

**R1 - Trigger.** An "Extract method" item (`action_extract_method`, already in `strings.xml`) in the editor code-actions menu for Java files, id `ide.editor.lsp.java.extractMethod`, tooltip tag `EDITOR_CODE_ACTIONS_EXTRACT_METHOD = "editor.codeactions.extractmethod"` - one new constant in `TooltipTag.kt`. The tag string is fixed by ADFA-4821: tooltip content lives in the out-of-repo tooltips database keyed by tag.

As with Java extract variable: **no `prepare()` visibility gate** (deciding extractability needs an attributed compile, far too costly on the UI thread), and `requiresUIThread = false` so the selection is read on a background thread. `BaseJavaCodeAction`'s file-type and module gate is all that applies.

**R2 - Region.** The selection resolves to exactly one extraction region, of one of two kinds.

*Expression candidate* - reuses `candidateExpressionsAt` including the whitespace trim, the `offset - 1` cursor retry, the innermost-first walk, `MAX_CANDIDATES = 3` and the type/lambda/method-reference exclusions. A bare cursor always takes this path first.

*Statement range* - a non-empty selection that spans statement boundaries snaps **outward** to whole statements; a touch selection will not land on a boundary. The result must be 1..N statements that are **siblings in one `BlockTree`**. A selection spanning two different blocks, or one whose ends do not both resolve to statements, is declined (`NotASingleRegion`). As in Kotlin, a selection that snaps to a single statement but sits strictly inside it prefers the expression path, falling back to the statement when nothing there is a legal target.

**Two predicates gain a `hoisted: Boolean = true` parameter**, and extract method passes `false`:

- `isExtractionPosition` skips `isConditionallyEvaluated`. That check exists because extract *variable* lifts the expression into a declaration **above** the enclosing statement, so hoisting a ternary branch, a `&&` right operand or a loop condition changes *when* it runs. Extract method substitutes a call **in place**, so `while (it.hasNext())` becomes `while (extracted(it))` and evaluation order is untouched. Refusing these would cost the feature its most useful candidates in exactly the guarded code where a helper reads best.
- `isLegalExtractionTarget` stops excluding an `ExpressionStatementTree`'s whole expression. Extract variable excludes it because replacing the expression leaves a bare `v;`, which is not a statement; `extracted(a, b);` is one. Without this a bare cursor in `foo(a, b);` - the single commonest place to reach for extract method - offers nothing.

The default value keeps every shipped extract-variable path byte-identical.

**R3 - Live offsets and the version guard.** Identical to Java extract variable: the plan is built inside `data.requireCompiler().compile(file).get { ... }`, records the document version from `FileManager.getActiveDocument`, and the confirm path re-reads that version, refusing on a mismatch and refusing outright when it is null.

**R4 - Target.** One uniform rule, no target picker: **the new method is inserted immediately after the anchor member**, as a member of the class that declares it.

| The region sits in | The new method becomes |
|---|---|
| a method or constructor | a `private` member of that class, after that method |
| an initializer or static initializer block | a `private` member, after that block |
| a lambda, in any of the above | still a sibling of the anchor member; the lambda's captures become parameters |
| a method of an anonymous or local class | a `private` member **of that class**, since the anchor member is the anonymous class's own method |
| a lambda or anonymous class inside a field initializer | a `private` member, after that field |

Java has no local-method form, so the insertion is *always after* the anchor and the new method always leads the descending edit list (R15). Kotlin's "no enclosing named declaration" refusal has no Java counterpart: a field is a class member, so a lambda in a field initializer anchors on the field and needs no special case.

The insertion offset absorbs a `;` sitting immediately after the anchor's end position, because javac's end position for a declaration does not reliably reach past its own semicolon. Absorbing one already inside the span is impossible, so the guard is a no-op where it is not needed; without it a field anchor risks an insertion between the field and its semicolon.

Unlike extract variable there is no scope chain and no ceiling: anything not visible at the insertion site becomes a parameter instead of constraining the anchor.

**R5 - Parameters.** A referenced variable needs a parameter exactly when it is a captured declaration - its declaration lies inside the anchor member. Fields need nothing: the new method is a member of the same class.

- **Order** - first textual appearance in the region, so the signature reads in the order the body uses it.
- **Names** - the original identifier, unchanged.
- **Types** - `VariableElement.asType()` rendered and then shortened by `shortenTypeText` against the file's own imports, exactly as extract variable renders a declared type: a name is shortened only where the file already resolves the short form, because this refactoring adds no imports. A type that cannot be written as source - a capture, an intersection, an anonymous class, an `<any>` resolution failure, detected by the existing `isUnrenderableTypeText` - **declines** (`UnrenderableType`).
- **Not editable.** The derived signature is shown read-only (R11). A wrong parameter *name* is fixable afterwards with rename; a wrong parameter *set* is not something the user could correct by hand anyway.

No receiver rules: Java has no extension receivers, and `this` resolves unchanged from a method of the same class.

**R6 - Return type and call-site form.** Determined by the region kind and its output:

| Case | Extracted body | Return type | Call site |
|---|---|---|---|
| expression candidate | `return <expr>;` | the expression's type | `extracted(args)` in the expression's place |
| expression candidate, `void`-typed call | `<expr>;` | `void` | `extracted(args)` |
| statement range, no output | the statements | `void` | `extracted(args);` |
| statement range, one output `x` | the statements, then `return x;` | `x`'s declared type | `T x = extracted(args);` |
| statement range, tail return (R8) | the statements including the `return` | the anchor method's return type | `return extracted(args);` |

A region that always throws still declares `void`; the exception propagates and the call site behaves identically.

**R7 - Outputs.** An output is a local declared inside the region and read after it, within the anchor member. Exactly one is supported; **two or more declines** (`MultipleOutputs`, naming them).

Kotlin's `OutputNotReturnable` has **no Java counterpart** and is not implemented: every Java local can be received back as `T x = extracted(...)`. The destructuring entries and local functions that refusal existed for do not exist in Java.

A variable declared **outside** the region and **assigned inside** it declines (`ReassignsOuterVar`, naming it): Java has no out parameters, so the assignment would be lost. Deliberately stricter than dataflow requires - a reassignment never read afterwards is still refused, because proving that needs real liveness analysis.

An **element** write through a captured reference (`arr[i] = x`, `list.add(x)`) is **not** a reassignment and does not decline: the reference is passed by value and the mutation is visible to the caller. `writeOffsetsFor` already draws exactly this distinction for extract variable.

The call site re-declares the output with its rendered type only; `final` and any annotations on the original declaration are dropped.

**R8 - Exits.** Every exit declines (`ExitsRegion`), with one syntactic exception.

An exit is a `return`, `break`, `continue` or `yield` inside the region whose target lies outside it. An unlabelled `break`/`continue` targets the nearest enclosing loop (or, for `break`, `switch`); a labelled one targets its `LabeledStatementTree`; a `yield` targets its switch expression. When that target is itself inside the region, nothing crosses the boundary and it is not an exit.

**Tail return:** when the region is a statement range whose *last* statement is a `return`, the region contains no other exit, there is no output, and the region's enclosing executable body **is the anchor member's own body** (not a nested lambda), the new method takes the anchor method's return type, keeps the `return`, and the call site becomes `return extracted(args);`. The body restriction is load-bearing: a `return` inside a lambda returns from the lambda, so taking the anchor's return type would emit a method returning something its body never returns.

A constructor anchor is treated as `void`; `return` is illegal in an initializer, so no tail return can arise there.

Not an exit: a `return` belonging to a method **declared inside** the region - a local class's method, an anonymous class's method, or a lambda body. It moves with its own declaration and its jump never crosses the region boundary.

A `throw` is not an exit; it propagates, and R10 declares it.

**R9 - Receivers.** Deliberately empty, and numbered to keep this document aligned with the Kotlin one. Java has no extension receivers and no `with`/`apply` scoping functions, so all three of Kotlin's receiver rules and the `InnerImplicitReceiver` refusal vanish. The dispatch receiver needs nothing: the new method is a member of the same class, so `this` and every instance member resolve unchanged.

**R10 - Modifiers and `throws`.** Copy nothing from the anchor member; add only what the body needs to compile in its new home.

- **Visibility** - always `private`. No annotations copied, no Javadoc generated.
- **`static`** - added exactly when the anchor member is static (a `static` method, a static initializer, or a `static` field). An instance anchor yields an instance method, where `this` and every instance member resolve unchanged.
- **`throws`** - the region's thrown checked types, rendered and shortened like every other type. Both halves of the derivation are load-bearing: under-declaring leaves the new method's body uncompilable, and over-declaring breaks the *call site*, which is only obliged to handle what the region actually threw.

  Collected from, within the region: each `MethodInvocationTree` and `NewClassTree`'s resolved `ExecutableElement.getThrownTypes()`, each `ThrowTree`'s expression type, and the `close()` thrown types of each try-with-resources resource. Then **subtracted**: a type caught by a `try` **inside** the region, where the site sits in that `try`'s block and some catch parameter type `C` satisfies `T <: C` (each alternative of a multi-catch considered separately). Then filtered to checked types - not assignable to `RuntimeException`, not assignable to `Error` - and deduplicated.

  The scan **does not descend into a nested lambda body, anonymous class body or local class**: a checked exception thrown there is constrained by that construct's own signature and never propagates to the anchor member.

  One case **declines** (`UnrenderableType`): a **generic `throws E`**, such as `Optional.orElseThrow(Supplier<? extends X>)`. `ExecutableElement.getThrownTypes()` reports the type variable as *declared on the callee*, and javac's public API does not hand back what it was inferred to at this call site. Writing `E` would emit a name nothing declares, and substituting its bound would over-declare and break the call site, so the region is declined rather than guessed at (ADR 0014).

  Copying the anchor member's own `throws` clause instead was rejected: it is wrong in exactly the commonest case, a region inside a `try` block whose method declares nothing.
- **Type parameters** - a region referencing a type variable **declared on the anchor method** declines (`UsesTypeParameter`, naming it). Class-level type parameters need no rule; they stay in scope for a member. Detection walks the `TypeMirror`s themselves for `TypeKind.TYPEVAR` whose element's generic element is the anchor method, rather than matching names in rendered text.

**R11 - Sheet.** The Kotlin extract-method sheet is **promoted from `lsp/kotlin` to `:lsp:ui`** and serves both languages, exactly as ADFA-5047 promoted the extract-variable sheet. `ExtractMethodSheet`, `ExtractMethodSheetContent`, `ExtractMethodViewModel`, `ExtractMethodUiState` and `ExtractMethodUiEvent` move unchanged in behaviour; `KOTLIN_NAME_MESSAGES`, hardcoded in the content today, becomes a `NameMessages` parameter as it already is on the extract-variable sheet.

The data boundary is a new `ExtractMethodContract.kt`, mirroring `ExtractVariableContract.kt`:

```kotlin
data class MethodCandidateView(
	val label: String,
	val suggestedName: String,
	val takenNames: Set<String>,
	val signaturePrefix: String,
	val signatureSuffix: String,
)

data class ExtractMethodSelection(val candidateIndex: Int, val name: String)
```

The signature is split around the name rather than pre-rendered, because the preview updates as the user types: Java composes `private static int ` + name + `(int a, int b) throws IOException`, Kotlin `private suspend fun ` + name + `(id: String): User`. One derivation each, so no preview can drift from its emitted declaration.

Contents, top to bottom: title, expression chooser (only for an expression region with more than one candidate), name field with its `NameProblem` message, signature preview, Cancel/Extract. No scope chooser (R4) and no replace-all checkbox (R13).

[ADR 0013](../adr/0013-refactoring-ui-lives-in-the-owning-lsp-module.md) deferred the shared-UI question until the extract-method surface was known. It now is, and the surface is identical for both languages, so that ADR is updated rather than left stale.

**R12 - Name.** Suggestion: for an expression region, `suggestVariableName` unchanged (shape, then rendered type, then `value`); for a statement range, the constant `extracted`, since there is no expression to read a name from. Uniquified as today.

Validation reuses `validateVariableName`, `NameProblem` and the shipped `JAVA_KEYWORDS`, so no new strings. Taken names are **every method name visible in the insertion class, including inherited ones** (`Elements.getAllMembers` filtered to methods and constructors).

Java private methods never override, so an inherited name is not an accidental-override hazard as it is in Kotlin; rejecting it anyway keeps one rule across both languages and stops the refactoring silently creating an overload the user did not ask for.

**R13 - One call site.** The region is the only site rewritten. No duplicate detection and no replace-all toggle: exact-duplicate matching would almost never fire, and near-duplicate matching needs anti-unification plus a per-site parameter mapping. `Occurrences.kt` is expression-granular by construction.

**R14 - Refusals.** The plan carries a typed `ExtractionRefusal` and `postExec` maps it to a specific message. Java uses **8 of Kotlin's 13 reasons**, all with strings that already exist:

| Reason | String |
|---|---|
| `NotASingleRegion` | `msg_extract_method_not_single_region` |
| `CouldNotAnalyse` | `msg_extract_method_could_not_analyse` |
| `MultipleOutputs` | `msg_extract_method_multiple_outputs` |
| `ReassignsOuterVar` | `msg_extract_method_reassigns_outer_var` |
| `ExitsRegion` | `msg_extract_method_exits_region` |
| `UsesTypeParameter` | `msg_extract_method_uses_type_parameter` |
| `UnrenderableType` | `msg_extract_method_unrenderable_type` |
| `CapturedLocalDeclaration` | `msg_extract_method_captured_local_declaration` |

`CapturedLocalDeclaration` covers the region referencing a local class, or a value whose type is a local class, declared inside the anchor member but outside the region: the value survives the move, the type name does not.

Dropped as inapplicable: `OutputNotReturnable` (R7), `AnonymousExtensionFunction`, `InnerImplicitReceiver`, `UsesBackingField`, `SmartCastParameter` - no Java construct produces any of them.

`CouldNotAnalyse` exists so the others stay truthful: a failed compile or a thrown analysis error must not be reported as `NotASingleRegion`, which blames a selection nothing ever looked at. Cancellation is not a refusal - `CancellationException` is re-thrown, so a cancelled action ends silently.

**R15 - Edit.** Two regions change - the region becomes a call, and the new method appears after the anchor member - emitted as **two `TextEdit`s in one `DocumentChange`, sorted by descending start offset**.

The ordering is mandatory, not stylistic, and was verified against the code rather than assumed: `IDELanguageClientImpl.applyActionEdits` iterates `change.getEdits()` in list order and `editInEditor` applies each with **line/column** ranges against whatever the text is at that moment (`Position.index` is ignored), so an earlier edit must never shift a later one. Java always inserts after the anchor, so the insertion always leads.

**Known consequence:** nothing on that path calls `beginBatchEdit`, so this is **two undo entries**, and a single undo leaves a half-refactored, non-compiling file. ADFA-5081 fixes it by batching the edit loop in `applyActionEdits`, which benefits every multi-edit action; until it lands, the two-step undo is a stated limitation to be covered in QA.

The new method is emitted **fully indented** at the anchor member's own indentation, separated by one blank line, reusing `detectIndentUnit`, `detectNewline`, `leadingIndentAt` and `positionAt`. Code-action edits bypass the editor's auto-indent, and running `CMD_FORMAT_CODE` here would reformat the whole file into the same undo step as the extraction.

One exception to re-indenting every line: lines inside a **text block** (`"""`) are emitted byte-for-byte. Their whitespace is part of the literal's value and the closing delimiter sets the incidental-whitespace margin, so shifting either edits the interior of the moved code (ADR 0014). The candidate carries those literals' spans so the text layer needs no tree.

**R16 - Responsiveness and failure isolation.** One background pass inside the compiler's own `compile(...).get { }` produces the whole plan; the sheet does pure string and offset arithmetic and never re-enters javac on confirm. Anything thrown in the pipeline degrades to a refusal (`CouldNotAnalyse`) plus a log line, never an uncaught throw: `DefaultActionsRegistry` catches only `IllegalArgumentException` and this runs on a scope with no exception handler.

`CancellationException` is the one deliberate exception and is re-thrown rather than swallowed, so a cancelled action ends quietly instead of flashing a message at a user who has moved on. The sheet's confirm path runs outside the framework's guards entirely, so it wraps its own body.

## Non-goals

- **Duplicate or near-duplicate call sites** (R13).
- **An editable parameter list** - rename, reorder or exclude (R5).
- **Two or more outputs, and a reassigned outer variable** (R7).
- **Mid-region `return`/`break`/`continue`/`yield`** (R8).
- **Type parameters declared on the anchor method** (R10).
- **Preserving `final` or annotations** on a re-declared output (R7).
- **Choosing a different target** - another class, another file, a nested class (R4). Moving a declaration elsewhere is a move refactoring.
- **Adding imports.** Types are shortened only where the file already resolves them, and otherwise written fully qualified (R5).
- **A region calling a method with a generic `throws E`** (R10). Declined, not guessed.
- **Extraction from a field initializer expression, annotation argument or `case` label** - inherited from `isExtractionPosition`.
- **Generated Javadoc** for the new method.
- **Atomic undo** of the two edits - ADFA-5081.
- **Formatting the result.** R15 emits indented text instead.

## Acceptance criteria

1. "Extract method" appears in the code-actions menu of a Java file and is absent in a non-Java file.
2. A cursor inside an expression offers the innermost-first candidates; extracting one replaces it with a call and adds a `private` method returning that expression, directly below the enclosing method.
3. A cursor inside `foo(a, b);` offers the call, and extracting it produces `extracted(a, b);` with a `void` method.
4. Selecting two adjacent statements that use two locals produces a method with those two locals as parameters, in first-use order, and a call passing them.
5. A selection with ragged boundaries snaps outward to whole statements before extracting.
6. A selection spanning two different blocks reports "Select an expression, or whole statements inside one block".
7. A range declaring a local that is read afterwards produces `T x = extracted(...);` at the call site.
8. A range declaring two locals that are both read afterwards is declined as producing more than one value.
9. Selecting a loop that accumulates into a local declared outside it is declined, and the message names that variable.
10. Selecting the tail of a method ending in `return x;` produces `return extracted(...);` and a method with the enclosing return type.
11. Selecting a range containing a `return` in the middle is declined; a range containing a whole loop with its own `break` is not.
12. Extracting from a `static` method produces a `private static` method.
13. Extracting a region that calls a method declaring `throws IOException` produces a method declaring `throws IOException`, and the file still compiles.
14. Extracting a region whose `try` catches that same `IOException` produces a method with **no** `throws` clause, and the file still compiles.
15. Extracting from inside an anonymous class's method inserts the new method into that anonymous class.
16. A region using a type parameter of the enclosing method is declined, naming the parameter.
17. A name matching an existing method - including an inherited one - is rejected with "That name is already used".
18. The signature preview matches the emitted declaration exactly, including `static` and `throws`.
19. Editing the file while the sheet is open, then confirming, reports the file-changed message and leaves the file untouched.
20. Undo restores the file; it currently takes **two** undo steps (R15), and the intermediate state is non-compiling.
21. A space-indented file receives space-indented output; a CRLF file keeps CRLF; a text block inside the region keeps its exact interior.
22. The Kotlin extract-method sheet is unchanged in appearance and behaviour after the move to `:lsp:ui`.

## Design

Same shape as Java extract variable, and the same data boundary as ADR 0013: one background pass produces a plain-data plan, the sheet holds no trees.

```
ExtractMethodAction.execAction (background)                lsp/java/actions
  data.requireCompiler().compile(file).get { task ->
    buildExtractMethodPlan(task, file, start, end, version) refactor/ExtractMethodPlanner.kt
      resolveExtractionRegion(root, text, start, end)       refactor/ExtractionRegion.kt        [R2]
        expression -> candidateExpressionsAt(hoisted=false) (reused)
        statements -> snap outward, sibling-in-one-block
      anchorMemberFor(regionPath)                                                               [R4]
      analyse against the attributed unit:                  refactor/MethodSignature.kt      [R5-R10]
        captured declarations -> parameters
        outputs / exits / thrown checked types / static / type parameters
      -> ExtractMethodPlan | ExtractionRefusal                                                 [R14]
  }
  <- ExtractMethodPlan (plain data, no trees)

ExtractMethodAction.postExec (UI thread)
  refusal -> flashInfo(message for reason)                                                     [R14]
  ExtractMethodSheet.show(views, JAVA_KEYWORDS, JAVA_NAME_MESSAGES)  lsp/ui                    [R11]
  on confirm -> version re-read; mismatch -> refuse                                             [R3]
    buildExtractMethodRewrites -> two RewriteSpans          refactor/ExtractMethodEdit.kt       [R15]
    client.performCodeAction(one DocumentChange, two TextEdits, descending)
```

New files in `lsp/java`:

- **`refactor/ExtractionRegion.kt`** - the region model and its resolution (R2). Purely syntactic apart from the legal-target check, so it tests against a parsed unit alone.
- **`refactor/MethodSignature.kt`** - captures to parameters, outputs, exits, thrown checked types, `static`, type parameters, and the signature's two halves (R5-R10). The only attribution-dependent part.
- **`refactor/ExtractMethodPlan.kt`** - `ExtractMethodCandidate`, `ExtractMethodPlan` and `ExtractionRefusal`.
- **`refactor/ExtractMethodPlanner.kt`** - the single background pass (R3, R16), with both a `CompileTask` and a bare `JavacTask` overload so tests need no project model, exactly as `buildExtractionPlan` has today.
- **`refactor/ExtractMethodEdit.kt`** - the two rewrites and their ordering (R15). Pure text and offsets.
- **`refactor/JavaExtractMethodUi.kt`** - the plan-to-view mapping and the selection-to-candidate lookup, mirroring `JavaExtractVariableUi.kt` (R11).
- **`actions/ExtractMethodAction.kt`** - registered in `JavaCodeActionsMenu`; the only class touching the editor, the document version or the language client.

Moved into `lsp/ui`, with `ExtractMethodContract.kt` added: `ExtractMethodSheet`, `ExtractMethodSheetContent`, `ExtractMethodViewModel`, `ExtractMethodUiState` (R11).

Reused unchanged from extract variable: `candidateExpressionsAt` / `CandidateSyntax`, `isExtractionPosition`, `isLegalExtractionTarget`, `enclosingExecutableBody`, `spanOf`, `trimToCode`, `deepestPathAt`, `referencedElements`, `INCREMENT_KINDS`, `shortenTypeText`, `importedNamesOf`, `starImportedPackagesOf`, `isUnrenderableTypeText`, `isValuelessKind`, `suggestVariableName`, `JAVA_KEYWORDS`, `JAVA_NAME_MESSAGES`, `collapseForLabel`, and all of `:lsp:refactor-core` (`TextSpan`, `RewriteSpan`, `toTextEdit`, `positionAt`, `detectIndentUnit`, `detectNewline`, `leadingIndentAt`, `MAX_CANDIDATES`).

Deliberately **not** reused: `ScopeChain.kt`, `ScopeOption`, `AnchorForm` and `Occurrences.findOccurrences`. Each is shaped by the legal scope chain and the replace-all feature, neither of which this refactoring has (R4, R13).

Outside these two modules, only `TooltipTag.kt` gains a constant. **No new strings, no new module, no new dependency.**

### Commits

One PR, reviewable by commit, mechanical before behavioural:

1. `refactor: promote the extract-method sheet into :lsp:ui` - move the four files, add the contract, thread `NameMessages` through, adapt `ExtractMethodAction` in `lsp/kotlin`, move its ViewModel test. No behaviour change.
2. `ADFA-5048: Add the Java extraction region` - `ExtractionRegion.kt` plus the two `hoisted` parameters.
3. `ADFA-5048: Add the Java extract-method signature analysis` - `MethodSignature.kt`, `ExtractMethodPlan.kt`, `ExtractMethodPlanner.kt`.
4. `ADFA-5048: Add the Java extract-method rewrite` - `ExtractMethodEdit.kt`.
5. `ADFA-5048: Add the Java extract method code action` - the action, the menu entry, the tooltip tag.
6. `docs: Java extract method, and ADR 0013's shared-UI update`.

## Verification

Unit tests in `:lsp:java` and `:lsp:ui`, mirroring the extract-variable split so a failure localises to one layer:

```bash
flox activate -d flox/local -- ./gradlew \
  :lsp:java:testV7DebugUnitTest --tests "com.itsaky.androidide.lsp.java.refactor.*" \
  :lsp:ui:testV7DebugUnitTest :lsp:refactor-core:testV7DebugUnitTest \
  :lsp:kotlin:testV7DebugUnitTest --tests "com.itsaky.androidide.lsp.kotlin.utils.refactor.*"
```

The `--tests` filter is not optional on `:lsp:java`: its unqualified suite includes the Robolectric `JavaLSPTest` harness, which boots the Gradle tooling API in a separate process and exceeds the task's 10-minute timeout on a developer machine. The refactor package needs none of it. `JavacFixture` already gives one hermetic attributed compile of a source string with no project model, and `compiles(source)` already answers whether a rewritten file still compiles.

- **`ExtractMethodRegionTest`** - parse-only: outward snapping to whole statements, the sibling-in-one-block rule, cross-block rejection, the expression path, and the two `hoisted = false` relaxations (R2).
- **`ExtractMethodPlanTest`** - attribution-backed, one case per rule: the parameter set, order and types (R5), each call-site form (R6), the single output and the `void` case (R7), the tail return and the nested-declaration `return` that is not an exit (R8), `static` (R10), `throws` derivation **and** its subtraction by an inner `catch` (R10), the anonymous-class anchor and the field anchor (R4), and **one case per refusal reason** (R14).
- **`ExtractMethodEditTest`** - pure text: the two edits and their descending order, the call-site forms, indentation, text blocks left verbatim, the blank-line separation, and CRLF preservation (R15).
- **`ExtractMethodViewModelTest`** - moved to `:lsp:ui`: chooser visibility, name validation, and the rendered signature preview for **both** languages' prefix/suffix shapes (R11, R12).

Every plan test asserts the extracted file **actually compiles** via `compiles(...)`, not merely that the emitted text matches a string. That is the assertion that matters for `throws`, `static` and captured types, where a plausible-looking signature is exactly the failure mode.

The sheet, `prepare()`/`ActionData`, the two-step undo and the new tooltip row are not unit-testable; they are covered by on-device QA from the acceptance criteria above, recorded in ADFA-5048's "Steps to QA" field, at font scale 1.0 and 2.0.

## Related

- [ADR 0014](../adr/0014-refactorings-decline-rather-than-rewrite.md) - refactorings decline rather than rewrite unselected code; the principle behind R7-R10
- [ADR 0013](../adr/0013-refactoring-ui-lives-in-the-owning-lsp-module.md) - refactoring UI placement; updated by R11
- [kotlin-extract-method.md](kotlin-extract-method.md) - ADFA-5080, the specification this achieves parity with
- [kotlin-extract-variable.md](kotlin-extract-variable.md) - ADFA-4826; owns the shared Language section
- ADFA-5047 - Java extract variable, which contributed every primitive reused here
- ADFA-4821 - the code-action parity table this ticket comes from
- ADFA-5081 - code action edits should be a single undo step (fixes R15's consequence)
- [ARCHITECTURE.md](../../ARCHITECTURE.md)
