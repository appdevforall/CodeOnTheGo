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
 * The parts of the plan that need real resolution: the target from either cursor position, the
 * reference set by symbol identity, the cutoff, the deletion rule, the mode table and one case per
 * refusal reason.
 *
 * Where a rewrite is produced the assertion is on the resulting file text, which is the only
 * assertion that catches an indentation or off-by-one error.
 */
class InlineVariablePlanEndToEndTest : KtLspTest() {
	private fun plan(
		content: String,
		offset: Int,
	): InlineVariablePlan {
		createSourceFile("Main.kt", content)
		val path = env.sourceRoots.first().resolve("Main.kt")
		return buildInlineVariablePlan(env, path, offset, documentVersion = 1, cancelChecker = noopCancelChecker())
	}

	private fun apply(
		text: String,
		rewrites: List<RewriteSpan>,
	): String =
		rewrites.fold(text) { current, rewrite ->
			current.substring(0, rewrite.span.start) + rewrite.newText + current.substring(rewrite.span.end)
		}

	/** The offset of [fragment]'s first character, skipping [after] occurrences of it. */
	private fun at(
		content: String,
		fragment: String,
		after: Int = 0,
	): Int {
		var index = -1
		repeat(after + 1) { index = content.indexOf(fragment, index + 1) }
		return index
	}

	@Test
	fun `both cursor positions resolve the same target`() {
		val content =
			"""
			package p
			fun demo(a: Int, b: Int): Int {
				val total = a + b
				return total * 2
			}
			""".trimIndent()

		val onName = plan(content, at(content, "total"))
		val onReference = plan(content, at(content, "total", after = 1))

		assertEquals("total", onName.variableName)
		assertEquals(InlineCursorPosition.Declaration, onName.cursorPosition)
		assertEquals(-1, onName.cursorReferenceIndex)
		assertEquals("total", onReference.variableName)
		assertEquals(InlineCursorPosition.Reference, onReference.cursorPosition)
		assertEquals(0, onReference.cursorReferenceIndex)
		assertEquals(1, onName.references.size)
		assertEquals(listOf(InlineMode.AllReferences), onReference.modes)
	}

	@Test
	fun `a whole inline rewrites every reference and removes the declaration`() {
		val content =
			"""
			package p
			fun demo(a: Int, b: Int): Int {
				val total = a + b
				return total * 2
			}
			""".trimIndent()

		val result = plan(content, at(content, "total"))
		val rewrites = buildInlineVariableRewrites(result, InlineMode.AllReferences)!!

		assertTrue(result.canDeleteDeclaration)
		assertEquals(
			"""
			package p
			fun demo(a: Int, b: Int): Int {
				return (a + b) * 2
			}
			""".trimIndent(),
			apply(content, rewrites),
		)
	}

	@Test
	fun `a reference with two or more inlinable offers both modes`() {
		val content =
			"""
			package p
			fun demo(a: Int): Int {
				val x = a
				return x + x
			}
			""".trimIndent()

		val result = plan(content, at(content, "x", after = 1))

		assertEquals(2, result.references.size)
		assertEquals(listOf(InlineMode.ThisReferenceOnly, InlineMode.AllReferences), result.modes)
		assertTrue(result.offersChoice)
	}

	@Test
	fun `a shadowing declaration's name is not mistaken for a reference`() {
		val content =
			"""
			package p
			fun demo(a: Int): Int {
				val x = a
				val outer = x
				return run {
					val x = 99
					x
				}
			}
			""".trimIndent()

		val result = plan(content, at(content, "x"))

		// Matching is by symbol identity, never by name text: the inner `val x` and its use belong to a
		// different declaration.
		assertEquals(1, result.references.size)
		assertEquals(at(content, "x", after = 1), result.references.single().span.start)
	}

	@Test
	fun `a var read after its own reassignment is past the cutoff`() {
		val content =
			"""
			package p
			fun demo(): Int {
				var count = 1
				val a = count
				val b = count
				count = 2
				return a + b + count
			}
			""".trimIndent()

		val result = plan(content, at(content, "count"))

		assertEquals(3, result.references.size)
		assertEquals(2, result.inlinableReferences.size)
		assertEquals(InlineExclusion.PastCutoff, result.references.last().exclusion)
		assertEquals(false, result.canDeleteDeclaration)
		assertEquals(InlineReport.InlinedPartially(2, 3, "count"), result.reportFor(InlineMode.AllReferences))
	}

	@Test
	fun `a write to a mutable the initializer reads sets the cutoff`() {
		val content =
			"""
			package p
			fun demo(): Int {
				var limit = 1
				val bound = limit + 1
				val first = bound
				limit = 5
				val second = bound
				return first + second
			}
			""".trimIndent()

		val result = plan(content, at(content, "bound"))

		assertEquals(2, result.references.size)
		assertNull(result.references.first().exclusion)
		assertEquals(InlineExclusion.PastCutoff, result.references.last().exclusion)
		assertEquals(false, result.canDeleteDeclaration)
	}

