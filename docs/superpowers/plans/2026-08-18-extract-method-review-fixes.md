# Extract-Method Review Fixes Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix the four confirmed correctness defects John Andrés Trujillo found in PR #1655 (Kotlin extract method), each with a regression test and the matching feature-doc update.

**Architecture:** Four independent, small changes inside `lsp/kotlin`. Tasks 1-3 each rewrite one private function in `MethodSignature.kt` (the analysis layer that derives a candidate). Task 4 adds one field to `ExtractMethodCandidate`, populates it in `MethodSignature.kt`, and reshapes `reindent` in `ExtractMethodEdit.kt` (the pure-text emission layer) to honour it. Tasks are ordered smallest-blast-radius first so a compile or test failure localises to the task that caused it.

**Tech Stack:** Kotlin, Kotlin K2 Analysis API (2.3.20) + Kotlin PSI, JUnit 4 + Robolectric, Gradle with `v7`/`v8` ABI flavors, Spotless/ktlint.

**Spec:**
- John's review on PR #1655: <https://github.com/appdevforall/CodeOnTheGo/pull/1655> (review id `4928006446`, inline comments `3776099257`, `3776099272`, `3776099279`, `3776099288`)
- `docs/features/kotlin-extract-method.md` - the feature spec these fixes must keep true (R4, R8, R10, R15)
- `docs/adr/0013-refactorings-decline-rather-than-rewrite.md` - "an interactive refactoring moves the user's code; it does not edit the interior of what it moved"

## Global Constraints

- **Indentation: tabs. Line endings: LF.** Enforced by Spotless; ktlint formats Kotlin. The `ratchetFrom = origin/stage` ratchet is file-level, so any touched file is reformatted in full.
- **ASCII only** in code and code comments. No em dashes, no curly quotes, no arrow glyphs.
- **Comments:** no `//` line comments outside function bodies - use `/* ... */` or KDoc. No separator or banner comments. Comment the non-obvious *why* only. No references to this plan, task numbers, or phases in any comment or commit message.
- **Commit subject only**, format `ADFA-5080: <subject>`. No `Co-Authored-By` trailer, no AI-attribution trailers. Never `git add .` - stage the exact paths listed in each task.
- **Never commit this plan file** or any planning/status document. `docs/features/kotlin-extract-method.md` is a checked-in artifact and *is* committed.
- **Branch:** `feat/ADFA-5080-extract-method`, already checked out in the worktree at `/var/mnt/data/dev/work/adfa/cogo/code-on-the-go/.claude/worktrees/ADFA-4826`. Do not branch, rebase or push unless asked.
- **Test task:** `flox activate -d flox/local -- ./gradlew :lsp:kotlin:testV7DebugUnitTest`. There is no flavorless `testDebugUnitTest`. A `local.properties` with `sdk.dir` may be required (git-ignored, safe to create, never commit). Run test tasks in the foreground; they take minutes.
- **No new dependencies.** `@Composable` is exercised by declaring `package androidx.compose.runtime; annotation class Composable` in a test source file, as the existing test already does.

---

## File Structure

**Modified - production:**

- `lsp/kotlin/src/main/java/com/itsaky/androidide/lsp/kotlin/utils/refactor/MethodSignature.kt` (882 lines) - derives one `ExtractMethodCandidate` from a region inside an analysis session. Tasks 1, 2, 3 and part of 4 each change exactly one private function here.
- `lsp/kotlin/src/main/java/com/itsaky/androidide/lsp/kotlin/utils/refactor/ExtractMethodEdit.kt` (95 lines) - turns a candidate into the two `RewriteSpan`s. Task 4 only.
- `lsp/kotlin/src/main/java/com/itsaky/androidide/lsp/kotlin/utils/refactor/ExtractMethodPlan.kt` (187 lines) - the PSI-free data carried from the analysis layer to the sheet and the edit builder. Task 4 adds one field to `ExtractMethodCandidate`.

**Modified - tests:**

- `lsp/kotlin/src/test/java/com/itsaky/androidide/lsp/kotlin/utils/refactor/ExtractMethodPlanEndToEndTest.kt` - analysis-backed, real PSI and resolution. Tasks 1, 2, 3, 4.
- `lsp/kotlin/src/test/java/com/itsaky/androidide/lsp/kotlin/utils/refactor/ExtractMethodEditTest.kt` - pure text, candidates built by hand. Task 4.

**Modified - docs:**

- `docs/features/kotlin-extract-method.md` - R4 target table (Task 1), R10 `@Composable` bullet + acceptance criterion 15 (Task 2), R8 declined list (Task 3), R15 emission paragraph (Task 4), Verification section (Task 6).

No new files. No module, DI, Compose or string-resource changes, so no architecture surface is touched.

---

### Task 1: An anonymous function is never an insertion anchor

**Why:** `enclosingDeclaration` matches `is KtNamedFunction`, and Kotlin PSI represents an anonymous function expression (`fun(v: Int) { }` used as a value) as a `KtNamedFunction` whose `name` is null (see `KtNamedFunction.isAnonymous = name == null && isLocal`). `enclosingExecutableBody` already accepts it, so nothing upstream declines. The anonymous function then becomes both `enclosing` and `anchor`; its parent is a `KtValueArgument` or a `KtProperty`, so `isLocalTarget` is false, `private` is added, and `insertOffset` lands at the anonymous function's own end - inside an argument list or a property initializer. The emitted file does not parse. `ExtractMethodEdit.kt:32` only rejects an `insertOffset` strictly *inside* the region, so it does not catch this.

**Files:**
- Modify: `lsp/kotlin/src/main/java/com/itsaky/androidide/lsp/kotlin/utils/refactor/MethodSignature.kt:223-243` (`enclosingDeclaration` and its KDoc)
- Modify: `docs/features/kotlin-extract-method.md:70-78` (R4 target table)
- Test: `lsp/kotlin/src/test/java/com/itsaky/androidide/lsp/kotlin/utils/refactor/ExtractMethodPlanEndToEndTest.kt`

**Interfaces:**
- Consumes: nothing from earlier tasks.
- Produces: no signature change. `enclosingDeclaration(element: PsiElement): KtDeclaration?` keeps its name, parameters and return type; only which node it returns changes.

