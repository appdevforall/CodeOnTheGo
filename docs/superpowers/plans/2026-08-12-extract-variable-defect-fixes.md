# Extract Variable Defect Fixes Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix the five defects found in the Kotlin extract-variable code action so every extraction it offers produces code that compiles, then hand ADFA-4826 to QA.

**Architecture:** All five fixes stay inside the existing analysis/UI split: the background pass produces a plain-data `ExtractionPlan`, and the rewrite is pure text and offset arithmetic on it. Two of the fixes add data to `AnchorForm` so the rewrite can honour the scope the user picked and can tell a one-line block from a multi-line one; one adds a rendered return type to the expression-body conversion; two are guard/label corrections in the syntactic layer.

**Tech Stack:** Kotlin, K2 Analysis API (`org.jetbrains.kotlin.analysis.api`), Kotlin PSI, JUnit 4, Gradle (flox-wrapped), `gh stack` for the PR stack.

## Global Constraints

- **Branch:** all five fix commits go on `feat/ADFA-4826-extract-variable` (PR #1654). The stack is `stage` <- `feat/ADFA-4826-common-compose-theme` (#1653) <- `feat/ADFA-4826-extract-variable` (#1654) <- `feat/ADFA-5080-extract-method` (#1655), tracked as `gh stack` Stack #1656.
- **Worktree:** `/var/mnt/data/dev/work/adfa/cogo/code-on-the-go/.claude/worktrees/ADFA-4826`. It currently has `feat/ADFA-5080-extract-method` checked out; Task 1 switches it.
- **Gradle:** every invocation is wrapped: `flox activate -d flox/local -- ./gradlew <task>`.
- **Unit test task:** `:lsp:kotlin:testV7DebugUnitTest` (V7 flavour; there is no flavourless `test`).
- **Formatting:** tabs for indentation, LF endings, ktlint via Spotless. Run `flox activate -d flox/local -- ./gradlew spotlessApply` before each commit.
- **Code comments:** comment the non-obvious *why* only. No separator or decorative comments. ASCII only in code and comments (`->`, `-`, straight quotes).
- **Commits:** subject `ADFA-4826: <imperative summary>` (`ADFA-5080: ...` for the one commit on the 5080 branch). No `Co-Authored-By` trailer. Never `git add .` - stage named paths. Never commit anything under `docs/superpowers/plans/`.
- **Invariants that must not regress:** exactly one `TextEdit` per code action; the plan carries no PSI; nothing in `prepare()`; no I/O on the main thread.
- **Jira:** ADFA-4826, field `customfield_10250` ("Steps to QA") is ADF, cloudId `bb66613e-967d-4549-a8d6-d9166759f2d2`.

## File Structure

**Created**

- `lsp/kotlin/src/main/java/com/itsaky/androidide/lsp/kotlin/utils/refactor/TypeText.kt` - the shared type-text layer: render a `KaType` as source-shaped text, reject what cannot be written out, and shorten qualified names that already resolve in the file. Extracted here (rather than left private in `MethodSignature.kt`, which lives one PR further up the stack) so both refactorings share one renderer.

**Modified**

- `.../utils/refactor/CandidateExpressions.kt` - `isLegalExtractionTarget` also rejects `KtLambdaExpression` (Task 1).
- `.../utils/refactor/ScopeChain.kt` - `blockLabel` unwraps the control-structure container node (Task 2); `frameFor` fills the new `ExistingBlock` fields (Task 4).
- `.../utils/refactor/ExtractionPlan.kt` - `AnchorForm.ExistingBlock` becomes a data class carrying `contentSpan` + `statementSpans` (Task 4); `AnchorForm.ConvertExpressionBody` gains `returnTypeText` (Task 3).
- `.../utils/refactor/ExtractVariablePlanner.kt` - computes `returnTypeText` and declines the rung when the type cannot be written (Task 3).
- `.../utils/refactor/ExtractVariableEdit.kt` - anchors on the chosen scope's statement (Task 4), expands a one-line block (Task 5), emits the return type (Task 3).
- `.../utils/refactor/MethodSignature.kt` - drops its private renderer copies in favour of `TypeText.kt` (Task 6, on the 5080 branch).
- `docs/features/kotlin-extract-variable.md` - R2, R5, R9, the acceptance criteria and the stale Status line, one delta per fix commit.

**Tests modified**

- `lsp/kotlin/src/test/java/com/itsaky/androidide/lsp/kotlin/utils/refactor/RefactorPrimitivesTest.kt` - pure `shortenTypeText` rules (Task 3).
- `.../utils/refactor/ExtractVariableEditTest.kt` - `ExistingBlock` fixtures, the outer-rung anchor, the one-line block, the return-type header (Tasks 3-5).
- `.../utils/refactor/ExtractVariablePlanEndToEndTest.kt` - lambda exclusion, rung labels, inferred return type, outer-rung text, one-line lambda text (Tasks 1-5).
- `lsp/kotlin/src/test/java/com/itsaky/androidide/lsp/kotlin/refactor/ui/ExtractVariableViewModelTest.kt` - one `ExistingBlock` fixture (Task 4).

**Test facts worth knowing before writing any test**

- `ExtractVariablePlanEndToEndTest` extends `KtLspTest` and has two private helpers already: `plan(content, start, end = start)` (writes `Main.kt` into the test source root and returns the `ExtractionPlan`) and `apply(text, rewrite)` (applies a `RewriteSpan` to a string). Use them; do not add new ones.
- Every analysis-backed test writes the **same** file name `Main.kt`, which overwrites the previous test's file. Do not give each test a unique file name: several files in one source root share package `p`, and duplicate top-level declarations across them silently break symbol resolution - which shows up as wrong `needsReturn`/type results rather than as a test error.
- `RefactorPrimitivesTest` and `ExtractVariableEditTest` are plain JUnit with no PSI and no analysis session. Keep them that way.

---

### Task 1: Stop offering the lambda that wraps the expression

**Files:**
- Modify: `lsp/kotlin/src/main/java/com/itsaky/androidide/lsp/kotlin/utils/refactor/CandidateExpressions.kt:190-210`
- Modify: `docs/features/kotlin-extract-variable.md:4` (Status), `:80` (R2 illegal-target list)
- Test: `lsp/kotlin/src/test/java/com/itsaky/androidide/lsp/kotlin/utils/refactor/ExtractVariablePlanEndToEndTest.kt`

**Interfaces:**
- Consumes: nothing from other tasks.
- Produces: nothing other tasks depend on. `isLegalExtractionTarget(): Boolean` keeps its signature.

**Why:** `isLegalExtractionTarget` rejects `KtFunctionLiteral`, but the candidate walk sees the `KtLambdaExpression` that wraps it, so `{ it.length + 1 }` is offered as a candidate. Extracting it emits `val value = { it.length + 1 }`, where `it` has no source, and R2 already says a lambda literal is not a legal target.

- [ ] **Step 1: Put the worktree on the right branch**

```bash
cd /var/mnt/data/dev/work/adfa/cogo/code-on-the-go/.claude/worktrees/ADFA-4826
gh stack checkout feat/ADFA-4826-extract-variable
git log --oneline -1
```

Expected: `617ed6f39 ADFA-4826: Document the extract-variable requirements` (the untracked plan file under `docs/superpowers/plans/` survives the switch; leave it untracked).

- [ ] **Step 2: Write the failing test**

Append to `ExtractVariablePlanEndToEndTest`:

```kotlin
	@Test
	fun `does not offer the lambda that wraps the expression`() {
		val content =
			"""
			package p
			fun demo(items: List<String>): List<Int> {
				return items.map {
					it.length + 1
				}
			}
			""".trimIndent()

		val target = "it.length + 1"
		val result = plan(content, content.indexOf(target), content.indexOf(target) + target.length)

		// `{ it.length + 1 }` must not appear between the two: a hoisted lambda loses the `it` the call
		// site was supplying.
		assertEquals(
			listOf("it.length + 1", "items.map { it.length + 1 }"),
			result.candidates.map { it.label },
		)
	}
```

- [ ] **Step 3: Run it and watch it fail**

```bash
flox activate -d flox/local -- ./gradlew :lsp:kotlin:testV7DebugUnitTest \
  --tests "com.itsaky.androidide.lsp.kotlin.utils.refactor.ExtractVariablePlanEndToEndTest"
```

Expected: FAIL on this test, with the actual list containing `{ it.length + 1 }` as its second entry.

- [ ] **Step 4: Exclude lambda expressions**

In `CandidateExpressions.kt`, add the import (keep the import block alphabetical - it goes directly after `KtFunctionLiteral`):

```kotlin
import org.jetbrains.kotlin.psi.KtLambdaExpression
```

and in `isLegalExtractionTarget`, directly after the `KtFunctionLiteral` line:

```kotlin
		if (this is KtFunctionLiteral) return false
		// The wrapper around the literal. A hoisted lambda loses the parameter types its call site was
		// supplying, so `{ it.length + 1 }` becomes uncompilable the moment it leaves the call.
		if (this is KtLambdaExpression) return false
```

Also extend the KDoc bullet above the function:

```kotlin
 * - blocks, loops, `return`/`throw`/`break`/`continue` -- no useful value to bind;
 * - lambdas, literal and wrapper alike -- outside their call site the parameter types are gone;
```

- [ ] **Step 5: Run it and watch it pass**

```bash
flox activate -d flox/local -- ./gradlew :lsp:kotlin:testV7DebugUnitTest \
  --tests "com.itsaky.androidide.lsp.kotlin.utils.refactor.ExtractVariablePlanEndToEndTest"
```

Expected: PASS, all tests in the class.

- [ ] **Step 6: Update the feature doc**

In `docs/features/kotlin-extract-variable.md`, replace the Status line (line 4):

```markdown
- **Status:** Implemented in `lsp/kotlin/utils/refactor/` and `lsp/kotlin/refactor/ui/`, pending on-device QA. Still to land in this PR: the `ExtractionPlan` -> `ExtractVariablePlan` rename (the sealed `RefactoringPlan` supertype it will sit under has landed).
```

and in R2, in the illegal-target sentence, replace `a lambda literal` with:

```markdown
a lambda (the `{ ... }` expression and the literal inside it -- outside its call site the parameter types are gone, so `val v = { it.length + 1 }` does not compile)
```

- [ ] **Step 7: Format and commit**

```bash
flox activate -d flox/local -- ./gradlew spotlessApply
git add lsp/kotlin/src/main/java/com/itsaky/androidide/lsp/kotlin/utils/refactor/CandidateExpressions.kt \
        lsp/kotlin/src/test/java/com/itsaky/androidide/lsp/kotlin/utils/refactor/ExtractVariablePlanEndToEndTest.kt \
        docs/features/kotlin-extract-variable.md
git commit -m "ADFA-4826: Stop offering the lambda that wraps the expression"
```

---

### Task 2: Name the construct that owns a braced block

**Files:**
- Modify: `lsp/kotlin/src/main/java/com/itsaky/androidide/lsp/kotlin/utils/refactor/ScopeChain.kt:172-186` (`blockLabel`)
- Modify: `docs/features/kotlin-extract-variable.md` (R5, after the anchor-form table)
- Test: `.../utils/refactor/ExtractVariablePlanEndToEndTest.kt`

**Interfaces:**
- Consumes: nothing.
- Produces: rung labels seen by the sheet's `Declare in` list. Task 4's tests assert `"fun demo"` and `"if block"`.

**Why:** a braced `if` branch's PSI is `KtIfExpression -> KtContainerNodeForControlStructureBody -> KtBlockExpression`, so `blockLabel`'s `when` sees the container node and falls through to the generic `"block"`. The doc promises `if block`. Same for braced loop bodies.

- [ ] **Step 1: Write the failing test**

Append to `ExtractVariablePlanEndToEndTest`:

```kotlin
	@Test
	fun `labels a braced if branch by its owner`() {
		val content =
			"""
			package p
			fun demo(flag: Boolean, a: Int, b: Int): Int {
				if (flag) {
					return a + b * 2
				}
				return 0
			}
			""".trimIndent()

		val target = "a + b * 2"
		val result = plan(content, content.indexOf(target), content.indexOf(target) + target.length)

		assertEquals(listOf("if block", "fun demo"), result.candidates.first().scopes.map { it.label })
	}
```

- [ ] **Step 2: Run it and watch it fail**

```bash
flox activate -d flox/local -- ./gradlew :lsp:kotlin:testV7DebugUnitTest \
  --tests "com.itsaky.androidide.lsp.kotlin.utils.refactor.ExtractVariablePlanEndToEndTest"
```

Expected: FAIL, actual `[block, fun demo]`.

- [ ] **Step 3: Unwrap the container node**

Replace `blockLabel` in `ScopeChain.kt`:

```kotlin
/**
 * The name shown for a block rung.
 *
 * A braceless *or* braced control-structure body is wrapped in a container node, so the `if`/loop is
 * the block's grandparent; without unwrapping, every braced branch reads as a generic "block". The
 * container is also what `then`/`else` point at, so the branch check compares against it.
 */
private fun blockLabel(block: KtBlockExpression): String {
	val parent = block.parent
	val container = parent as? KtContainerNodeForControlStructureBody
	val branch = container ?: block
	return when (val owner = container?.parent ?: parent) {
		is KtNamedFunction -> "fun ${owner.name ?: "<anonymous>"}"
		is KtPropertyAccessor -> if (owner.isGetter) "getter" else "setter"
		is KtAnonymousInitializer -> "init block"
		is KtFunctionLiteral -> "lambda"
		is KtIfExpression -> if (owner.then === branch) "if block" else "else block"
		is KtForExpression -> "for loop"
		is KtWhileExpression -> "while loop"
		is KtDoWhileExpression -> "do-while loop"
		is KtWhenEntry -> "when branch"
		else -> "block"
	}
}
```

- [ ] **Step 4: Run it and watch it pass**

```bash
flox activate -d flox/local -- ./gradlew :lsp:kotlin:testV7DebugUnitTest \
  --tests "com.itsaky.androidide.lsp.kotlin.utils.refactor.ExtractVariablePlanEndToEndTest"
```

Expected: PASS, all tests in the class.

- [ ] **Step 5: Document the labels**

In `docs/features/kotlin-extract-variable.md`, immediately after the R5 anchor-form table, insert:

```markdown
Each rung is labelled with the construct that owns it -- `fun name`, `getter`, `setter`, `init block`,
`lambda`, `if block`, `else block`, `for loop`, `while loop`, `do-while loop`, `when branch` -- so the
`Declare in` list reads as a place rather than as a nesting level. A braced control-structure body is
wrapped in a container node, so the owner is the block's grandparent, not its parent.
```

- [ ] **Step 6: Format and commit**

```bash
flox activate -d flox/local -- ./gradlew spotlessApply
git add lsp/kotlin/src/main/java/com/itsaky/androidide/lsp/kotlin/utils/refactor/ScopeChain.kt \
        lsp/kotlin/src/test/java/com/itsaky/androidide/lsp/kotlin/utils/refactor/ExtractVariablePlanEndToEndTest.kt \
        docs/features/kotlin-extract-variable.md
git commit -m "ADFA-4826: Label a block rung by the construct that owns it"
```

---

### Task 3: Write out the return type when converting an expression body

**Files:**
- Create: `lsp/kotlin/src/main/java/com/itsaky/androidide/lsp/kotlin/utils/refactor/TypeText.kt`
- Modify: `.../utils/refactor/ExtractionPlan.kt:44-57` (`ConvertExpressionBody`)
- Modify: `.../utils/refactor/ExtractVariablePlanner.kt:77-129`
- Modify: `.../utils/refactor/ExtractVariableEdit.kt:104-125`
- Modify: `docs/features/kotlin-extract-variable.md` (R5 table row, acceptance criteria 10-11)
- Test: `.../utils/refactor/RefactorPrimitivesTest.kt`, `.../utils/refactor/ExtractVariableEditTest.kt`, `.../utils/refactor/ExtractVariablePlanEndToEndTest.kt`

**Interfaces:**
- Consumes: nothing from Tasks 1-2.
- Produces:
  - `internal fun KaSession.renderedTypeTextOrNull(type: KaType): String?`
  - `internal fun isUnrenderableTypeText(text: String): Boolean`
  - `internal fun shortenTypeText(rendered: String, importedNames: Set<String>, starImportedPackages: Set<String>): String`
  - `internal fun importedNamesOf(file: KtFile): Set<String>` and `internal fun starImportedPackagesOf(file: KtFile): Set<String>`
  - `AnchorForm.ConvertExpressionBody` gains `val returnTypeText: String?` (null = insert nothing). Task 6 reuses the first three from `MethodSignature.kt`.

**Why:** `fun area(r: Int) = r * r` has no declared return type. Converting it to a block body with `return squared` leaves a Unit-returning function returning an `Int`, which does not compile. The type has to be written into the signature, and it is only safe to shorten a qualified name when that short name already resolves in the file.

- [ ] **Step 1: Write the failing pure tests for the shortening rule**

Append to `RefactorPrimitivesTest`:

```kotlin
	@Test
	fun `shortens types from Kotlin's default-imported packages`() {
		assertEquals("Int", shortenTypeText("kotlin.Int", emptySet(), emptySet()))
		assertEquals(
			"List<String>",
			shortenTypeText("kotlin.collections.List<kotlin.String>", emptySet(), emptySet()),
		)
	}

	@Test
	fun `keeps a type qualified when its short name would not resolve`() {
		assertEquals("java.util.Date", shortenTypeText("java.util.Date", emptySet(), emptySet()))
		// An import of the enclosing class is not an import of the nested one.
		assertEquals(
			"com.example.Outer.Inner",
			shortenTypeText("com.example.Outer.Inner", setOf("com.example.Outer"), emptySet()),
		)
	}

	@Test
	fun `shortens a type the file already imports, by name or by star`() {
		assertEquals("Date", shortenTypeText("java.util.Date", setOf("java.util.Date"), emptySet()))
		assertEquals("Date", shortenTypeText("java.util.Date", emptySet(), setOf("java.util")))
		assertEquals(
			"Flow<Widget>",
			shortenTypeText(
				"kotlinx.coroutines.flow.Flow<com.example.Widget>",
				setOf("kotlinx.coroutines.flow.Flow", "com.example.Widget"),
				emptySet(),
			),
		)
	}

	@Test
	fun `unrenderable type text is recognised`() {
		assertTrue(isUnrenderableTypeText(""))
		assertTrue(isUnrenderableTypeText("kotlin.collections.List<kotlin.String!>"))
		assertTrue(isUnrenderableTypeText("<anonymous object>"))
		assertTrue(isUnrenderableTypeText("ERROR CLASS: unresolved"))
		assertTrue(isUnrenderableTypeText("kotlin.Any & kotlin.Comparable<*>"))
		assertFalse(isUnrenderableTypeText("kotlin.Int"))
	}
