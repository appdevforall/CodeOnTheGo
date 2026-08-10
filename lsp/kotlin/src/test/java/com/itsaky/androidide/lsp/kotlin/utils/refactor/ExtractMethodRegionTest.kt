package com.itsaky.androidide.lsp.kotlin.utils.refactor

import com.itsaky.androidide.lsp.kotlin.fixtures.KtLspTest
import org.jetbrains.kotlin.psi.KtFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

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
		// On the `+`, not `+ 1`: that lands between `a` and the space, which resolves to the `a`
		// identifier itself (also a legal candidate) rather than the binary expression.
		val region = region(twoStatements, twoStatements.indexOf("a + b") + 2)

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
	fun `a partial selection with no expression candidate still snaps to the statement`() {
		// Skips the leading `val`, as a touch drag that starts a little late routinely does. Both
		// ends land inside the same KtProperty, which is a declaration, not a legal expression
		// target, so the expression path has nothing to offer and the snapped statement wins.
		val start = twoStatements.indexOf("sum")
		val end = twoStatements.indexOf("a + b") + "a + b".length

		val region = region(twoStatements, start, end)

		assertTrue(region is ExtractionRegion.Statements)
		assertEquals(
			listOf("val sum = a + b"),
			(region as ExtractionRegion.Statements).statements.map { it.text },
		)
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
