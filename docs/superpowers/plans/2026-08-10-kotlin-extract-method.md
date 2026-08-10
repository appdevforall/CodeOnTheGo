# Kotlin Extract Method Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add an "Extract method" code action to the Kotlin K2 LSP that moves an expression or a range of sibling statements into a new `private fun` and replaces it with a call, declining with a specific reason wherever it cannot do that faithfully.

**Architecture:** Same shape as extract variable (ADFA-4826), which is already on this branch. One background analysis pass produces a plain-data `ExtractMethodPlan` (no PSI); a Compose bottom sheet does pure string arithmetic on it; confirming re-reads the document version and emits **two** `TextEdit`s in one `DocumentChange`, ordered new-function-first. Every hard case is a typed `ExtractionRefusal` rather than a clever rewrite (ADR 0012).

**Tech Stack:** Kotlin, K2 Analysis API (`org.jetbrains.kotlin.analysis.api`), IntelliJ PSI (`org.jetbrains.kotlin.psi`), Jetpack Compose + Material3, JUnit4 + Robolectric.

## Global Constraints

- **Module:** everything lives in `lsp/kotlin`, except one constant in `idetooltips/.../TooltipTag.kt` and new strings in `resources/src/main/res/values/strings.xml`. No new module, no new dependency.
- **Vocabulary:** the term is **method** in user-facing text and type names, even though the output is a Kotlin `fun`. Internal vocabulary is fixed by the spec: *statement range*, *enclosing declaration*, *captured declaration*, *output*, *exit*, *refusal*.
- **Code style:** tabs for indentation, LF endings, ktlint via Spotless. ASCII only in code and comments (`->` not the arrow glyph, `--` not an em dash). No separator/banner comments. Comment the non-obvious *why*, never the what.
- **Ticket:** ADFA-5080. Commit subjects are `ADFA-5080: Short description` (colon, imperative). **Never** add a `Co-Authored-By` trailer. **Never** `git add .` / `git add -A` -- stage named files only.
- **Never commit this plan file** or anything under `docs/superpowers/`. `docs/features/kotlin-extract-method.md` IS a real project doc and does get committed.
- **Build wrapper:** every Gradle call is `flox activate -d flox/local -- ./gradlew <task>`.
- **Test task:** `:lsp:kotlin:testV7DebugUnitTest` (V7 flavor; there is no flavorless `testDebugUnitTest`). Compile-only check: `:lsp:kotlin:compileV7DebugKotlin`.
- **Tooltip tag string is fixed:** `"editor.codeactions.kotlin.extractmethod"`. Tooltip content lives in an out-of-repo database keyed by that tag, so it cannot be renamed.
- **Action id is fixed:** `ide.editor.lsp.kt.extractMethod`.
- **No `prepare()` visibility gate** and `requiresUIThread = false` -- deciding extractability needs an analysis session, far too costly for the UI thread. Never do I/O or analysis on the main thread.
- **Edit ordering is mandatory:** `IDELanguageClientImpl.applyActionEdits` applies edits in list order using line/column ranges against the text as it is at that moment. Emit the function insertion **before** the call-site replacement (descending document order) or the file is corrupted.
- **Emitted text must be fully indented.** Code-action `TextEdit`s bypass the editor's auto-indent and `CMD_FORMAT_CODE` is a no-op for Kotlin.

---

## Existing code you will reuse

All in `lsp/kotlin/src/main/java/com/itsaky/androidide/lsp/kotlin/`. Read these before starting -- the plan assumes their exact signatures.

| Symbol | File | Signature |
|---|---|---|
| `TextSpan` | `utils/refactor/ExtractionPlan.kt` | `data class TextSpan(val start: Int, val end: Int)`, `.length`, `.overlaps(other)` |
| `collapseForLabel` | `utils/refactor/ExtractionPlan.kt` | `internal fun collapseForLabel(text: String, maxLength: Int = 80): String` |
| `candidateExpressionsAt` | `utils/refactor/CandidateExpressions.kt` | `fun candidateExpressionsAt(file: KtFile, selectionStart: Int, selectionEnd: Int): CandidateSyntax` |
| `CandidateSyntax` | `utils/refactor/CandidateExpressions.kt` | `data class CandidateSyntax(val expressions: List<KtExpression>, val selectionMatchedInnermost: Boolean)` |
| `trimToCode` | `utils/refactor/CandidateExpressions.kt` | `internal fun trimToCode(text: String, start: Int, end: Int): Pair<Int, Int>?` |
| `isExtractionPosition` | `utils/refactor/CandidateExpressions.kt` | `internal fun isExtractionPosition(element: PsiElement): Boolean` |
| `enclosingExecutableBody` | `utils/refactor/CandidateExpressions.kt` | `internal fun enclosingExecutableBody(element: PsiElement): PsiElement?` |
| `NameProblem`, `validateVariableName` | `utils/refactor/NameSuggestion.kt` | `fun validateVariableName(name: String, takenNames: Set<String>): NameProblem?` |
| `suggestVariableName` | `utils/refactor/NameSuggestion.kt` | `fun suggestVariableName(expression: KtExpression, typeName: String?, takenNames: Set<String>): String` |
| `detectIndentUnit`, `leadingIndentAt`, `lineStartOffset` | `utils/refactor/ScopeChain.kt` | `internal fun detectIndentUnit(text: String): String` etc. |
| `detectNewline`, `positionAt`, `RewriteSpan`, `toTextEdit` | `utils/refactor/ExtractVariableEdit.kt` | `data class RewriteSpan(val span: TextSpan, val newText: String)`, `fun RewriteSpan.toTextEdit(fileText: String): TextEdit` |
| `renderName` | `utils/TypeRendering.kt` | `internal fun KaSession.renderName(type: KaType, ...): String` |
| `analyzeMaybeDangling` | `compiler/modules/` | `analyzeMaybeDangling(ktFile, AnalysisPriority.INTERACTIVE, cancelChecker) { ... }` |
| `env.project.read { }` | `compiler/` | plain read lock; `getCurrentKtFile(...).get()` must be called **outside** it or it deadlocks |
| `KtLspTest` | `src/test/.../fixtures/KtLspTest.kt` | base class; `createSourceFile(name, content)`, `env`, `noopCancelChecker()` |

Deliberately **not** reused: `ScopeOption`, `AnchorForm`, `CandidateExpression`, `Occurrences.kt`. Those are shaped by extract variable's legal scope chain, which this refactoring does not have.

## File Structure

**Created (all under `lsp/kotlin/src/main/java/com/itsaky/androidide/lsp/kotlin/`):**

| File | Responsibility |
|---|---|
| `utils/refactor/RefactoringPlan.kt` | The sealed supertype both plans share: `fileText`, `documentVersion` (R3). |
| `utils/refactor/ExtractionRegion.kt` | Resolving a selection to one region: expression candidates or a statement range (R2). Pure PSI. |
| `utils/refactor/ExtractMethodPlan.kt` | `ExtractMethodPlan`, `ExtractMethodCandidate`, `MethodParameter`, `ExtractedBody`, `CallSiteForm`, `ExtractionRefusal`, `signatureText` (R5-R6, R11, R14). Plain data. |
| `utils/refactor/ExtractMethodEdit.kt` | The two rewrites and their descending order (R15). Pure text and offsets. |
| `utils/refactor/MethodSignature.kt` | The analysis: captured declarations -> parameters, outputs, exits, receivers, modifiers, taken names, refusals (R5-R10, R12). |
| `utils/refactor/ExtractMethodPlanner.kt` | The single background pass (R3, R16). |
| `refactor/ui/SheetComponents.kt` | `LabelledSection`, `OptionList`, `NameProblem.messageRes()` promoted out of the extract-variable sheet (R11). |
| `refactor/ui/ExtractMethodUiState.kt` | `ExtractMethodUiState`, `ExtractMethodUiEvent`, `ExtractMethodChoice` (R11). |
| `refactor/ui/ExtractMethodViewModel.kt` | State derivation, name validation, signature preview (R11, R12). |
| `refactor/ui/ExtractMethodSheetContent.kt` | Stateless Compose content (R11). |
| `refactor/ui/ExtractMethodSheet.kt` | `BottomSheetDialogFragment` hosting a `ComposeView` (R11). |
| `actions/ExtractMethodAction.kt` | The only class touching the editor, the document version or the language client (R1, R3, R14, R15). |

**Modified:**

| File | Change |
|---|---|
| `utils/refactor/ExtractionPlan.kt` | `ExtractionPlan` implements `RefactoringPlan`. |
| `utils/refactor/NameSuggestion.kt` | Expose `uniqueName(base, taken)` (was the private `makeUnique`). |
| `refactor/ui/ExtractVariableSheetContent.kt` | Delete the local `LabelledSection`, `OptionList`, `messageRes()`; they move to `SheetComponents.kt`. |
| `KotlinCodeActionsMenu.kt` | Register `ExtractMethodAction()`. |
| `idetooltips/.../TooltipTag.kt` | `EDITOR_CODE_ACTIONS_KT_EXTRACT_METHOD`. |
| `resources/src/main/res/values/strings.xml` | Title, labels, and the seven refusal messages. |
| `docs/features/kotlin-extract-method.md` | Status line. |

**Tests (under `lsp/kotlin/src/test/java/com/itsaky/androidide/lsp/kotlin/`):**

- `utils/refactor/ExtractMethodRegionTest.kt` -- PSI only (R2).
- `utils/refactor/ExtractMethodEditTest.kt` -- pure text (R6, R15).
- `utils/refactor/ExtractMethodPlanEndToEndTest.kt` -- analysis-backed (R5-R10, R12, R14).
- `refactor/ui/ExtractMethodViewModelTest.kt` -- state derivation (R11, R12).
- `KotlinCodeActionTooltipTagTest.kt` -- one new row.

---

## Task 1: The shared `RefactoringPlan` supertype

The spec (R3) says the version guard is "shared via the `RefactoringPlan` supertype", described as already introduced by the extract-variable PR. **It was not** -- `ExtractionPlan` is a standalone data class. This task introduces it so extract method is purely additive, exactly as the spec assumes.

**Files:**
- Create: `lsp/kotlin/src/main/java/com/itsaky/androidide/lsp/kotlin/utils/refactor/RefactoringPlan.kt`
- Modify: `lsp/kotlin/src/main/java/com/itsaky/androidide/lsp/kotlin/utils/refactor/ExtractionPlan.kt` (the `data class ExtractionPlan(...)` declaration, around line 128)

**Interfaces:**
- Consumes: nothing.
- Produces: `sealed interface RefactoringPlan { val fileText: String; val documentVersion: Int }`. Task 3's `ExtractMethodPlan` implements it.

- [ ] **Step 1: Create the supertype**

Create `RefactoringPlan.kt`:

```kotlin
package com.itsaky.androidide.lsp.kotlin.utils.refactor

/**
 * What every interactive refactoring's background pass returns.
 *
 * The two fields are what makes applying a plan safe long after it was computed: [fileText] is the
 * text its offsets refer to, and [documentVersion] is re-read on confirm so a plan computed against
 * text the user has since edited is discarded rather than applied against shifted offsets.
 */
sealed interface RefactoringPlan {
	val fileText: String
	val documentVersion: Int
}
```

- [ ] **Step 2: Make `ExtractionPlan` implement it**

In `ExtractionPlan.kt`, change the declaration (keep the whole KDoc block above it untouched):

```kotlin
data class ExtractionPlan(
	override val fileText: String,
	override val documentVersion: Int,
	val candidates: List<CandidateExpression>,
	val selectionMatchedCandidate: Boolean,
) : RefactoringPlan {
```

- [ ] **Step 3: Verify the existing tests still pass**

Run: `flox activate -d flox/local -- ./gradlew :lsp:kotlin:testV7DebugUnitTest`
Expected: PASS. This is a pure retrofit; a failure means something else broke.

- [ ] **Step 4: Commit**

```bash
git add lsp/kotlin/src/main/java/com/itsaky/androidide/lsp/kotlin/utils/refactor/RefactoringPlan.kt \
        lsp/kotlin/src/main/java/com/itsaky/androidide/lsp/kotlin/utils/refactor/ExtractionPlan.kt
git commit -m "ADFA-5080: Hoist the shared refactoring plan supertype"
```

---

## Task 2: Region resolution

**Files:**
- Create: `lsp/kotlin/src/main/java/com/itsaky/androidide/lsp/kotlin/utils/refactor/ExtractionRegion.kt`
- Test: `lsp/kotlin/src/test/java/com/itsaky/androidide/lsp/kotlin/utils/refactor/ExtractMethodRegionTest.kt`

**Interfaces:**
- Consumes: `TextSpan`, `trimToCode`, `candidateExpressionsAt`, `CandidateSyntax`, `isExtractionPosition`.
- Produces:
  - `sealed interface ExtractionRegion { val span: TextSpan }`
  - `data class ExtractionRegion.Expressions(val candidates: List<KtExpression>, val selectionMatchedInnermost: Boolean)`
  - `data class ExtractionRegion.Statements(val statements: List<KtExpression>, val block: KtBlockExpression)`
  - `fun resolveExtractionRegion(file: KtFile, selectionStart: Int, selectionEnd: Int): ExtractionRegion?`

- [ ] **Step 1: Write the failing test**

Create `ExtractMethodRegionTest.kt`. It extends `KtLspTest` for the PSI factory only -- it never opens an analysis session.

