package com.itsaky.androidide.lsp.kotlin.utils

import com.itsaky.androidide.lsp.kotlin.compiler.modules.analyzeMaybeDangling
import com.itsaky.androidide.lsp.kotlin.compiler.read
import com.itsaky.androidide.lsp.kotlin.fixtures.KtLspTest
import com.itsaky.androidide.lsp.models.TextEdit
import org.jetbrains.kotlin.analysis.api.components.KaDiagnosticCheckerFilter
import org.jetbrains.kotlin.psi.KtFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NullSafetyFixTest : KtLspTest() {
	/** The [start, end) source offsets of the sole UNSAFE_CALL diagnostic in [ktFile]. */
	private fun unsafeCallRange(ktFile: KtFile): Pair<Int, Int> =
		env.project.read {
			analyzeMaybeDangling(ktFile) {
				ktFile
					.collectDiagnostics(KaDiagnosticCheckerFilter.EXTENDED_AND_COMMON_CHECKERS)
					.filter { it.factoryName == UNSAFE_CALL_FACTORY }
					.map { it.psi.textRange.startOffset to it.psi.textRange.endOffset }
					.single()
			}
		}

	/** The null-safety marker the diagnostic provider would store for each diagnostic in [ktFile]. */
	private fun nullSafetyMarkers(ktFile: KtFile): List<String?> =
		env.project.read {
			analyzeMaybeDangling(ktFile) {
				ktFile
					.collectDiagnostics(KaDiagnosticCheckerFilter.EXTENDED_AND_COMMON_CHECKERS)
					.map { nullSafetyFactoryFor(it.factoryName) }
			}
		}

	/** Applies a single-edit variant to [source] and returns the rewritten text. */
	private fun apply(
		source: String,
		edits: List<TextEdit>,
	): String {
		val e = edits.single()
		return source.substring(0, e.range.start.index) + e.newText + source.substring(e.range.end.index)
	}

	private fun variantsFor(
		ktFile: KtFile,
		source: String,
	): Map<NullSafetyKind, String> {
		val (start, end) = unsafeCallRange(ktFile)
		return env.project.read {
			val qe = findNullableMemberAccess(ktFile, start, end)!!
			nullSafetyVariants(qe).associate { it.kind to apply(source, it.edits) }
		}
	}

	@Test
	fun `rewrites nullable property access`() {
		val source =
			"""
			package p
			class Box { val prop: Int = 0 }
			fun f(b: Box?) { val x = b.prop }
			""".trimIndent()
		val ktFile = createSourceFile("Prop.kt", source)
		val variants = variantsFor(ktFile, source)

		assertEquals("fun f(b: Box?) { val x = b!!.prop }", variants[NullSafetyKind.ASSERT_NON_NULL]!!.lineSequence().last())
		assertEquals("fun f(b: Box?) { val x = b?.prop }", variants[NullSafetyKind.SAFE_CALL]!!.lineSequence().last())
		assertEquals(
			"fun f(b: Box?) { val x = (b ?: TODO()).prop }",
			variants[NullSafetyKind.ELVIS]!!.lineSequence().last(),
		)
	}

	@Test
	fun `rewrites nullable method call`() {
		val source =
			"""
			package p
			class Box { fun member() {} }
			fun f(b: Box?) { b.member() }
			""".trimIndent()
		val ktFile = createSourceFile("Call.kt", source)
		val variants = variantsFor(ktFile, source)

		assertEquals("fun f(b: Box?) { b!!.member() }", variants[NullSafetyKind.ASSERT_NON_NULL]!!.lineSequence().last())
		assertEquals("fun f(b: Box?) { b?.member() }", variants[NullSafetyKind.SAFE_CALL]!!.lineSequence().last())
		assertEquals(
			"fun f(b: Box?) { (b ?: TODO()).member() }",
			variants[NullSafetyKind.ELVIS]!!.lineSequence().last(),
		)
	}

	@Test
	fun `elvis stays valid inside a larger expression`() {
		// The Elvis variant wraps only the receiver, so the top-level access keeps its precedence
		// and the rewrite is valid even when the access is an operand of a tighter-binding operator.
		val source =
			"""
			package p
			class Box { val n: Int = 0 }
			fun f(b: Box?) { val x = 1 + b.n }
			""".trimIndent()
		val ktFile = createSourceFile("Nested.kt", source)
		val variants = variantsFor(ktFile, source)

		assertEquals(
			"fun f(b: Box?) { val x = 1 + (b ?: TODO()).n }",
			variants[NullSafetyKind.ELVIS]!!.lineSequence().last(),
		)
	}

	@Test
	fun `rewrites when receiver is itself a chained access`() {
		val source =
			"""
			package p
			class Inner { fun member() {} }
			class Outer { val inner: Inner? = null }
			fun f(o: Outer) { o.inner.member() }
			""".trimIndent()
		val ktFile = createSourceFile("Chain.kt", source)
		val variants = variantsFor(ktFile, source)

		// UNSAFE_CALL is on the outer `.member()` whose receiver is `o.inner`; the fix targets that.
		assertEquals("fun f(o: Outer) { o.inner!!.member() }", variants[NullSafetyKind.ASSERT_NON_NULL]!!.lineSequence().last())
		assertEquals("fun f(o: Outer) { o.inner?.member() }", variants[NullSafetyKind.SAFE_CALL]!!.lineSequence().last())
		assertEquals(
			"fun f(o: Outer) { (o.inner ?: TODO()).member() }",
			variants[NullSafetyKind.ELVIS]!!.lineSequence().last(),
		)
	}

	@Test
	fun `real UNSAFE_CALL diagnostic carries the null-safety marker`() {
		// Guards the trigger, not just the transform: the string the provider stores (and the action
		// gates on) must equal the factory name the analysis API actually produces for an unsafe call.
		val ktFile =
			createSourceFile(
				"Marker.kt",
				"""
				package p
				class Box { val prop: Int = 0 }
				fun f(b: Box?) { val x = b.prop }
				""".trimIndent(),
			)
		assertTrue(UNSAFE_CALL_FACTORY in nullSafetyMarkers(ktFile))
	}

	@Test
	fun `non-nullability diagnostics carry no null-safety marker`() {
		val ktFile =
			createSourceFile(
				"NoMarker.kt",
				"""
				package p
				fun f() { val x: Int = "not an int" }
				""".trimIndent(),
			)
		val markers = nullSafetyMarkers(ktFile)
		assertTrue("expected at least one diagnostic", markers.isNotEmpty())
		assertTrue("no diagnostic should be flagged null-safety", markers.all { it == null })
	}

	@Test
	fun `no member access for a mismatched range`() {
		val source =
			"""
			package p
			fun f() { val x = 1 }
			""".trimIndent()
		val ktFile = createSourceFile("None.kt", source)
		env.project.read {
			assertNull(findNullableMemberAccess(ktFile, 0, source.length))
		}
	}
}