```

Add the two imports the class does not have yet:

```kotlin
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
```

- [ ] **Step 2: Run them and watch them fail**

```bash
flox activate -d flox/local -- ./gradlew :lsp:kotlin:testV7DebugUnitTest \
  --tests "com.itsaky.androidide.lsp.kotlin.utils.refactor.RefactorPrimitivesTest"
```

Expected: compilation failure - `Unresolved reference: shortenTypeText` and `isUnrenderableTypeText`.

- [ ] **Step 3: Create the shared type-text layer**

Create `lsp/kotlin/src/main/java/com/itsaky/androidide/lsp/kotlin/utils/refactor/TypeText.kt`:

```kotlin
package com.itsaky.androidide.lsp.kotlin.utils.refactor

import com.itsaky.androidide.lsp.kotlin.utils.renderName
import org.jetbrains.kotlin.analysis.api.KaExperimentalApi
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.renderer.types.impl.KaTypeRendererForSource
import org.jetbrains.kotlin.analysis.api.types.KaFlexibleType
import org.jetbrains.kotlin.analysis.api.types.KaType
import org.jetbrains.kotlin.psi.KtFile

/**
 * Types are rendered **fully qualified** and only then shortened against what the file can resolve.
 *
 * A short name resolves only when the file imports it or it comes from a default-imported package, and
 * a refactoring that adds imports would be a much larger change -- so qualified is the safe starting
 * point and [shortenTypeText] gives back readability where it provably costs nothing.
 */