- [ ] **Step 1: Write the two failing tests**

Append to `ExtractMethodPlanEndToEndTest.kt`, inside the class:

```kotlin
	@Test
	fun `a region inside an anonymous function argument anchors on the enclosing member`() {
		val content =
			"""
			package p
			class C {
				fun demo() {
					register(fun(v: Int) {
						work(v)
					})
				}
				fun register(h: (Int) -> Unit) {}
				fun work(n: Int) {}
			}
			""".trimIndent()

		val candidate = plan(content, content.indexOf("work(v)") + 1).candidates.first { it.label == "work(v)" }

		// The anonymous function is a value, not a declaration a sibling can follow: an insertion at its
		// own end lands before the closing `)` of `register(...)` and the file stops parsing.
		val callEnd = content.indexOf("})") + "})".length
		assertTrue(
			"insertOffset ${candidate.insertOffset} must be past the enclosing call at $callEnd",
			candidate.insertOffset >= callEnd,
		)
		assertEquals("\t", candidate.insertIndent)
		assertEquals(listOf("private"), candidate.modifiers)
		assertEquals(listOf("v" to "kotlin.Int"), candidate.parameters.map { it.name to it.typeText })
	}

	@Test
	fun `a region inside an anonymous function initializer anchors on the enclosing function`() {
		val content =
			"""
			package p
			fun demo() {
				val f = fun(): Int {
					return compute()
				}
				f()
			}
			fun compute(): Int = 1
			""".trimIndent()

		val candidate = plan(content, content.indexOf("compute()") + 1).candidates.first { it.label == "compute()" }

		assertEquals("", candidate.insertIndent)
		assertTrue(
			"insertOffset ${candidate.insertOffset} must be past the property initializer",
			candidate.insertOffset >= content.indexOf("\tf()"),
		)
		assertEquals(listOf("private"), candidate.modifiers)
	}
```

`assertTrue`, `assertEquals` and `Test` are already imported in this file.

- [ ] **Step 2: Run the tests to verify they fail**

```bash
flox activate -d flox/local -- ./gradlew :lsp:kotlin:testV7DebugUnitTest --tests "com.itsaky.androidide.lsp.kotlin.utils.refactor.ExtractMethodPlanEndToEndTest"
```

Expected: both new tests FAIL. The first on `insertOffset ... must be past the enclosing call` (the offset sits one character before the closing `)`); the second on the `insertIndent` assertion (`expected:<> but was:<\t>`).

- [ ] **Step 3: Skip a nameless `KtNamedFunction` and keep walking**

Replace `enclosingDeclaration` and its KDoc in `MethodSignature.kt` with:

```kotlin
/**
 * The named function, accessor, `init` block or constructor whose body holds [element]. Lambdas and
 * anonymous functions are skipped: the new function is a sibling of the enclosing *named* declaration
 * (R4), and their captures become parameters.
 */
private fun enclosingDeclaration(element: PsiElement): KtDeclaration? {
	var current: PsiElement? = element.parent
	while (current != null) {
		when (current) {
			is KtNamedFunction -> {
				// PSI gives an anonymous `fun(...) { }` the same node type as a named function, with a null
				// name. It is a value, not a declaration a sibling can follow: anchoring on it inserts the
				// new function into an argument list or a property initializer, and the file stops parsing.
				if (current.name != null) return current
			}

			is KtPropertyAccessor, is KtAnonymousInitializer, is KtSecondaryConstructor -> {
				return current
			}

			is KtClassOrObject -> {
				return null
			}
		}
		current = current.parent
	}
	return null
}
```

`current.name` smart-casts because `is KtNamedFunction` is now its own branch. Nothing else needs an import.

- [ ] **Step 4: Run the tests to verify they pass**

```bash
flox activate -d flox/local -- ./gradlew :lsp:kotlin:testV7DebugUnitTest --tests "com.itsaky.androidide.lsp.kotlin.utils.refactor.ExtractMethodPlanEndToEndTest"
```

Expected: PASS, the whole class. If a pre-existing test now fails, stop and report it rather than editing the assertion.

- [ ] **Step 5: Add the R4 target-table row**

In `docs/features/kotlin-extract-method.md`, add this row to the R4 table immediately after the existing lambda row (`| a lambda inside either of the above | ... |`):

```markdown
| an anonymous `fun(...) { }` used as a value | still a sibling of the enclosing *named* declaration, exactly as for a lambda; PSI gives it the same node type as a named function, but it is a value and nothing can be inserted after it |
```

- [ ] **Step 6: Commit**

```bash
git add lsp/kotlin/src/main/java/com/itsaky/androidide/lsp/kotlin/utils/refactor/MethodSignature.kt \
        lsp/kotlin/src/test/java/com/itsaky/androidide/lsp/kotlin/utils/refactor/ExtractMethodPlanEndToEndTest.kt \
        docs/features/kotlin-extract-method.md
git commit -m "ADFA-5080: Stop anchoring an extraction on an anonymous function"
```

---

### Task 2: `@Composable` property getters count as Composable use

**Why:** `usesComposable` walks only `KtCallExpression` descendants and resolves them with `successfulFunctionCallOrNull`. `MaterialTheme.colorScheme`, `MaterialTheme.typography` and `LocalDensity.current` are `@Composable @ReadOnlyComposable` *property getters* reached through a `KtDotQualifiedExpression`, not calls. Extracting such a region emits a function with no `@Composable`, which is exactly the compile failure R10 exists to prevent, on an everyday Compose shape.

**Files:**
- Modify: `lsp/kotlin/src/main/java/com/itsaky/androidide/lsp/kotlin/utils/refactor/MethodSignature.kt:825-839` (`usesComposable` and its KDoc), plus two imports
- Modify: `docs/features/kotlin-extract-method.md:122` (R10 `@Composable` bullet) and `:210` (acceptance criterion 15)
- Test: `lsp/kotlin/src/test/java/com/itsaky/androidide/lsp/kotlin/utils/refactor/ExtractMethodPlanEndToEndTest.kt`

**Interfaces:**
- Consumes: nothing from Task 1.
- Produces: `private fun KaAnnotatedSymbol.hasComposableAnnotation(): Boolean` - file-private, used only by `usesComposable`. `usesComposable(elements: List<KtExpression>): Boolean` keeps its signature.

