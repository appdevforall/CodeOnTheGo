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