@OptIn(KaExperimentalApi::class)
private val QUALIFIED_TYPE_RENDERER = KaTypeRendererForSource.WITH_QUALIFIED_NAMES

/** Packages whose simple names resolve with no import at all on the JVM/Android target. */
private val DEFAULT_IMPORTED_PACKAGES =
	setOf(
		"kotlin",
		"kotlin.annotation",
		"kotlin.collections",
		"kotlin.comparisons",
		"kotlin.io",
		"kotlin.jvm",
		"kotlin.ranges",
		"kotlin.sequences",
		"kotlin.text",
		"java.lang",
	)

/** A dotted run of identifiers -- one qualified name inside rendered type text. */
private val QUALIFIED_NAME = Regex("""[\p{L}_][\p{L}\p{Nd}_]*(?:\.[\p{L}_][\p{L}\p{Nd}_]*)+""")

/**
 * A type that cannot be written out as source -- anonymous, intersection, a resolution error, or a
 * platform type the renderer could not reduce (`List<String!>`, where the `!` is on a type argument).
 * `!` is not Kotlin syntax anywhere, so its presence alone settles it.
 */
internal fun isUnrenderableTypeText(text: String): Boolean =
	text.isBlank() ||
		text.contains("anonymous") ||
		text.contains("ERROR") ||
		text.contains(" & ") ||
		text.contains('!')

/**
 * One type as source text, fully qualified, or null when it cannot be written out.
 *
 * A platform type is unwrapped to its lower bound first: the renderer prints `String!`, which does not
 * parse. Only the outermost bound is unwrapped, so a `!` on a type argument still reaches
 * [isUnrenderableTypeText].
 */
@OptIn(KaExperimentalApi::class)
internal fun KaSession.renderedTypeTextOrNull(type: KaType): String? =
	runCatching { renderName((type as? KaFlexibleType)?.lowerBound ?: type, QUALIFIED_TYPE_RENDERER) }
		.getOrNull()
		?.takeUnless(::isUnrenderableTypeText)

/**
 * Replaces each qualified name in [rendered] with its simple name when that name already resolves in
 * the file -- because the file imports it exactly, star-imports its package, or it comes from a
 * default-imported package. Everything else stays qualified: verbose, but it always compiles.
 *
 * Purely textual, so it needs no analysis session and is unit-testable on its own. A nested class
 * (`com.example.Outer.Inner`) is only shortened by an import of the nested name itself; an import of
 * the outer class leaves it alone rather than emitting an unresolvable `Inner`.
 */
internal fun shortenTypeText(
	rendered: String,
	importedNames: Set<String>,
	starImportedPackages: Set<String>,
): String =
	QUALIFIED_NAME.replace(rendered) { match ->
		val qualified = match.value
		val container = qualified.substringBeforeLast('.')
		val resolvable =
			qualified in importedNames ||
				container in DEFAULT_IMPORTED_PACKAGES ||
				container in starImportedPackages
		if (resolvable) qualified.substringAfterLast('.') else qualified
	}

/** The fully qualified names [file] imports by name. Syntactic: no analysis session needed. */
internal fun importedNamesOf(file: KtFile): Set<String> =
	file.importDirectives
		.filterNot { it.isAllUnder }
		.mapNotNullTo(mutableSetOf()) { it.importedFqName?.asString() }

/** The packages [file] star-imports (`import com.example.*`). */
internal fun starImportedPackagesOf(file: KtFile): Set<String> =
	file.importDirectives
		.filter { it.isAllUnder }
		.mapNotNullTo(mutableSetOf()) { it.importedFqName?.asString() }
```

- [ ] **Step 4: Run the pure tests and watch them pass**

```bash
flox activate -d flox/local -- ./gradlew :lsp:kotlin:testV7DebugUnitTest \
  --tests "com.itsaky.androidide.lsp.kotlin.utils.refactor.RefactorPrimitivesTest"
```

Expected: PASS, all tests in the class.

- [ ] **Step 5: Write the failing rewrite test for the emitted header**

Append to `ExtractVariableEditTest`:

```kotlin
	@Test
	fun `writes the return type into the signature when the declaration has none`() {
		val text = "fun area(r: Int) = r * r"
		val candidate = spanOf(text, "r * r")
		val form =
			AnchorForm.ConvertExpressionBody(
				assignStart = text.indexOf('='),
				bodyStart = candidate.start,
				bodyEnd = text.length,
				indent = "",
				innerIndent = "\t",
				needsReturn = true,
				returnTypeText = "Int",
			)

		val result = rewrite(text, candidate, form, listOf(candidate), "squared", replaceAll = false)!!

		assertEquals(
			"fun area(r: Int): Int {\n" +
				"\tval squared = r * r\n" +
				"\treturn squared\n" +
				"}",
			apply(text, result),
		)
	}
```

- [ ] **Step 6: Run it and watch it fail**

```bash
flox activate -d flox/local -- ./gradlew :lsp:kotlin:testV7DebugUnitTest \
  --tests "com.itsaky.androidide.lsp.kotlin.utils.refactor.ExtractVariableEditTest"
```

Expected: compilation failure - `ConvertExpressionBody` has no `returnTypeText` parameter.

- [ ] **Step 7: Add the field and emit it**

In `ExtractionPlan.kt`, replace the `ConvertExpressionBody` declaration and its KDoc:

```kotlin
	/**
	 * An expression-bodied function or property accessor -- `fun area(r: Int) = r * r`. The `=` and
	 * the body are replaced by a block body. [needsReturn] is false only when the declaration
	 * returns `Unit`, where `return` is both unnecessary and wrong for a non-`Unit` expression.
	 *
	 * [returnTypeText] is the type to write into the signature, or null when there is nothing to write
	 * -- the declaration already spells its type out, or the block body infers `Unit` anyway. A block
	 * body with no declared type returns `Unit`, so `return <value>` without this would not compile.
	 */
	data class ConvertExpressionBody(
		val assignStart: Int,
		val bodyStart: Int,
		val bodyEnd: Int,
		val indent: String,
		val innerIndent: String,
		val needsReturn: Boolean,
		val returnTypeText: String? = null,
	) : AnchorForm
```

In `ExtractVariableEdit.kt`, replace `convertExpressionBodyRewrite` and add the helper below it:

```kotlin
/** Converts `= expr` into a block body holding the declaration and a `return` of the rewritten body. */
private fun convertExpressionBodyRewrite(
	fileText: String,
	form: AnchorForm.ConvertExpressionBody,
	targets: List<TextSpan>,
	declaration: String,
	name: String,
): RewriteSpan {
	val bodySpan = TextSpan(form.bodyStart, form.bodyEnd)
	val newline = detectNewline(fileText)
	val body = replaceOccurrences(fileText, bodySpan, targets, name)
	val returned = if (form.needsReturn) "return $body" else body

	// Writing a type means rewriting from the end of the signature, not from the `=`: starting at the
	// `=` would leave the space in front of it and emit `fun area(r: Int) : Int {`.
	val spanStart =
		if (form.returnTypeText == null) form.assignStart else startOfWhitespaceBefore(fileText, form.assignStart)
	val header = form.returnTypeText?.let { ": $it " } ?: ""

	val newText =
		buildString {
			append(header).append('{').append(newline)
			append(form.innerIndent).append(declaration).append(newline)
			append(form.innerIndent).append(returned).append(newline)
			append(form.indent).append('}')
		}
	return RewriteSpan(TextSpan(spanStart, form.bodyEnd), newText)
}

/** The offset where the run of whitespace ending at [offset] begins. */
private fun startOfWhitespaceBefore(
	text: String,
	offset: Int,
): Int {
	var index = offset.coerceIn(0, text.length)
	while (index > 0 && text[index - 1].isWhitespace()) index--
	return index
}
```

- [ ] **Step 8: Run the rewrite tests and watch them pass**

```bash
flox activate -d flox/local -- ./gradlew :lsp:kotlin:testV7DebugUnitTest \
  --tests "com.itsaky.androidide.lsp.kotlin.utils.refactor.ExtractVariableEditTest"