```kotlin
package com.itsaky.androidide.lsp.kotlin.utils.refactor

import com.itsaky.androidide.lsp.kotlin.fixtures.KtLspTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.jetbrains.kotlin.psi.KtFile

/**
 * Region resolution is purely syntactic, so it is tested with no analysis session at all -- the same
 * split `CandidateExpressions.kt` already has.
 */
class ExtractMethodRegionTest : KtLspTest() {
	private fun file(content: String): KtFile = createSourceFile("Main.kt", content)

	private fun region(
		content: String,
		start: Int,
		end: Int = start,
	): ExtractionRegion? = resolveExtractionRegion(file(content), start, end)

	private val twoStatements =
		"""
		package p
		fun log(n: Int) {}
		fun demo(a: Int, b: Int) {
			val sum = a + b
			log(sum)
		}
		""".trimIndent()

	@Test
	fun `a bare cursor resolves to expression candidates`() {
		val region = region(twoStatements, twoStatements.indexOf("a + b") + 1)

		assertTrue(region is ExtractionRegion.Expressions)
		assertEquals("a + b", (region as ExtractionRegion.Expressions).candidates.first().text)
	}

	@Test
	fun `a selection over two whole statements resolves to a statement range`() {
		val start = twoStatements.indexOf("val sum")
		val end = twoStatements.indexOf("log(sum)") + "log(sum)".length

		val region = region(twoStatements, start, end)

		assertTrue(region is ExtractionRegion.Statements)
		assertEquals(
			listOf("val sum = a + b", "log(sum)"),
			(region as ExtractionRegion.Statements).statements.map { it.text },
		)
	}

	@Test
	fun `ragged boundaries snap outward to whole statements`() {
		// Starts mid-`sum` and stops mid-`log(sum)`, as a touch drag routinely does.
		val start = twoStatements.indexOf("sum = a + b")
		val end = twoStatements.indexOf("log(sum)") + 3

		val region = region(twoStatements, start, end)

		assertTrue(region is ExtractionRegion.Statements)
		assertEquals(
			listOf("val sum = a + b", "log(sum)"),
			(region as ExtractionRegion.Statements).statements.map { it.text },
		)
	}

	@Test
	fun `a selection inside a single statement stays an expression selection`() {
		val start = twoStatements.indexOf("a + b")

		val region = region(twoStatements, start, start + "a + b".length)

		assertTrue(region is ExtractionRegion.Expressions)
		assertEquals("a + b", (region as ExtractionRegion.Expressions).candidates.first().text)
		assertTrue(region.selectionMatchedInnermost)
	}

	@Test
	fun `a selection spanning two different blocks resolves to nothing`() {
		val content =
			"""
			package p
			fun log(n: Int) {}
			fun demo(c: Boolean, a: Int) {
				if (c) {
					log(a)
				}
				log(a + 1)
			}
			""".trimIndent()
		val start = content.indexOf("log(a)")
		val end = content.indexOf("log(a + 1)") + "log(a + 1)".length

		assertNull(region(content, start, end))
	}

	@Test
	fun `the statement range span covers first to last statement`() {
		val start = twoStatements.indexOf("val sum")
		val end = twoStatements.indexOf("log(sum)") + "log(sum)".length

		val region = region(twoStatements, start, end) as ExtractionRegion.Statements

		assertEquals(TextSpan(start, end), region.span)
	}

	@Test
	fun `a whitespace-only selection resolves to nothing`() {
		val start = twoStatements.indexOf("val sum") - 1

		assertNull(region(twoStatements, start, start + 1))
	}

	@Test
	fun `a property initializer outside an executable body resolves to nothing`() {
		val content =
			"""
			package p
			fun compute(): Int = 1
			class C {
				val x = compute() + compute()
			}
			""".trimIndent()

		assertNull(region(content, content.indexOf("compute() + compute()") + 1))
	}
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `flox activate -d flox/local -- ./gradlew :lsp:kotlin:testV7DebugUnitTest --tests "com.itsaky.androidide.lsp.kotlin.utils.refactor.ExtractMethodRegionTest"`
Expected: compilation failure -- `Unresolved reference: resolveExtractionRegion`.

- [ ] **Step 3: Write the implementation**

Create `ExtractionRegion.kt`:

```kotlin
package com.itsaky.androidide.lsp.kotlin.utils.refactor

import org.jetbrains.kotlin.com.intellij.psi.PsiElement
import org.jetbrains.kotlin.psi.KtBlockExpression
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtFile

/**
 * What a selection resolved to. Exactly two kinds, which is the whole reason the hard cases never
 * arise: a selection covering half an `if` and half its `else`, or straddling a lambda boundary,
 * is neither, and is declined by construction rather than filtered out later.
 */
sealed interface ExtractionRegion {
	/** The region's covering span in the file's text. */
	val span: TextSpan

	/**
	 * One or more nested expressions at the cursor, innermost first. The user picks between them in
	 * the sheet unless [selectionMatchedInnermost] says they already have.
	 */
	data class Expressions(
		val candidates: List<KtExpression>,
		val selectionMatchedInnermost: Boolean,
	) : ExtractionRegion {
		override val span: TextSpan
			get() = candidates.first().textRange.let { TextSpan(it.startOffset, it.endOffset) }
	}

	/** One or more sibling statements in a single [block]. */
	data class Statements(
		val statements: List<KtExpression>,
		val block: KtBlockExpression,
	) : ExtractionRegion {
		override val span: TextSpan
			get() =
				TextSpan(
					statements.first().textRange.startOffset,
					statements.last().textRange.endOffset,
				)
	}
}

/**
 * Resolves `[selectionStart, selectionEnd)` to the one region the refactoring will act on, or null
 * when it is neither kind.
 *
 * A bare cursor is always the expression path. A non-empty selection snaps **outward** to whole
 * statements -- a touch selection will not land on a boundary -- but a selection that lies strictly
 * inside one statement is still an expression selection: widening it to the whole statement would
 * silently extract more than the user picked.
 */
fun resolveExtractionRegion(
	file: KtFile,
	selectionStart: Int,
	selectionEnd: Int,
): ExtractionRegion? {
	val (start, end) = trimToCode(file.text, selectionStart, selectionEnd) ?: return null
	if (start == end) return expressionRegion(file, selectionStart, selectionEnd)

	val statements = snapToStatements(file, start, end) ?: return expressionRegion(file, selectionStart, selectionEnd)

	val only = statements.singleOrNull()
	if (only != null && (start > only.textRange.startOffset || end < only.textRange.endOffset)) {
		expressionRegion(file, selectionStart, selectionEnd)?.let { return it }
	}

	val block = statements.first().parent as? KtBlockExpression ?: return null
	return ExtractionRegion.Statements(statements, block)
}

private fun expressionRegion(
	file: KtFile,
	selectionStart: Int,
	selectionEnd: Int,
): ExtractionRegion.Expressions? {
	val syntax = candidateExpressionsAt(file, selectionStart, selectionEnd)
	if (syntax.expressions.isEmpty()) return null
	return ExtractionRegion.Expressions(syntax.expressions, syntax.selectionMatchedInnermost)
}

/**
 * The whole statements `[start, end)` touches, when they are siblings in one [KtBlockExpression].
 *
 * Null when the two ends land in different blocks, which is what rejects a selection spanning an
 * `if` body and the code after it without needing to reason about the constructs involved.
 */
private fun snapToStatements(
	file: KtFile,
	start: Int,
	end: Int,
): List<KtExpression>? {
	val first = statementContaining(file, start) ?: return null
	val last = statementContaining(file, (end - 1).coerceAtLeast(start)) ?: return null

	val block = first.parent as? KtBlockExpression ?: return null
	if (last.parent !== block) return null
	if (!isExtractionPosition(first)) return null

	val statements = block.statements
	val from = statements.indexOfFirst { it === first }
	val to = statements.indexOfFirst { it === last }
	if (from < 0 || to < from) return null
	return statements.subList(from, to + 1).toList()
}

/**
 * The statement containing [offset]: the nearest ancestor that is a direct expression child of a
 * block. Null for a position that is not inside one, such as a comment or a class body.
 */