	@Test
	fun `a var with a later write keeps its declaration even when every read is inlined`() {
		val content =
			"""
			package p
			fun demo(): Int {
				var count = 1
				val a = count
				count = 2
				return a
			}
			""".trimIndent()

		val result = plan(content, at(content, "count"))

		// Removing the write would be dead-store elimination, which is not this refactoring.
		assertEquals(1, result.inlinableReferences.size)
		assertEquals(false, result.canDeleteDeclaration)
		assertEquals(
			InlineReport.InlinedKeepingDeclaration(1, "count"),
			result.reportFor(InlineMode.AllReferences),
		)
	}

	@Test
	fun `a reference inside a string template is recorded as a short-form entry`() {
		val content =
			"""
			package p
			fun demo(a: Int, b: Int): String {
				val sum = a + b
				return "total: ${'$'}sum"
			}
			""".trimIndent()

		val result = plan(content, at(content, "sum"))
		val reference = result.references.single()

		assertTrue(reference.isShortTemplateEntry)
		// The span covers the whole entry, `$sum`, so the substitution can emit `${a + b}`.
		assertEquals(at(content, "${'$'}sum"), reference.span.start)
		assertEquals(
			"""
			package p
			fun demo(a: Int, b: Int): String {
				return "total: ${'$'}{a + b}"
			}
			""".trimIndent(),
			apply(content, buildInlineVariableRewrites(result, InlineMode.AllReferences)!!),
		)
	}

	@Test
	fun `an initializer needing no parentheses is classified as atomic`() {
		val content =
			"""
			package p
			class User(val name: String)
			fun f(s: String) = s
			fun demo(user: User): String {
				val name = user.name
				return f(name)
			}
			""".trimIndent()

		// Occurrence count: the class's own `val name` is 0, the local declaration's `name` is 1, its
		// `user.name` initializer is 2, so the reference in `f(name)` is 3.
		val result = plan(content, at(content, "name", after = 3))

		assertEquals(false, result.initializerNeedsParentheses)
		assertEquals("user.name", result.initializerText)
	}

	@Test
	fun `an unused local is refused as never used`() {
		val content =
			"""
			package p
			fun demo(a: Int) {
				val unused = a
			}
			""".trimIndent()

		assertEquals(InlineRefusal.NeverUsed("unused"), plan(content, at(content, "unused")).refusal)
	}

	@Test
	fun `a member property is refused as not local`() {
		val content =
			"""
			package p
			class C {
				val size = 1
				fun demo(): Int = size
			}
			""".trimIndent()

		assertEquals(InlineRefusal.NotALocalVariable, plan(content, at(content, "size")).refusal)
	}

	@Test
	fun `a local with no initializer is refused before its declared type is considered`() {
		val content =
			"""
			package p
			fun demo(): Int {
				val x: Int
				x = 1
				return x
			}
			""".trimIndent()

		// This shape carries an explicit type too, and "has no value at its declaration"
		// is the truthful reason.
		assertEquals(InlineRefusal.NoInitializer("x"), plan(content, at(content, "x")).refusal)
	}

	@Test
	fun `an explicit type refuses and names the type`() {
		val content =
			"""
			package p
			fun demo(): Long {
				val x: Long = 1
				return x
			}
			""".trimIndent()

		assertEquals(
			InlineRefusal.DeclaredTypeIsLoadBearing("x", "Long"),
			plan(content, at(content, "x")).refusal,
		)
	}

	@Test
	fun `a destructuring entry is refused specifically`() {
		val content =
			"""
			package p
			fun demo(pair: Pair<Int, Int>): Int {
				val (first, second) = pair
				return first + second
			}
			""".trimIndent()

		assertEquals(
			InlineRefusal.DestructuringDeclaration,
			plan(content, at(content, "first")).refusal,
		)
	}

	@Test
	fun `a cursor on nothing inlinable is refused as not a variable`() {
		val content =
			"""
			package p
			fun demo(a: Int): Int {
				return a
			}
			""".trimIndent()

		// A parameter is not a KtProperty, so it is excluded by construction rather than by a check.
		assertEquals(InlineRefusal.NotAVariable, plan(content, at(content, "a", after = 1)).refusal)
	}

	@Test
	fun `a cursor on a reference past the cutoff refuses rather than rewriting the others`() {
		val content =
			"""
			package p
			fun demo(): Int {
				var count = 1
				val a = count
				count = 2
				return count
			}
			""".trimIndent()

		assertEquals(
			InlineRefusal.ReferenceNotInlinable("count"),
			plan(content, at(content, "return count") + "return ".length).refusal,
		)
	}