```

Expected: PASS, all tests in the class (the two pre-existing `ConvertExpressionBody` tests pass `returnTypeText` implicitly as null and must be unchanged).

- [ ] **Step 9: Write the failing plan tests for the three signature shapes**

Append to `ExtractVariablePlanEndToEndTest`:

```kotlin
	@Test
	fun `converting an inferred-type expression body writes the type out`() {
		val content =
			"""
			package p
			fun area(r: Int) = r * r
			""".trimIndent()

		val target = "r * r"
		val result = plan(content, content.indexOf(target), content.indexOf(target) + target.length)
		val candidate = result.candidates.first()
		val rewrite =
			buildExtractVariableRewrite(
				fileText = result.fileText,
				candidateSpan = candidate.span,
				scope = candidate.scopes.first(),
				name = "squared",
				replaceAll = false,
			)!!

		assertEquals(
			"package p\n" +
				"fun area(r: Int): Int {\n" +
				"\tval squared = r * r\n" +
				"\treturn squared\n" +
				"}",
			apply(content, rewrite),
		)
	}

	@Test
	fun `a declared return type is not written twice`() {
		val content =
			"""
			package p
			fun area(r: Int): Int = r * r
			""".trimIndent()

		val target = "r * r"
		val result = plan(content, content.indexOf(target), content.indexOf(target) + target.length)
		val candidate = result.candidates.first()
		val rewrite =
			buildExtractVariableRewrite(
				fileText = result.fileText,
				candidateSpan = candidate.span,
				scope = candidate.scopes.first(),
				name = "squared",
				replaceAll = false,
			)!!

		assertEquals(
			"package p\n" +
				"fun area(r: Int): Int {\n" +
				"\tval squared = r * r\n" +
				"\treturn squared\n" +
				"}",
			apply(content, rewrite),
		)
	}

	@Test
	fun `a Unit-returning expression body gets neither a type nor a return`() {
		val content =
			"""
			package p
			fun report(value: Int) {
				println(value)
			}
			fun show(text: String) = report(text.length + 1)
			""".trimIndent()

		val target = "text.length + 1"
		val result = plan(content, content.indexOf(target), content.indexOf(target) + target.length)
		val candidate = result.candidates.first()
		val rewrite =
			buildExtractVariableRewrite(
				fileText = result.fileText,
				candidateSpan = candidate.span,
				scope = candidate.scopes.first(),
				name = "length",
				replaceAll = false,
			)!!

		assertEquals(
			"package p\n" +
				"fun report(value: Int) {\n" +
				"\tprintln(value)\n" +
				"}\n" +
				"fun show(text: String) {\n" +
				"\tval length = text.length + 1\n" +
				"\treport(length)\n" +
				"}",
			apply(content, rewrite),
		)
	}
```

- [ ] **Step 10: Run them and watch the first one fail**

```bash
flox activate -d flox/local -- ./gradlew :lsp:kotlin:testV7DebugUnitTest \
  --tests "com.itsaky.androidide.lsp.kotlin.utils.refactor.ExtractVariablePlanEndToEndTest"