private fun statementContaining(
	file: KtFile,
	offset: Int,
): KtExpression? {
	var current: PsiElement? = file.findElementAt(offset) ?: return null
	while (current != null && current !is KtFile) {
		if (current is KtExpression && current.parent is KtBlockExpression) return current
		current = current.parent
	}
	return null
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `flox activate -d flox/local -- ./gradlew :lsp:kotlin:testV7DebugUnitTest --tests "com.itsaky.androidide.lsp.kotlin.utils.refactor.ExtractMethodRegionTest"`
Expected: PASS, 8 tests.

- [ ] **Step 5: Format and commit**

```bash
flox activate -d flox/local -- ./gradlew spotlessApply
git add lsp/kotlin/src/main/java/com/itsaky/androidide/lsp/kotlin/utils/refactor/ExtractionRegion.kt \
        lsp/kotlin/src/test/java/com/itsaky/androidide/lsp/kotlin/utils/refactor/ExtractMethodRegionTest.kt
git commit -m "ADFA-5080: Resolve a selection to an extraction region"
```

---

## Task 3: The plan data model and the two rewrites

Pure data and pure text: no PSI, no analysis. Doing this before the analysis means the edit shape is pinned down and tested before anything has to derive it.

**Files:**
- Create: `lsp/kotlin/src/main/java/com/itsaky/androidide/lsp/kotlin/utils/refactor/ExtractMethodPlan.kt`
- Create: `lsp/kotlin/src/main/java/com/itsaky/androidide/lsp/kotlin/utils/refactor/ExtractMethodEdit.kt`
- Test: `lsp/kotlin/src/test/java/com/itsaky/androidide/lsp/kotlin/utils/refactor/ExtractMethodEditTest.kt`

**Interfaces:**
- Consumes: `RefactoringPlan` (Task 1), `TextSpan`, `RewriteSpan`, `detectNewline`, `detectIndentUnit`, `leadingIndentAt`.
- Produces, all used by Tasks 4-7:
  - `data class MethodParameter(val name: String, val typeText: String)`
  - `sealed interface ExtractedBody` with `ExpressionBody(needsReturn: Boolean)` and `StatementBody(trailingReturn: String?)`
  - `sealed interface CallSiteForm` with `Call`, `AssignOutput(name: String)`, `Return`
  - `data class ExtractMethodCandidate(label, span, suggestedName, takenNames, annotations, modifiers, receiverTypeText, parameters, returnTypeText, body, callSite, insertOffset, insertIndent)`
  - `sealed interface ExtractionRefusal` with `NotASingleRegion`, `MultipleOutputs(names: List<String>)`, `ReassignsOuterVar(name: String)`, `ExitsRegion`, `InnerImplicitReceiver(construct: String)`, `UsesTypeParameter(name: String)`, `UnrenderableType`
  - `data class ExtractMethodPlan(fileText, documentVersion, candidates, selectionMatchedCandidate, refusal) : RefactoringPlan` with `.isEmpty` and `companion object { fun refused(refusal, fileText = "", documentVersion = -1) }`
  - `fun ExtractMethodCandidate.signatureText(name: String): String`
  - `fun buildExtractMethodRewrites(fileText: String, candidate: ExtractMethodCandidate, name: String): List<RewriteSpan>?`

- [ ] **Step 1: Write the failing test**

Create `ExtractMethodEditTest.kt`:

```kotlin
package com.itsaky.androidide.lsp.kotlin.utils.refactor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The emitted text, with every candidate built by hand -- no PSI, no analysis. Assertions are on the
 * resulting file text, the only kind that catches an indentation or off-by-one error.
 */
class ExtractMethodEditTest {
	private val file =
		"package p\n" +
			"class C {\n" +
			"\tfun demo(a: Int, b: Int): Int {\n" +
			"\t\tval sum = a + b\n" +
			"\t\treturn sum\n" +
			"\t}\n" +
			"}\n"

	private val enclosingStart = file.indexOf("fun demo")
	private val enclosingEnd = file.indexOf("\t}\n}") + 2

	private fun candidate(
		span: TextSpan,
		body: ExtractedBody,
		callSite: CallSiteForm,
		parameters: List<MethodParameter> = emptyList(),
		returnTypeText: String? = null,
		modifiers: List<String> = listOf("private"),
		annotations: List<String> = emptyList(),
		receiverTypeText: String? = null,
	) = ExtractMethodCandidate(
		label = "region",
		span = span,
		suggestedName = "extracted",
		takenNames = emptySet(),
		annotations = annotations,
		modifiers = modifiers,
		receiverTypeText = receiverTypeText,
		parameters = parameters,
		returnTypeText = returnTypeText,
		body = body,
		callSite = callSite,
		insertOffset = enclosingEnd,
		insertIndent = "\t",
	)

	/** Applies the rewrites in the order they are returned, exactly as the language client does. */
	private fun apply(
		text: String,
		rewrites: List<RewriteSpan>,
	): String =
		rewrites.fold(text) { current, rewrite ->
			current.substring(0, rewrite.span.start) + rewrite.newText + current.substring(rewrite.span.end)
		}

	@Test
	fun `the function insertion comes before the call site`() {
		val span = TextSpan(file.indexOf("a + b"), file.indexOf("a + b") + "a + b".length)
		val rewrites =
			buildExtractMethodRewrites(
				file,
				candidate(
					span,
					ExtractedBody.ExpressionBody(needsReturn = true),
					CallSiteForm.Call,
					parameters = listOf(MethodParameter("a", "Int"), MethodParameter("b", "Int")),
					returnTypeText = "Int",
				),
				"total",
			)

		assertNotNull(rewrites)
		assertEquals(2, rewrites!!.size)
		assertTrue(
			"the insertion must be at a higher offset than the call site",
			rewrites[0].span.start > rewrites[1].span.start,
		)
	}

	@Test
	fun `an expression region becomes a call and a returning function`() {
		val span = TextSpan(file.indexOf("a + b"), file.indexOf("a + b") + "a + b".length)
		val rewrites =
			buildExtractMethodRewrites(
				file,
				candidate(
					span,
					ExtractedBody.ExpressionBody(needsReturn = true),
					CallSiteForm.Call,
					parameters = listOf(MethodParameter("a", "Int"), MethodParameter("b", "Int")),
					returnTypeText = "Int",
				),
				"total",
			)!!

		assertEquals(
			"package p\n" +
				"class C {\n" +
				"\tfun demo(a: Int, b: Int): Int {\n" +
				"\t\tval sum = total(a, b)\n" +
				"\t\treturn sum\n" +
				"\t}\n" +
				"\n" +
				"\tprivate fun total(a: Int, b: Int): Int {\n" +
				"\t\treturn a + b\n" +
				"\t}\n" +
				"}\n",
			apply(file, rewrites),
		)
	}

	@Test
	fun `a statement range with one output assigns at the call site`() {
		val span = TextSpan(file.indexOf("val sum"), file.indexOf("val sum") + "val sum = a + b".length)
		val rewrites =
			buildExtractMethodRewrites(
				file,
				candidate(
					span,
					ExtractedBody.StatementBody(trailingReturn = "return sum"),
					CallSiteForm.AssignOutput("sum"),
					parameters = listOf(MethodParameter("a", "Int"), MethodParameter("b", "Int")),
					returnTypeText = "Int",
				),
				"total",
			)!!

		assertEquals(
			"package p\n" +
				"class C {\n" +
				"\tfun demo(a: Int, b: Int): Int {\n" +
				"\t\tval sum = total(a, b)\n" +
				"\t\treturn sum\n" +
				"\t}\n" +
				"\n" +
				"\tprivate fun total(a: Int, b: Int): Int {\n" +
				"\t\tval sum = a + b\n" +
				"\t\treturn sum\n" +
				"\t}\n" +
				"}\n",
			apply(file, rewrites),
		)
	}

	@Test
	fun `a tail return region returns the call`() {
		val span = TextSpan(file.indexOf("return sum"), file.indexOf("return sum") + "return sum".length)
		val rewrites =
			buildExtractMethodRewrites(
				file,
				candidate(
					span,
					ExtractedBody.StatementBody(trailingReturn = null),
					CallSiteForm.Return,
					parameters = listOf(MethodParameter("sum", "Int")),
					returnTypeText = "Int",
				),
				"finish",
			)!!

		assertEquals(
			"package p\n" +
				"class C {\n" +
				"\tfun demo(a: Int, b: Int): Int {\n" +
				"\t\tval sum = a + b\n" +
				"\t\treturn finish(sum)\n" +
				"\t}\n" +
				"\n" +
				"\tprivate fun finish(sum: Int): Int {\n" +
				"\t\treturn sum\n" +
				"\t}\n" +
				"}\n",
			apply(file, rewrites),
		)
	}

	@Test
	fun `a multi-line statement range is reindented under the new function`() {
		val text =
			"package p\n" +
				"fun demo(a: Int) {\n" +
				"\tif (a > 0) {\n" +
				"\t\tprintln(a)\n" +
				"\t}\n" +
				"}\n"
		val start = text.indexOf("if (a > 0)")
		val rewrites =
			buildExtractMethodRewrites(
				text,
				ExtractMethodCandidate(
					label = "region",
					span = TextSpan(start, text.indexOf("\t}\n}") + 2),
					suggestedName = "extracted",
					takenNames = emptySet(),
					annotations = emptyList(),
					modifiers = listOf("private"),
					receiverTypeText = null,
					parameters = listOf(MethodParameter("a", "Int")),
					returnTypeText = null,
					body = ExtractedBody.StatementBody(trailingReturn = null),
					callSite = CallSiteForm.Call,
					insertOffset = text.length - 1,
					insertIndent = "",
				),
				"report",
			)!!

		assertEquals(
			"package p\n" +
				"fun demo(a: Int) {\n" +
				"\treport(a)\n" +
				"}\n" +
				"\n" +
				"private fun report(a: Int) {\n" +
				"\tif (a > 0) {\n" +
				"\t\tprintln(a)\n" +
				"\t}\n" +
				"}\n",
			apply(text, rewrites),
		)
	}

	@Test
	fun `a CRLF file keeps CRLF`() {
		val text =
			"package p\r\n" +
				"fun demo(a: Int) {\r\n" +
				"\tprintln(a)\r\n" +
				"}\r\n"
		val start = text.indexOf("println(a)")
		val rewrites =
			buildExtractMethodRewrites(
				text,
				ExtractMethodCandidate(
					label = "region",
					span = TextSpan(start, start + "println(a)".length),
					suggestedName = "extracted",
					takenNames = emptySet(),
					annotations = emptyList(),
					modifiers = listOf("private"),
					receiverTypeText = null,
					parameters = listOf(MethodParameter("a", "Int")),
					returnTypeText = null,
					body = ExtractedBody.StatementBody(trailingReturn = null),
					callSite = CallSiteForm.Call,
					insertOffset = text.length - 2,
					insertIndent = "",
				),
				"report",
			)!!

		assertTrue(rewrites.all { !it.newText.contains("\n") || it.newText.contains("\r\n") })
		assertTrue(apply(text, rewrites).contains("\r\nprivate fun report(a: Int) {\r\n"))
	}

	@Test
	fun `the signature preview matches what is emitted`() {
		val span = TextSpan(file.indexOf("a + b"), file.indexOf("a + b") + "a + b".length)
		val subject =
			candidate(
				span,
				ExtractedBody.ExpressionBody(needsReturn = true),
				CallSiteForm.Call,
				parameters = listOf(MethodParameter("a", "Int")),
				returnTypeText = "Int",
				modifiers = listOf("private", "suspend"),
				annotations = listOf("@Composable"),
				receiverTypeText = "Foo",
			)

		assertEquals("@Composable private suspend fun Foo.total(a: Int): Int", subject.signatureText("total"))
		assertTrue(
			buildExtractMethodRewrites(file, subject, "total")!![0]
				.newText
				.contains("@Composable private suspend fun Foo.total(a: Int): Int {"),
		)
	}

	@Test
	fun `a span past the end of the text produces nothing`() {
		val subject =
			candidate(
				TextSpan(file.length - 1, file.length + 10),
				ExtractedBody.StatementBody(trailingReturn = null),
				CallSiteForm.Call,
			)

		assertNull(buildExtractMethodRewrites(file, subject, "total"))
	}
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `flox activate -d flox/local -- ./gradlew :lsp:kotlin:testV7DebugUnitTest --tests "com.itsaky.androidide.lsp.kotlin.utils.refactor.ExtractMethodEditTest"`
Expected: compilation failure -- `Unresolved reference: ExtractMethodCandidate`.

- [ ] **Step 3: Write the data model**

Create `ExtractMethodPlan.kt`:

```kotlin
package com.itsaky.androidide.lsp.kotlin.utils.refactor

/** One derived parameter of the new function. Names are the originals, unchanged (R5). */
data class MethodParameter(
	val name: String,
	val typeText: String,
)

/** What goes inside the new function's braces. */
sealed interface ExtractedBody {
	/**
	 * The region's expression text. [needsReturn] is false only for a `Unit`-valued expression, where
	 * the function returns `Unit` and a bare statement reads better than `return println(x)`.
	 */
	data class ExpressionBody(
		val needsReturn: Boolean,
	) : ExtractedBody

	/**
	 * The statements verbatim. [trailingReturn] is the `return <output>` line appended for the
	 * single-output case, and null otherwise -- including the tail-return case, where the region
	 * already ends in a `return`.
	 */
	data class StatementBody(
		val trailingReturn: String?,
	) : ExtractedBody
}

/** How the region's own text is replaced (R6). */
sealed interface CallSiteForm {
	/** `extracted(args)` -- an expression in place, or a statement. */
	data object Call : CallSiteForm

	/** `val x = extracted(args)` for the single output [name]. */
	data class AssignOutput(
		val name: String,
	) : CallSiteForm

	/** `return extracted(args)` for the tail-return case (R8). */
	data object Return : CallSiteForm
}

/**
 * One extractable region, fully derived: everything the sheet renders and the edit builder emits,
 * with no PSI left in it.
 *
 * [span] is what the call site replaces. [insertOffset] is the end of the enclosing declaration --
 * the new function goes immediately after it (R4) -- and [insertIndent] is that declaration's own
 * indentation, since nothing re-indents a code-action edit after it is applied.
 *
 * [returnTypeText] is null for a `Unit` function, where the `: Unit` is left off.
 */
data class ExtractMethodCandidate(
	val label: String,
	val span: TextSpan,
	val suggestedName: String,
	val takenNames: Set<String>,
	val annotations: List<String>,
	val modifiers: List<String>,
	val receiverTypeText: String?,
	val parameters: List<MethodParameter>,
	val returnTypeText: String?,
	val body: ExtractedBody,
	val callSite: CallSiteForm,
	val insertOffset: Int,
	val insertIndent: String,
)

/**
 * Why a region could not be extracted. A refusal is a designed outcome, not an error (ADR 0012):
 * each reason gets its own message naming the construct in the way, because a generic one reads as
 * the feature being broken.
 */
sealed interface ExtractionRefusal {
	/** The selection is neither one expression nor whole statements inside one block (R2). */
	data object NotASingleRegion : ExtractionRefusal

	/** Two or more locals declared inside the region are read after it (R7). */
	data class MultipleOutputs(
		val names: List<String>,
	) : ExtractionRefusal

	/** A `var` declared outside the region is assigned inside it. ADFA-5082 lifts this (R7). */
	data class ReassignsOuterVar(
		val name: String,
	) : ExtractionRefusal

	/** A `return`, `break` or `continue` whose target is outside the region (R8). */
	data object ExitsRegion : ExtractionRefusal

	/** Members of a `with`/`apply`/`run` receiver introduced inside the enclosing declaration (R9). */
	data class InnerImplicitReceiver(
		val construct: String,
	) : ExtractionRefusal

	/** A type parameter declared on the enclosing function (R10). */
	data class UsesTypeParameter(
		val name: String,
	) : ExtractionRefusal

	/** A parameter or return type that cannot be written out as source (R5). */
	data object UnrenderableType : ExtractionRefusal
}

/**
 * The complete result of the background pass.
 *
 * Unlike extract variable's plan this carries a [refusal] rather than merely being empty, because
 * "why not" is most of what this refactoring has to say (ADR 0012). [candidates] and [refusal] are
 * mutually exclusive in practice: a non-empty candidate list means at least one region survived.
 */
data class ExtractMethodPlan(
	override val fileText: String,
	override val documentVersion: Int,
	val candidates: List<ExtractMethodCandidate>,
	val selectionMatchedCandidate: Boolean,
	val refusal: ExtractionRefusal?,
) : RefactoringPlan {
	val isEmpty: Boolean get() = candidates.isEmpty()

	companion object {
		fun refused(
			refusal: ExtractionRefusal,
			fileText: String = "",
			documentVersion: Int = -1,
		) = ExtractMethodPlan(fileText, documentVersion, emptyList(), selectionMatchedCandidate = false, refusal = refusal)
	}
}

/**
 * The signature exactly as [buildExtractMethodRewrites] emits it. The sheet's preview calls this, so
 * there is one derivation and the preview cannot drift from the declaration (R11).
 */
fun ExtractMethodCandidate.signatureText(name: String): String =
	buildString {
		annotations.forEach { append(it).append(' ') }
		modifiers.forEach { append(it).append(' ') }
		append("fun ")
		receiverTypeText?.let { append(it).append('.') }
		append(name)
		append('(')
		append(parameters.joinToString(", ") { "${it.name}: ${it.typeText}" })
		append(')')
		returnTypeText?.let { append(": ").append(it) }
	}
```

- [ ] **Step 4: Write the edit builder**

Create `ExtractMethodEdit.kt`:

```kotlin
package com.itsaky.androidide.lsp.kotlin.utils.refactor

/**
 * The two replacements an extraction performs: the new function, then the call that replaces the
 * region.
 *
 * **The order is mandatory, not stylistic.** `IDELanguageClientImpl.applyActionEdits` iterates the
 * list and applies each edit with line/column ranges against whatever the text is at that moment.
 * The insertion point sits after the region, so emitting the call first would shift it and corrupt
 * the file. Descending document order is the only safe order.
 *
 * Nothing on that path calls `beginBatchEdit`, so this costs the user **two** undo steps and the
 * intermediate state does not compile. ADFA-5081 fixes that by batching the edit loop; until it
 * lands the two-step undo is a stated limitation.
 *
 * The region is the only site rewritten (R13). Exact-duplicate matching would almost never fire, and
 * near-duplicate matching needs anti-unification plus a per-site parameter mapping.
 *
 * Returns null when the offsets cannot be honoured, which the caller reports rather than applying.
 */
fun buildExtractMethodRewrites(
	fileText: String,
	candidate: ExtractMethodCandidate,
	name: String,
): List<RewriteSpan>? {
	val span = candidate.span
	if (span.end > fileText.length) return null
	if (candidate.insertOffset > fileText.length || candidate.insertOffset < span.end) return null

	val newline = detectNewline(fileText)
	val indent = candidate.insertIndent
	val bodyIndent = indent + detectIndentUnit(fileText)
	val regionText = fileText.substring(span.start, span.end)
	val baseIndent = leadingIndentAt(fileText, span.start)

	val bodyLines =
		when (val body = candidate.body) {
			is ExtractedBody.ExpressionBody -> {
				val lines = reindent(regionText, baseIndent, newline)
				if (body.needsReturn) listOf("return " + lines.first()) + lines.drop(1) else lines
			}

			is ExtractedBody.StatementBody ->
				reindent(regionText, baseIndent, newline) + listOfNotNull(body.trailingReturn)
		}

	val declaration =
		buildString {
			// A blank line separates the new function from the declaration it follows.
			append(newline).append(newline)
			append(indent).append(candidate.signatureText(name)).append(" {").append(newline)
			bodyLines.forEach { append(bodyIndent).append(it).append(newline) }
			append(indent).append('}')
		}

	val call = "$name(${candidate.parameters.joinToString(", ") { it.name }})"
	val callText =
		when (val form = candidate.callSite) {
			CallSiteForm.Call -> call
			is CallSiteForm.AssignOutput -> "val ${form.name} = $call"
			CallSiteForm.Return -> "return $call"
		}

	return listOf(
		RewriteSpan(TextSpan(candidate.insertOffset, candidate.insertOffset), declaration),
		RewriteSpan(span, callText),
	)
}

/**
 * Splits the region into lines with its original base indentation removed, so the caller can prefix
 * each with the new function's body indentation. Lines nested deeper than the base keep the extra
 * depth; the first line never carries indentation, since the span starts at the code itself.
 */
private fun reindent(
	text: String,
	baseIndent: String,
	newline: String,
): List<String> =
	text.split(newline).mapIndexed { index, line ->
		if (index == 0) line else line.removePrefix(baseIndent)
	}
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `flox activate -d flox/local -- ./gradlew :lsp:kotlin:testV7DebugUnitTest --tests "com.itsaky.androidide.lsp.kotlin.utils.refactor.ExtractMethodEditTest"`
Expected: PASS, 8 tests. If an assertion on exact text fails, fix the *implementation*, not the expectation, unless the expectation itself has a wrong tab count.

- [ ] **Step 6: Format and commit**

```bash
flox activate -d flox/local -- ./gradlew spotlessApply
git add lsp/kotlin/src/main/java/com/itsaky/androidide/lsp/kotlin/utils/refactor/ExtractMethodPlan.kt \
        lsp/kotlin/src/main/java/com/itsaky/androidide/lsp/kotlin/utils/refactor/ExtractMethodEdit.kt \
        lsp/kotlin/src/test/java/com/itsaky/androidide/lsp/kotlin/utils/refactor/ExtractMethodEditTest.kt
git commit -m "ADFA-5080: Add the extract-method plan model and its two rewrites"
```

---

## Task 4: The analysis -- signature derivation and refusals

The only analysis-dependent part. This is the largest task; compile early with `:lsp:kotlin:compileV7DebugKotlin` rather than waiting for the tests.

**Files:**
- Create: `lsp/kotlin/src/main/java/com/itsaky/androidide/lsp/kotlin/utils/refactor/MethodSignature.kt`
- Create: `lsp/kotlin/src/main/java/com/itsaky/androidide/lsp/kotlin/utils/refactor/ExtractMethodPlanner.kt`
- Modify: `lsp/kotlin/src/main/java/com/itsaky/androidide/lsp/kotlin/utils/refactor/NameSuggestion.kt` (the private `makeUnique`, around line 147)
- Create: `lsp/kotlin/src/test/java/com/itsaky/androidide/lsp/kotlin/utils/refactor/ExtractMethodPlanEndToEndTest.kt`

**Interfaces:**
- Consumes: everything from Tasks 2 and 3, plus `renderName`, `suggestVariableName`, `collapseForLabel`, `leadingIndentAt`, `analyzeMaybeDangling`, `env.project.read`, `env.ktSymbolIndex.getCurrentKtFile`.
- Produces:
  - `internal fun uniqueName(base: String, takenNames: Set<String>): String` (in `NameSuggestion.kt`)
  - `internal fun KaSession.buildCandidate(elements: List<KtExpression>, isExpression: Boolean, fileText: String): SignatureResult`
  - `internal sealed interface SignatureResult { data class Success(val candidate: ExtractMethodCandidate); data class Refused(val refusal: ExtractionRefusal) }`
  - `internal fun buildExtractMethodPlan(env: AbstractCompilationEnvironment, nioPath: Path, selectionStart: Int, selectionEnd: Int, documentVersion: Int, cancelChecker: ScheduledCancelChecker): ExtractMethodPlan`

- [ ] **Step 1: Expose `uniqueName`**

In `NameSuggestion.kt`, rename the private helper and make it internal. Change

```kotlin
/** `size` -> `size1` -> `size2` until nothing in [takenNames] matches. */
private fun makeUnique(
	base: String,
	takenNames: Set<String>,
): String {
```

to

```kotlin
/** `size` -> `size1` -> `size2` until nothing in [takenNames] matches. */
internal fun uniqueName(
	base: String,
	takenNames: Set<String>,
): String {
```

and update the one call site inside `suggestVariableName` from `makeUnique(sanitised, takenNames)` to `uniqueName(sanitised, takenNames)`.

- [ ] **Step 2: Write the failing test**

Create `ExtractMethodPlanEndToEndTest.kt`. One case per rule, plus one per refusal reason.

```kotlin
package com.itsaky.androidide.lsp.kotlin.utils.refactor

import com.itsaky.androidide.lsp.kotlin.fixtures.KtLspTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The parts of the plan that need real resolution: the parameter set, the return type and call-site
 * form, the modifiers, and one case per refusal reason.
 *
 * Where a rewrite is produced the assertion is on the resulting file text, which is the only
 * assertion that catches an indentation or off-by-one error.
 */
class ExtractMethodPlanEndToEndTest : KtLspTest() {
	private fun plan(
		content: String,
		start: Int,
		end: Int = start,
	): ExtractMethodPlan {
		createSourceFile("Main.kt", content)
		val path = env.sourceRoots.first().resolve("Main.kt")
		return buildExtractMethodPlan(env, path, start, end, documentVersion = 1, cancelChecker = noopCancelChecker())
	}

	private fun apply(
		text: String,
		rewrites: List<RewriteSpan>,
	): String =
		rewrites.fold(text) { current, rewrite ->
			current.substring(0, rewrite.span.start) + rewrite.newText + current.substring(rewrite.span.end)
		}

	private fun selection(
		content: String,
		from: String,
		to: String,
	): Pair<Int, Int> = content.indexOf(from) to (content.indexOf(to) + to.length)

	@Test
	fun `an expression region parameterises the locals it uses, in first-use order`() {
		val content =
			"""
			package p
			fun demo(a: Int, b: Int): Int {
				return b * a + a
			}
			""".trimIndent()

		val result = plan(content, content.indexOf("b * a") + 1)
		val candidate = result.candidates.first { it.label == "b * a" }

		assertEquals(listOf("b" to "Int", "a" to "Int"), candidate.parameters.map { it.name to it.typeText })
		assertEquals("Int", candidate.returnTypeText)
		assertEquals(listOf("private"), candidate.modifiers)
	}

	@Test
	fun `a statement range with no output returns Unit and calls as a statement`() {
		val content =
			"""
			package p
			fun log(n: Int) {}
			fun demo(a: Int) {
				log(a)
				log(a + 1)
			}
			""".trimIndent()
		val (start, end) = selection(content, "log(a)", "log(a + 1)")

		val result = plan(content, start, end)
		val candidate = result.candidates.single()

		assertNull(candidate.returnTypeText)
		assertEquals(CallSiteForm.Call, candidate.callSite)
		assertEquals(listOf("a"), candidate.parameters.map { it.name })
		assertEquals("extracted", candidate.suggestedName)
	}

	@Test
	fun `a single output becomes the return value and a val at the call site`() {
		val content =
			"""
			package p
			fun demo(a: Int): Int {
				val doubled = a * 2
				return doubled + 1
			}
			""".trimIndent()
		val (start, end) = selection(content, "val doubled", "val doubled = a * 2")

		val result = plan(content, start, end)
		val candidate = result.candidates.single()

		assertEquals(CallSiteForm.AssignOutput("doubled"), candidate.callSite)
		assertEquals("Int", candidate.returnTypeText)
	}

	@Test
	fun `two outputs are declined`() {
		val content =
			"""
			package p
			fun demo(a: Int): Int {
				val x = a * 2
				val y = a * 3
				return x + y
			}
			""".trimIndent()
		val (start, end) = selection(content, "val x", "val y = a * 3")

		val refusal = plan(content, start, end).refusal

		assertTrue(refusal is ExtractionRefusal.MultipleOutputs)
		assertEquals(listOf("x", "y"), (refusal as ExtractionRefusal.MultipleOutputs).names)
	}

	@Test
	fun `a reassigned outer var is declined and names the variable`() {
		val content =
			"""
			package p
			fun demo(items: List<Int>): Int {
				var total = 0
				for (item in items) {
					total += item
				}
				return total
			}
			""".trimIndent()
		val (start, end) = selection(content, "for (item in items)", "\t}")

		val refusal = plan(content, start, end).refusal

		assertEquals(ExtractionRefusal.ReassignsOuterVar("total"), refusal)
	}

	@Test
	fun `a tail return keeps the return and returns the call`() {
		val content =
			"""
			package p
			fun demo(a: Int): Int {
				val doubled = a * 2
				return doubled + 1
			}
			""".trimIndent()
		val (start, end) = selection(content, "return doubled", "return doubled + 1")

		val result = plan(content, start, end)
		val candidate = result.candidates.single()

		assertEquals(CallSiteForm.Return, candidate.callSite)
		assertEquals("Int", candidate.returnTypeText)
		assertEquals(
			"""
			package p
			fun demo(a: Int): Int {
				val doubled = a * 2
				return finish(doubled)
			}

			private fun finish(doubled: Int): Int {
				return doubled + 1
			}
			""".trimIndent(),
			apply(content, buildExtractMethodRewrites(result.fileText, candidate, "finish")!!),
		)
	}

	@Test
	fun `a return in the middle of the range is declined`() {
		val content =
			"""
			package p
			fun demo(a: Int): Int {
				if (a > 0) return a
				val b = a * 2
				return b
			}
			""".trimIndent()
		val (start, end) = selection(content, "if (a > 0) return a", "val b = a * 2")

		assertEquals(ExtractionRefusal.ExitsRegion, plan(content, start, end).refusal)
	}

	@Test
	fun `a break targeting an outer loop is declined`() {
		val content =
			"""
			package p
			fun demo(items: List<Int>) {
				for (item in items) {
					if (item < 0) break
					println(item)
				}
			}
			""".trimIndent()
		val (start, end) = selection(content, "if (item < 0) break", "println(item)")

		assertEquals(ExtractionRefusal.ExitsRegion, plan(content, start, end).refusal)
	}

	@Test
	fun `an extension receiver is copied onto the new function`() {
		val content =
			"""
			package p
			class Foo(val n: Int)
			fun Foo.bar(): Int {
				return n * 2
			}
			""".trimIndent()

		val result = plan(content, content.indexOf("n * 2") + 1)
		val candidate = result.candidates.first { it.label == "n * 2" }

		assertEquals("Foo", candidate.receiverTypeText)
		// `this` is a Foo at the call site, so nothing is passed and nothing is captured.
		assertEquals(emptyList<MethodParameter>(), candidate.parameters)
	}

	@Test
	fun `an inner with receiver is declined and names the construct`() {
		val content =
			"""
			package p
			class Foo { val n: Int = 1 }
			fun demo(f: Foo): Int {
				with(f) {
					return n * 2
				}
			}
			""".trimIndent()

		val refusal = plan(content, content.indexOf("n * 2") + 1).refusal

		assertEquals(ExtractionRefusal.InnerImplicitReceiver("with"), refusal)
	}

	@Test
	fun `a suspend call adds the suspend modifier`() {
		val content =
			"""
			package p
			suspend fun load(): Int = 1
			suspend fun demo(): Int {
				return load() + 1
			}
			""".trimIndent()

		val result = plan(content, content.indexOf("load() + 1") + 1)
		val candidate = result.candidates.first { it.label == "load() + 1" }

		assertEquals(listOf("private", "suspend"), candidate.modifiers)
	}

	@Test
	fun `a Composable call adds the Composable annotation`() {
		createSourceFile(
			"Composable.kt",
			"""
			package androidx.compose.runtime
			annotation class Composable
			""".trimIndent(),
		)
		val content =
			"""
			package p
			import androidx.compose.runtime.Composable
			@Composable fun Label(text: String) {}
			@Composable fun Demo(name: String) {
				Label(name)
			}
			""".trimIndent()
		val (start, end) = selection(content, "Label(name)", "Label(name)")

		val candidate = plan(content, start, end).candidates.single()

		assertEquals(listOf("@Composable"), candidate.annotations)
	}

	@Test
	fun `a function-level type parameter is declined and names it`() {
		val content =
			"""
			package p
			fun <T> demo(value: T): String {
				val held: T = value
				return held.toString()
			}
			""".trimIndent()
		val (start, end) = selection(content, "val held", "val held: T = value")

		assertEquals(ExtractionRefusal.UsesTypeParameter("T"), plan(content, start, end).refusal)
	}

	@Test
	fun `taken names include inherited members`() {
		val content =
			"""
			package p
			open class Base { fun helper(): Int = 1 }
			class Child : Base() {
				fun demo(a: Int): Int {
					return a * 2
				}
			}
			""".trimIndent()

		val candidate = plan(content, content.indexOf("a * 2") + 1).candidates.first { it.label == "a * 2" }

		// A private member matching an inherited name is an accidental-override compile error.
		assertTrue("helper" in candidate.takenNames)
		assertTrue("demo" in candidate.takenNames)
	}

	@Test
	fun `a selection spanning two blocks is declined as not a single region`() {
		val content =
			"""
			package p
			fun log(n: Int) {}
			fun demo(c: Boolean, a: Int) {
				if (c) {
					log(a)
				}
				log(a + 1)
			}
			""".trimIndent()
		val (start, end) = selection(content, "log(a)", "log(a + 1)")

		assertEquals(ExtractionRefusal.NotASingleRegion, plan(content, start, end).refusal)
	}

	@Test
	fun `an expression extraction rewrites the call site and adds a member function`() {
		val content =
			"""
			package p
			class C {
				fun demo(a: Int, b: Int): Int {
					return a + b
				}
			}
			""".trimIndent()

		val result = plan(content, content.indexOf("a + b") + 1)
		val candidate = result.candidates.first { it.label == "a + b" }

		assertEquals(
			"""
			package p
			class C {
				fun demo(a: Int, b: Int): Int {
					return total(a, b)
				}

				private fun total(a: Int, b: Int): Int {
					return a + b
				}
			}
			""".trimIndent(),
			apply(content, buildExtractMethodRewrites(result.fileText, candidate, "total")!!),
		)
	}
}
```

- [ ] **Step 3: Run the test to verify it fails**

Run: `flox activate -d flox/local -- ./gradlew :lsp:kotlin:testV7DebugUnitTest --tests "com.itsaky.androidide.lsp.kotlin.utils.refactor.ExtractMethodPlanEndToEndTest"`
Expected: compilation failure -- `Unresolved reference: buildExtractMethodPlan`.

- [ ] **Step 4: Write `MethodSignature.kt`**

This derives one candidate from one region. Every resolution call is wrapped in `runCatching` -- resolution over broken code throws, and a throw here must read as a refusal, not a crash.

```kotlin
package com.itsaky.androidide.lsp.kotlin.utils.refactor

import com.itsaky.androidide.lsp.kotlin.utils.renderName
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.resolution.successfulFunctionCallOrNull
import org.jetbrains.kotlin.analysis.api.resolution.symbol
import org.jetbrains.kotlin.analysis.api.symbols.KaCallableSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaClassSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaNamedFunctionSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaValueParameterSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaVariableSymbol
import org.jetbrains.kotlin.analysis.api.symbols.markers.KaNamedSymbol
import org.jetbrains.kotlin.builtins.StandardNames
import org.jetbrains.kotlin.com.intellij.psi.PsiElement
import org.jetbrains.kotlin.com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.idea.references.mainReference
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtAnonymousInitializer
import org.jetbrains.kotlin.psi.KtBinaryExpression
import org.jetbrains.kotlin.psi.KtBlockExpression
import org.jetbrains.kotlin.psi.KtBreakExpression
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtContinueExpression
import org.jetbrains.kotlin.psi.KtDeclaration
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtFunctionLiteral
import org.jetbrains.kotlin.psi.KtLoopExpression
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtPropertyAccessor
import org.jetbrains.kotlin.psi.KtQualifiedExpression
import org.jetbrains.kotlin.psi.KtReturnExpression
import org.jetbrains.kotlin.psi.KtSecondaryConstructor
import org.jetbrains.kotlin.psi.KtSimpleNameExpression
import org.jetbrains.kotlin.psi.KtTypeReference
import org.jetbrains.kotlin.psi.KtUnaryExpression

/** The name of the statement-range suggestion; there is no expression to read a name from (R12). */
private const val STATEMENT_RANGE_NAME = "extracted"

private const val COMPOSABLE_FQ_NAME = "androidx.compose.runtime.Composable"

/**
 * Receiver-binding scoping functions. `let`, `also` and `forEach` are absent on purpose: they bind
 * `it`, which is a captured declaration and becomes an ordinary parameter (R5).
 */
private val RECEIVER_SCOPING_FUNCTIONS =
	setOf("with", "apply", "run", "buildString", "buildList", "buildMap", "buildSet")

/** Either a derived candidate or the reason there is not one. */
internal sealed interface SignatureResult {
	data class Success(
		val candidate: ExtractMethodCandidate,
	) : SignatureResult

	data class Refused(
		val refusal: ExtractionRefusal,
	) : SignatureResult
}

/**
 * Derives one candidate from [elements] -- a single expression, or the statement range.
 *
 * Ordered so the cheapest refusals come first and nothing expensive runs for a region that is going
 * to be declined anyway. MUST be called inside an analysis session.
 */
internal fun KaSession.buildCandidate(
	elements: List<KtExpression>,
	isExpression: Boolean,
	fileText: String,
): SignatureResult {
	val first = elements.first()
	val last = elements.last()
	val span = TextSpan(first.textRange.startOffset, last.textRange.endOffset)
	val enclosing = enclosingDeclaration(first) ?: return refuse(ExtractionRefusal.NotASingleRegion)

	typeParameterIn(enclosing, elements)?.let { return refuse(ExtractionRefusal.UsesTypeParameter(it)) }
	innerImplicitReceiver(enclosing, elements, span)?.let { return refuse(ExtractionRefusal.InnerImplicitReceiver(it)) }
	reassignedOuterVar(enclosing, elements, span)?.let { return refuse(ExtractionRefusal.ReassignsOuterVar(it)) }

	val tailReturn = !isExpression && isTailReturn(elements, span)
	if (!tailReturn && hasExit(elements, span)) return refuse(ExtractionRefusal.ExitsRegion)

	val outputs = if (isExpression) emptyList() else outputsOf(enclosing, elements, span)
	if (outputs.size > 1) {
		return refuse(ExtractionRefusal.MultipleOutputs(outputs.mapNotNull { it.name }))
	}
	// The tail-return exception holds only when nothing else flows out (R8).
	if (tailReturn && outputs.isNotEmpty()) return refuse(ExtractionRefusal.ExitsRegion)

	val parameters = capturedParameters(enclosing, elements, span) ?: return refuse(ExtractionRefusal.UnrenderableType)

	val returnTypeText =
		when {
			isExpression -> renderedTypeOrNull(first) ?: return refuse(ExtractionRefusal.UnrenderableType)
			tailReturn -> enclosingReturnType(enclosing) ?: return refuse(ExtractionRefusal.UnrenderableType)
			outputs.size == 1 ->
				renderedDeclarationType(outputs.single()) ?: return refuse(ExtractionRefusal.UnrenderableType)

			else -> null
		}.takeUnless { it == "Unit" }

	val body =
		when {
			isExpression -> ExtractedBody.ExpressionBody(needsReturn = returnTypeText != null)
			outputs.size == 1 -> ExtractedBody.StatementBody(trailingReturn = "return ${outputs.single().name.orEmpty()}")
			else -> ExtractedBody.StatementBody(trailingReturn = null)
		}

	val callSite =
		when {
			tailReturn -> CallSiteForm.Return
			outputs.size == 1 -> CallSiteForm.AssignOutput(outputs.single().name.orEmpty())
			else -> CallSiteForm.Call
		}

	val takenNames = takenNamesFor(enclosing)

	return SignatureResult.Success(
		ExtractMethodCandidate(
			label = collapseForLabel(fileText.substring(span.start, span.end)),
			span = span,
			suggestedName =
				if (isExpression) {
					suggestVariableName(first, renderedTypeOrNull(first), takenNames)
				} else {
					uniqueName(STATEMENT_RANGE_NAME, takenNames)
				},
			takenNames = takenNames,
			annotations = if (usesComposable(elements)) listOf("@Composable") else emptyList(),
			modifiers = if (usesSuspend(elements)) listOf("private", "suspend") else listOf("private"),
			receiverTypeText = (enclosing as? KtNamedFunction)?.receiverTypeReference?.text,
			parameters = parameters,
			returnTypeText = returnTypeText,
			body = body,
			callSite = callSite,
			insertOffset = enclosing.textRange.endOffset,
			insertIndent = leadingIndentAt(fileText, enclosing.textRange.startOffset),
		),
	)
}

private fun refuse(refusal: ExtractionRefusal): SignatureResult = SignatureResult.Refused(refusal)

/**
 * The named function, accessor, `init` block or constructor whose body holds [element]. Lambdas are
 * skipped: the new function is a sibling of the enclosing *named* declaration (R4), and the lambda's
 * captures become parameters.
 */
private fun enclosingDeclaration(element: PsiElement): KtDeclaration? {
	var current: PsiElement? = element.parent
	while (current != null) {
		when (current) {
			is KtNamedFunction, is KtPropertyAccessor, is KtAnonymousInitializer, is KtSecondaryConstructor ->
				return current as KtDeclaration

			is KtClassOrObject -> return null
		}
		current = current.parent
	}
	return null
}

/** Whether [element] is inside the region's span. */
private fun inRegion(
	element: PsiElement,
	span: TextSpan,
): Boolean = element.textRange.startOffset >= span.start && element.textRange.endOffset <= span.end

private fun simpleNamesIn(elements: List<KtExpression>): List<KtSimpleNameExpression> =
	elements.flatMap { PsiTreeUtil.collectElementsOfType(it, KtSimpleNameExpression::class.java) }

private fun <T : PsiElement> descendantsOf(
	elements: List<KtExpression>,
	type: Class<T>,
): List<T> = elements.flatMap { PsiTreeUtil.collectElementsOfType(it, type) }

/**
 * A captured declaration is one the region references whose PSI lies inside the enclosing
 * declaration but outside the region itself. Anything else -- a class member, a top-level
 * declaration, an import -- resolves unchanged from the new function's body (R5).
 *
 * Returns null when a type cannot be rendered as source, which declines the extraction rather than
 * emitting text that will not compile.
 */
private fun KaSession.capturedParameters(
	enclosing: KtDeclaration,
	elements: List<KtExpression>,
	span: TextSpan,
): List<MethodParameter>? {
	val parameters = mutableListOf<MethodParameter>()
	val seen = mutableSetOf<Any>()

	for (reference in simpleNamesIn(elements).sortedBy { it.textRange.startOffset }) {
		val symbol =
			runCatching { reference.mainReference?.resolveToSymbols()?.firstOrNull() }.getOrNull() as? KaCallableSymbol
				?: continue
		val declarationPsi = runCatching { symbol.psi }.getOrNull()

		val key: Any =
			when {
				declarationPsi != null -> {
					if (!PsiTreeUtil.isAncestor(enclosing, declarationPsi, true)) continue
					if (inRegion(declarationPsi, span)) continue
					declarationPsi
				}

				// `it` has no source PSI, so it would otherwise read as "not captured" and be dropped.
				symbol is KaValueParameterSymbol &&
					reference.getReferencedName() == StandardNames.IMPLICIT_LAMBDA_PARAMETER_NAME.asString() -> "it"

				else -> continue
			}
		if (!seen.add(key)) continue

		val typeText = renderedSymbolType(symbol) ?: return null
		parameters += MethodParameter(name = reference.getReferencedName(), typeText = typeText)
	}
	return parameters
}

/** A type that cannot be written out as source -- anonymous, intersection, or a resolution error. */
private fun isUnrenderable(text: String): Boolean =
	text.isBlank() ||
		text.contains("anonymous") ||
		text.contains("ERROR") ||
		text.contains(" & ")

private fun KaSession.renderedSymbolType(symbol: KaCallableSymbol): String? =
	runCatching { renderName(symbol.returnType) }.getOrNull()?.takeUnless(::isUnrenderable)

private fun KaSession.renderedTypeOrNull(expression: KtExpression): String? =
	runCatching { expression.expressionType?.let { renderName(it) } }.getOrNull()?.takeUnless(::isUnrenderable)

private fun KaSession.renderedDeclarationType(property: KtProperty): String? =
	runCatching { (property.symbol as? KaCallableSymbol)?.returnType?.let { renderName(it) } }
		.getOrNull()
		?.takeUnless(::isUnrenderable)

private fun KaSession.enclosingReturnType(enclosing: KtDeclaration): String? =
	runCatching { (enclosing.symbol as? KaCallableSymbol)?.returnType?.let { renderName(it) } }
		.getOrNull()
		?.takeUnless(::isUnrenderable)

/**
 * Locals declared inside the region and read after it (R7). Exactly one is supported.
 *
 * "Read after it" is a textual-offset test inside the enclosing declaration, which is sound because
 * a local is only in scope after its own declaration in the same block.
 */
private fun KaSession.outputsOf(
	enclosing: KtDeclaration,
	elements: List<KtExpression>,
	span: TextSpan,
): List<KtProperty> {
	val declared = descendantsOf(elements, KtProperty::class.java)
	if (declared.isEmpty()) return emptyList()

	val laterReads =
		PsiTreeUtil
			.collectElementsOfType(enclosing, KtSimpleNameExpression::class.java)
			.filter { it.textRange.startOffset >= span.end }
			.mapNotNull { runCatching { it.mainReference?.resolveToSymbols()?.firstOrNull()?.psi }.getOrNull() }
			.toSet()

	return declared.filter { it in laterReads }
}

/**
 * A `var` declared inside the enclosing declaration but outside the region, assigned inside it.
 * Kotlin has no `out` parameters, so the faithful emission would shadow a name (R7, ADR 0012).
 */
private fun KaSession.reassignedOuterVar(
	enclosing: KtDeclaration,
	elements: List<KtExpression>,
	span: TextSpan,
): String? {
	for (reference in simpleNamesIn(elements)) {
		if (!reference.isWriteTarget()) continue
		val symbol =
			runCatching { reference.mainReference?.resolveToSymbols()?.firstOrNull() }.getOrNull() as? KaVariableSymbol
				?: continue
		if (symbol.isVal) continue
		val declarationPsi = runCatching { symbol.psi }.getOrNull() ?: continue
		if (!PsiTreeUtil.isAncestor(enclosing, declarationPsi, true)) continue
		if (inRegion(declarationPsi, span)) continue
		return reference.getReferencedName()
	}
	return null
}

private fun KtSimpleNameExpression.isWriteTarget(): Boolean {
	val parent = parent
	if (parent is KtBinaryExpression && parent.left === this && parent.operationToken in ASSIGNMENT_TOKENS) return true
	if (parent is KtUnaryExpression && parent.operationToken in INCREMENT_TOKENS) return true
	return false
}

private val ASSIGNMENT_TOKENS =
	setOf(KtTokens.EQ, KtTokens.PLUSEQ, KtTokens.MINUSEQ, KtTokens.MULTEQ, KtTokens.DIVEQ, KtTokens.PERCEQ)

private val INCREMENT_TOKENS = setOf(KtTokens.PLUSPLUS, KtTokens.MINUSMINUS)

/**
 * The tail-return exception (R8): the region's last statement is a `return`, and it is the region's
 * only `return`, `break` or `continue`. Purely syntactic, which is why it is worth having.
 */
private fun isTailReturn(
	elements: List<KtExpression>,
	span: TextSpan,
): Boolean {
	if (elements.last() !is KtReturnExpression) return false
	val returns = descendantsOf(elements, KtReturnExpression::class.java)
	if (returns.size != 1 || returns.single() !== elements.last()) return false
	return !hasLoopExit(elements, span)
}

/** Any `return`, `break` or `continue` whose target lies outside the region (R8). */
private fun hasExit(
	elements: List<KtExpression>,
	span: TextSpan,
): Boolean {
	for (returnExpression in descendantsOf(elements, KtReturnExpression::class.java)) {
		// An unlabelled `return` always targets the enclosing named declaration, which is outside the
		// region by construction. A labelled one is fine only when its lambda is inside the region.
		if (returnExpression.getLabelName() == null) return true
		val lambda = PsiTreeUtil.getParentOfType(returnExpression, KtFunctionLiteral::class.java, true)
		if (lambda == null || !inRegion(lambda, span)) return true
	}
	return hasLoopExit(elements, span)
}

private fun hasLoopExit(
	elements: List<KtExpression>,
	span: TextSpan,
): Boolean {
	val jumps =
		descendantsOf(elements, KtBreakExpression::class.java) +
			descendantsOf(elements, KtContinueExpression::class.java)
	return jumps.any { jump ->
		val loop = PsiTreeUtil.getParentOfType(jump, KtLoopExpression::class.java, true)
		loop == null || !inRegion(loop, span)
	}
}

/**
 * The name of the enclosing function's type parameter the region uses, or null. A filtered copy of
 * the type-parameter list with its bounds is the alternative, and deciding "is `T` referenced" from
 * rendered type text is exactly the fragility that rules it out (R10).
 */
private fun typeParameterIn(
	enclosing: KtDeclaration,
	elements: List<KtExpression>,
): String? {
	val names = (enclosing as? KtNamedFunction)?.typeParameters?.mapNotNull { it.name }.orEmpty()
	if (names.isEmpty()) return null

	val typeTexts =
		descendantsOf(elements, KtTypeReference::class.java).map { it.text } +
			simpleNamesIn(elements).map { it.getReferencedName() }
	return names.firstOrNull { name -> typeTexts.any { it == name || it.containsWord(name) } }
}

/** Whole-word containment, so `T` does not match `Type`. */
private fun String.containsWord(word: String): Boolean =
	Regex("(^|[^A-Za-z0-9_])" + Regex.escape(word) + "($|[^A-Za-z0-9_])").containsMatchIn(this)

/**
 * The scoping construct whose implicit receiver the region uses unqualified, or null (R9).
 *
 * Turning that receiver into a parameter would mean qualifying every unqualified member access
 * inside the extracted body -- editing the interior of the moved code, which this refactoring does
 * not do. Android code leans on `with`/`apply` heavily, so the message names the construct.
 */
private fun KaSession.innerImplicitReceiver(
	enclosing: KtDeclaration,
	elements: List<KtExpression>,
	span: TextSpan,
): String? {
	val construct = enclosingScopingCall(elements.first(), enclosing) ?: return null
	val enclosingClass = PsiTreeUtil.getParentOfType(enclosing, KtClassOrObject::class.java, true)

	for (reference in simpleNamesIn(elements)) {
		val parent = reference.parent
		if (parent is KtQualifiedExpression && parent.selectorExpression === reference) continue
		if (parent is KtCallExpression && parent.calleeExpression !== reference) continue

		val symbol =
			runCatching { reference.mainReference?.resolveToSymbols()?.firstOrNull() }.getOrNull() as? KaCallableSymbol
				?: continue
		val declarationPsi = runCatching { symbol.psi }.getOrNull() ?: continue

		// A local or a member of the class the new function joins needs nothing.
		if (PsiTreeUtil.isAncestor(enclosing, declarationPsi, true)) continue
		if (enclosingClass != null && PsiTreeUtil.isAncestor(enclosingClass, declarationPsi, true)) continue
		// A top-level declaration resolves unchanged from anywhere in the file.
		if (declarationPsi.parent is KtFile) continue
		// Anything else reached without a qualifier came in through the scoping receiver.
		if (inRegion(declarationPsi, span)) continue
		return construct
	}
	return null
}

/** The callee name of the nearest receiver-binding scoping call between [element] and [enclosing]. */
private fun enclosingScopingCall(
	element: PsiElement,
	enclosing: KtDeclaration,
): String? {
	var current: PsiElement? = element
	while (current != null && current !== enclosing) {
		if (current is KtFunctionLiteral) {
			val call = PsiTreeUtil.getParentOfType(current, KtCallExpression::class.java, true)
			val callee = (call?.calleeExpression as? KtNameReferenceExpression)?.getReferencedName()
			if (callee != null && callee in RECEIVER_SCOPING_FUNCTIONS) return callee
		}
		current = current.parent
	}
	return null
}

/** `suspend` is added when the region calls one, or touches `coroutineContext` (R10). */
private fun KaSession.usesSuspend(elements: List<KtExpression>): Boolean {
	if (simpleNamesIn(elements).any { it.getReferencedName() == "coroutineContext" }) return true
	return descendantsOf(elements, KtCallExpression::class.java).any { call ->
		runCatching {
			(call.resolveToCall()?.successfulFunctionCallOrNull()?.symbol as? KaNamedFunctionSymbol)?.isSuspend
		}.getOrNull() == true
	}
}

/**
 * `@Composable` is added when the region calls one. Not polish: CoGo users write Compose apps on the
 * device, and an extracted composable without the annotation does not compile (R10).
 */
private fun KaSession.usesComposable(elements: List<KtExpression>): Boolean =
	descendantsOf(elements, KtCallExpression::class.java).any { call ->
		runCatching {
			call
				.resolveToCall()
				?.successfulFunctionCallOrNull()
				?.symbol
				?.annotations
				?.any { it.classId?.asFqNameString() == COMPOSABLE_FQ_NAME }
		}.getOrNull() == true
	}

/**
 * Names the new function must avoid (R12).
 *
 * For a class target this is the whole member scope, **including inherited members**: a private
 * function accidentally matching a supertype member is an accidental-override compile error.
 * Rejecting any name match rather than only a signature match also means the refactoring never
 * creates an overload the user did not ask for.
 */
private fun KaSession.takenNamesFor(enclosing: KtDeclaration): Set<String> {
	val containingClass = PsiTreeUtil.getParentOfType(enclosing, KtClassOrObject::class.java, true)
	if (containingClass != null) {
		val fromScope =
			runCatching {
				(containingClass.symbol as? KaClassSymbol)
					?.memberScope
					?.callables
					?.mapNotNull { (it as? KaNamedSymbol)?.name?.asString() }
					?.toSet()
			}.getOrNull().orEmpty()
		val declared = containingClass.declarations.mapNotNull { it.name }
		return fromScope + declared
	}

	// A local `fun` target: the enclosing block's own declarations. Otherwise the file's top level.
	val block = enclosing.parent
	if (block is KtBlockExpression) {
		return PsiTreeUtil
			.collectElementsOfType(block, KtDeclaration::class.java)
			.mapNotNull { it.name }
			.toSet()
	}
	return enclosing.containingKtFile.declarations.mapNotNull { it.name }.toSet()
}
```

- [ ] **Step 5: Write `ExtractMethodPlanner.kt`**

```kotlin
package com.itsaky.androidide.lsp.kotlin.utils.refactor

import com.itsaky.androidide.lsp.kotlin.compiler.AbstractCompilationEnvironment
import com.itsaky.androidide.lsp.kotlin.compiler.modules.AnalysisPriority
import com.itsaky.androidide.lsp.kotlin.compiler.modules.ScheduledCancelChecker
import com.itsaky.androidide.lsp.kotlin.compiler.modules.analyzeMaybeDangling
import com.itsaky.androidide.lsp.kotlin.compiler.read
import org.slf4j.LoggerFactory
import java.nio.file.Path

private val logger = LoggerFactory.getLogger("ExtractMethodPlanner")

/**
 * Computes the whole [ExtractMethodPlan] in one background analysis pass.
 *
 * The current `KtFile` is fetched *before* entering [read] -- blocking on `getCurrentKtFile(...).get()`
 * inside `project.read` deadlocks.
 *
 * Anything thrown in this pipeline degrades to a refusal plus a log line: the action framework
 * catches only `IllegalArgumentException` and this runs on a scope with no exception handler, so an
 * uncaught throw would crash the app (R16).
 */
internal fun buildExtractMethodPlan(
	env: AbstractCompilationEnvironment,
	nioPath: Path,
	selectionStart: Int,
	selectionEnd: Int,
	documentVersion: Int,
	cancelChecker: ScheduledCancelChecker,
): ExtractMethodPlan =
	runCatching {
		val ktFile =
			env.ktSymbolIndex.getCurrentKtFile(nioPath).get()
				?: return ExtractMethodPlan.refused(ExtractionRefusal.NotASingleRegion)

		env.project.read {
			val fileText = ktFile.text
			val region =
				resolveExtractionRegion(ktFile, selectionStart, selectionEnd)
					?: return@read ExtractMethodPlan.refused(ExtractionRefusal.NotASingleRegion, fileText, documentVersion)

			analyzeMaybeDangling(ktFile, AnalysisPriority.INTERACTIVE, cancelChecker) {
				val results =
					when (region) {
						is ExtractionRegion.Expressions ->
							region.candidates.map { buildCandidate(listOf(it), isExpression = true, fileText = fileText) }

						is ExtractionRegion.Statements ->
							listOf(buildCandidate(region.statements, isExpression = false, fileText = fileText))
					}

				val candidates = results.filterIsInstance<SignatureResult.Success>().map { it.candidate }
				if (candidates.isEmpty()) {
					// The innermost region is the one the user pointed at, so its reason is the one to show.
					val refusal =
						results.filterIsInstance<SignatureResult.Refused>().firstOrNull()?.refusal
							?: ExtractionRefusal.NotASingleRegion
					return@analyzeMaybeDangling ExtractMethodPlan.refused(refusal, fileText, documentVersion)
				}

				ExtractMethodPlan(
					fileText = fileText,
					documentVersion = documentVersion,
					candidates = candidates,
					// Only meaningful while the innermost candidate survived: otherwise the selection no
					// longer corresponds to the first option shown.
					selectionMatchedCandidate =
						region is ExtractionRegion.Expressions &&
							region.selectionMatchedInnermost &&
							candidates.first().span == region.span,
					refusal = null,
				)
			}
		}
	}.getOrElse { error ->
		logger.warn("Failed to build extract-method plan for {}", nioPath, error)
		ExtractMethodPlan.refused(ExtractionRefusal.NotASingleRegion)
	}
```

- [ ] **Step 6: Compile before running the tests**

Run: `flox activate -d flox/local -- ./gradlew :lsp:kotlin:compileV7DebugKotlin`
Expected: BUILD SUCCESSFUL. Analysis API symbol names drift between Kotlin versions; if `annotations`, `memberScope`, `isSuspend` or `symbol` do not resolve, find the equivalent by grepping `lsp/kotlin/src/main/java/com/itsaky/androidide/lsp/kotlin/utils/AbstractMemberStubs.kt` and `completion/KotlinCompletions.kt`, which already use them. Do not add a dependency.

- [ ] **Step 7: Run the test to verify it passes**

Run: `flox activate -d flox/local -- ./gradlew :lsp:kotlin:testV7DebugUnitTest --tests "com.itsaky.androidide.lsp.kotlin.utils.refactor.ExtractMethodPlanEndToEndTest"`
Expected: PASS, 16 tests.

If a refusal test reports the wrong reason, check the ordering in `buildCandidate` -- the checks are ordered deliberately and a case can be caught by an earlier one. If the `@Composable` test cannot resolve the annotation, confirm the second `createSourceFile` call registers the file with the symbol index (`KtLspTest.createSourceFile` does this itself).

- [ ] **Step 8: Run the whole module's tests, then format and commit**

Run: `flox activate -d flox/local -- ./gradlew :lsp:kotlin:testV7DebugUnitTest`
Expected: PASS.

```bash
flox activate -d flox/local -- ./gradlew spotlessApply
git add lsp/kotlin/src/main/java/com/itsaky/androidide/lsp/kotlin/utils/refactor/MethodSignature.kt \
        lsp/kotlin/src/main/java/com/itsaky/androidide/lsp/kotlin/utils/refactor/ExtractMethodPlanner.kt \
        lsp/kotlin/src/main/java/com/itsaky/androidide/lsp/kotlin/utils/refactor/NameSuggestion.kt \
        lsp/kotlin/src/test/java/com/itsaky/androidide/lsp/kotlin/utils/refactor/ExtractMethodPlanEndToEndTest.kt
git commit -m "ADFA-5080: Derive the extracted signature, or a typed refusal"
```

---

## Task 5: Strings, shared sheet components, and the ViewModel

**Files:**
- Modify: `resources/src/main/res/values/strings.xml` (after the extract-variable block, currently ending at `msg_extract_variable_file_changed`)
- Create: `lsp/kotlin/src/main/java/com/itsaky/androidide/lsp/kotlin/refactor/ui/SheetComponents.kt`
- Modify: `lsp/kotlin/src/main/java/com/itsaky/androidide/lsp/kotlin/refactor/ui/ExtractVariableSheetContent.kt` (delete lines 138-200: `LabelledSection`, `OptionList`, `messageRes`)
- Create: `lsp/kotlin/src/main/java/com/itsaky/androidide/lsp/kotlin/refactor/ui/ExtractMethodUiState.kt`
- Create: `lsp/kotlin/src/main/java/com/itsaky/androidide/lsp/kotlin/refactor/ui/ExtractMethodViewModel.kt`
- Test: `lsp/kotlin/src/test/java/com/itsaky/androidide/lsp/kotlin/refactor/ui/ExtractMethodViewModelTest.kt`

**Interfaces:**
- Consumes: `ExtractMethodPlan`, `ExtractMethodCandidate`, `signatureText`, `validateVariableName`, `NameProblem`.
- Produces:
  - `internal @Composable fun LabelledSection(label: String, content: @Composable () -> Unit)`
  - `internal @Composable fun OptionList(options: List<String>, selected: Int, monospace: Boolean, onSelect: (Int) -> Unit)`
  - `internal fun NameProblem.messageRes(): Int`
  - `data class ExtractMethodUiState(candidateLabels, selectedCandidate, showCandidatePicker, name, nameProblem, signaturePreview)` with `canConfirm`
  - `sealed interface ExtractMethodUiEvent` with `CandidateSelected(index)`, `NameChanged(name)`, `Confirmed`, `Dismissed`
  - `data class ExtractMethodChoice(val candidate: ExtractMethodCandidate, val name: String)`
  - `class ExtractMethodViewModel(plan: ExtractMethodPlan)` with `uiState: StateFlow<ExtractMethodUiState>`, `onEvent(event)`, `choice(): ExtractMethodChoice?`, `companion object { fun factory(plan): ViewModelProvider.Factory }`

- [ ] **Step 1: Add the strings**

In `resources/src/main/res/values/strings.xml`, immediately after the line
`<string name="msg_extract_variable_file_changed">The file changed. Try extracting again.</string>`
insert:

```xml

	<!-- Extract method refactoring (Kotlin) -->
	<string name="action_extract_method">Extract method</string>
	<string name="title_extract_method">Extract method</string>
	<string name="label_extract_method_signature">Signature</string>
	<string name="msg_extract_method_file_changed">The file changed. Try extracting again.</string>
	<string name="msg_extract_method_not_single_region">Select an expression, or whole statements inside one block</string>
	<string name="msg_extract_method_multiple_outputs">The selection produces more than one value: %1$s</string>
	<string name="msg_extract_method_reassigns_outer_var">The selection assigns to %1$s, which is declared outside it</string>
	<string name="msg_extract_method_exits_region">The selection jumps out of itself with return, break or continue</string>
	<string name="msg_extract_method_inner_implicit_receiver">The selection uses members of the enclosing %1$s receiver</string>
	<string name="msg_extract_method_uses_type_parameter">The selection uses type parameter %1$s</string>
	<string name="msg_extract_method_unrenderable_type">A type in the selection cannot be written out</string>
```

Reuse the existing `action_extract`, `label_extract_variable_expression`, `label_extract_variable_name` and the four `msg_extract_variable_name_*` messages -- R12 keeps name validation identical, so no new error strings.

- [ ] **Step 2: Promote the shared sheet components**

Create `SheetComponents.kt` with the three declarations moved **verbatim** from `ExtractVariableSheetContent.kt` (lines 138-200), changing `private` to `internal`:

```kotlin
package com.itsaky.androidide.lsp.kotlin.refactor.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.itsaky.androidide.lsp.kotlin.utils.refactor.NameProblem
import com.itsaky.androidide.resources.R

/** Shared by the extract-variable and extract-method sheets; neither owns them. */
@Composable
internal fun LabelledSection(
	label: String,
	content: @Composable () -> Unit,
) {
	Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
		Text(text = label, style = MaterialTheme.typography.labelLarge)
		content()
	}
}

/** A radio group. Expression text is monospaced so a candidate reads as the code it is. */
@Composable
internal fun OptionList(
	options: List<String>,
	selected: Int,
	monospace: Boolean,
	onSelect: (Int) -> Unit,
) {
	Column(
		modifier = Modifier.selectableGroup(),
		verticalArrangement = Arrangement.spacedBy(8.dp),
	) {
		options.forEachIndexed { index, option ->
			Row(
				verticalAlignment = Alignment.CenterVertically,
				modifier =
					Modifier
						.fillMaxWidth()
						.selectable(
							selected = index == selected,
							role = Role.RadioButton,
							onClick = { onSelect(index) },
						),
			) {
				RadioButton(
					selected = index == selected,
					onClick = null,
				)

				Text(
					text = option,
					style =
						if (monospace) {
							MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace)
						} else {
							MaterialTheme.typography.bodyMedium
						},
					modifier = Modifier.padding(start = 8.dp),
				)
			}
		}
	}
}

/** The message shown under a name field for each way a name can be unusable. */
internal fun NameProblem.messageRes(): Int =
	when (this) {
		NameProblem.Blank -> R.string.msg_extract_variable_name_blank
		NameProblem.NotAnIdentifier -> R.string.msg_extract_variable_name_invalid
		NameProblem.Keyword -> R.string.msg_extract_variable_name_keyword
		NameProblem.AlreadyTaken -> R.string.msg_extract_variable_name_taken
	}
```

Then delete those three declarations from `ExtractVariableSheetContent.kt` and remove the imports they alone used (`selectable`, `selectableGroup`, `RadioButton`, `FontFamily`, `Role` stays -- it is used by the replace-all `toggleable`). Let the compiler tell you which imports are now unused.

- [ ] **Step 3: Write the failing ViewModel test**

Create `ExtractMethodViewModelTest.kt`:

```kotlin
package com.itsaky.androidide.lsp.kotlin.refactor.ui

import com.itsaky.androidide.lsp.kotlin.utils.refactor.CallSiteForm
import com.itsaky.androidide.lsp.kotlin.utils.refactor.ExtractMethodCandidate
import com.itsaky.androidide.lsp.kotlin.utils.refactor.ExtractMethodPlan
import com.itsaky.androidide.lsp.kotlin.utils.refactor.ExtractedBody
import com.itsaky.androidide.lsp.kotlin.utils.refactor.MethodParameter
import com.itsaky.androidide.lsp.kotlin.utils.refactor.NameProblem
import com.itsaky.androidide.lsp.kotlin.utils.refactor.TextSpan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The sheet's derivation logic, tested without Compose, a fragment or an activity. */
class ExtractMethodViewModelTest {
	private fun candidate(
		label: String,
		suggestedName: String,
		parameters: List<MethodParameter> = listOf(MethodParameter("a", "Int")),
		returnTypeText: String? = "Int",
		modifiers: List<String> = listOf("private"),
		takenNames: Set<String> = emptySet(),
	) = ExtractMethodCandidate(
		label = label,
		span = TextSpan(0, 5),
		suggestedName = suggestedName,
		takenNames = takenNames,
		annotations = emptyList(),
		modifiers = modifiers,
		receiverTypeText = null,
		parameters = parameters,
		returnTypeText = returnTypeText,
		body = ExtractedBody.ExpressionBody(needsReturn = true),
		callSite = CallSiteForm.Call,
		insertOffset = 100,
		insertIndent = "\t",
	)

	private fun plan(
		candidates: List<ExtractMethodCandidate>,
		selectionMatched: Boolean = false,
	) = ExtractMethodPlan(
		fileText = "unused",
		documentVersion = 1,
		candidates = candidates,
		selectionMatchedCandidate = selectionMatched,
		refusal = null,
	)

	@Test
	fun `the initial state takes the first candidate's suggestion`() {
		val model = ExtractMethodViewModel(plan(listOf(candidate("a + b", "total"))))

		assertEquals("total", model.uiState.value.name)
		assertEquals(0, model.uiState.value.selectedCandidate)
		assertNull(model.uiState.value.nameProblem)
	}

	@Test
	fun `the chooser is hidden for one candidate and for an exact selection match`() {
		val single = ExtractMethodViewModel(plan(listOf(candidate("a + b", "total"))))
		assertFalse(single.uiState.value.showCandidatePicker)

		val many = listOf(candidate("a + b", "total"), candidate("a + b + c", "total1"))
		assertTrue(ExtractMethodViewModel(plan(many)).uiState.value.showCandidatePicker)
		assertFalse(ExtractMethodViewModel(plan(many, selectionMatched = true)).uiState.value.showCandidatePicker)
	}

	@Test
	fun `the preview is the signature as it will be emitted`() {
		val model =
			ExtractMethodViewModel(
				plan(
					listOf(
						candidate(
							"load() + 1",
							"total",
							parameters = listOf(MethodParameter("id", "String")),
							returnTypeText = "User",
							modifiers = listOf("private", "suspend"),
						),
					),
				),
			)

		assertEquals("private suspend fun total(id: String): User", model.uiState.value.signaturePreview)

		model.onEvent(ExtractMethodUiEvent.NameChanged("loadUser"))

		assertEquals("private suspend fun loadUser(id: String): User", model.uiState.value.signaturePreview)
	}

	@Test
	fun `a name matching an inherited member is rejected`() {
		val model =
			ExtractMethodViewModel(plan(listOf(candidate("a + b", "total", takenNames = setOf("helper")))))

		model.onEvent(ExtractMethodUiEvent.NameChanged("helper"))

		assertEquals(NameProblem.AlreadyTaken, model.uiState.value.nameProblem)
		assertFalse(model.uiState.value.canConfirm)
		assertNull(model.choice())
	}

	@Test
	fun `switching candidate re-suggests the name`() {
		val model =
			ExtractMethodViewModel(
				plan(listOf(candidate("a + b", "total"), candidate("a + b + c", "sum"))),
			)
		model.onEvent(ExtractMethodUiEvent.NameChanged("mine"))

		model.onEvent(ExtractMethodUiEvent.CandidateSelected(1))

		assertEquals("sum", model.uiState.value.name)
		assertEquals(1, model.uiState.value.selectedCandidate)
	}

	@Test
	fun `the choice carries the selected candidate and the typed name`() {
		val model =
			ExtractMethodViewModel(
				plan(listOf(candidate("a + b", "total"), candidate("a + b + c", "sum"))),
			)
		model.onEvent(ExtractMethodUiEvent.CandidateSelected(1))
		model.onEvent(ExtractMethodUiEvent.NameChanged("combined"))

		val choice = model.choice()

		assertNotNull(choice)
		assertEquals("a + b + c", choice!!.candidate.label)
		assertEquals("combined", choice.name)
	}

	@Test
	fun `a blank name blocks confirmation`() {
		val model = ExtractMethodViewModel(plan(listOf(candidate("a + b", "total"))))

		model.onEvent(ExtractMethodUiEvent.NameChanged(""))

		assertEquals(NameProblem.Blank, model.uiState.value.nameProblem)
		assertNull(model.choice())
	}
}
```

- [ ] **Step 4: Run the test to verify it fails**

Run: `flox activate -d flox/local -- ./gradlew :lsp:kotlin:testV7DebugUnitTest --tests "com.itsaky.androidide.lsp.kotlin.refactor.ui.ExtractMethodViewModelTest"`
Expected: compilation failure -- `Unresolved reference: ExtractMethodViewModel`.

- [ ] **Step 5: Write the state and the ViewModel**

Create `ExtractMethodUiState.kt`:

```kotlin
package com.itsaky.androidide.lsp.kotlin.refactor.ui

