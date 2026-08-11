package com.itsaky.androidide.lsp.kotlin.utils.refactor

import com.itsaky.androidide.lsp.kotlin.compiler.modules.ScheduledCancelChecker
import com.itsaky.androidide.lsp.kotlin.fixtures.KtLspTest
import com.itsaky.androidide.progress.ICancelChecker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CancellationException

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

		// Types are emitted fully qualified so they resolve without an import the file may not have.
		assertEquals(listOf("b" to "kotlin.Int", "a" to "kotlin.Int"), candidate.parameters.map { it.name to it.typeText })
		assertEquals("kotlin.Int", candidate.returnTypeText)
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
		assertEquals("kotlin.Int", candidate.returnTypeText)
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
		assertEquals("kotlin.Int", candidate.returnTypeText)
		assertEquals(
			"""
			package p
			fun demo(a: Int): Int {
				val doubled = a * 2
				return finish(doubled)
			}

			private fun finish(doubled: kotlin.Int): kotlin.Int {
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

				private fun total(a: kotlin.Int, b: kotlin.Int): kotlin.Int {
					return a + b
				}
			}
			""".trimIndent(),
			apply(content, buildExtractMethodRewrites(result.fileText, candidate, "total")!!),
		)
	}

	@Test
	fun `an it bound by a lambda inside the region is not turned into a parameter`() {
		val content =
			"""
			package p
			fun log(n: Int) {}
			fun demo(names: List<Int>, extra: Int) {
				names.forEach { log(it + extra) }
			}
			""".trimIndent()
		val (start, end) = selection(content, "names.forEach", "names.forEach { log(it + extra) }")

		val candidate = plan(content, start, end).candidates.single()

		// `it` belongs to a lambda the region carries with it, so it is not captured from outside.
		assertEquals(listOf("names", "extra"), candidate.parameters.map { it.name })
	}

	@Test
	fun `a destructuring declaration read after the region is declined`() {
		val content =
			"""
			package p
			data class Point(val a: Int, val b: Int)
			fun demo(p: Point): Int {
				val (x, y) = p
				return x + y
			}
			""".trimIndent()
		val (start, end) = selection(content, "val (x, y)", "val (x, y) = p")

		val refusal = plan(content, start, end).refusal

		assertTrue(refusal is ExtractionRefusal.MultipleOutputs)
		assertEquals(listOf("x", "y"), (refusal as ExtractionRefusal.MultipleOutputs).names)
	}

	@Test
	fun `an output reassigned after the region is declined`() {
		val content =
			"""
			package p
			fun compute(): Int = 1
			fun demo(flag: Boolean): Int {
				var result = compute()
				if (flag) result = 0
				return result
			}
			""".trimIndent()
		val (start, end) = selection(content, "var result", "var result = compute()")

		// A `val` at the call site cannot carry an output the following code assigns to -- which is one
		// value the call site cannot receive, not "more than one value".
		assertEquals(ExtractionRefusal.OutputNotReturnable("result"), plan(content, start, end).refusal)
	}

	@Test
	fun `an inferred type parameter is declined even though the region names no type`() {
		val content =
			"""
			package p
			fun <T> pick(a: T, b: T): T = a
			fun <T> demo(a: T, b: T): T {
				return pick(a, b)
			}
			""".trimIndent()

		assertEquals(
			ExtractionRefusal.UsesTypeParameter("T"),
			plan(content, content.indexOf("pick(a, b)") + 1).refusal,
		)
	}

	@Test
	fun `a labelled return targeting an outer lambda is declined`() {
		val content =
			"""
			package p
			fun demo(items: List<Int>) {
				items.forEach outer@{ item ->
					listOf(item).forEach {
						if (it < 0) return@outer
						println(it)
					}
				}
			}
			""".trimIndent()
		val (start, end) = selection(content, "listOf(item).forEach {", "\t\t}")

		// The nearest lambda is in the region, but `outer@` is not.
		assertEquals(ExtractionRefusal.ExitsRegion, plan(content, start, end).refusal)
	}

	@Test
	fun `an inherited member used inside a with block is not mistaken for the receiver`() {
		val content =
			"""
			package p
			open class Base { fun helper(): Int = 1 }
			class Child : Base() {
				fun demo(n: Int): Int =
					with(n) {
						helper() + 1
					}
			}
			""".trimIndent()

		val result = plan(content, content.indexOf("helper() + 1") + 1)

		// `helper()` comes from the supertype, not from `with`'s receiver.
		assertNull(result.refusal)
		assertEquals("kotlin.Int", result.candidates.first { it.label == "helper() + 1" }.returnTypeText)
	}

	@Test
	fun `a scope receiver outside the stdlib scoping names is still declined`() {
		val content =
			"""
			package p
			class Scope { fun item(n: Int) {} }
			fun column(body: Scope.() -> Unit) {}
			fun demo() {
				column {
					item(1)
				}
			}
			""".trimIndent()
		val (start, end) = selection(content, "item(1)", "item(1)")

		assertEquals(ExtractionRefusal.InnerImplicitReceiver("column"), plan(content, start, end).refusal)
	}

	@Test
	fun `extracting from a getter inserts the new function after the whole property`() {
		val content =
			"""
			package p
			class C {
				var backing: Int = 0
				var total: Int
					get() {
						return backing + 1
					}
					set(value) {
						backing = value
					}
			}
			""".trimIndent()

		val result = plan(content, content.indexOf("backing + 1") + 1)
		val candidate = result.candidates.first { it.label == "backing + 1" }

		assertEquals(
			"""
			package p
			class C {
				var backing: Int = 0
				var total: Int
					get() {
						return next()
					}
					set(value) {
						backing = value
					}

				private fun next(): kotlin.Int {
					return backing + 1
				}
			}
			""".trimIndent(),
			apply(content, buildExtractMethodRewrites(result.fileText, candidate, "next")!!),
		)
	}

	@Test
	fun `a region using the backing field is declined`() {
		val content =
			"""
			package p
			class C {
				var n: Int = 0
					get() {
						return field + 1
					}
			}
			""".trimIndent()

		assertEquals(
			ExtractionRefusal.UsesBackingField,
			plan(content, content.indexOf("field + 1") + 1).refusal,
		)
	}

	@Test
	fun `a compound assignment through a receiver lambda is declined`() {
		val content =
			"""
			package p
			class Counter { var n = 0 }
			fun demo(c: Counter) {
				c.apply {
					n += 1
				}
			}
			""".trimIndent()
		val (start, end) = selection(content, "n += 1", "n += 1")

		// The assignment resolves to a compound access, not a member call, and used to slip through.
		assertEquals(ExtractionRefusal.InnerImplicitReceiver("apply"), plan(content, start, end).refusal)
	}

	@Test
	fun `an increment through a receiver lambda is declined`() {
		val content =
			"""
			package p
			class Counter { var n = 0 }
			fun demo(c: Counter) {
				c.apply {
					n++
				}
			}
			""".trimIndent()
		val (start, end) = selection(content, "n++", "n++")

		assertEquals(ExtractionRefusal.InnerImplicitReceiver("apply"), plan(content, start, end).refusal)
	}

	@Test
	fun `a bare this inside a receiver lambda is declined`() {
		val content =
			"""
			package p
			class Foo(val n: Int)
			fun log(f: Foo) {}
			fun demo(f: Foo) {
				f.apply {
					log(this)
				}
			}
			""".trimIndent()
		val (start, end) = selection(content, "log(this)", "log(this)")

		assertEquals(ExtractionRefusal.InnerImplicitReceiver("apply"), plan(content, start, end).refusal)
	}

	@Test
	fun `a this inside a lambda that does not rebind it is not declined`() {
		val content =
			"""
			package p
			class Foo {
				fun log(f: Foo) {}
				fun demo(items: List<Int>) {
					items.forEach {
						log(this)
					}
				}
			}
			""".trimIndent()
		val (start, end) = selection(content, "log(this)", "log(this)")

		// `forEach` binds `it`, not `this`, so `this` still means the Foo instance after the move.
		assertNull(plan(content, start, end).refusal)
	}

	@Test
	fun `a type parameter reaching only the receiver is declined`() {
		val content =
			"""
			package p
			fun log(s: String) {}
			fun <T> List<T>.summarize() {
				log("size=" + size)
			}
			""".trimIndent()
		val (start, end) = selection(content, "log(\"size=\" + size)", "log(\"size=\" + size)")

		// Nothing in the region names `T`; only the copied receiver does.
		assertEquals(ExtractionRefusal.UsesTypeParameter("T"), plan(content, start, end).refusal)
	}

	@Test
	fun `a labelled break targeting an outer loop is declined`() {
		val content =
			"""
			package p
			fun demo(rows: List<List<Int>>) {
				outer@ for (row in rows) {
					for (cell in row) {
						if (cell < 0) break@outer
						println(cell)
					}
				}
			}
			""".trimIndent()
		val (start, end) = selection(content, "for (cell in row)", "\t\t}")

		assertEquals(ExtractionRefusal.ExitsRegion, plan(content, start, end).refusal)
	}

	@Test
	fun `a labelled continue targeting an outer loop is declined`() {
		val content =
			"""
			package p
			fun demo(rows: List<List<Int>>) {
				outer@ for (row in rows) {
					for (cell in row) {
						if (cell < 0) continue@outer
						println(cell)
					}
				}
			}
			""".trimIndent()
		val (start, end) = selection(content, "for (cell in row)", "\t\t}")

		assertEquals(ExtractionRefusal.ExitsRegion, plan(content, start, end).refusal)
	}

	@Test
	fun `a local function target gets no visibility modifier`() {
		val content =
			"""
			package p
			fun demo(a: Int): Int {
				fun inner(b: Int): Int {
					return b * 2
				}
				return inner(a)
			}
			""".trimIndent()

		val result = plan(content, content.indexOf("b * 2") + 1)
		val candidate = result.candidates.first { it.label == "b * 2" }

		// `private fun` inside a block does not compile, and a local function is only visible from its
		// declaration onward -- so it must land *before* the function that calls it.
		assertEquals(emptyList<String>(), candidate.modifiers)
		assertEquals(
			"""
			package p
			fun demo(a: Int): Int {
				fun doubled(b: kotlin.Int): kotlin.Int {
					return b * 2
				}

				fun inner(b: Int): Int {
					return doubled(b)
				}
				return inner(a)
			}
			""".trimIndent(),
			apply(content, buildExtractMethodRewrites(result.fileText, candidate, "doubled")!!),
		)
	}

	@Test
	fun `a local target inserts the new function before the call site`() {
		val content =
			"""
			package p
			fun demo(a: Int): Int {
				fun inner(b: Int): Int {
					return b * 2
				}
				return inner(a)
			}
			""".trimIndent()

		val result = plan(content, content.indexOf("b * 2") + 1)
		val candidate = result.candidates.first { it.label == "b * 2" }

		assertTrue(candidate.insertOffset < candidate.span.start)
	}

	@Test
	fun `a type parameter on an extension property is declined`() {
		val content =
			"""
			package p
			val <T> List<T>.doubled: Int
				get() {
					return size * 2
				}
			""".trimIndent()
		val (start, end) = selection(content, "return size * 2", "return size * 2")

		// The accessor's type parameters live on its property, the same place its receiver does.
		assertEquals(ExtractionRefusal.UsesTypeParameter("T"), plan(content, start, end).refusal)
	}

	@Test
	fun `a smart cast to an intersection type is declined`() {
		val content =
			"""
			package p
			interface A { fun a(): Int }
			interface B { fun b(): Int }
			fun demo(x: Any): Int {
				if (x is A && x is B) {
					return x.a() + x.b()
				}
				return 0
			}
			""".trimIndent()
		val (start, end) = selection(content, "return x.a() + x.b()", "return x.a() + x.b()")

		// The narrowed type cannot be written out at all, which is not the same as not knowing it.
		assertEquals(ExtractionRefusal.SmartCastParameter("x"), plan(content, start, end).refusal)
	}

	@Test
	fun `a captured local function is declined`() {
		val content =
			"""
			package p
			fun demo(a: Int): Int {
				fun helper(): Int = 1
				return helper() + a
			}
			""".trimIndent()
		val (start, end) = selection(content, "return helper() + a", "return helper() + a")

		assertEquals(
			ExtractionRefusal.CapturedLocalDeclaration("helper"),
			plan(content, start, end).refusal,
		)
	}

	@Test
	fun `a captured local class is declined`() {
		val content =
			"""
			package p
			fun demo(a: Int): Int {
				class Holder(val n: Int)
				return Holder(a).n
			}
			""".trimIndent()
		val (start, end) = selection(content, "return Holder(a).n", "return Holder(a).n")

		assertEquals(
			ExtractionRefusal.CapturedLocalDeclaration("Holder"),
			plan(content, start, end).refusal,
		)
	}

	@Test
	fun `an extension property accessor keeps its receiver`() {
		val content =
			"""
			package p
			class Foo(val n: Int)
			fun Foo.bar(): Int = n * 2
			val Foo.doubled: Int
				get() {
					return bar() + 1
				}
			""".trimIndent()

		val result = plan(content, content.indexOf("bar() + 1") + 1)
		val candidate = result.candidates.first { it.label == "bar() + 1" }

		assertEquals("Foo", candidate.receiverTypeText)
	}

	@Test
	fun `a smart-cast parameter is declined`() {
		val content =
			"""
			package p
			fun demo(value: Any): Int {
				if (value is String) {
					return value.length + 1
				}
				return 0
			}
			""".trimIndent()

		// `value: Any` breaks the moved body; `value: String` breaks the call site.
		assertEquals(
			ExtractionRefusal.SmartCastParameter("value"),
			plan(content, content.indexOf("value.length + 1") + 1).refusal,
		)
	}

	@Test
	fun `a file the analysis cannot reach is declined as not analysable, not as a bad selection`() {
		createSourceFile("Main.kt", "package p\n")
		val missing = env.sourceRoots.first().resolve("Absent.kt")

		// "Select an expression, or whole statements inside one block" would blame a selection that
		// never got looked at.
		assertEquals(
			ExtractionRefusal.CouldNotAnalyse,
			buildExtractMethodPlan(env, missing, 0, 0, documentVersion = 1, cancelChecker = noopCancelChecker()).refusal,
		)
	}

	@Test
	fun `cancellation propagates instead of being reported as a refusal`() {
		val content =
			"""
			package p
			fun demo(a: Int): Int {
				return a * 2
			}
			""".trimIndent()
		createSourceFile("Main.kt", content)
		val path = env.sourceRoots.first().resolve("Main.kt")
		val cancelled = ScheduledCancelChecker(ICancelChecker.CANCELLED)

		// A cancelled action has no result to report; swallowing this would flash a message at a user
		// who already moved on.
		assertThrows(CancellationException::class.java) {
			buildExtractMethodPlan(
				env,
				path,
				content.indexOf("a * 2"),
				content.indexOf("a * 2") + 5,
				documentVersion = 1,
				cancelChecker = cancelled,
			)
		}
	}

	@Test
	fun `a type the file does not import is emitted fully qualified`() {
		val content =
			"""
			package p
			fun demo() {
				val d = java.util.Date()
				println(d.time)
			}
			""".trimIndent()
		val (start, end) = selection(content, "println(d.time)", "println(d.time)")

		val result = plan(content, start, end)
		val candidate = result.candidates.single()

		// `Date` came from inference, so the file names it nowhere and a short name would not resolve.
		assertEquals(listOf("d" to "java.util.Date"), candidate.parameters.map { it.name to it.typeText })
		assertEquals(
			"""
			package p
			fun demo() {
				val d = java.util.Date()
				extracted(d)
			}

			private fun extracted(d: java.util.Date) {
				println(d.time)
			}
			""".trimIndent(),
			apply(content, buildExtractMethodRewrites(result.fileText, candidate, "extracted")!!),
		)
	}

	@Test
	fun `a platform type is emitted as its lower bound rather than as String bang`() {
		val content =
			"""
			package p
			fun demo() {
				val v = System.getProperty("k")
				println(v.length)
			}
			""".trimIndent()
		val (start, end) = selection(content, "println(v.length)", "println(v.length)")

		val candidate = plan(content, start, end).candidates.single()

		// `String!` is not Kotlin syntax; the lower bound is what the moved body already assumes.
		assertEquals(listOf("v" to "kotlin.String"), candidate.parameters.map { it.name to it.typeText })
	}

	@Test
	fun `a suspend call inside a nested suspend lambda does not add the suspend modifier`() {
		val content =
			"""
			package p
			suspend fun work() {}
			fun launchIt(block: suspend () -> Unit) {}
			fun demo() {
				launchIt { work() }
				println("x")
			}
			""".trimIndent()
		val (start, end) = selection(content, "launchIt { work() }", "launchIt { work() }")

		val candidate = plan(content, start, end).candidates.single()

		// `demo` is not a suspend context, so a `suspend fun` here would not compile at the call site.
		assertEquals(listOf("private"), candidate.modifiers)
	}

	@Test
	fun `a suspend call inside an ordinary inline lambda still adds the suspend modifier`() {
		val content =
			"""
			package p
			suspend fun work(n: Int) {}
			suspend fun demo(items: List<Int>) {
				items.forEach { work(it) }
				println("x")
			}
			""".trimIndent()
		val (start, end) = selection(content, "items.forEach", "items.forEach { work(it) }")

		val candidate = plan(content, start, end).candidates.single()

		// `forEach`'s lambda runs in the caller's context, so the suspension is the new function's.
		assertEquals(listOf("private", "suspend"), candidate.modifiers)
	}

	@Test
	fun `statements inside a suspend lambda still add the suspend modifier`() {
		val content =
			"""
			package p
			suspend fun work() {}
			fun launchIt(block: suspend () -> Unit) {}
			fun demo() {
				launchIt {
					work()
				}
			}
			""".trimIndent()
		val start = content.indexOf("work()", content.indexOf("launchIt {"))

		val candidate = plan(content, start, start + "work()".length).candidates.single()

		// The region is *inside* the suspend lambda, so its own call site is a suspend context.
		assertEquals(listOf("private", "suspend"), candidate.modifiers)
	}

	@Test
	fun `a qualified selector is not turned into a parameter`() {
		val content =
			"""
			package p
			class Holder(val n: Int)
			fun demo(h: Holder): Int {
				return h.n + 1
			}
			""".trimIndent()
		val (start, end) = selection(content, "return h.n + 1", "return h.n + 1")

		val candidate = plan(content, start, end).candidates.single()

		// `n` resolves through `h` wherever the code lives; passing it would name a nonexistent local.
		assertEquals(listOf("h"), candidate.parameters.map { it.name })
	}

	@Test
	fun `a value typed by a local class is declined rather than emitted`() {
		val content =
			"""
			package p
			fun demo(): Int {
				class Holder(val n: Int)
				val h = Holder(1)
				return h.n + 1
			}
			""".trimIndent()
		val (start, end) = selection(content, "return h.n + 1", "return h.n + 1")

		// `Holder` is out of scope at the insertion point, so no parameter for `h` can be written.
		assertEquals(
			ExtractionRefusal.CapturedLocalDeclaration("Holder"),
			plan(content, start, end).refusal,
		)
	}

	@Test
	fun `a local object used as a qualifier is declined rather than dropped`() {
		val content =
			"""
			package p
			fun demo(): Int {
				object Cfg { val n = 1 }
				return Cfg.n + 1
			}
			""".trimIndent()
		val (start, end) = selection(content, "return Cfg.n + 1", "return Cfg.n + 1")

		// A class symbol is not callable, so it used to fail the capture cast and vanish silently.
		assertEquals(
			ExtractionRefusal.CapturedLocalDeclaration("Cfg"),
			plan(content, start, end).refusal,
		)
	}

	@Test
	fun `a tail return in a secondary constructor extracts a Unit function`() {
		val content =
			"""
			package p
			class Foo {
				constructor(x: Int) {
					println(x)
					return
				}
			}
			""".trimIndent()
		val (start, end) = selection(content, "println(x)", "return")

		val result = plan(content, start, end)
		val candidate = result.candidates.single()

		// A constructor's symbol returns the constructed class, but its `return` carries no value.
		assertNull(candidate.returnTypeText)
		assertEquals(
			"""
			package p
			class Foo {
				constructor(x: Int) {
					return tail(x)
				}

				private fun tail(x: kotlin.Int) {
					println(x)
					return
				}
			}
			""".trimIndent(),
			apply(content, buildExtractMethodRewrites(result.fileText, candidate, "tail")!!),
		)
	}

	@Test
	fun `a single destructuring entry read after the region is not reported as more than one value`() {
		val content =
			"""
			package p
			data class Point(val a: Int, val b: Int)
			fun demo(p: Point): Int {
				val (x, y) = p
				return x + 1
			}
			""".trimIndent()
		val (start, end) = selection(content, "val (x, y)", "val (x, y) = p")

		// One value, in a form the call site cannot receive -- not "more than one value".
		assertEquals(ExtractionRefusal.OutputNotReturnable("x"), plan(content, start, end).refusal)
	}

	@Test
	fun `a local fun target validates its name against the enclosing block, not the class`() {
		val content =
			"""
			package p
			class C {
				fun demo(a: Int): Int {
					fun inner(b: Int): Int {
						return b * 2
					}
					return inner(a)
				}
			}
			""".trimIndent()

		val candidate = plan(content, content.indexOf("b * 2") + 1).candidates.first { it.label == "b * 2" }

		// A sibling local named `inner` is what the new local `fun` would redeclare; `demo` is not.
		assertTrue("inner" in candidate.takenNames)
		assertTrue("demo" !in candidate.takenNames)
	}

	@Test
	fun `a parameter whose type cannot be written out is declined`() {
		val content =
			"""
			package p
			fun demo(): Int {
				val helper = object {
					fun value(): Int = 1
				}
				return helper.value()
			}
			""".trimIndent()

		assertEquals(
			ExtractionRefusal.UnrenderableType,
			plan(content, content.indexOf("helper.value()") + 1).refusal,
		)
	}
}