```

Expected: `converting an inferred-type expression body writes the type out` FAILS (actual has `fun area(r: Int) {`). The other two PASS.

- [ ] **Step 11: Compute the type in the planner, and decline when it cannot be written**

In `ExtractVariablePlanner.kt`, add these imports:

```kotlin
import org.jetbrains.kotlin.psi.KtCallableDeclaration
import org.jetbrains.kotlin.psi.KtPropertyAccessor
```

Replace `candidateFor`'s scope-building lines so a rung can be declined:

```kotlin
	val span = TextSpan(expression.textRange.startOffset, expression.textRange.endOffset)
	val file = expression.containingKtFile
	val scopes = frames.mapNotNull { scopeOptionFor(expression, span, it, file) }
	if (scopes.isEmpty()) return null
	val takenNames = visibleNamesAt(expression)
```

Replace `scopeOptionFor` with:

```kotlin
/**
 * Builds one scope option, resolving its occurrence set and fixing up expression-body details.
 *
 * Returns null when the rung cannot be honoured: converting an expression body whose return type is
 * neither declared nor renderable would emit a block body that does not compile, and declining is
 * always safe (ADR 0013).
 */
private fun KaSession.scopeOptionFor(
	expression: KtExpression,
	span: TextSpan,
	frame: ScopeFrame,
	file: KtFile,
): ScopeOption? {
	val matches = findOccurrences(expression, frame.scopeElement, frame.searchRange)
	val writes = writeOffsetsFor(expression, frame.scopeElement)
	val occurrences = excludeUnsoundOccurrences(matches, span, writes)

	val anchorForm =
		when (val form = frame.anchorForm) {
			is AnchorForm.ConvertExpressionBody -> {
				val declaration = frame.scopeElement.parent as? KtDeclarationWithBody
				val needsReturn = expressionBodyNeedsReturn(frame.scopeElement)
				val returnTypeText =
					if (needsReturn && declaration != null && !declaration.declaresReturnType()) {
						returnTypeTextOf(declaration, file) ?: return null
					} else {
						null
					}
				form.copy(needsReturn = needsReturn, returnTypeText = returnTypeText)
			}

			else -> form
		}

	return ScopeOption(label = frame.label, anchorForm = anchorForm, occurrences = occurrences)
}

/** Whether the declaration spells its return type out, in which case nothing needs writing. */
private fun KtDeclarationWithBody.declaresReturnType(): Boolean =
	when (this) {
		is KtPropertyAccessor -> returnTypeReference != null
		is KtCallableDeclaration -> typeReference != null
		else -> false
	}

/** The declaration's return type as source text, shortened where the file can resolve it. */
private fun KaSession.returnTypeTextOf(
	declaration: KtDeclarationWithBody,
	file: KtFile,
): String? {
	val type = runCatching { ((declaration as? KtDeclaration)?.symbol as? KaCallableSymbol)?.returnType }.getOrNull() ?: return null
	val rendered = renderedTypeTextOrNull(type) ?: return null
	return shortenTypeText(rendered, importedNamesOf(file), starImportedPackagesOf(file))
}
```

- [ ] **Step 12: Run the whole module's tests and watch them pass**

```bash
flox activate -d flox/local -- ./gradlew :lsp:kotlin:testV7DebugUnitTest
```

Expected: PASS for the whole module, including all four refactor test classes.

- [ ] **Step 13: Update the feature doc**

In the R5 anchor-form table, replace the `ConvertExpressionBody` row's "Emitted as" cell:

```markdown
| `ConvertExpressionBody` | an expression-bodied function or accessor, `fun area(r: Int) = r * r` | `=` and the body become a block body; `return` is added unless the declaration returns `Unit`; the return type is written into the signature when the declaration does not spell one out, because a block body with no declared type returns `Unit` |
```

Immediately after the table, add:

```markdown
A written-out return type is rendered fully qualified and then shortened to its simple name only where
that name already resolves in the file -- an exact import, a star import of its package, or a
default-imported package such as `kotlin.collections`. Everything else stays qualified: verbose, but it
compiles, and this refactoring adds no imports. When the type cannot be written as source at all
(anonymous, intersection, an unresolved type, or a platform type the renderer cannot reduce) the rung
is declined rather than emitting a block body that does not compile.
```

Replace acceptance criteria 10 and 11:

```markdown
10. Extracting from `fun area(r: Int): Int = r * r` converts it to a block body with `return`, leaving the declared type alone; extracting from `fun area(r: Int) = r * r` converts it *and* writes `: Int` into the signature.
11. Extracting from a `Unit`-returning expression-bodied function converts it without adding `return` and without writing a type.
```

- [ ] **Step 14: Format and commit**

```bash
flox activate -d flox/local -- ./gradlew spotlessApply
git add lsp/kotlin/src/main/java/com/itsaky/androidide/lsp/kotlin/utils/refactor/TypeText.kt \
        lsp/kotlin/src/main/java/com/itsaky/androidide/lsp/kotlin/utils/refactor/ExtractionPlan.kt \
        lsp/kotlin/src/main/java/com/itsaky/androidide/lsp/kotlin/utils/refactor/ExtractVariablePlanner.kt \
        lsp/kotlin/src/main/java/com/itsaky/androidide/lsp/kotlin/utils/refactor/ExtractVariableEdit.kt \
        lsp/kotlin/src/test/java/com/itsaky/androidide/lsp/kotlin/utils/refactor/RefactorPrimitivesTest.kt \
        lsp/kotlin/src/test/java/com/itsaky/androidide/lsp/kotlin/utils/refactor/ExtractVariableEditTest.kt \
        lsp/kotlin/src/test/java/com/itsaky/androidide/lsp/kotlin/utils/refactor/ExtractVariablePlanEndToEndTest.kt \
        docs/features/kotlin-extract-variable.md
git commit -m "ADFA-4826: Write the return type when converting an expression body"
```

---

### Task 4: Anchor the declaration in the scope the user picked

**Files:**
- Modify: `.../utils/refactor/ExtractionPlan.kt:21-31` (`AnchorForm.ExistingBlock`)
- Modify: `.../utils/refactor/ScopeChain.kt:108-116` (`frameFor`'s block branch), plus a new `contentSpanOf`
- Modify: `.../utils/refactor/ExtractVariableEdit.kt:46-78` (`buildExtractVariableRewrite`, `existingBlockRewrite`)
- Modify: `docs/features/kotlin-extract-variable.md` (R5 anchor-point paragraph, acceptance criteria)
- Test: `.../utils/refactor/ExtractVariableEditTest.kt`, `.../utils/refactor/ExtractVariablePlanEndToEndTest.kt`, `lsp/kotlin/src/test/java/com/itsaky/androidide/lsp/kotlin/refactor/ui/ExtractVariableViewModelTest.kt`

**Interfaces:**
- Consumes: `AnchorForm` from Task 3 (unchanged by this task); rung labels from Task 2.
- Produces: `AnchorForm.ExistingBlock(contentSpan: TextSpan, statementSpans: List<TextSpan>)` - `contentSpan` is the region *inside* the block's braces, `statementSpans` are the block's direct child statements, ascending. Task 5 consumes `contentSpan`.

**Why:** `existingBlockRewrite` inserts before the line of the first *occurrence*, so every block rung produces byte-identical output and the sheet's `Declare in` choice does nothing. R5 defines the anchor point as the first statement *within the anchor scope* that contains a replaced occurrence, which needs that scope's statement list in the plan.

- [ ] **Step 1: Write the failing rewrite tests for both rungs**

Append to `ExtractVariableEditTest`:

```kotlin
	@Test
	fun `the inner rung declares inside the if block`() {
		val text =
			"fun f(flag: Boolean, a: Int, b: Int): Int {\n" +
				"\tif (flag) {\n" +
				"\t\treturn a + b * 2\n" +
				"\t}\n" +
				"\treturn 0\n" +
				"}"
		val candidate = spanOf(text, "a + b * 2")
		val form =
			AnchorForm.ExistingBlock(
				contentSpan = spanOf(text, "\n\t\treturn a + b * 2\n\t"),
				statementSpans = listOf(spanOf(text, "return a + b * 2")),
			)

		val result = rewrite(text, candidate, form, listOf(candidate), "total", replaceAll = false)!!

		assertEquals(
			"fun f(flag: Boolean, a: Int, b: Int): Int {\n" +
				"\tif (flag) {\n" +
				"\t\tval total = a + b * 2\n" +
				"\t\treturn total\n" +
				"\t}\n" +
				"\treturn 0\n" +
				"}",
			apply(text, result),
		)
	}

	@Test
	fun `the outer rung declares above the enclosing statement`() {
		val text =
			"fun f(flag: Boolean, a: Int, b: Int): Int {\n" +
				"\tif (flag) {\n" +
				"\t\treturn a + b * 2\n" +
				"\t}\n" +
				"\treturn 0\n" +
				"}"
		val candidate = spanOf(text, "a + b * 2")
		// The function block's rung: its statements are the whole `if` and the trailing `return 0`.
		val form =
			AnchorForm.ExistingBlock(
				contentSpan = TextSpan(text.indexOf('{') + 1, text.lastIndexOf('}')),
				statementSpans =
					listOf(
						spanOf(text, "if (flag) {\n\t\treturn a + b * 2\n\t}"),
						spanOf(text, "return 0"),
					),
			)

		val result = rewrite(text, candidate, form, listOf(candidate), "total", replaceAll = false)!!

		assertEquals(
			"fun f(flag: Boolean, a: Int, b: Int): Int {\n" +
				"\tval total = a + b * 2\n" +
				"\tif (flag) {\n" +
				"\t\treturn total\n" +
				"\t}\n" +
				"\treturn 0\n" +
				"}",
			apply(text, result),
		)
	}
```

- [ ] **Step 2: Run them and watch them fail**

```bash
flox activate -d flox/local -- ./gradlew :lsp:kotlin:testV7DebugUnitTest \
  --tests "com.itsaky.androidide.lsp.kotlin.utils.refactor.ExtractVariableEditTest"
```

Expected: compilation failure - `ExistingBlock` is an object and takes no arguments.

- [ ] **Step 3: Give `ExistingBlock` its data**

In `ExtractionPlan.kt`, replace the `ExistingBlock` declaration and its KDoc:

```kotlin
	/**
	 * The scope already has a `{ ... }` body (function body, `if` block, lambda body, ...), so the
	 * declaration is a new statement line inside it.
	 *
	 * [statementSpans] are the block's direct child statements, ascending. The anchor point is the
	 * first of them containing the first served occurrence -- which is what makes an outer rung differ
	 * from an inner one. Anchoring on the occurrence's own line instead would make every rung of a
	 * chain produce the same edit.
	 *
	 * [contentSpan] is the region *inside* the braces. It tells a block written on one line
	 * (`items.map { it.length + 1 }`) from a multi-line one, where inserting at the statement's line
	 * start would put the declaration outside the braces.
	 */
	data class ExistingBlock(
		val contentSpan: TextSpan,
		val statementSpans: List<TextSpan>,
	) : AnchorForm
```

- [ ] **Step 4: Fill the fields in the scope chain**

In `ScopeChain.kt`, replace the `parent is KtBlockExpression` branch of `frameFor`:

```kotlin
	if (parent is KtBlockExpression) {
		val lineStart = lineStartOffset(text, inner.textRange.startOffset)
		return ScopeFrame(
			label = blockLabel(parent),
			scopeElement = parent,
			searchRange = parent.textRange.let { TextSpan(it.startOffset, it.endOffset) },
			statementSpan = TextSpan(lineStart, inner.textRange.endOffset),
			anchorForm =
				AnchorForm.ExistingBlock(
					contentSpan = contentSpanOf(parent),
					statementSpans =
						parent.statements.map { TextSpan(it.textRange.startOffset, it.textRange.endOffset) },
				),
		)
	}
```

and add, next to `lineStartOffset`:

```kotlin
/**
 * The region inside a block's braces.
 *
 * A function, `if` or loop body owns its braces, so they are trimmed off. A lambda body block does not
 * -- the braces and any `param ->` header belong to the enclosing function literal -- so its own range
 * already *is* the content, which is what keeps the header on the brace line when the block is
 * expanded. Deriving this from the block's text rather than from brace PSI keeps one code path for
 * both shapes.
 */
internal fun contentSpanOf(block: KtBlockExpression): TextSpan {
	val range = block.textRange
	val text = block.text
	return if (text.length >= 2 && text.startsWith("{") && text.endsWith("}")) {
		TextSpan(range.startOffset + 1, range.endOffset - 1)
	} else {
		TextSpan(range.startOffset, range.endOffset)
	}
}
```

- [ ] **Step 5: Anchor the rewrite on the chosen scope's statement**

In `ExtractVariableEdit.kt`, change the `ExistingBlock` dispatch line in `buildExtractVariableRewrite`:

```kotlin
		is AnchorForm.ExistingBlock -> existingBlockRewrite(fileText, form, targets, declaration, name)
```

and replace `existingBlockRewrite`:

```kotlin
/**
 * Inserts the declaration as its own line before the anchor statement, and rewrites everything from
 * there through the last occurrence.
 *
 * The anchor is the statement *of this scope* that holds the first served occurrence, so picking an
 * outer rung hoists the declaration above the enclosing statement rather than leaving it where the
 * inner rung would have put it. The rewritten span starts at that statement's line start so the
 * declaration lands on a line of its own at the right indentation, and ends at the last occurrence so
 * untouched trailing code is left alone.
 *
 * Null when no statement of the scope contains the occurrence, which would mean the plan and the text
 * disagree; the caller reports that rather than guessing.
 */
private fun existingBlockRewrite(
	fileText: String,
	form: AnchorForm.ExistingBlock,
	targets: List<TextSpan>,
	declaration: String,
	name: String,
): RewriteSpan? {
	val first = targets.first()
	val last = targets.last()
	val anchor = form.statementSpans.firstOrNull { it.start <= first.start && first.end <= it.end } ?: return null
	val lineStart = lineStartOffset(fileText, anchor.start)
	val indent = leadingIndentAt(fileText, anchor.start)
	val newline = detectNewline(fileText)

	val span = TextSpan(lineStart, last.end)
	val body = replaceOccurrences(fileText, span, targets, name)
	return RewriteSpan(span = span, newText = indent + declaration + newline + body)
}
```

- [ ] **Step 6: Update the existing `ExistingBlock` fixtures**

In `ExtractVariableEditTest`, add this helper directly below `allSpansOf`:

```kotlin
	/**
	 * The block rung of a single-block fixture: content is everything between the first `{` and the
	 * last `}`, and [statements] are the block's direct child statements in source order.
	 */
	private fun existingBlock(
		text: String,
		vararg statements: String,
	) = AnchorForm.ExistingBlock(
		contentSpan = TextSpan(text.indexOf('{') + 1, text.lastIndexOf('}')),
		statementSpans = statements.map { spanOf(text, it) },
	)
```

Then replace each `AnchorForm.ExistingBlock` usage:

- `inserts the declaration above the statement and replaces the selected occurrence`:

```kotlin
		val result =
			rewrite(
				text,
				candidate,
				existingBlock(text, "println(items.size * 2)"),
				listOf(candidate),
				"size",
				replaceAll = false,
			)!!
```

- `replace-all rewrites every occurrence and anchors above the first`:

```kotlin
		val result =
			rewrite(
				text,
				candidate,
				existingBlock(text, "println(items.size * 2)", "log(items.size * 2)", "use(items.size * 2)"),
				occurrences,
				"size",
				replaceAll = true,
			)!!
```

- `replace-all off leaves the other occurrences alone`:

```kotlin
		val result =
			rewrite(
				text,
				occurrences[0],
				existingBlock(text, "println(items.size * 2)", "log(items.size * 2)"),
				occurrences,
				"size",
				replaceAll = false,
			)!!
```

- `matches the file's space indentation rather than assuming tabs`, `keeps CRLF line endings when the file uses them` and `deeper indentation is preserved` (each has one statement):

```kotlin
		val result =
			rewrite(
				text,
				candidate,
				existingBlock(text, "println(items.size * 2)"),
				listOf(candidate),
				"size",
				replaceAll = false,
			)!!
```

- `null when there is nothing to replace` and `null when an occurrence lies outside the file` (text is `"fun f() {}"`, so the block is empty):

```kotlin
					scope = ScopeOption("scope", AnchorForm.ExistingBlock(TextSpan(9, 9), emptyList()), emptyList()),
```

```kotlin
					scope =
						ScopeOption(
							"scope",
							AnchorForm.ExistingBlock(TextSpan(9, 9), emptyList()),
							listOf(TextSpan(0, text.length + 5)),
						),
```

In `ExtractVariableViewModelTest`, replace the `anchorForm` line of the `scope` helper:

```kotlin
		anchorForm = AnchorForm.ExistingBlock(contentSpan = TextSpan(0, 100), statementSpans = emptyList()),
```

- [ ] **Step 7: Run the rewrite and view-model tests and watch them pass**

```bash
flox activate -d flox/local -- ./gradlew :lsp:kotlin:testV7DebugUnitTest \
  --tests "com.itsaky.androidide.lsp.kotlin.utils.refactor.ExtractVariableEditTest" \
  --tests "com.itsaky.androidide.lsp.kotlin.refactor.ui.ExtractVariableViewModelTest"
```

Expected: PASS in both classes.

- [ ] **Step 8: Write the failing end-to-end test for the outer rung**

Append to `ExtractVariablePlanEndToEndTest`:

```kotlin
	@Test
	fun `picking the outer rung hoists the declaration above the enclosing statement`() {
		val content =
			"""
			package p
			fun demo(flag: Boolean, a: Int, b: Int): Int {
				if (flag) {
					return a + b * 2
				}
				return 0
			}
			""".trimIndent()

		val target = "a + b * 2"
		val result = plan(content, content.indexOf(target), content.indexOf(target) + target.length)
		val candidate = result.candidates.first()
		assertEquals(listOf("if block", "fun demo"), candidate.scopes.map { it.label })

		val rewrite =
			buildExtractVariableRewrite(
				fileText = result.fileText,
				candidateSpan = candidate.span,
				scope = candidate.scopes[1],
				name = "total",
				replaceAll = false,
			)!!

		assertEquals(
			"package p\n" +
				"fun demo(flag: Boolean, a: Int, b: Int): Int {\n" +
				"\tval total = a + b * 2\n" +
				"\tif (flag) {\n" +
				"\t\treturn total\n" +
				"\t}\n" +
				"\treturn 0\n" +
				"}",
			apply(content, rewrite),
		)
	}
```

- [ ] **Step 9: Run the whole module's tests and watch them pass**

```bash
flox activate -d flox/local -- ./gradlew :lsp:kotlin:testV7DebugUnitTest
```

Expected: PASS for the whole module. If `picking the outer rung ...` fails on the *inner* rung's output instead, the plan is handing both rungs the same statement list - check `contentSpanOf` and `parent.statements` in `frameFor`.

- [ ] **Step 10: Update the feature doc**

In R5, replace the anchor-point sentence in the Language section's `Anchor point` entry with:

```markdown
The exact insertion offset - the start of the line holding the first statement *within the anchor
scope* that contains a replaced occurrence. Recorded per rung in the plan (`ExistingBlock`'s
`statementSpans`), because it is the only thing that makes an outer rung differ from an inner one.
```

In R9, after the first paragraph, add:

```markdown
The span is anchored on the chosen rung's statement, not on the occurrence: for an outer rung the
declaration goes above the whole enclosing statement, at that statement's indentation.
```

Add an acceptance criterion after 9:

```markdown
9a. With a candidate inside a braced `if` inside a function, picking `fun name` in `Declare in` puts the declaration above the `if`, and picking `if block` puts it inside the branch.
```

- [ ] **Step 11: Format and commit**

```bash
flox activate -d flox/local -- ./gradlew spotlessApply
git add lsp/kotlin/src/main/java/com/itsaky/androidide/lsp/kotlin/utils/refactor/ExtractionPlan.kt \
        lsp/kotlin/src/main/java/com/itsaky/androidide/lsp/kotlin/utils/refactor/ScopeChain.kt \
        lsp/kotlin/src/main/java/com/itsaky/androidide/lsp/kotlin/utils/refactor/ExtractVariableEdit.kt \
        lsp/kotlin/src/test/java/com/itsaky/androidide/lsp/kotlin/utils/refactor/ExtractVariableEditTest.kt \
        lsp/kotlin/src/test/java/com/itsaky/androidide/lsp/kotlin/utils/refactor/ExtractVariablePlanEndToEndTest.kt \
        lsp/kotlin/src/test/java/com/itsaky/androidide/lsp/kotlin/refactor/ui/ExtractVariableViewModelTest.kt \
        docs/features/kotlin-extract-variable.md
git commit -m "ADFA-4826: Anchor the declaration in the scope the user picked"
```

---

### Task 5: Expand a block written on one line

**Files:**
- Modify: `.../utils/refactor/ExtractVariableEdit.kt` (`existingBlockRewrite`, plus a new `oneLineBlockRewrite`)
- Modify: `docs/features/kotlin-extract-variable.md` (R9, acceptance criteria)
- Test: `.../utils/refactor/ExtractVariableEditTest.kt`, `.../utils/refactor/ExtractVariablePlanEndToEndTest.kt`

**Interfaces:**
- Consumes: `AnchorForm.ExistingBlock.contentSpan` from Task 4.
- Produces: nothing new; `buildExtractVariableRewrite` keeps its signature.

**Why:** when the anchor statement shares its line with the block's `{`, inserting at the line start puts the declaration *outside* the block: `return items.map { it.length + 1 }` becomes a `val` above the `return` with an unresolved `it`, and `fun f(n: Int): Int { return n * 2 }` puts the `val` above the function signature. Both are uncompilable.

- [ ] **Step 1: Write the failing rewrite tests**

Append to `ExtractVariableEditTest`:

```kotlin
	@Test
	fun `expands a one-line lambda so the declaration lands inside the braces`() {
		val text = "fun f(items: List<String>): List<Int> {\n\treturn items.map { it.length + 1 }\n}"
		val candidate = spanOf(text, "it.length + 1")
		val form =
			AnchorForm.ExistingBlock(
				contentSpan = spanOf(text, " it.length + 1 "),
				statementSpans = listOf(candidate),
			)

		val result = rewrite(text, candidate, form, listOf(candidate), "length", replaceAll = false)!!

		assertEquals(
			"fun f(items: List<String>): List<Int> {\n" +
				"\treturn items.map {\n" +
				"\t\tval length = it.length + 1\n" +
				"\t\tlength\n" +
				"\t}\n" +
				"}",
			apply(text, result),
		)
	}

	@Test
	fun `expanding a one-line lambda keeps its parameter header on the brace line`() {
		val text = "fun f(items: List<String>): List<Int> {\n\treturn items.map { item -> item.length + 1 }\n}"
		val candidate = spanOf(text, "item.length + 1")
		// A lambda body block excludes the `item ->` header, so the header is outside the content span.
		val form =
			AnchorForm.ExistingBlock(
				contentSpan = spanOf(text, " item.length + 1 "),
				statementSpans = listOf(candidate),
			)

		val result = rewrite(text, candidate, form, listOf(candidate), "length", replaceAll = false)!!

		assertEquals(
			"fun f(items: List<String>): List<Int> {\n" +
				"\treturn items.map { item ->\n" +
				"\t\tval length = item.length + 1\n" +
				"\t\tlength\n" +
				"\t}\n" +
				"}",
			apply(text, result),
		)
	}

	@Test
	fun `expands a one-line function body`() {
		val text = "fun f(n: Int): Int { return n * 2 }"
		val candidate = spanOf(text, "n * 2")
		val form =
			AnchorForm.ExistingBlock(
				contentSpan = TextSpan(text.indexOf('{') + 1, text.lastIndexOf('}')),
				statementSpans = listOf(spanOf(text, "return n * 2")),
			)

		val result = rewrite(text, candidate, form, listOf(candidate), "doubled", replaceAll = false)!!

		assertEquals(
			"fun f(n: Int): Int {\n" +
				"\tval doubled = n * 2\n" +
				"\treturn doubled\n" +
				"}",
			apply(text, result),
		)
	}
```

- [ ] **Step 2: Run them and watch them fail**

```bash
flox activate -d flox/local -- ./gradlew :lsp:kotlin:testV7DebugUnitTest \
  --tests "com.itsaky.androidide.lsp.kotlin.utils.refactor.ExtractVariableEditTest"
```

Expected: all three FAIL, each with the declaration emitted on its own line *before* `return`.

- [ ] **Step 3: Branch to an expansion when the statement shares its line with the brace**

In `ExtractVariableEdit.kt`, insert these four lines in `existingBlockRewrite` directly **after** its existing `val lineStart = lineStartOffset(fileText, anchor.start)` line and before `val indent = ...`:

```kotlin
	// The statement shares its line with the block's opening brace (a one-line lambda or body). The
	// line start is then *outside* the block, so the declaration has to go inside the braces instead.
	if (lineStart < form.contentSpan.start) {
		return oneLineBlockRewrite(fileText, form, targets, declaration, name)
	}
```

Add below the function:

```kotlin
/**
 * Puts the declaration inside a block written on one line, moving the block's content and its closing
 * brace onto their own lines.
 *
 * Only the content between the braces is rewritten: the braces, and a lambda's `param ->` header,
 * stay exactly where they are, so the expansion cannot disturb the call around it.
 */
private fun oneLineBlockRewrite(
	fileText: String,
	form: AnchorForm.ExistingBlock,
	targets: List<TextSpan>,
	declaration: String,
	name: String,
): RewriteSpan {
	val content = form.contentSpan
	val newline = detectNewline(fileText)
	val indent = leadingIndentAt(fileText, content.start)
	val innerIndent = indent + detectIndentUnit(fileText)
	val body = replaceOccurrences(fileText, content, targets, name).trim()

	val newText =
		buildString {
			append(newline)
			append(innerIndent).append(declaration).append(newline)
			append(innerIndent).append(body).append(newline)
			append(indent)
		}
	return RewriteSpan(span = content, newText = newText)
}
```

- [ ] **Step 4: Run the rewrite tests and watch them pass**

```bash
flox activate -d flox/local -- ./gradlew :lsp:kotlin:testV7DebugUnitTest \
  --tests "com.itsaky.androidide.lsp.kotlin.utils.refactor.ExtractVariableEditTest"
```

Expected: PASS, all tests in the class - the six multi-line `ExistingBlock` tests included, since their statement lines start after the brace.

- [ ] **Step 5: Write the failing end-to-end test**

Append to `ExtractVariablePlanEndToEndTest`:

```kotlin
	@Test
	fun `extracting from a one-line lambda stays inside the lambda`() {
		val content =
			"""
			package p
			fun demo(items: List<String>): List<Int> {
				return items.map { it.length + 1 }
			}
			""".trimIndent()

		val target = "it.length + 1"
		val result = plan(content, content.indexOf(target), content.indexOf(target) + target.length)
		val candidate = result.candidates.first()
		// `it` is lambda-scoped, so the lambda is the ceiling: there is no outer rung to choose.
		assertEquals(listOf("lambda"), candidate.scopes.map { it.label })

		val rewrite =
			buildExtractVariableRewrite(
				fileText = result.fileText,
				candidateSpan = candidate.span,
				scope = candidate.scopes.first(),
				name = "length",
				replaceAll = false,
			)!!

		assertEquals(
			"package p\n" +
				"fun demo(items: List<String>): List<Int> {\n" +
				"\treturn items.map {\n" +
				"\t\tval length = it.length + 1\n" +
				"\t\tlength\n" +
				"\t}\n" +
				"}",
			apply(content, rewrite),
		)
	}
```

- [ ] **Step 6: Run the whole module's tests and watch them pass**

```bash
flox activate -d flox/local -- ./gradlew :lsp:kotlin:testV7DebugUnitTest
```

Expected: PASS for the whole module.

- [ ] **Step 7: Update the feature doc**

In R9, after the paragraph added in Task 4, add:

```markdown
A block written on one line -- `items.map { it.length + 1 }`, `fun f(n: Int): Int { return n * 2 }`,
a one-line `if` body -- is expanded instead: the content between the braces moves onto its own line
with the declaration above it and the closing brace below. Anchoring on the statement's line start
there would place the declaration *before* the `{`, outside the scope the value belongs to, which
leaves a lambda's `it` unresolved. The braces themselves and a lambda's `param ->` header are left
where they are.
```

Add an acceptance criterion after 9a:

```markdown
9b. Extracting from `return items.map { it.length + 1 }` puts the declaration inside the lambda and expands the block over three lines; the same holds for a one-line function body.
```

- [ ] **Step 8: Format and commit**

```bash
flox activate -d flox/local -- ./gradlew spotlessApply
git add lsp/kotlin/src/main/java/com/itsaky/androidide/lsp/kotlin/utils/refactor/ExtractVariableEdit.kt \
        lsp/kotlin/src/test/java/com/itsaky/androidide/lsp/kotlin/utils/refactor/ExtractVariableEditTest.kt \
        lsp/kotlin/src/test/java/com/itsaky/androidide/lsp/kotlin/utils/refactor/ExtractVariablePlanEndToEndTest.kt \
        docs/features/kotlin-extract-variable.md
git commit -m "ADFA-4826: Expand a block written on one line"
```

---

### Task 6: Restack ADFA-5080 onto the fixes

**Files:**
- Modify: `lsp/kotlin/src/main/java/com/itsaky/androidide/lsp/kotlin/utils/refactor/MethodSignature.kt:404-470` (on branch `feat/ADFA-5080-extract-method`)

**Interfaces:**
- Consumes: `renderedTypeTextOrNull`, `isUnrenderableTypeText` from Task 3's `TypeText.kt`.
- Produces: a rebased, pushed stack; no API change.

**Why:** the fixes are three commits below #1655 in the stack, and `MethodSignature.kt` now carries private copies of the renderer that `TypeText.kt` owns.

- [ ] **Step 1: Verify the branch is green and push it**

```bash
flox activate -d flox/local -- ./gradlew :lsp:kotlin:testV7DebugUnitTest :lsp:kotlin:compileV8DebugKotlin
git log --oneline stage..HEAD | head -8
git push --force-with-lease origin feat/ADFA-4826-extract-variable
```

Expected: tests pass, five new `ADFA-4826:` commits on top of `617ed6f39`, push accepted.

- [ ] **Step 2: Rebase the stack**

```bash
gh stack rebase
gh stack view
```

Expected: `feat/ADFA-5080-extract-method` replays onto the new tip with no conflicts (its commits touch different files). If a conflict does appear in `ExtractionPlan.kt`, keep both changes: `ExistingBlock`'s new fields *and* whatever 5080 added.

- [ ] **Step 3: Point `MethodSignature` at the shared renderer**

```bash
git checkout feat/ADFA-5080-extract-method
```

In `MethodSignature.kt`, delete `isUnrenderable`, `SIGNATURE_TYPE_RENDERER` and `renderTypeText` together with their KDoc (they are the block from the `/** A type that cannot be written out as source ... */` comment through the `renderTypeText` body), and replace the four helpers that used them with:

```kotlin
private fun KaSession.renderedSymbolType(symbol: KaCallableSymbol): String? = renderedTypeTextOrNull(symbol.returnType)

private fun KaSession.renderedTypeOrNull(expression: KtExpression): String? =
	runCatching { expression.expressionType }.getOrNull()?.let { renderedTypeTextOrNull(it) }

private fun KaSession.renderedDeclarationType(property: KtProperty): String? =
	runCatching { (property.symbol as? KaCallableSymbol)?.returnType }.getOrNull()?.let { renderedTypeTextOrNull(it) }

private fun KaSession.enclosingReturnType(enclosing: KtDeclaration): String? =
	runCatching { (enclosing.symbol as? KaCallableSymbol)?.returnType }.getOrNull()?.let { renderedTypeTextOrNull(it) }
```

`renderedTypeTextOrNull` already wraps its own rendering in `runCatching` and applies the unrenderable filter, so the `?.takeUnless(::isUnrenderable)` suffixes go away with it.

Then find the remaining `renderTypeText(` call in `usedTypeOf` (around line 404):

```bash
grep -n "renderTypeText\|isUnrenderable\|SIGNATURE_TYPE_RENDERER" \
  lsp/kotlin/src/main/java/com/itsaky/androidide/lsp/kotlin/utils/refactor/MethodSignature.kt
```

and replace each surviving `renderTypeText(x)` with `renderedTypeTextOrNull(x)`, keeping the `?: return UsedType.Absent` guard exactly as it is. Re-run that grep until it prints nothing.

Finally drop the imports that are now unused - `KaTypeRendererForSource`, and `KaFlexibleType` / `renderName` if nothing else in the file references them (check with `grep -n "KaFlexibleType\|renderName" <file>`). ktlint fails the build on an unused import, so this is not optional.

- [ ] **Step 4: Verify and commit on the 5080 branch**

```bash
flox activate -d flox/local -- ./gradlew :lsp:kotlin:testV7DebugUnitTest :lsp:kotlin:compileV8DebugKotlin
flox activate -d flox/local -- ./gradlew spotlessApply
git add lsp/kotlin/src/main/java/com/itsaky/androidide/lsp/kotlin/utils/refactor/MethodSignature.kt
git commit -m "ADFA-5080: Use the shared type-text helpers"
```

Expected: tests pass on the rebased 5080 branch.

- [ ] **Step 5: Push the stack**

```bash
gh stack push
gh stack view
```

Expected: #1654 and #1655 both updated, bases unchanged (`#1653` <- `#1654` <- `#1655`).

---

### Task 7: Probe extract method for the same class of defect

**Files:**
- Create (temporarily): `lsp/kotlin/src/test/java/com/itsaky/androidide/lsp/kotlin/utils/refactor/ScratchOneLineAnchorTest.kt` - deleted again in the last step, never committed.

**Interfaces:**
- Consumes: `buildExtractMethodRewrites`, `ExtractMethodCandidate` from the 5080 branch.
- Produces: a Jira comment on ADFA-5080. No code change.

**Why:** `MethodSignature` sets `insertOffset = anchor.textRange.startOffset` for a local-function target, which has the same line-sharing exposure Task 5 fixed for extract variable: if the anchor declaration shares its line with other code, a multi-line function is inserted mid-line.

- [ ] **Step 1: Write the probe**

On `feat/ADFA-5080-extract-method`, create `ScratchOneLineAnchorTest.kt`:

```kotlin
package com.itsaky.androidide.lsp.kotlin.utils.refactor

import com.itsaky.androidide.lsp.kotlin.fixtures.KtLspTest
import org.junit.Test

/** Scratch probe: what does extract method emit when the anchor shares its line with other code? */
class ScratchOneLineAnchorTest : KtLspTest() {
	@Test
	fun probe() {
		val cases =
			listOf(
				"package p\nfun outer(n: Int): Int { return n * 2 }",
				"package p\nfun outer(n: Int): Int {\n\tfun inner(): Int { return n * 2 }\n\treturn inner()\n}",
				"package p\nfun outer(items: List<String>) { items.forEach { println(it.length + 1) } }",
			)
		cases.forEachIndexed { index, content ->
			createSourceFile("Main.kt", content)
			val path = env.sourceRoots.first().resolve("Main.kt")
			val target = "n * 2".takeIf { content.contains("n * 2") } ?: "it.length + 1"
			val start = content.indexOf(target)
			println("### case $index")
			println(
				buildExtractMethodPlan(
					env = env,
					nioPath = path,
					selectionStart = start,
					selectionEnd = start + target.length,
					documentVersion = 1,
					cancelChecker = noopCancelChecker(),
				),
			)
		}
	}
}
```

Before running, check the real entry point and its parameter names:

```bash
grep -n "^internal fun buildExtractMethodPlan\|^fun buildExtractMethodPlan" -A 10 \
  lsp/kotlin/src/main/java/com/itsaky/androidide/lsp/kotlin/utils/refactor/ExtractMethodPlanner.kt
```

Adjust the call to match exactly what that signature says.

- [ ] **Step 2: Run the probe and read the output**

```bash
flox activate -d flox/local -- ./gradlew :lsp:kotlin:testV7DebugUnitTest \
  --tests "com.itsaky.androidide.lsp.kotlin.utils.refactor.ScratchOneLineAnchorTest" -i 2>&1 \
  | grep -vE "DEBUG|Took org" | sed -n '/### case 0/,$p' | head -60
```

Expected: for each case either a refusal (fine - extract method declines) or a candidate whose `insertOffset` sits mid-line. Apply the rewrites by hand in the output if needed to judge whether the emitted text would compile.

- [ ] **Step 3: Delete the probe**

```bash
rm lsp/kotlin/src/test/java/com/itsaky/androidide/lsp/kotlin/utils/refactor/ScratchOneLineAnchorTest.kt
git status --short
```

Expected: no changes to tracked files.

- [ ] **Step 4: Report the finding on ADFA-5080**

Only if the probe showed a real defect. Write the comment body to the scratchpad first, then:

```bash
jira issue comment add ADFA-5080 --template /tmp/claude-1000/adfa-5080-probe.md
```

Comment body, with the bracketed parts filled in from the probe output:

```markdown
Probe while fixing the extract-variable defects on ADFA-4826 (PR #1654): extract method's insertion
anchor has the same line-sharing exposure.

`MethodSignature` sets `insertOffset = anchor.textRange.startOffset` for a local-function target, and
`buildExtractMethodRewrites` emits the declaration at that offset. When the anchor declaration shares
its line with other code, the multi-line function lands mid-line.

Case: `[the source that failed]`
Emitted: `[the text the rewrite produces]`
Compiles: no.

Not fixed here - ADFA-4826's fix is confined to the extract-variable rewrite (`ExtractVariableEdit`),
which now expands a one-line block instead of anchoring outside it. Same shape of fix would apply.
```

If the probe found nothing, skip the comment and record "extract method declines / anchors soundly in all three shapes" in the execution notes instead.

---

### Task 8: Hand ADFA-4826 to QA

**Files:**
- Modify: `/tmp/claude-1000/-var-mnt-data-dev-work-adfa-cogo-code-on-the-go--claude-worktrees-ADFA-4826/93ae2f82-68a1-4eca-8a27-23fbfdc5f395/scratchpad/ADFA-4826-steps-to-qa.md` (two expectation edits)

**Interfaces:**
- Consumes: the shipped behaviour from Tasks 1-5.
- Produces: ADFA-4826's `customfield_10250`, a Jira comment, and a status transition.

**Why:** the QA draft was written against correct behaviour, so two of its cases describe what only Tasks 3 and 5 make true, and QA should not receive steps before the build can pass them.

- [ ] **Step 1: Comment the findings on the ticket and move it out of QA**

Write this to `/tmp/claude-1000/adfa-4826-findings.md`:

```markdown
Five defects found while drafting the Steps to QA, each reproduced through the module's own test
fixture. Moving back to In Progress; all five are fixed on PR #1654 with tests.

1. `Declare in` was ignored for block anchors. The rewrite anchored on the first *occurrence's* line,
   so every rung of the scope chain produced a byte-identical edit. Now anchored on the statement of
   the chosen scope, per R5.
2. A block written on one line put the declaration outside the braces. `return items.map { it.length
   + 1 }` produced a `val` above the `return` with an unresolved `it`; `fun f(n: Int): Int { return n
   * 2 }` put it above the signature. The block is now expanded over three lines instead.
3. An expression body with an inferred return type converted without writing the type, so
   `fun area(r: Int) = r * r` became a Unit-returning function with `return squared` - uncompilable.
   The type is now written into the signature, shortened only where the short name already resolves.
4. The `{ ... }` lambda expression was offered as a candidate (only the literal inside it was
   excluded), which contradicts R2 and yields `val v = { it.length + 1 }`. Now excluded.
5. A braced `if` branch's rung was labelled `block` rather than `if block`, because the label lookup
   saw the control-structure container node. Fixed.

Steps to QA follows once the on-device pass over the three affected cases is done.
```

Then:

```bash
jira issue comment add ADFA-4826 --template /tmp/claude-1000/adfa-4826-findings.md
jira issue move ADFA-4826 "In Progress"
jira issue view ADFA-4826 --plain | head -3
```

Expected: status reads `In Progress`. If `jira issue move` rejects the target, list what is reachable with `jira issue move ADFA-4826` (no argument prints the available transitions) and pick the In Progress one - a subtask's workflow does not always allow every hop directly.

- [ ] **Step 2: Build and install the debug APK**

```bash
adb devices -l | grep -v offline
flox activate -d flox/local -- ./gradlew :app:assembleV8Debug --parallel --max-workers=6
```

Expected: `BUILD SUCCESSFUL` and at least one arm device or arm-translation emulator listed. The app is arm-only, so an x86_64 emulator cannot run it - if only that is available, stop here and report that the device pass needs hardware.

- [ ] **Step 3: Run the three device cases**

Install the APK, open a Kotlin file containing the setup from the QA draft, and run:

- **QA-12** - `fun squaredInferred(r: Int) = r * r`: select `r * r`, name it `squared`, extract. Expected `fun squaredInferred(r: Int): Int {` with `return squared` inside, and no new error underline.
- **QA-14** - `fun nested`: select `a + b * 2`, confirm `Declare in` lists `if block` then `fun nested`, extract once with each rung, and confirm the declaration lands inside the branch and above the `if` respectively.
- **QA-15** - `fun oneLineLambda`: select `it.length + 1` in `return items.map { it.length + 1 }`, extract, and confirm the block expands with the declaration inside the braces and no error underline.

Record what each one actually produced.

- [ ] **Step 4: Correct the two QA expectations**

In `ADFA-4826-steps-to-qa.md`:

- QA-12 step 3's Expected becomes: `the return type is added - fun squaredInferred(r: Int): Int { - and the file compiles.` and its "Fails if" becomes: `the signature is left as fun squaredInferred(r: Int) { with return squared inside, which does not compile.`
- QA-14's Expected for `Declare in` becomes: `Declare in lists two rungs, innermost first: if block, then fun nested.` (the label is no longer a generic `block`).

- [ ] **Step 5: Post the Steps to QA field**

The source is the corrected `ADFA-4826-steps-to-qa.md` from Step 4. Convert it to ADF - `heading` nodes for the section titles, `orderedList`/`bulletList` for the steps, `codeBlock` with `language: "kotlin"` for the fixture and expected-output snippets, `paragraph` elsewhere - and set the field with:

```
mcp__claude_ai_Atlassian_Rovo__editJiraIssue
  cloudId: bb66613e-967d-4549-a8d6-d9166759f2d2
  issueIdOrKey: ADFA-4826
  fields: { "customfield_10250": { "type": "doc", "version": 1, "content": [ ... ] } }
```

A plain string is rejected for this field; it must be an ADF `doc` object. Load the tool schema first with `ToolSearch("select:mcp__claude_ai_Atlassian_Rovo__editJiraIssue")`.

- [ ] **Step 6: Confirm what landed**

```bash
jira issue view ADFA-4826 --raw | python3 -c "import json,sys; print(bool(json.load(sys.stdin)['fields']['customfield_10250']))"
```

Expected: `True`. Then report the device-pass results and the five commits to the user; whether the ticket moves on to Code review is theirs to call.

---

## Notes for whoever executes this

- **Do not** run `:app:assembleV8Debug` between tasks; it is multi-minute. `:lsp:kotlin:testV7DebugUnitTest` is the loop, and the assemble happens once, in Task 8.
- Tasks 4 and 5 both touch `existingBlockRewrite`. Task 4 deliberately leaves the one-line block broken (its line start is unchanged), so do not "fix" it early - Task 5's tests are what pin the expansion down.
- If a test in `ExtractVariablePlanEndToEndTest` reports a type or `needsReturn` that makes no sense, check that the test wrote `Main.kt` and not a uniquely named file: duplicate top-level declarations across files in package `p` break resolution silently.
