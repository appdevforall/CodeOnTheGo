package com.itsaky.androidide.lsp.kotlin.utils.refactor

import com.itsaky.androidide.lsp.kotlin.fixtures.KtLspTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
	fun `a selection matching an expression exactly short-circuits the chooser`() {
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

		assertTrue(result.selectionMatchedCandidate)
		assertEquals("n * 2", result.candidates.first().label)
	}

	@Test
	fun `an off-boundary selection still resolves, without short-circuiting`() {
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

		assertFalse(result.selectionMatchedCandidate)
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
			fun area(r: Int) {
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
}
