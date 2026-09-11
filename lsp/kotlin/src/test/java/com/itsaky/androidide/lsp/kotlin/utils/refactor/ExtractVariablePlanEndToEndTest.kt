package com.itsaky.androidide.lsp.kotlin.utils.refactor

import com.itsaky.androidide.lsp.kotlin.fixtures.KtLspTest
import com.itsaky.androidide.lsp.kotlin.utils.refactor.HARD_KEYWORDS
import com.itsaky.androidide.lsp.refactor.RewriteSpan
import com.itsaky.androidide.lsp.refactor.TextSpan
import com.itsaky.androidide.lsp.ui.NameProblem
import com.itsaky.androidide.lsp.ui.validateVariableName
import org.jetbrains.kotlin.com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.psi.KtBlockExpression
import org.jetbrains.kotlin.psi.KtIfExpression
import org.jetbrains.kotlin.psi.KtLambdaExpression
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The parts of the plan that need real symbol resolution: candidate filtering, the legal scope chain
 * across lambda boundaries, occurrence matching by symbol identity, and reassignment soundness.
 *
 * Where a rewrite is produced, the assertion is on the **resulting file text** -- the only assertion
 * that can catch an indentation or off-by-one error.
 */
class ExtractVariablePlanEndToEndTest : KtLspTest() {
	private fun plan(
		content: String,
		start: Int,
		end: Int = start,
	): ExtractionPlan {
		createSourceFile("Main.kt", content)
		val path = env.sourceRoots.first().resolve("Main.kt")
		return buildExtractionPlan(env, path, start, end, documentVersion = 1, cancelChecker = noopCancelChecker())
	}

	private fun apply(
		text: String,
		rewrite: RewriteSpan,
	): String = text.substring(0, rewrite.span.start) + rewrite.newText + text.substring(rewrite.span.end)

	@Test
	fun `offers the innermost three candidates, innermost first`() {
		val content =
			"""
			package p
			class B { fun c(): Int = 1 }
			class A { val b: B = B() }
			fun wrap(n: Int): Int = n
			fun demo(a: A) {
				wrap(a.b.c() * 2)
			}
			""".trimIndent()

		// Anchor on the call site, not the `fun c()` declaration that appears earlier in the file.
		val result = plan(content, content.indexOf("a.b.c()") + "a.b.c".length)

		assertEquals(
			listOf("a.b.c()", "a.b.c() * 2", "wrap(a.b.c() * 2)"),
			result.candidates.map { it.label },
		)
	}

	@Test
	fun `does not offer bare literals`() {
		val content =
			"""
			package p
			fun demo(n: Int): Int {
				return n * 2
			}
			""".trimIndent()

		val result = plan(content, content.indexOf("2", content.indexOf("n * 2")))

		assertFalse(result.candidates.any { it.label == "2" })
		assertTrue(result.candidates.any { it.label == "n * 2" })
	}

	@Test
	fun `offers nothing for a class-body property initializer`() {
		val content =
			"""
			package p
			fun compute(): Int = 1
			class C {
				val x = compute() + compute()
			}
			""".trimIndent()

		assertTrue(plan(content, content.indexOf("compute() + compute()") + 1).isEmpty)
	}

	@Test
	fun `offers nothing for a default parameter value`() {
		val content =
			"""
			package p
			fun base(): Int = 1
			fun demo(n: Int = base() * 2) {
				println(n)
			}
			""".trimIndent()

		assertTrue(plan(content, content.indexOf("base() * 2") + 1).isEmpty)
	}

	@Test
	fun `offers nothing when the cursor is in a comment`() {
		val content =
			"""
			package p
			fun demo() {
				// nothing here
			}
			""".trimIndent()

		assertTrue(plan(content, content.indexOf("nothing")).isEmpty)
	}

	@Test
	fun `a selection matching an expression exactly resolves to that expression`() {
		val content =
			"""
			package p
			fun wrap(n: Int): Int = n
			fun demo(n: Int) {
				wrap(n * 2)
			}
			""".trimIndent()
		val start = content.indexOf("n * 2")

		val result = plan(content, start, start + "n * 2".length)

		assertEquals("n * 2", result.candidates.first().label)
		// The enclosing expression stays on offer: an exact selection no longer hides the chooser.
		assertEquals(listOf("n * 2", "wrap(n * 2)"), result.candidates.map { it.label })
	}