	@Test
	fun `a file the analysis cannot reach is refused as not analysable`() {
		createSourceFile("Main.kt", "package p\n")
		val missing = env.sourceRoots.first().resolve("Absent.kt")

		// "Place the cursor on a local variable" would blame a cursor nothing ever looked at.
		assertEquals(
			InlineRefusal.CouldNotAnalyse,
			buildInlineVariablePlan(env, missing, 0, documentVersion = 1, cancelChecker = noopCancelChecker()).refusal,
		)
	}

	@Test
	fun `cancellation propagates instead of being reported as a refusal`() {
		val content =
			"""
			package p
			fun demo(a: Int): Int {
				val x = a
				return x
			}
			""".trimIndent()
		createSourceFile("Main.kt", content)
		val path = env.sourceRoots.first().resolve("Main.kt")
		val cancelled = ScheduledCancelChecker(ICancelChecker.CANCELLED)

		assertThrows(CancellationException::class.java) {
			buildInlineVariablePlan(env, path, at(content, "x"), documentVersion = 1, cancelChecker = cancelled)
		}
	}

	@Test
	fun `a one-line declaration inlines without taking the rest of the line`() {
		val content =
			"""
			package p
			fun g(n: Int) = n
			fun demo(): Int {
				val x = 1; return g(x)
			}
			""".trimIndent()

		val result = plan(content, at(content, "x"))

		assertEquals(
			"""
			package p
			fun g(n: Int) = n
			fun demo(): Int {
				return g(1)
			}
			""".trimIndent(),
			apply(content, buildInlineVariableRewrites(result, InlineMode.AllReferences)!!),
		)
	}

	@Test
	fun `a shadowed reference is left untouched and the declaration is kept`() {
		val content =
			"""
			package p
			fun f(n: Int) = n
			fun demo(): Int {
				val a = 1
				val x = a + 1
				return run {
					val a = 99
					f(x)
				}
			}
			""".trimIndent()

		val result = plan(content, at(content, "val x") + "val ".length)

		// Inlining would produce `f(a + 1)` reading the inner `a`.
		assertEquals(1, result.references.size)
		assertEquals(InlineExclusion.Shadowed, result.references.single().exclusion)
		assertEquals(InlineRefusal.NothingInlinable("x"), result.refusal)
	}

	@Test
	fun `a reference under a different implicit receiver is left untouched`() {
		val content =
			"""
			package p
			class Other {
				val label: String = "other"
			}
			class Holder {
				val label: String = "holder"

				fun demo(other: Other): String {
					val text = label + "!"
					return with(other) { text }
				}
			}
			""".trimIndent()

		val result = plan(content, at(content, "val text") + "val ".length)

		assertEquals(InlineExclusion.ReceiverShift, result.references.single().exclusion)
	}

	@Test
	fun `an implicit-receiver initializer is fine where no lambda changes the receiver`() {
		val content =
			"""
			package p
			class Holder {
				val label: String = "holder"

				fun demo(): String {
					val text = label + "!"
					return text
				}
			}
			""".trimIndent()

		val result = plan(content, at(content, "val text") + "val ".length)

		// Only the conjunction of both questions is a problem; either alone is not.
		assertNull(result.references.single().exclusion)
	}

	@Test
	fun `a smart-cast reference is left untouched`() {
		val content =
			"""
			package p
			class Box(val value: String?)
			fun demo(box: Box): Int {
				val b = box.value
				return if (b != null) b.length else 0
			}
			""".trimIndent()

		val result = plan(content, at(content, "val b") + "val ".length)

		// `box.value.length` does not compile: a smart cast needs a stable value.
		assertEquals(2, result.references.size)
		assertNull(result.references.first().exclusion)
		assertEquals(InlineExclusion.SmartCast, result.references.last().exclusion)
		assertEquals(false, result.canDeleteDeclaration)
	}

	@Test
	fun `a lambda initializer in call position is left untouched`() {
		val content =
			"""
			package p
			fun demo(): Int {
				val f = { n: Int -> n * 2 }
				return f(3)
			}
			""".trimIndent()

		val result = plan(content, at(content, "val f") + "val ".length)

		// The substitution would be a lambda literal in call position, which needs `.invoke()`.
		assertEquals(InlineExclusion.InvokesLambdaInitializer, result.references.single().exclusion)
		assertEquals(InlineRefusal.NothingInlinable("f"), result.refusal)
	}

	@Test
	fun `a lambda initializer passed as an argument stays inlinable`() {
		val content =
			"""
			package p
			fun call(f: (Int) -> Int): Int = f(1)
			fun demo(): Int {
				val f = { n: Int -> n * 2 }
				return call(f)
			}
			""".trimIndent()

		val result = plan(content, at(content, "val f") + "val ".length)

		assertNull(result.references.single().exclusion)
		assertEquals(
			"""
			package p
			fun call(f: (Int) -> Int): Int = f(1)
			fun demo(): Int {
				return call({ n: Int -> n * 2 })
			}
			""".trimIndent(),
			apply(content, buildInlineVariableRewrites(result, InlineMode.AllReferences)!!),
		)
	}
}