- [ ] **Step 1: Write the failing test and its negative guard**

Append to `ExtractMethodPlanEndToEndTest.kt`, inside the class:

```kotlin
	@Test
	fun `reading a Composable property getter adds the Composable annotation`() {
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
			object Palette {
				val accent: Int
					@Composable get() = 1
			}
			fun use(n: Int) {}
			@Composable fun Demo() {
				use(Palette.accent)
			}
			""".trimIndent()

		val candidate = plan(content, content.indexOf("Palette.accent") + 1).candidates.first { it.label == "Palette.accent" }

		assertEquals(listOf("@Composable"), candidate.annotations)
	}

	@Test
	fun `reading a plain property getter adds no annotation`() {
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
			object Palette {
				val accent: Int
					get() = 1
			}
			fun use(n: Int) {}
			@Composable fun Demo() {
				use(Palette.accent)
			}
			""".trimIndent()

		val candidate = plan(content, content.indexOf("Palette.accent") + 1).candidates.first { it.label == "Palette.accent" }

		assertEquals(emptyList<String>(), candidate.annotations)
	}
```

The second test is not redundant: it rules out the naive fix of treating any `@Composable` anywhere in the file, or on the enclosing function, as a reason to annotate.

- [ ] **Step 2: Run the tests to verify the first fails and the second passes**

```bash
flox activate -d flox/local -- ./gradlew :lsp:kotlin:testV7DebugUnitTest --tests "com.itsaky.androidide.lsp.kotlin.utils.refactor.ExtractMethodPlanEndToEndTest"
```

Expected: `reading a Composable property getter adds the Composable annotation` FAILS with `expected:<[@Composable]> but was:<[]>`. `reading a plain property getter adds no annotation` PASSES already.

- [ ] **Step 3: Resolve simple names to property symbols too**

Add these two imports to `MethodSignature.kt`, each in its existing alphabetical run:

```kotlin
import org.jetbrains.kotlin.analysis.api.symbols.KaPropertySymbol
import org.jetbrains.kotlin.analysis.api.symbols.markers.KaAnnotatedSymbol
```

Replace `usesComposable` and its KDoc with:

```kotlin
/**
 * `@Composable` is added when the region uses one. Not polish: CoGo users write Compose apps on the
 * device, and an extracted composable without the annotation does not compile (R10).
 *
 * Property *getters* count, not only calls. `MaterialTheme.colorScheme` and `LocalDensity.current` are
 * annotated getters reached through a name reference, and they are as common in Compose code as any
 * composable call.
 */
private fun KaSession.usesComposable(elements: List<KtExpression>): Boolean =
	descendantsOf(elements, KtCallExpression::class.java).any { call ->
		runCatching {
			call
				.resolveToCall()
				?.successfulFunctionCallOrNull()
				?.symbol
				?.hasComposableAnnotation()
		}.getOrNull() == true
	} ||
		simpleNamesIn(elements).any { reference ->
			runCatching {
				val property = reference.mainReference?.resolveToSymbols()?.firstOrNull() as? KaPropertySymbol
				property?.hasComposableAnnotation() == true || property?.getter?.hasComposableAnnotation() == true
			}.getOrNull() == true
		}

/** Whether [this] carries `@Composable`. */
private fun KaAnnotatedSymbol.hasComposableAnnotation(): Boolean =
	annotations.any { it.classId?.asFqNameString() == COMPOSABLE_FQ_NAME }
```

The property symbol itself is checked as well as its getter because a use-site-free `@Composable` on a `val` targets whichever of the two the annotation declares.

- [ ] **Step 4: Run the tests to verify both pass**

```bash
flox activate -d flox/local -- ./gradlew :lsp:kotlin:testV7DebugUnitTest --tests "com.itsaky.androidide.lsp.kotlin.utils.refactor.ExtractMethodPlanEndToEndTest"
```

Expected: PASS, the whole class - including the pre-existing `a Composable call adds the Composable annotation`, which must keep passing through the call branch.

- [ ] **Step 5: Update R10 and acceptance criterion 15**

In `docs/features/kotlin-extract-method.md`, replace the `@Composable` bullet under R10:

```markdown
- **`@Composable`** - added when the region uses one: any call resolving to a `@Composable`-annotated function, **or any name reference resolving to a property whose getter is annotated**. The second half is not an edge case - `MaterialTheme.colorScheme` and `LocalDensity.current` are annotated getters, not calls. This is not polish: CoGo users write Compose apps on the device, and an extracted composable without the annotation does not compile.
```

And replace acceptance criterion 15:

```markdown
15. A region calling a `@Composable` function, or reading a `@Composable` property such as `MaterialTheme.colorScheme`, produces a `@Composable` function that compiles.
```

- [ ] **Step 6: Commit**

```bash
git add lsp/kotlin/src/main/java/com/itsaky/androidide/lsp/kotlin/utils/refactor/MethodSignature.kt \
        lsp/kotlin/src/test/java/com/itsaky/androidide/lsp/kotlin/utils/refactor/ExtractMethodPlanEndToEndTest.kt \
        docs/features/kotlin-extract-method.md
git commit -m "ADFA-5080: Detect Composable property getters when annotating"
```

---

### Task 3: A return belonging to a declaration inside the region is not an exit

**Why:** `hasExit` and `isTailReturn` both use `descendantsOf(elements, KtReturnExpression::class.java)`, which collects every `return` in the subtree - including ones inside a local `fun`, an anonymous `fun`, or an anonymous-object override *declared within the region*. Those returns have no label, so `hasExit` returns true and the region is refused as `ExitsRegion` ("The selection jumps out of itself with return, break or continue") even though the jump never leaves the region. `isTailReturn` is skewed the same way through `returns.size != 1`. The `object : Listener { override fun onX() { ... return ... } }` shape makes this common in Android code, and the message describes something the user did not write.

The nested-owner search must skip `KtFunctionLiteral`: a lambda is transparent to an unlabelled `return`, which targets the enclosing function declaration, so a non-local return out of a lambda inside the region is still a genuine exit and must stay refused. An anonymous `fun` is *not* transparent, and is not a literal, so the same walk handles it correctly. `hasLoopExit` already checks `inRegion(loop, span)` and needs no change.