	@Test
	fun `an off-boundary selection still resolves`() {
		val content =
			"""
			package p
			fun wrap(n: Int): Int = n
			fun demo(n: Int) {
				wrap(n * 2)
			}
			""".trimIndent()
		val start = content.indexOf("n * 2")

		// Selection stops mid-expression, as a touch-screen drag routinely does.
		val result = plan(content, start, start + 3)

		assertEquals("n * 2", result.candidates.first().label)
	}

	@Test
	fun `a shadowed name in a nested lambda is not the same expression`() {
		val content =
			"""
			package p
			class Config(val timeout: Int)
			fun log(n: Int) {}
			fun demo(config: Config, list: List<Config>) {
				log(config.timeout)
				list.forEach { config -> log(config.timeout) }
			}
			""".trimIndent()

		val result = plan(content, content.indexOf("config.timeout") + 1)
		val functionScope =
			result.candidates
				.first()
				.scopes
				.first()

		// `config` inside the lambda is a different declaration, so only one occurrence exists.
		assertEquals(1, functionScope.occurrences.size)
	}

	@Test
	fun `the same expression in both branches of an if is one occurrence set`() {
		val content =
			"""
			package p
			class A(val b: Int)
			fun log(n: Int) {}
			fun warn(n: Int) {}
			fun demo(c: Boolean, a: A) {
				if (c) {
					log(a.b)
				} else {
					warn(a.b)
				}
			}
			""".trimIndent()

		val result = plan(content, content.indexOf("a.b") + 1)
		val candidate = result.candidates.first { it.label == "a.b" }
		// The outermost rung is the function body, which contains both branches.
		val functionScope = candidate.scopes.last()

		assertEquals(2, functionScope.occurrences.size)
	}

	@Test
	fun `a reassignment between occurrences drops the unsound one`() {
		val content =
			"""
			package p
			fun wrap(n: Int): Int = n
			fun demo(): Int {
				var limit = 1
				wrap(limit + 1)
				limit = 5
				wrap(limit + 1)
				return limit
			}
			""".trimIndent()

		val result = plan(content, content.indexOf("limit + 1") + 1)
		val candidate = result.candidates.first { it.label == "limit + 1" }
		val functionScope = candidate.scopes.last()

		// Both sites are the same expression, but `limit = 5` makes the second a different value.
		assertEquals(1, functionScope.occurrences.size)
	}

	@Test
	fun `a rung outside a loop that writes what the expression reads is declined`() {
		// itsaky, ExtractVariablePlanner.kt:143 -- this half had no loop check at all, so the hoist
		// guards the Java planner gained this round never reached it. Hoisting `limit + 1` out of the
		// `while` evaluates it once and feeds every iteration the same value.
		val content =
			"""
			package p
			fun wrap(n: Int): Int = n
			fun demo() {
				var limit = 0
				while (limit < 10) {
					wrap(limit + 1)
					limit++
				}
			}
			""".trimIndent()

		val result = plan(content, content.indexOf("limit + 1") + 1)
		val candidate = result.candidates.first { it.label == "limit + 1" }

		// The loop's own block survives: a declaration placed there re-runs with every iteration.
		assertFalse(
			"the function rung hoists the declaration out of the loop",
			candidate.scopes.any { it.label == "fun demo" },
		)
	}

	@Test
	fun `a candidate using the implicit lambda parameter cannot be hoisted out of the lambda`() {
		val content =
			"""
			package p
			fun log(n: Int) {}
			fun demo(items: List<String>) {
				items.forEach { log(it.length + 1) }
			}
			""".trimIndent()

		val result = plan(content, content.indexOf("it.length + 1") + 1)
		val candidate = result.candidates.first { it.label == "it.length + 1" }

		// `it` belongs to the lambda, so the lambda body is the only legal anchor.
		assertEquals(listOf("lambda"), candidate.scopes.map { it.label })
	}

	@Test
	fun `a lambda-invariant candidate can be hoisted to the enclosing function`() {
		val content =
			"""
			package p
			class Config(val timeout: Int)
			fun log(n: Int) {}
			fun demo(config: Config, items: List<String>) {
				items.forEach { log(config.timeout * 2) }
			}
			""".trimIndent()

		val result = plan(content, content.indexOf("config.timeout * 2") + 1)
		val candidate = result.candidates.first { it.label == "config.timeout * 2" }

		// Nothing lambda-scoped is referenced, so hoisting out to the function body is offered.
		assertEquals(listOf("lambda", "fun demo"), candidate.scopes.map { it.label })
	}