import com.itsaky.androidide.lsp.kotlin.utils.refactor.ExtractMethodCandidate
import com.itsaky.androidide.lsp.kotlin.utils.refactor.NameProblem

/**
 * Everything the extract-method sheet renders.
 *
 * There is no scope chooser (the new function is always a sibling of the enclosing declaration) and
 * no replace-all checkbox (the region is the only site rewritten), so the sheet is a chooser, a name
 * field and a preview.
 *
 * [signaturePreview] is the signature exactly as it will be emitted -- the one derived artefact, and
 * the one place the derivation can surprise the user. The body is the code they selected and can see
 * behind the sheet, so previewing it says nothing new.
 */
data class ExtractMethodUiState(
	val candidateLabels: List<String>,
	val selectedCandidate: Int,
	val showCandidatePicker: Boolean,
	val name: String,
	val nameProblem: NameProblem?,
	val signaturePreview: String,
) {
	val canConfirm: Boolean get() = nameProblem == null
}

/** What the sheet reports back up; the ViewModel never touches the document itself. */
sealed interface ExtractMethodUiEvent {
	data class CandidateSelected(
		val index: Int,
	) : ExtractMethodUiEvent

	data class NameChanged(
		val name: String,
	) : ExtractMethodUiEvent

	data object Confirmed : ExtractMethodUiEvent