**Files:**
- Modify: `lsp/kotlin/src/main/java/com/itsaky/androidide/lsp/kotlin/utils/refactor/MethodSignature.kt:511-538` (`isTailReturn`, `hasExit`, plus one new private helper), plus one import
- Modify: `docs/features/kotlin-extract-method.md:110` (R8 declined list)
- Test: `lsp/kotlin/src/test/java/com/itsaky/androidide/lsp/kotlin/utils/refactor/ExtractMethodPlanEndToEndTest.kt`

**Interfaces:**
- Consumes: nothing from Tasks 1-2.
- Produces: `private fun returnTargetInRegion(returnExpression: KtReturnExpression, span: TextSpan): Boolean` - file-private, used by both `isTailReturn` and `hasExit`. Neither of those changes signature.

- [ ] **Step 1: Write the four failing/guard tests**

Append to `ExtractMethodPlanEndToEndTest.kt`, inside the class:

```kotlin
	@Test
	fun `a return inside a local function declared in the region is not an exit`() {
		val content =
			"""
			package p
			fun demo(a: Int): Int {
				fun helper(): Int {
					return a * 2
				}
				val x = helper()
				return x
			}
			""".trimIndent()
		val (start, end) = selection(content, "fun helper", "val x = helper()")

		val result = plan(content, start, end)

		assertNull(result.refusal)
		val candidate = result.candidates.single()
		assertEquals(CallSiteForm.AssignOutput("x"), candidate.callSite)
		assertEquals(listOf("a"), candidate.parameters.map { it.name })
	}

	@Test
	fun `a return inside an anonymous object override in the region is not an exit`() {
		val content =
			"""
			package p
			interface Runner { fun run() }
			fun work() {}
			fun use(r: Runner) {}
			fun demo(flag: Boolean) {
				val r = object : Runner {
					override fun run() {
						if (flag) return
						work()
					}
				}
				use(r)
			}
			""".trimIndent()
		val (start, end) = selection(content, "val r = object", "use(r)")

		val result = plan(content, start, end)

		assertNull(result.refusal)
		val candidate = result.candidates.single()
		assertEquals(listOf("flag"), candidate.parameters.map { it.name })
		assertEquals(CallSiteForm.Call, candidate.callSite)
	}

	@Test
	fun `a tail return is recognised when a nested function in the region also returns`() {
		val content =
			"""
			package p
			fun demo(a: Int): Int {
				fun helper(): Int {
					return a * 2
				}
				return helper()
			}
			""".trimIndent()
		val (start, end) = selection(content, "fun helper", "return helper()")

		val candidate = plan(content, start, end).candidates.single()

		assertEquals(CallSiteForm.Return, candidate.callSite)
		assertEquals("kotlin.Int", candidate.returnTypeText)
	}

	@Test
	fun `a non-local return from a lambda in the region is still an exit`() {
		// A lambda is transparent to an unlabelled `return`, so this one really does leave the region.
		val content =
			"""
			package p
			fun demo(items: List<Int>): Int {
				items.forEach { item ->
					if (item > 0) return item
				}
				return 0
			}
			""".trimIndent()
		val (start, end) = selection(content, "items.forEach", "\t}")

		assertEquals(ExtractionRefusal.ExitsRegion, plan(content, start, end).refusal)
	}
```

`assertNull` and the `selection` helper are already available in this file.

- [ ] **Step 2: Run the tests to verify the first three fail and the fourth passes**

```bash
flox activate -d flox/local -- ./gradlew :lsp:kotlin:testV7DebugUnitTest --tests "com.itsaky.androidide.lsp.kotlin.utils.refactor.ExtractMethodPlanEndToEndTest"
```

Expected: the first two FAIL on `assertNull(result.refusal)` (the refusal is `ExitsRegion`), the third FAILS with `NoSuchElementException` / empty candidate list, and `a non-local return from a lambda in the region is still an exit` PASSES already.

- [ ] **Step 3: Filter out returns owned by a declaration inside the region**

Add this import to `MethodSignature.kt`, in its existing alphabetical run:

```kotlin
import org.jetbrains.kotlin.psi.KtDeclarationWithBody
```

Insert this helper immediately above `isTailReturn`:

```kotlin
/**
 * Whether [returnExpression] returns from a function declared *inside* the region, so its jump never
 * crosses the region boundary and it is not an exit (R8).
 *
 * A `KtFunctionLiteral` is skipped rather than accepted: a lambda is transparent to an unlabelled
 * `return`, which targets the enclosing function declaration, so a non-local return out of a lambda in
 * the region really does leave it. An anonymous `fun` is not transparent and is not a literal, so the
 * same walk stops on it correctly.
 */
private fun returnTargetInRegion(
	returnExpression: KtReturnExpression,
	span: TextSpan,
): Boolean {
	var owner = PsiTreeUtil.getParentOfType(returnExpression, KtDeclarationWithBody::class.java, true)
	while (owner is KtFunctionLiteral) {
		owner = PsiTreeUtil.getParentOfType(owner, KtDeclarationWithBody::class.java, true)
	}
	return owner != null && inRegion(owner, span)
}
```

Replace the body of `isTailReturn` so the nested returns are filtered before it counts:

```kotlin
private fun isTailReturn(
	elements: List<KtExpression>,
	span: TextSpan,
): Boolean {
	if (elements.last() !is KtReturnExpression) return false
	val returns =
		descendantsOf(elements, KtReturnExpression::class.java)
			.filterNot { returnTargetInRegion(it, span) }
	if (returns.size != 1 || returns.single() !== elements.last()) return false
	return !hasLoopExit(elements, span)
}
```

And add the same skip as the first statement of `hasExit`'s loop:

```kotlin
	for (returnExpression in descendantsOf(elements, KtReturnExpression::class.java)) {
		if (returnTargetInRegion(returnExpression, span)) continue
		// An unlabelled `return` always targets the enclosing named declaration, which is outside the
		// region by construction. A labelled one targets the lambda carrying that label, which is not
		// necessarily the nearest one -- `return@outer` from a nested lambda still leaves the region.
		val label = returnExpression.getLabelName() ?: return true
		val target = labelledLambdaFor(returnExpression, label) ?: return true
		if (!inRegion(target, span)) return true
	}
```

