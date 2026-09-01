package com.itsaky.androidide.lsp.kotlin.utils.refactor

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The parts of [InlineVariableEdit] that are `internal` to this module.
 *
 * The rest of its tests live beside their siblings in `lsp/kotlin-compiler-impl`, where the refactor
 * suites sit. This one cannot: `internal` does not cross a module boundary, and ADFA-5010 moved that
 * suite into the carrier while these helpers stayed here. Splitting one test out is cheaper than
 * widening `isPlainIdentifier` to `public` for a test's benefit.
 */
class InlineVariableEditInternalsTest {
	@Test
	fun `a plain identifier is recognised, an expression is not`() {
		assertTrue(isPlainIdentifier("name"))
		assertTrue(isPlainIdentifier("_x2"))
		assertTrue(!isPlainIdentifier("user.name"))
		assertTrue(!isPlainIdentifier("a + b"))
		assertTrue(!isPlainIdentifier("2fast"))
		assertTrue(!isPlainIdentifier(""))
	}
}