	data object Dismissed : ExtractMethodUiEvent
}

/**
 * The user's finished decision, handed to the action to turn into edits. Free of offsets and text so
 * the sheet stays a pure chooser.
 */
data class ExtractMethodChoice(
	val candidate: ExtractMethodCandidate,
	val name: String,
)
```

Create `ExtractMethodViewModel.kt`:

```kotlin
package com.itsaky.androidide.lsp.kotlin.refactor.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.itsaky.androidide.lsp.kotlin.utils.refactor.ExtractMethodPlan
import com.itsaky.androidide.lsp.kotlin.utils.refactor.signatureText
import com.itsaky.androidide.lsp.kotlin.utils.refactor.validateVariableName
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Derives the sheet's state from an [ExtractMethodPlan] and nothing else -- no analysis, no PSI, no
 * I/O -- which is what lets it hold all the sheet's logic and still be a plain unit test.
 *
 * A plain [ViewModelProvider.Factory] rather than a Koin definition, for the same reason as
 * `ExtractVariableViewModel`: sheet-scoped, injects nothing, takes the plan as a runtime argument.
 */
class ExtractMethodViewModel(
	private val plan: ExtractMethodPlan,
) : ViewModel() {
	private val _uiState = MutableStateFlow(stateFor(candidateIndex = 0, name = null))
	val uiState: StateFlow<ExtractMethodUiState> = _uiState.asStateFlow()

	fun onEvent(event: ExtractMethodUiEvent) {
		val current = _uiState.value
		when (event) {
			is ExtractMethodUiEvent.CandidateSelected -> {
				if (event.index == current.selectedCandidate) return
				// A different expression means a different signature and suggested name, so the name is
				// re-suggested rather than carried over -- the old one described the old expression.
				_uiState.value = stateFor(event.index, name = null)
			}

			is ExtractMethodUiEvent.NameChanged -> {
				_uiState.value = stateFor(current.selectedCandidate, name = event.name)
			}

			ExtractMethodUiEvent.Confirmed, ExtractMethodUiEvent.Dismissed -> Unit
		}
	}

	/** The user's decision, or null when the name is unusable. */
	fun choice(): ExtractMethodChoice? {
		val state = _uiState.value
		if (!state.canConfirm) return null
		return ExtractMethodChoice(candidate(state.selectedCandidate), state.name)
	}

	private fun candidate(index: Int) = plan.candidates[index.coerceIn(plan.candidates.indices)]

	private fun stateFor(
		candidateIndex: Int,
		name: String?,
	): ExtractMethodUiState {
		val bounded = candidateIndex.coerceIn(plan.candidates.indices)
		val candidate = candidate(bounded)
		val resolvedName = name ?: candidate.suggestedName

		return ExtractMethodUiState(
			candidateLabels = plan.candidates.map { it.label },
			selectedCandidate = bounded,
			showCandidatePicker = plan.candidates.size > 1 && !plan.selectionMatchedCandidate,
			name = resolvedName,
			nameProblem = validateVariableName(resolvedName, candidate.takenNames),
			// The same call the edit builder makes, so the preview cannot drift from the declaration.
			signaturePreview = candidate.signatureText(resolvedName),
		)
	}

	companion object {
		fun factory(plan: ExtractMethodPlan): ViewModelProvider.Factory =
			object : ViewModelProvider.Factory {
				@Suppress("UNCHECKED_CAST")
				override fun <T : ViewModel> create(modelClass: Class<T>): T = ExtractMethodViewModel(plan) as T
			}
	}
}
```

- [ ] **Step 6: Run the tests to verify they pass**

Run: `flox activate -d flox/local -- ./gradlew :lsp:kotlin:testV7DebugUnitTest`
Expected: PASS, including the untouched `ExtractVariableViewModelTest` -- the component promotion must not have changed extract-variable behaviour.

- [ ] **Step 7: Format and commit**

```bash
flox activate -d flox/local -- ./gradlew spotlessApply
git add resources/src/main/res/values/strings.xml \
        lsp/kotlin/src/main/java/com/itsaky/androidide/lsp/kotlin/refactor/ui/SheetComponents.kt \
        lsp/kotlin/src/main/java/com/itsaky/androidide/lsp/kotlin/refactor/ui/ExtractVariableSheetContent.kt \
        lsp/kotlin/src/main/java/com/itsaky/androidide/lsp/kotlin/refactor/ui/ExtractMethodUiState.kt \
        lsp/kotlin/src/main/java/com/itsaky/androidide/lsp/kotlin/refactor/ui/ExtractMethodViewModel.kt \
        lsp/kotlin/src/test/java/com/itsaky/androidide/lsp/kotlin/refactor/ui/ExtractMethodViewModelTest.kt