- [ ] **Step 4: Run the tests to verify all four pass**

```bash
flox activate -d flox/local -- ./gradlew :lsp:kotlin:testV7DebugUnitTest --tests "com.itsaky.androidide.lsp.kotlin.utils.refactor.ExtractMethodPlanEndToEndTest"
```

Expected: PASS, the whole class. The pre-existing mid-region-`return` and `break`-out-of-region refusal tests must still pass; if one does not, the filter is too broad - check whether its `return` sits in a lambda rather than a nested declaration.

- [ ] **Step 5: Update the R8 declined list**

In `docs/features/kotlin-extract-method.md`, replace the "Declined:" paragraph under R8 with:

```markdown
Declined: a `return` anywhere but the tail position, a `break`/`continue` whose target loop is outside the region, a labelled `return@` whose target is outside it, and a non-local return from an inlined lambda. Each would silently change meaning, since a `return` in the extracted body returns from *it*.

Not an exit: a `return` belonging to a function **declared inside** the region - a local `fun`, an anonymous `fun`, or an anonymous-object override. It moves with its own declaration and its jump never crosses the region boundary, so counting it would refuse a perfectly good extraction with a message describing something the user did not write.
```

- [ ] **Step 6: Commit**

```bash
git add lsp/kotlin/src/main/java/com/itsaky/androidide/lsp/kotlin/utils/refactor/MethodSignature.kt \
        lsp/kotlin/src/test/java/com/itsaky/androidide/lsp/kotlin/utils/refactor/ExtractMethodPlanEndToEndTest.kt \
        docs/features/kotlin-extract-method.md
git commit -m "ADFA-5080: Stop counting a nested declaration's return as a region exit"
```

---

### Task 4: Re-indentation leaves multi-line string literals verbatim

**Why:** `reindent` strips `baseIndent` from every line of the region and `buildExtractMethodRewrites` then prefixes `bodyIndent` to every line, with no awareness of string literals. Two ways that changes a raw string's value:

1. `bodyIndent != baseIndent` - the normal case for a region nested inside an `if` or a lambda - shifts every continuation line by the difference.
2. `bodyIndent == baseIndent` still breaks a literal whose lines are indented *less* than the base: `removePrefix(baseIndent)` is a no-op on `line one`, but `bodyIndent` is prefixed anyway, so the literal gains an indent level on the ordinary member-function path.

The result compiles but the runtime string differs, contradicting ADR 0013's "it does not edit the interior of what it moved". A literal followed by `.trimIndent()` is unaffected by case 1; nothing is safe from case 2.

The fix carries the literal spans from the analysis layer (which has PSI) to the edit layer (which does not), and folds the `bodyIndent` prefixing into `reindent` so a protected line can be emitted untouched. The closing delimiter line is protected too - its position sets `trimIndent`'s margin, so moving it changes the value just as much.

**Files:**
- Modify: `lsp/kotlin/src/main/java/com/itsaky/androidide/lsp/kotlin/utils/refactor/ExtractMethodPlan.kt:53-67` (add `rawStringSpans` to `ExtractMethodCandidate`)
- Modify: `lsp/kotlin/src/main/java/com/itsaky/androidide/lsp/kotlin/utils/refactor/MethodSignature.kt:195-217` (populate it) and add one private helper, plus one import
- Modify: `lsp/kotlin/src/main/java/com/itsaky/androidide/lsp/kotlin/utils/refactor/ExtractMethodEdit.kt:40-57` and `:83-95` (`reindent` becomes `indentedBodyLines`)
- Test: `lsp/kotlin/src/test/java/com/itsaky/androidide/lsp/kotlin/utils/refactor/ExtractMethodEditTest.kt`, `lsp/kotlin/src/test/java/com/itsaky/androidide/lsp/kotlin/utils/refactor/ExtractMethodPlanEndToEndTest.kt`
- Modify: `docs/features/kotlin-extract-method.md:172` (R15 emission paragraph)

**Interfaces:**
- Consumes: nothing from Tasks 1-3.
- Produces:
  - `ExtractMethodCandidate.rawStringSpans: List<TextSpan>`, defaulted to `emptyList()` so the four existing construction sites keep compiling unchanged.
  - `private fun indentedBodyLines(regionText: String, regionStart: Int, baseIndent: String, bodyIndent: String, newline: String, protectedSpans: List<TextSpan>): List<String>` in `ExtractMethodEdit.kt`, replacing `reindent`. It returns **fully indented** lines, so the declaration builder no longer prefixes anything.
  - `private fun multiLineStringSpans(elements: List<KtExpression>): List<TextSpan>` in `MethodSignature.kt`.

- [ ] **Step 1: Write the failing text-level tests**

Append to `ExtractMethodEditTest.kt`, inside the class:

```kotlin
	@Test
	fun `a raw string keeps its interior lines when the body indent differs from the base`() {
		val quotes = "\"\"\""
		val nested =
			"package p\n" +
				"class C {\n" +
				"\tfun demo() {\n" +
				"\t\tif (true) {\n" +
				"\t\t\tsend($quotes\n" +
				"line one\n" +
				"\t\t\t\tline two\n" +
				"$quotes)\n" +
				"\t\t}\n" +
				"\t}\n" +
				"}\n"
		val span = TextSpan(nested.indexOf("send("), nested.indexOf("$quotes)") + "$quotes)".length)
		val rewrites =
			buildExtractMethodRewrites(
				nested,
				candidate(
					span,
					ExtractedBody.StatementBody(trailingReturn = null),
					CallSiteForm.Call,
				).copy(
					insertOffset = nested.indexOf("\t}\n}") + 2,
					insertIndent = "\t",
					rawStringSpans = listOf(TextSpan(nested.indexOf(quotes), nested.indexOf("$quotes)") + quotes.length)),
				),
				"emit",
			)

		val text = apply(nested, rewrites!!)

		assertTrue("the first line takes the body indent", text.contains("\n\t\tsend($quotes\n"))
		assertTrue("an unindented literal line stays unindented", text.contains("\nline one\n"))
		assertTrue("an indented literal line keeps its own indent", text.contains("\n\t\t\t\tline two\n"))
		assertTrue("the closing delimiter line is untouched", text.contains("\n$quotes)\n"))
	}

	@Test
	fun `a raw string is left alone when the body and base indents match`() {
		// The base indent is not a prefix of an unindented literal line, so stripping it is a no-op while
		// the body indent is still prefixed. Equal indents are not a safe case.
		val quotes = "\"\"\""
		val flat =
			"package p\n" +
				"class C {\n" +
				"\tfun demo() {\n" +
				"\t\tsend($quotes\n" +
				"line one\n" +
				"$quotes)\n" +
				"\t}\n" +
				"}\n"
		val span = TextSpan(flat.indexOf("send("), flat.indexOf("$quotes)") + "$quotes)".length)
		val rewrites =
			buildExtractMethodRewrites(
				flat,
				candidate(
					span,
					ExtractedBody.StatementBody(trailingReturn = null),
					CallSiteForm.Call,
				).copy(
					insertOffset = flat.indexOf("\t}\n}") + 2,
					insertIndent = "\t",
					rawStringSpans = listOf(TextSpan(flat.indexOf(quotes), flat.indexOf("$quotes)") + quotes.length)),
				),
				"emit",
			)

		val text = apply(flat, rewrites!!)

		assertTrue("an unindented literal line stays unindented", text.contains("\nline one\n"))
		assertTrue("the closing delimiter line is untouched", text.contains("\n$quotes)\n"))
	}
```