	@Test
	fun `suggests a name from the expression shape`() {
		val content =
			"""
			package p
			fun wrap(n: Int): Int = n
			fun demo(items: List<String>) {
				wrap(items.size * 2)
			}
			""".trimIndent()

		val result = plan(content, content.indexOf("items.size") + 1)

		assertEquals("size", result.candidates.first { it.label == "items.size" }.suggestedName)
	}

	@Test
	fun `does not suggest a name that is already taken`() {
		val content =
			"""
			package p
			fun wrap(n: Int): Int = n
			fun demo(items: List<String>) {
				val size = 0
				wrap(items.size * 2)
			}
			""".trimIndent()

		val result = plan(content, content.indexOf("items.size") + 1)

		assertEquals("size1", result.candidates.first { it.label == "items.size" }.suggestedName)
	}

	@Test
	fun `end to end rewrite replaces all occurrences in the function body`() {
		val content =
			"""
			package p
			fun wrap(n: Int): Int = n
			fun demo(items: List<String>): Int {
				wrap(items.size * 2)
				return items.size * 2
			}
			""".trimIndent()

		val result = plan(content, content.indexOf("items.size * 2") + 1)
		val candidate = result.candidates.first { it.label == "items.size * 2" }
		val scope = candidate.scopes.last()
		assertEquals(2, scope.occurrences.size)

		val rewrite =
			buildExtractVariableRewrite(result.fileText, candidate.span, scope, "size", replaceAll = true)
		assertNotNull(rewrite)

		assertEquals(
			"""
			package p
			fun wrap(n: Int): Int = n
			fun demo(items: List<String>): Int {
				val size = items.size * 2
				wrap(size)
				return size
			}
			""".trimIndent(),
			apply(content, rewrite!!),
		)
	}

	@Test
	fun `end to end rewrite converts an expression-bodied function to a block body`() {
		val content =
			"""
			package p
			fun area(r: Int) = r * r + r * r
			""".trimIndent()

		val result = plan(content, content.indexOf("r * r") + 1)
		val candidate = result.candidates.first { it.label == "r * r" }
		val scope = candidate.scopes.first()

		val rewrite =
			buildExtractVariableRewrite(result.fileText, candidate.span, scope, "square", replaceAll = true)
		assertNotNull(rewrite)

		assertEquals(
			"""
			package p
			fun area(r: Int): Int {
				val square = r * r
				return square + square
			}
			""".trimIndent(),
			apply(content, rewrite!!),
		)
	}

	@Test
	fun `end to end rewrite wraps a braceless if branch`() {
		val content =
			"""
			package p
			class A(val b: Int)
			fun log(n: Int) {}
			fun demo(c: Boolean, a: A) {
				if (c) log(a.b + 1)
			}
			""".trimIndent()

		val result = plan(content, content.indexOf("a.b + 1") + 1)
		val candidate = result.candidates.first { it.label == "a.b + 1" }
		val scope = candidate.scopes.first()

		val rewrite =
			buildExtractVariableRewrite(result.fileText, candidate.span, scope, "offset", replaceAll = false)
		assertNotNull(rewrite)

		assertEquals(
			"""
			package p
			class A(val b: Int)
			fun log(n: Int) {}
			fun demo(c: Boolean, a: A) {
				if (c) {
					val offset = a.b + 1
					log(offset)
				}
			}
			""".trimIndent(),
			apply(content, rewrite!!),
		)
	}

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