git commit -m "ADFA-5080: Add the extract-method sheet state and strings"
```

---

## Task 6: The sheet

Compose UI is not unit-testable in this module (`lsp/kotlin` has no `androidTest` source set and none is added). Verification is a compile plus the on-device QA in Task 7.

**Files:**
- Create: `lsp/kotlin/src/main/java/com/itsaky/androidide/lsp/kotlin/refactor/ui/ExtractMethodSheetContent.kt`
- Create: `lsp/kotlin/src/main/java/com/itsaky/androidide/lsp/kotlin/refactor/ui/ExtractMethodSheet.kt`

**Interfaces:**
- Consumes: `ExtractMethodUiState`, `ExtractMethodUiEvent`, `ExtractMethodChoice`, `ExtractMethodViewModel`, `LabelledSection`, `OptionList`, `messageRes()`, `IdeTheme`, `findFragmentActivity` (already in `ExtractVariableSheet.kt`).
- Produces: `fun ExtractMethodSheet.Companion.show(activity: FragmentActivity, plan: ExtractMethodPlan, onChoice: (ExtractMethodChoice) -> Unit): Boolean`.

- [ ] **Step 1: Write the content**

Create `ExtractMethodSheetContent.kt`:

```kotlin
package com.itsaky.androidide.lsp.kotlin.refactor.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.itsaky.androidide.resources.R