Then append to `ExtractMethodPlanEndToEndTest.kt`, inside the class, so the spans are proven to be *populated*, not just honoured:

```kotlin
	@Test
	fun `a multi-line string in the region is recorded and emitted verbatim`() {
		val quotes = "\"\"\""
		val content =
			"package p\n" +
				"fun send(s: String) {}\n" +
				"fun demo() {\n" +
				"\tif (true) {\n" +
				"\t\tsend($quotes\n" +
				"line one\n" +
				"$quotes)\n" +
				"\t}\n" +
				"}\n"
		val (start, end) = selection(content, "send($quotes", "$quotes)")

		val candidate = plan(content, start, end).candidates.single()

		assertEquals(1, candidate.rawStringSpans.size)
		assertEquals(content.indexOf(quotes), candidate.rawStringSpans.single().start)
		assertEquals(content.indexOf("$quotes)") + quotes.length, candidate.rawStringSpans.single().end)

		val text = apply(content, buildExtractMethodRewrites(content, candidate, "emit")!!)
		assertTrue("the literal must not gain an indent level", text.contains("\nline one\n"))
	}
```

- [ ] **Step 2: Run both test classes to verify the new tests fail**

```bash
flox activate -d flox/local -- ./gradlew :lsp:kotlin:testV7DebugUnitTest \
  --tests "com.itsaky.androidide.lsp.kotlin.utils.refactor.ExtractMethodEditTest" \
  --tests "com.itsaky.androidide.lsp.kotlin.utils.refactor.ExtractMethodPlanEndToEndTest"
```

Expected: the two `ExtractMethodEditTest` cases fail to **compile** first (`rawStringSpans` is not a parameter of `ExtractMethodCandidate` yet). That counts as red. Add the field in Step 3, then re-run this command before Step 4 and expect assertion failures instead: `line one` comes out as `\tline one` / `\t\tline one`.

- [ ] **Step 3: Add the field to the candidate**

In `ExtractMethodPlan.kt`, add the last parameter of `ExtractMethodCandidate` and extend its KDoc with one paragraph:

```kotlin
 * [rawStringSpans] are the multi-line string literals inside the region, in file offsets. Their
 * interior is whitespace-sensitive, so re-indentation must leave those lines byte-for-byte (ADR 0013).
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
	val rawStringSpans: List<TextSpan> = emptyList(),
)
```

- [ ] **Step 4: Populate it in the analysis layer**

Add this import to `MethodSignature.kt`, in its existing alphabetical run:

```kotlin
import org.jetbrains.kotlin.psi.KtStringTemplateExpression
```

Add `rawStringSpans = multiLineStringSpans(elements),` to the `ExtractMethodCandidate(...)` construction in `buildCandidate`, immediately after `insertIndent = ...`.

Add this helper next to the other `descendantsOf` users, above `localTypeNameIn`:

```kotlin
/**
 * The multi-line string literals inside [elements], in file offsets. A single-line literal needs no
 * protection: `\n` inside it is an escape, not a line break the re-indentation can reach.
 */
private fun multiLineStringSpans(elements: List<KtExpression>): List<TextSpan> =
	descendantsOf(elements, KtStringTemplateExpression::class.java)
		.filter { it.text.contains('\n') }
		.map { TextSpan(it.textRange.startOffset, it.textRange.endOffset) }
```

- [ ] **Step 5: Make the emission honour the spans**

In `ExtractMethodEdit.kt`, replace the `bodyLines` block and the declaration builder (lines 40-57) with:

```kotlin
	val bodyLines =
		when (val body = candidate.body) {
			is ExtractedBody.ExpressionBody -> {
				val lines =
					indentedBodyLines(regionText, span.start, baseIndent, bodyIndent, newline, candidate.rawStringSpans)
				// The first line is never inside a literal's interior -- the region starts at the code
				// itself -- so it always carries bodyIndent and `return ` goes straight after it.
				if (body.needsReturn) {
					listOf(bodyIndent + "return " + lines.first().substring(bodyIndent.length)) + lines.drop(1)
				} else {
					lines
				}
			}

			is ExtractedBody.StatementBody -> {
				indentedBodyLines(regionText, span.start, baseIndent, bodyIndent, newline, candidate.rawStringSpans) +
					listOfNotNull(body.trailingReturn?.let { bodyIndent + it })
			}
		}

	val declaration =
		buildString {
			append(indent).append(candidate.signatureText(name)).append(" {").append(newline)
			bodyLines.forEach { append(it).append(newline) }
			append(indent).append('}')
		}
```

And replace `reindent` and its KDoc (lines 83-95) with:

```kotlin
/**
 * The region's lines at the new function's body indentation: the original base indentation removed and
 * [bodyIndent] put in its place. Lines nested deeper than the base keep the extra depth; the first line
 * only gains the indent, since the span starts at the code itself.
 *
 * A line inside one of [protectedSpans] is emitted byte-for-byte. Those are multi-line string literals,
 * whose interior whitespace is part of their value, and whose closing delimiter sets `trimIndent`'s
 * margin -- moving either edits the interior of the moved code (ADR 0013).
 */
private fun indentedBodyLines(
	regionText: String,
	regionStart: Int,
	baseIndent: String,
	bodyIndent: String,
	newline: String,
	protectedSpans: List<TextSpan>,
): List<String> {
	var offset = regionStart
	return regionText.split(newline).mapIndexed { index, line ->
		val lineStart = offset
		offset += line.length + newline.length
		when {
			index == 0 -> bodyIndent + line
			protectedSpans.any { lineStart > it.start && lineStart < it.end } -> line
			else -> bodyIndent + line.removePrefix(baseIndent)
		}
	}
}
```

- [ ] **Step 6: Run both test classes to verify everything passes**

```bash
flox activate -d flox/local -- ./gradlew :lsp:kotlin:testV7DebugUnitTest \
  --tests "com.itsaky.androidide.lsp.kotlin.utils.refactor.ExtractMethodEditTest" \
  --tests "com.itsaky.androidide.lsp.kotlin.utils.refactor.ExtractMethodPlanEndToEndTest"
```

Expected: PASS, both classes. Every pre-existing `ExtractMethodEditTest` case - indentation, blank-line separation, CRLF preservation, the three call-site forms - must still pass unchanged; they all leave `rawStringSpans` at its default, and the refactored `indentedBodyLines` must produce byte-identical output for them.

- [ ] **Step 7: Update the R15 emission paragraph**

In `docs/features/kotlin-extract-method.md`, replace the paragraph beginning "The new function is emitted **fully indented**" with:

```markdown
The new function is emitted **fully indented** at the enclosing declaration's own indentation, separated by one blank line, reusing `detectIndentUnit`, `detectNewline`, `leadingIndentAt` and `positionAt`. Code-action edits bypass the editor's auto-indent and `CMD_FORMAT_CODE` is a no-op for Kotlin.

One exception to re-indenting every line: the interior and closing delimiter of a **multi-line string literal** are emitted byte-for-byte. Their whitespace is part of the literal's value, and the closing delimiter's column sets `trimIndent`'s margin, so shifting either would edit the interior of the moved code (ADR 0013). The candidate carries those literals' spans so the text layer can skip them without needing PSI.
```

- [ ] **Step 8: Commit**

```bash
git add lsp/kotlin/src/main/java/com/itsaky/androidide/lsp/kotlin/utils/refactor/ExtractMethodPlan.kt \
        lsp/kotlin/src/main/java/com/itsaky/androidide/lsp/kotlin/utils/refactor/MethodSignature.kt \
        lsp/kotlin/src/main/java/com/itsaky/androidide/lsp/kotlin/utils/refactor/ExtractMethodEdit.kt \
        lsp/kotlin/src/test/java/com/itsaky/androidide/lsp/kotlin/utils/refactor/ExtractMethodEditTest.kt \
        lsp/kotlin/src/test/java/com/itsaky/androidide/lsp/kotlin/utils/refactor/ExtractMethodPlanEndToEndTest.kt \
        docs/features/kotlin-extract-method.md
git commit -m "ADFA-5080: Keep multi-line string literals verbatim when re-indenting"
```

---

### Task 5: File the type-text shortening follow-up

**Why:** John's fifth point - extract-method signatures print `kotlin.Int` while `ExtractVariablePlanner.kt:162` runs the same rendering through `shortenTypeText` - is real but is a *documented* decision, not an oversight: R5 and R11 of `docs/features/kotlin-extract-method.md` both specify fully-qualified rendering, and `ExtractMethodPlanEndToEndTest` asserts it in several places. Changing it means a doc change, a code change and a test sweep, which does not belong in a review-fix commit. It gets a ticket instead.

**Files:** none. No repository change; this task creates a Jira issue and leaves the working tree clean.

**Interfaces:**
- Consumes: nothing.
- Produces: a ticket id, quoted in Task 6's PR reply.

- [ ] **Step 1: Confirm no equivalent ticket exists**

```bash
jira issue list -q 'project = ADFA AND text ~ "shorten type text" AND statusCategory != Done' --plain
```

If a matching ticket already exists, note its id and skip Step 2.

- [ ] **Step 2: Create the ticket**

Write the body to a tempfile first - the description contains backticks, which break inline heredocs:

```bash
cat > /tmp/adfa-shorten-body.md <<'BODY'
Extract method renders every signature type fully qualified (`private fun total(a: kotlin.Int): kotlin.Int`).
Extract variable renders the same types through `shortenTypeText`, which drops the qualifier whenever the
file already resolves the simple name. Two refactorings in the same family read differently for no reason
the user can see.

Raised by John Andrés Trujillo in review on PR #1655 as a follow-up, not a correctness issue: fully
qualified text always compiles.

Scope:
- Run the derived parameter, return and receiver type text through `shortenTypeText` in the extract-method
  path, using `importedNamesOf` / `starImportedPackagesOf` on the enclosing `KtFile`.
- Update R5 and R11 of `docs/features/kotlin-extract-method.md`, which currently specify fully-qualified
  rendering as deliberate.
- Update the `ExtractMethodPlanEndToEndTest` assertions that expect `kotlin.Int`, and the signature-preview
  assertions in `ExtractMethodViewModelTest`.
BODY

jira issue create --type Task --project ADFA \
  --summary "Shorten extract-method signature types to match extract variable" \
  --template /tmp/adfa-shorten-body.md \
  --no-input
```

- [ ] **Step 3: Link it from the feature doc's Related list**

In `docs/features/kotlin-extract-method.md`, add one bullet to the `## Related` list, after the ADFA-5082 line, substituting the real ticket id:

```markdown
- ADFA-<id> - shorten signature type text to match extract variable (revisits R5's fully-qualified rendering)
```

- [ ] **Step 4: Commit**

```bash
git add docs/features/kotlin-extract-method.md
git commit -m "ADFA-5080: Link the type-text shortening follow-up"
```

---

### Task 6: Verify the whole module, format, and answer the review

**Why:** Each earlier task ran only the classes it touched. This task proves the four fixes hold together, that nothing else in `:lsp:kotlin` regressed, and that the branch is formatted for push. Then it closes the loop with the reviewer and the ticket, which the project asks for explicitly.