		assertEquals(
			listOf("if block", "fun demo"),
			result.candidates
				.first()
				.scopes
				.map { it.label },
		)
	}

	@Test
	fun `labels a braced else branch by its owner`() {
		val content =
			"""
			package p
			fun demo(flag: Boolean, a: Int, b: Int): Int {
				if (flag) {
					return 0
				} else {
					return a + b * 2
				}
			}
			""".trimIndent()

		val target = "a + b * 2"
		val result = plan(content, content.indexOf(target), content.indexOf(target) + target.length)

		assertEquals(
			listOf("else block", "fun demo"),
			result.candidates
				.first()
				.scopes
				.map { it.label },
		)
	}

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

	@Test
	fun `contentSpanOf finds the region inside a block's braces`() {
		val content =
			"""
			package p
			fun functionBody(a: Int, b: Int): Int {
				return a + b
			}
			fun ifBody(flag: Boolean, a: Int, b: Int): Int {
				if (flag) {
					return a + b
				}
				return 0
			}
			fun lambdaWithHeader(items: List<Int>): List<Int> {
				return items.map { x -> x + 1 }
			}
			fun lambdaWithoutHeader(items: List<Int>): List<Int> {
				return items.map { it + 1 }
			}
			fun emptyBody() {}
			fun nestedLambda(items: List<Int>): List<() -> Int> {
				return items.map { x -> { x + 1 } }
			}
			""".trimIndent()
		val ktFile = createSourceFile("Main.kt", content)
		val functions = ktFile.declarations.filterIsInstance<KtNamedFunction>().associateBy { it.name }

		fun contentOf(block: KtBlockExpression): String {
			val span = contentSpanOf(block)
			return content.substring(span.start, span.end)
		}

		assertEquals("\n\treturn a + b\n", contentOf(functions.getValue("functionBody").bodyBlockExpression!!))

		val ifBody = functions.getValue("ifBody").bodyBlockExpression!!
		val ifThen = PsiTreeUtil.findChildOfType(ifBody, KtIfExpression::class.java)!!.then as KtBlockExpression
		assertEquals("\n\tif (flag) {\n\t\treturn a + b\n\t}\n\treturn 0\n", contentOf(ifBody))
		assertEquals("\n\t\treturn a + b\n\t", contentOf(ifThen))

		val lambdaWithHeaderBody =
			PsiTreeUtil
				.findChildOfType(
					functions.getValue("lambdaWithHeader").bodyBlockExpression,
					KtLambdaExpression::class.java,
				)!!
				.bodyExpression!!
		val lambdaWithHeaderContent = contentOf(lambdaWithHeaderBody)
		// The `x ->` header belongs to the enclosing function literal, not to this block.
		assertFalse(lambdaWithHeaderContent.contains("->"))
		assertEquals("x + 1", lambdaWithHeaderContent.trim())

		val lambdaWithoutHeaderBody =
			PsiTreeUtil
				.findChildOfType(
					functions.getValue("lambdaWithoutHeader").bodyBlockExpression,
					KtLambdaExpression::class.java,
				)!!
				.bodyExpression!!
		assertEquals("it + 1", contentOf(lambdaWithoutHeaderBody).trim())

		assertEquals("", contentOf(functions.getValue("emptyBody").bodyBlockExpression!!))

		// The outer lambda's sole statement is itself a lambda literal, so its text alone (`{ x + 1 }`)
		// looks brace-owned; the content must still be that whole statement, not the inner lambda's
		// interior.
		val nestedOuterLambda =
			PsiTreeUtil.findChildOfType(
				functions.getValue("nestedLambda").bodyBlockExpression,
				KtLambdaExpression::class.java,
			)!!
		val nestedOuterBody = nestedOuterLambda.bodyExpression!!
		assertEquals("{ x + 1 }", contentOf(nestedOuterBody).trim())

		val nestedInnerLambda = PsiTreeUtil.findChildOfType(nestedOuterBody, KtLambdaExpression::class.java)!!
		assertEquals("x + 1", contentOf(nestedInnerLambda.bodyExpression!!).trim())
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

	@Test
	fun `converting a Nothing-returning expression body preserves the signature`() {
		val content =
			"""
			package p
			fun boom(name: String) = error("bad " + name)
			fun demo(x: Int?): Int = x ?: boom("missing")
			""".trimIndent()

		val target = "\"bad \" + name"
		val result = plan(content, content.indexOf(target), content.indexOf(target) + target.length)
		val candidate = result.candidates.first()
		val rewrite =
			buildExtractVariableRewrite(
				fileText = result.fileText,
				candidateSpan = candidate.span,
				scope = candidate.scopes.first(),
				name = "message",
				replaceAll = false,
			)!!

		// `boom`'s inferred return type is `Nothing`; folding it into the `Unit` case would drop both
		// the `return` and the written-out `: Nothing`, and `x ?: boom(...)` would stop compiling.
		assertEquals(
			"package p\n" +
				"fun boom(name: String): Nothing {\n" +
				"\tval message = \"bad \" + name\n" +
				"\treturn error(message)\n" +
				"}\n" +
				"fun demo(x: Int?): Int = x ?: boom(\"missing\")",
			apply(content, rewrite),
		)
	}

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

	@Test
	fun `extracting from a multi-line lambda with a header on its own line is not collapsed`() {
		val content =
			"""
			package p
			fun demo(items: List<Int>): List<Int> {
				return items.map { x ->
					x + 1
				}
			}
			""".trimIndent()

		val target = "x + 1"
		val result = plan(content, content.indexOf(target), content.indexOf(target) + target.length)
		val candidate = result.candidates.first()
		// `x` is the lambda's own parameter, so the lambda is still the ceiling.
		assertEquals(listOf("lambda"), candidate.scopes.map { it.label })

		val rewrite =
			buildExtractVariableRewrite(
				fileText = result.fileText,
				candidateSpan = candidate.span,
				scope = candidate.scopes.first(),
				name = "next",
				replaceAll = false,
			)!!

		// The body already starts its own line, so this is the normal path, not the one-line
		// expansion: the header and the closing brace are left exactly where they were.
		assertEquals(
			"package p\n" +
				"fun demo(items: List<Int>): List<Int> {\n" +
				"\treturn items.map { x ->\n" +
				"\t\tval next = x + 1\n" +
				"\t\tnext\n" +
				"\t}\n" +
				"}",
			apply(content, rewrite),
		)
	}

	@Test
	fun `extracting from a multi-line lambda without a header is not collapsed`() {
		val content =
			"""
			package p
			fun demo(items: List<Int>): List<Int> {
				return items.map {
					it + 1
				}
			}
			""".trimIndent()

		val target = "it + 1"
		val result = plan(content, content.indexOf(target), content.indexOf(target) + target.length)
		val candidate = result.candidates.first()
		assertEquals(listOf("lambda"), candidate.scopes.map { it.label })

		val rewrite =
			buildExtractVariableRewrite(
				fileText = result.fileText,
				candidateSpan = candidate.span,
				scope = candidate.scopes.first(),
				name = "next",
				replaceAll = false,
			)!!

		assertEquals(
			"package p\n" +
				"fun demo(items: List<Int>): List<Int> {\n" +
				"\treturn items.map {\n" +
				"\t\tval next = it + 1\n" +
				"\t\tnext\n" +
				"\t}\n" +
				"}",
			apply(content, rewrite),
		)
	}

	@Test
	fun `offers nothing when the only rung's anchor shares the brace line of a multi-line block`() {
		val content =
			"""
			package p
			fun log(n: Int) {}
			fun demo(items: List<String>) {
				items.forEach { log(it.length + 1)
					log(it) }
			}
			""".trimIndent()

		val target = "it.length + 1"
		val result = plan(content, content.indexOf(target), content.indexOf(target) + target.length)

		// `it` is lambda-scoped, so the lambda body is the only legal rung -- and its anchor statement
		// shares the `items.forEach {` line while the block's own content spans two lines. Anchoring at
		// that line start would put the declaration before the `{`, where `it` does not exist. The rung
		// is refused, which leaves the candidate with no rung, which empties the plan: the action then
		// reports "no expression to extract here" instead of opening a sheet whose confirm must fail.
		assertTrue(result.isEmpty)
	}

	@Test
	fun `extracting from a semicolon-joined statement is refused rather than reordered`() {
		val content =
			"""
			package p
			fun demo(a: Int, b: Int): Int {
				val x = a + 1; return x + b
			}
			""".trimIndent()

		val target = "x + b"
		val result = plan(content, content.indexOf(target), content.indexOf(target) + target.length)

		/*
		 * A statement already precedes the candidate on its line, and the block spans several lines, so
		 * there is nowhere the declaration can go. A new line above the whole line reorders it in front of
		 * `val x = a + 1`, which the expression reads -- this used to emit exactly that, and it does not
		 * compile. Expanding is only sound when the whole block is the one line. Declining is the answer,
		 * and it is what the shared placement in :lsp:refactor-core now gives both languages.
		 */
		assertTrue(result.isEmpty)
	}

	@Test
	fun `an occurrence sharing the brace line is not offered for replace-all`() {
		val content =
			"""
			package p
			fun log(n: Int) {}
			fun demo(items: List<String>) {
				items.forEach { log(it.length + 1)
					log(it.length + 1) }
			}
			""".trimIndent()

		val target = "it.length + 1"
		val second = content.indexOf(target, content.indexOf(target) + 1)
		val result = plan(content, second, second + target.length)
		val candidate = result.candidates.first()

		// The second site is on its own line and can host the declaration, so the rung stands. The first
		// site shares the `items.forEach {` line, and anchoring on it would refuse the whole rewrite --
		// so it is not offered as an occurrence, and the count the sheet shows stays achievable.
		assertEquals(
			1,
			candidate.scopes
				.first()
				.occurrences.size,
		)
		assertEquals(
			listOf(TextSpan(second, second + target.length)),
			candidate.scopes.first().occurrences,
		)

		val rewrite =
			buildExtractVariableRewrite(
				fileText = result.fileText,
				candidateSpan = candidate.span,
				scope = candidate.scopes.first(),
				name = "length",
				replaceAll = true,
			)
		assertNotNull(rewrite)
	}

	@Test
	fun `a local in a sibling function does not take the name`() {
		val content =
			"""
			package p
			class Extract {
				fun lengths(items: List<String>): List<Int> {
					return items.map {
						val length = it.length + 1
						length
					}
				}

				fun oneLineLambda(items: List<String>): List<Int> {
					return items.map { it.length + 1 }
				}
			}
			""".trimIndent()

		val target = "it.length + 1"
		val start = content.indexOf(target, content.indexOf("oneLineLambda"))
		val result = plan(content, start, start + target.length)

		// `val length` lives in another function's lambda: invisible here, so naming this one `length`
		// is legal and must not be refused.
		assertNull(validateVariableName("length", result.candidates.first().takenNames, HARD_KEYWORDS))
	}

	@Test
	fun `an enclosing parameter and an enclosing local take the name`() {
		val content =
			"""
			package p
			fun wrap(n: Int): Int = n
			fun demo(items: List<String>) {
				val size = 0
				wrap(items.size * 2)
			}
			""".trimIndent()

		val taken = plan(content, content.indexOf("items.size") + 1).candidates.first().takenNames

		assertEquals(NameProblem.AlreadyTaken, validateVariableName("items", taken, HARD_KEYWORDS))
		assertEquals(NameProblem.AlreadyTaken, validateVariableName("size", taken, HARD_KEYWORDS))
	}

	@Test
	fun `a member of the enclosing class takes the name`() {
		val content =
			"""
			package p
			class Extract {
				private val total = 0

				fun demo(n: Int): Int {
					return n * 2
				}
			}
			""".trimIndent()

		val target = "n * 2"
		val taken =
			plan(content, content.indexOf(target), content.indexOf(target) + target.length)
				.candidates
				.first()
				.takenNames

		// A local `val total` would shadow the member, changing what every other `total` in the block
		// means, so it stays refused.
		assertEquals(NameProblem.AlreadyTaken, validateVariableName("total", taken, HARD_KEYWORDS))
		assertEquals(NameProblem.AlreadyTaken, validateVariableName("demo", taken, HARD_KEYWORDS))
	}

	@Test
	fun `a Unit-returning member expression body gets neither a type nor a return`() {
		val content =
			"""
			package p
			class Extract {
				fun show(text: String) = report(text.length + 1)

				private fun report(value: Int) {
					println(value)
				}
			}
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

		// The QA fixture's shape: a member, with the callee declared after the caller. `show` returns
		// `Unit`, so the block body needs neither a `return` nor a written-out type.
		assertEquals(
			"package p\n" +
				"class Extract {\n" +
				"\tfun show(text: String) {\n" +
				"\t\tval length = text.length + 1\n" +
				"\t\treport(length)\n" +
				"\t}\n" +
				"\n" +
				"\tprivate fun report(value: Int) {\n" +
				"\t\tprintln(value)\n" +
				"\t}\n" +
				"}",
			apply(content, rewrite),
		)
	}

	@Test
	fun `a whitespace-only selection resolves like a caret at its start`() {
		val content =
			"""
			package p
			fun demo(a: Int, b: Int, c: Int): Int {
				return a + b * c
			}
			""".trimIndent()

		// The gap between `b` and `*`, as a touch drag over whitespace produces it rather than a caret.
		val gap = content.indexOf("b * c") + 1
		val result = plan(content, gap, gap + 1)

		assertEquals(listOf("b", "b * c", "a + b * c"), result.candidates.map { it.label })
	}
}