/**
 * The extract-method sheet: the expression chooser (when there is a choice), the name, and the
 * signature exactly as it will be emitted.
 *
 * A sibling of the extract-variable sheet rather than a generalisation of it: a single shared sheet
 * would need a state class where half the fields are meaningless to either caller (ADR 0011).
 *
 * Stateless: all state arrives in [state] and every interaction leaves as an [ExtractMethodUiEvent].
 */
@Composable
fun ExtractMethodSheetContent(
	state: ExtractMethodUiState,
	onEvent: (ExtractMethodUiEvent) -> Unit,
	modifier: Modifier = Modifier,
) {
	Column(
		modifier =
			modifier
				.fillMaxWidth()
				.navigationBarsPadding()
				.padding(horizontal = 24.dp, vertical = 16.dp),
		verticalArrangement = Arrangement.spacedBy(16.dp),
	) {
		Text(
			text = stringResource(R.string.title_extract_method),
			style = MaterialTheme.typography.titleLarge,
		)

		if (state.showCandidatePicker) {
			LabelledSection(stringResource(R.string.label_extract_variable_expression)) {
				OptionList(
					options = state.candidateLabels,
					selected = state.selectedCandidate,
					monospace = true,
					onSelect = { onEvent(ExtractMethodUiEvent.CandidateSelected(it)) },
				)
			}
		}

		OutlinedTextField(
			value = state.name,
			onValueChange = { onEvent(ExtractMethodUiEvent.NameChanged(it)) },
			label = { Text(stringResource(R.string.label_extract_variable_name)) },
			isError = state.nameProblem != null,
			singleLine = true,
			supportingText = state.nameProblem?.let { problem -> { Text(stringResource(problem.messageRes())) } },
			modifier = Modifier.fillMaxWidth(),
		)

		LabelledSection(stringResource(R.string.label_extract_method_signature)) {
			Text(
				text = state.signaturePreview,
				style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
				modifier = Modifier.fillMaxWidth(),
			)
		}

		Row(
			modifier = Modifier.fillMaxWidth(),
			horizontalArrangement = Arrangement.End,
		) {
			TextButton(onClick = { onEvent(ExtractMethodUiEvent.Dismissed) }) {
				Text(stringResource(android.R.string.cancel))
			}
			Button(
				onClick = { onEvent(ExtractMethodUiEvent.Confirmed) },
				enabled = state.canConfirm,
				modifier = Modifier.padding(start = 8.dp),
			) {
				Text(stringResource(R.string.action_extract))
			}
		}
	}
}
```

Note: the preview **wraps rather than truncating** (R11), which a plain `Text` with no `maxLines` does by default. Do not make it horizontally scrollable.

- [ ] **Step 2: Write the sheet**

Create `ExtractMethodSheet.kt`:

```kotlin
package com.itsaky.androidide.lsp.kotlin.refactor.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.viewModels
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.itsaky.androidide.common.compose.IdeTheme
import com.itsaky.androidide.lsp.kotlin.utils.refactor.ExtractMethodPlan

/**
 * Hosts [ExtractMethodSheetContent].
 *
 * The plan is handed in directly rather than through fragment arguments: it carries the file's text
 * and offset spans, which is neither `Parcelable` nor meaningful to restore -- after process death
 * the document may be entirely different. So [plan] is null on a recreated instance and the sheet
 * dismisses itself, the same outcome the action's document-version guard would reach anyway.
 */
class ExtractMethodSheet : BottomSheetDialogFragment() {
	private var plan: ExtractMethodPlan? = null
	private var onChoice: ((ExtractMethodChoice) -> Unit)? = null

	private val viewModel: ExtractMethodViewModel by viewModels {
		ExtractMethodViewModel.factory(requireNotNull(plan) { "sheet shown without a plan" })
	}

	override fun onCreateView(
		inflater: LayoutInflater,
		container: ViewGroup?,
		savedInstanceState: Bundle?,
	): View? {
		if (plan == null) {
			dismissAllowingStateLoss()
			return null
		}

		return ComposeView(requireContext()).apply {
			setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
			setContent {
				IdeTheme {
					val state by viewModel.uiState.collectAsStateWithLifecycle()
					ExtractMethodSheetContent(
						state = state,
						onEvent = ::handleEvent,
					)
				}
			}
		}
	}

	private fun handleEvent(event: ExtractMethodUiEvent) {
		when (event) {
			ExtractMethodUiEvent.Confirmed -> {
				viewModel.choice()?.let { choice -> onChoice?.invoke(choice) }
				dismiss()
			}

			ExtractMethodUiEvent.Dismissed -> dismiss()

			else -> viewModel.onEvent(event)
		}
	}