**Files:**
- Modify: `docs/features/kotlin-extract-method.md` (Verification section - the new coverage)
- No source changes expected; Spotless may reformat touched files.

**Interfaces:**
- Consumes: the four commits from Tasks 1-4 and the ticket id from Task 5.
- Produces: nothing consumed by a later task.

- [ ] **Step 1: Run the full module test suite**

```bash
flox activate -d flox/local -- ./gradlew :lsp:kotlin:testV7DebugUnitTest
```

Expected: PASS. Report the actual failure output if anything fails; do not weaken an assertion to make it green.

- [ ] **Step 2: Compile the app flavor the fixes ship in**

```bash
flox activate -d flox/local -- ./gradlew :lsp:kotlin:compileV8DebugKotlin
```

Expected: `BUILD SUCCESSFUL`. This catches anything that compiles under `v7` but not `v8`.

- [ ] **Step 3: Update the Verification section's coverage list**

In `docs/features/kotlin-extract-method.md`, replace the `ExtractMethodPlanEndToEndTest` and `ExtractMethodEditTest` bullets under `## Verification` with:

```markdown
- **`ExtractMethodPlanEndToEndTest`** - analysis-backed, one case per rule: the parameter set, order and types (R5), the single output and the `Unit` case (R6, R7), the tail return and the nested-declaration `return` that is not an exit (R8), the extension receiver (R9), `suspend`, a `@Composable` call and a `@Composable` property getter (R10), the anonymous-function anchor (R4), the recorded multi-line-string spans (R15), and **one case per refusal reason** (R14).
- **`ExtractMethodEditTest`** - pure text: the two edits and their descending order, the three call-site forms, indentation, multi-line string literals left verbatim, the blank-line separation, and CRLF preservation (R15).
```

- [ ] **Step 4: Format and commit**

```bash
flox activate -d flox/local -- ./gradlew spotlessApply
git status --short
```

Review what Spotless changed. The file-level ratchet reformats any file differing from `origin/stage` in full, so an unrelated whole-file reindent may appear - if it does, keep it in its own commit:

```bash
git add docs/features/kotlin-extract-method.md
git commit -m "ADFA-5080: Record the new extract-method test coverage"
```

- [ ] **Step 5: Reply in each review thread**

Reply in the thread, not as a top-level PR comment. One reply per comment id, each naming the commit and what changed:

```bash
gh api repos/appdevforall/CodeOnTheGo/pulls/1655/comments/3776099257/replies -f body="Fixed. \`enclosingDeclaration\` now skips a nameless \`KtNamedFunction\` and keeps walking, so the anchor is the enclosing named declaration and the anonymous function's parameters become captures. Two end-to-end tests cover the argument and property-initializer shapes, and R4's target table has a row for it."
gh api repos/appdevforall/CodeOnTheGo/pulls/1655/comments/3776099272/replies -f body="Fixed. \`usesComposable\` now also resolves simple names to \`KaPropertySymbol\` and checks the property and its getter for the annotation. Tests cover an annotated getter and a plain one, so the negative case is pinned too."
gh api repos/appdevforall/CodeOnTheGo/pulls/1655/comments/3776099279/replies -f body="Fixed. Both \`hasExit\` and \`isTailReturn\` now skip a \`return\` whose nearest enclosing \`KtDeclarationWithBody\` is inside the region. The walk skips \`KtFunctionLiteral\` so a non-local return out of a lambda is still refused, and there is a test holding that line. Worth noting the same fault hit \`object : Runner { override fun run() { ... return ... } }\`, which is the more common shape - that case is tested."
gh api repos/appdevforall/CodeOnTheGo/pulls/1655/comments/3776099288/replies -f body="Fixed. The candidate carries the multi-line string spans and the text layer emits those lines byte-for-byte, closing delimiter included. One correction to the scope: it also fired when \`bodyIndent == baseIndent\`, because \`removePrefix(baseIndent)\` is a no-op on a literal line indented less than the base while \`bodyIndent\` was still prefixed - so an unindented literal gained an indent level on the ordinary member-function path. Both cases are tested."
```

- [ ] **Step 6: Comment on the ticket**

```bash
jira issue comment add ADFA-5080 "Addressed John's review on PR #1655: anonymous-function insertion anchor, @Composable property getters, nested-declaration returns wrongly counted as region exits, and raw-string interiors being re-indented. Each has a regression test and the feature doc is updated. The fully-qualified-vs-shortened type text point is tracked separately as ADFA-<id>."
```

Substitute the ticket id from Task 5.

---

## Self-Review

**Spec coverage.** All five items in the review analysis have a task: HIGH anonymous anchor (Task 1), MEDIUM `@Composable` getters (Task 2), LOW nested returns (Task 3), LOW raw-string re-indentation (Task 4), the qualified-type-text follow-up (Task 5). The "missing tests" row of the analysis is folded into Tasks 1-4 rather than deferred, and the doc-consistency requirement from `CLAUDE.md` is satisfied per task rather than in a sweep at the end.

**Placeholder scan.** No TBDs. Every code step carries the actual replacement text; every doc step carries the actual markdown; every command is runnable as written. The only substitution is the Jira ticket id created in Task 5 and quoted in Task 6, which cannot be known ahead of time and is flagged at both use sites.

**Type consistency.** `returnTargetInRegion` (Task 3) is used by both `isTailReturn` and `hasExit` with the same `(KtReturnExpression, TextSpan)` signature. `rawStringSpans: List<TextSpan>` is named identically in `ExtractMethodPlan.kt`, `MethodSignature.kt`, `ExtractMethodEdit.kt` and both test files. `indentedBodyLines` replaces `reindent` at all three call sites in Task 4 Step 5, and its parameter order matches both invocations. `hasComposableAnnotation` is declared on `KaAnnotatedSymbol`, which both `KaDeclarationSymbol` (the function-call branch) and `KaPropertyGetterSymbol` (the property branch) satisfy.

**Known risk.** Task 4 Step 2 goes red by failing to compile rather than by failing an assertion, because the test needs a field the fix introduces. The step says so and requires a second red run after the field exists, so the assertions themselves are still proven to fail before the behaviour changes.