	companion object {
		private const val TAG = "extract_method_sheet"

		/**
		 * Shows the sheet on [activity], calling [onChoice] once if the user confirms. Returns false
		 * when it could not be shown, so the caller can report a failure rather than doing nothing.
		 */
		fun show(
			activity: FragmentActivity,
			plan: ExtractMethodPlan,
			onChoice: (ExtractMethodChoice) -> Unit,
		): Boolean {
			val manager = activity.supportFragmentManager
			if (manager.isStateSaved || manager.isDestroyed) return false
			ExtractMethodSheet()
				.apply {
					this.plan = plan
					this.onChoice = onChoice
				}.show(manager, TAG)
			return true
		}
	}
}
```

- [ ] **Step 3: Compile and run the tests**

Run: `flox activate -d flox/local -- ./gradlew :lsp:kotlin:compileV7DebugKotlin :lsp:kotlin:testV7DebugUnitTest`
Expected: BUILD SUCCESSFUL, tests PASS.

- [ ] **Step 4: Format and commit**

```bash
flox activate -d flox/local -- ./gradlew spotlessApply
git add lsp/kotlin/src/main/java/com/itsaky/androidide/lsp/kotlin/refactor/ui/ExtractMethodSheetContent.kt \
        lsp/kotlin/src/main/java/com/itsaky/androidide/lsp/kotlin/refactor/ui/ExtractMethodSheet.kt
git commit -m "ADFA-5080: Add the extract-method Compose sheet"
```

---

## Task 7: Wire up the code action

**Files:**
- Create: `lsp/kotlin/src/main/java/com/itsaky/androidide/lsp/kotlin/actions/ExtractMethodAction.kt`
- Modify: `idetooltips/src/main/java/com/itsaky/androidide/idetooltips/TooltipTag.kt` (next to `EDITOR_CODE_ACTIONS_KT_EXTRACT_VARIABLE`, around line 92)
- Modify: `lsp/kotlin/src/main/java/com/itsaky/androidide/lsp/kotlin/KotlinCodeActionsMenu.kt`
- Modify: `lsp/kotlin/src/test/java/com/itsaky/androidide/lsp/kotlin/KotlinCodeActionTooltipTagTest.kt`
- Modify: `docs/features/kotlin-extract-method.md` (the `Status:` line, line 4)

**Interfaces:**
- Consumes: `buildExtractMethodPlan`, `ExtractMethodPlan`, `ExtractionRefusal`, `buildExtractMethodRewrites`, `toTextEdit`, `ExtractMethodSheet.show`, `ExtractMethodChoice`, `findFragmentActivity`.
- Produces: `class ExtractMethodAction : BaseKotlinCodeAction()` with `companion object { const val ID = "ide.editor.lsp.kt.extractMethod" }`.

- [ ] **Step 1: Add the tooltip tag**

In `TooltipTag.kt`, directly below the extract-variable constant:

```kotlin
	const val EDITOR_CODE_ACTIONS_KT_EXTRACT_METHOD = "editor.codeactions.kotlin.extractmethod"
```

- [ ] **Step 2: Write the failing tooltip-tag test change**

In `KotlinCodeActionTooltipTagTest.kt`, add the import `com.itsaky.androidide.lsp.kotlin.actions.ExtractMethodAction` and add the row to the `expected` map, next to the extract-variable row:

```kotlin
				ExtractMethodAction.ID to TooltipTag.EDITOR_CODE_ACTIONS_KT_EXTRACT_METHOD,
```

Also add `ExtractMethodAction()` to the action list this test builds, mirroring how `ExtractVariableAction()` appears there.

- [ ] **Step 3: Run the test to verify it fails**

Run: `flox activate -d flox/local -- ./gradlew :lsp:kotlin:testV7DebugUnitTest --tests "com.itsaky.androidide.lsp.kotlin.KotlinCodeActionTooltipTagTest"`
Expected: compilation failure -- `Unresolved reference: ExtractMethodAction`.

- [ ] **Step 4: Write the action**

Create `ExtractMethodAction.kt`:

```kotlin
package com.itsaky.androidide.lsp.kotlin.actions

import android.content.Context
import com.itsaky.androidide.actions.ActionData
import com.itsaky.androidide.actions.get
import com.itsaky.androidide.actions.requireContext
import com.itsaky.androidide.actions.requireEditor
import com.itsaky.androidide.actions.requireFile
import com.itsaky.androidide.idetooltips.TooltipTag
import com.itsaky.androidide.lsp.kotlin.KotlinLanguageServer
import com.itsaky.androidide.lsp.kotlin.compiler.modules.ScheduledCancelChecker
import com.itsaky.androidide.lsp.kotlin.refactor.ui.ExtractMethodChoice
import com.itsaky.androidide.lsp.kotlin.refactor.ui.ExtractMethodSheet
import com.itsaky.androidide.lsp.kotlin.refactor.ui.findFragmentActivity
import com.itsaky.androidide.lsp.kotlin.utils.refactor.ExtractMethodPlan
import com.itsaky.androidide.lsp.kotlin.utils.refactor.ExtractionRefusal
import com.itsaky.androidide.lsp.kotlin.utils.refactor.buildExtractMethodPlan
import com.itsaky.androidide.lsp.kotlin.utils.refactor.buildExtractMethodRewrites
import com.itsaky.androidide.lsp.kotlin.utils.refactor.toTextEdit
import com.itsaky.androidide.lsp.models.CodeActionItem
import com.itsaky.androidide.lsp.models.CodeActionKind
import com.itsaky.androidide.lsp.models.Command
import com.itsaky.androidide.lsp.models.DocumentChange
import com.itsaky.androidide.projects.FileManager
import com.itsaky.androidide.resources.R
import com.itsaky.androidide.tasks.createJobCancelChecker
import com.itsaky.androidide.utils.flashError
import com.itsaky.androidide.utils.flashInfo
import java.nio.file.Path

/**
 * Moves the expression at the cursor, or a selected range of statements, into a new `private fun`.
 *
 * [execAction] runs one background analysis pass and returns a plain-data [ExtractMethodPlan];
 * [postExec] shows the sheet and turns the user's choice into two text edits with pure offset
 * arithmetic. Where the region cannot be moved faithfully the plan carries a typed refusal, which
 * postExec renders as a specific message rather than a generic failure (ADR 0012).
 */
class ExtractMethodAction : BaseKotlinCodeAction() {
	companion object {
		const val ID = "ide.editor.lsp.kt.extractMethod"
	}

	override var titleTextRes: Int = R.string.action_extract_method
	override var tooltipTag: String = TooltipTag.EDITOR_CODE_ACTIONS_KT_EXTRACT_METHOD

	override val id: String = ID
	override var label: String = ""

	// Analysis must not run on the UI thread, so the selection is read at the top of execAction on a
	// background thread. A torn read while the user is mid-edit can only produce a plan the
	// document-version guard then refuses to apply.
	override var requiresUIThread: Boolean = false

	// Intentionally no prepare() visibility gate: deciding whether anything is extractable needs a K2
	// analysis session, far too costly for prepare(). The action stays visible on any Kotlin file and
	// reports a refusal instead.

	override suspend fun execAction(data: ActionData): ExtractMethodPlan {
		val server =
			data.get<KotlinLanguageServer>()
				?: return ExtractMethodPlan.refused(ExtractionRefusal.NotASingleRegion)
		val nioPath = data.requireFile().toPath()
		val env =
			server.compilationEnvironmentFor(nioPath)
				?: return ExtractMethodPlan.refused(ExtractionRefusal.NotASingleRegion)

		val cursor = data.requireEditor().cursor
		return buildExtractMethodPlan(
			env = env,
			nioPath = nioPath,
			selectionStart = minOf(cursor.left, cursor.right),
			selectionEnd = maxOf(cursor.left, cursor.right),
			documentVersion = documentVersionOf(nioPath),
			// Ties the analysis to this action's coroutine: cancelling the action aborts the analysis.
			cancelChecker = ScheduledCancelChecker(createJobCancelChecker()),
		)
	}

	override fun postExec(
		data: ActionData,
		result: Any,
	) {
		super.postExec(data, result)
		if (result !is ExtractMethodPlan) return

		val context = data.requireContext()
		if (result.isEmpty) {
			flashInfo(refusalMessage(context, result.refusal ?: ExtractionRefusal.NotASingleRegion))
			return
		}

		val activity =
			context.findFragmentActivity()
				?: run {
					// A wiring problem rather than a user path: the editor is always hosted by one.
					logger.warn("No FragmentActivity for the editor context. Cannot show the extract sheet.")
					flashError(R.string.msg_cannot_perform_fix)
					return
				}

		val shown = ExtractMethodSheet.show(activity, result) { choice -> applyChoice(data, result, choice) }
		if (!shown) {
			logger.warn("Fragment manager unavailable. Cannot show the extract sheet.")
		}
	}

	/**
	 * Turns the user's choice into the two edits and hands them to the language client.
	 *
	 * The document version is re-read here rather than trusted from the plan: the editor stays
	 * reachable while the sheet is open, and applying spans computed against older text would corrupt
	 * the file. Refusing is always safe; the user can invoke the action again.
	 */
	private fun applyChoice(
		data: ActionData,
		plan: ExtractMethodPlan,
		choice: ExtractMethodChoice,
	) {
		val file = data.requireFile()
		val nioPath = file.toPath()
		if (documentVersionOf(nioPath) != plan.documentVersion) {
			flashInfo(R.string.msg_extract_method_file_changed)
			return
		}

		val rewrites =
			buildExtractMethodRewrites(plan.fileText, choice.candidate, choice.name) ?: run {
				logger.warn("Could not build an extract-method rewrite for '{}'", choice.candidate.label)
				flashError(R.string.msg_cannot_perform_fix)
				return
			}

		val client =
			data.languageClient ?: run {
				logger.warn("No language client set. Cannot extract method.")
				return
			}

		client.performCodeAction(
			CodeActionItem(
				title = label,
				changes =
					listOf(
						DocumentChange(
							file = nioPath,
							// Descending document order: applyActionEdits applies these in list order with
							// line/column ranges, so the call site must not shift the insertion point.
							edits = rewrites.map { it.toTextEdit(plan.fileText) },
						),
					),
				kind = CodeActionKind.QuickFix,
				// The rewrites are emitted fully indented; CMD_FORMAT_CODE is a no-op for Kotlin anyway.
				command = Command("", ""),
			),
		)
	}

	/** Each refusal names the construct in the way; a generic message reads as a broken feature. */
	private fun refusalMessage(
		context: Context,
		refusal: ExtractionRefusal,
	): String =
		when (refusal) {
			ExtractionRefusal.NotASingleRegion -> context.getString(R.string.msg_extract_method_not_single_region)
			is ExtractionRefusal.MultipleOutputs ->
				context.getString(R.string.msg_extract_method_multiple_outputs, refusal.names.joinToString(", "))

			is ExtractionRefusal.ReassignsOuterVar ->
				context.getString(R.string.msg_extract_method_reassigns_outer_var, refusal.name)

			ExtractionRefusal.ExitsRegion -> context.getString(R.string.msg_extract_method_exits_region)
			is ExtractionRefusal.InnerImplicitReceiver ->
				context.getString(R.string.msg_extract_method_inner_implicit_receiver, refusal.construct)

			is ExtractionRefusal.UsesTypeParameter ->
				context.getString(R.string.msg_extract_method_uses_type_parameter, refusal.name)

			ExtractionRefusal.UnrenderableType -> context.getString(R.string.msg_extract_method_unrenderable_type)
		}

	/** -1 when the document is not open, which never matches a real version and so fails the guard. */
	private fun documentVersionOf(path: Path): Int = FileManager.getActiveDocument(path)?.version ?: -1
}
```

- [ ] **Step 5: Register the action**

In `KotlinCodeActionsMenu.kt`, add the import `com.itsaky.androidide.lsp.kotlin.actions.ExtractMethodAction` and add `ExtractMethodAction(),` to the action list, directly after `ExtractVariableAction(),`.

- [ ] **Step 6: Run the tests to verify they pass**

Run: `flox activate -d flox/local -- ./gradlew :lsp:kotlin:testV7DebugUnitTest`
Expected: PASS, all tests including `KotlinCodeActionTooltipTagTest`.

- [ ] **Step 7: Update the feature doc's status**

In `docs/features/kotlin-extract-method.md`, change line 4 from

```markdown
- **Status:** Requirements only - not implemented
```

to

```markdown
- **Status:** Implemented
```

- [ ] **Step 8: Build the app end to end**

Run: `flox activate -d flox/local -- ./gradlew :app:assembleV8Debug --parallel --max-workers=6`
Expected: BUILD SUCCESSFUL. This is slow (multi-minute) but it is the only check that the resource strings, the tooltip module and the LSP module all agree.

- [ ] **Step 9: Format and commit**

```bash
flox activate -d flox/local -- ./gradlew spotlessApply
git status
git add lsp/kotlin/src/main/java/com/itsaky/androidide/lsp/kotlin/actions/ExtractMethodAction.kt \
        lsp/kotlin/src/main/java/com/itsaky/androidide/lsp/kotlin/KotlinCodeActionsMenu.kt \
        lsp/kotlin/src/test/java/com/itsaky/androidide/lsp/kotlin/KotlinCodeActionTooltipTagTest.kt \
        idetooltips/src/main/java/com/itsaky/androidide/idetooltips/TooltipTag.kt \
        docs/features/kotlin-extract-method.md
git commit -m "ADFA-5080: Wire up the extract-method code action"
```

`git status` must show `docs/superpowers/plans/2026-08-10-kotlin-extract-method.md` as untracked and it must stay that way.

---

## On-device QA (not unit-testable)

The sheet, `prepare()`/`ActionData`, the two-step undo and the new tooltip row are covered by manual QA. Record these in ADFA-5080's "Steps to QA" field (`customfield_10250`), taken from the spec's acceptance criteria:

1. "Extract method" appears in the code-actions menu of a Kotlin file and is absent in a non-Kotlin file.
2. A cursor inside an expression offers innermost-first candidates; extracting one replaces it with a call and adds a `private fun` below the enclosing function.
3. Selecting two adjacent statements that use two locals produces a function with those two locals as parameters, in first-use order.
4. A ragged selection snaps outward to whole statements.
5. A selection spanning two blocks reports "Select an expression, or whole statements inside one block".
6. A range declaring a local read afterwards produces `val x = extracted(...)`.
7. A range declaring two such locals is declined as producing more than one value.
8. A loop accumulating into an outer `var` is declined, naming that variable.
9. Selecting a tail ending in `return x` produces `return extracted(...)`.
10. A `return` mid-range is declined; a `break` targeting an outer loop is declined.
11. Inside `fun Foo.bar()`, a region touching `Foo`'s members produces `private fun Foo.extracted(...)` with an unchanged call site.
12. Inside `with(x) { ... }`, a region using `x`'s members is declined, naming the construct.
13. A region calling a suspend function produces a `suspend fun`; one calling a `@Composable` produces a `@Composable` function that compiles.
14. A region using an enclosing function's type parameter is declined, naming it.
15. A name matching an inherited member is rejected with "That name is already used".
16. The signature preview matches the emitted declaration exactly.
17. Editing the file while the sheet is open, then confirming, reports the file-changed message and leaves the file untouched.
18. Undo restores the file; it currently takes **two** undo steps (ADFA-5081) and the intermediate state does not compile.
19. A space-indented file receives space-indented output; a CRLF file keeps CRLF.
20. The tooltip long-press on the menu item resolves `editor.codeactions.kotlin.extractmethod`.
