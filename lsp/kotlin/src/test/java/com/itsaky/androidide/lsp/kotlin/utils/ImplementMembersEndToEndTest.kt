package com.itsaky.androidide.lsp.kotlin.utils

import com.itsaky.androidide.lsp.kotlin.actions.ImplementMembersAction
import com.itsaky.androidide.lsp.kotlin.fixtures.KtLspTest
import com.itsaky.androidide.lsp.models.TextEdit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ImplementMembersEndToEndTest : KtLspTest() {
	/** Writes [content] to Main.kt and runs the action with the caret at [caret]. */
	private fun edits(
		content: String,
		caret: Int,
	): List<TextEdit> {
		createSourceFile("Main.kt", content)
		val mainPath = env.sourceRoots.first().resolve("Main.kt")
		return ImplementMembersAction().computeImplementMembersEdit(env, mainPath, caret)
	}

	/** Applies a single edit's newText over its [TextEdit.range] index span, returning the resulting text. */
	private fun apply(
		content: String,
		edit: TextEdit,
	): String = content.substring(0, edit.range.start.index) + edit.newText + content.substring(edit.range.end.index)

	@Test
	fun `adds body and stub to a class with no body`() {
		val content =
			"""
			package p
			interface I { fun foo() }
			class C : I
			""".trimIndent()
		val result = edits(content, content.indexOf("class C") + 2)
		assertEquals(1, result.size)
		assertEquals(
			"""
			package p
			interface I { fun foo() }
			class C : I {
				override fun foo() {
					TODO("Not yet implemented")
				}
			}
			""".trimIndent(),
			apply(content, result.single()),
		)
	}

	@Test
	fun `adds body and stub to an object with no body`() {
		val content =
			"""
			package p
			interface I { fun foo() }
			object O : I
			""".trimIndent()
		val result = edits(content, content.indexOf("object O") + 2)
		assertEquals(
			"""
			package p
			interface I { fun foo() }
			object O : I {
				override fun foo() {
					TODO("Not yet implemented")
				}
			}
			""".trimIndent(),
			apply(content, result.single()),
		)
	}

	@Test
	fun `indents a nested class one level deeper`() {
		val content =
			"""
			package p
			interface I { fun foo() }
			class Outer {
				class C : I
			}
			""".trimIndent()
		val result = edits(content, content.indexOf("class C") + 2)
		assertEquals(
			"""
			package p
			interface I { fun foo() }
			class Outer {
				class C : I {
					override fun foo() {
						TODO("Not yet implemented")
					}
				}
			}
			""".trimIndent(),
			apply(content, result.single()),
		)
	}

	@Test
	fun `matches the file's space indentation`() {
		// Explicit \n strings so the 4-space indentation is unambiguous (the test file itself uses tabs).
		val content =
			"package p\n" +
				"interface I {\n" +
				"    fun foo()\n" +
				"}\n" +
				"class C : I"
		val result = edits(content, content.indexOf("class C") + 2)
		val expected =
			"package p\n" +
				"interface I {\n" +
				"    fun foo()\n" +
				"}\n" +
				"class C : I {\n" +
				"    override fun foo() {\n" +
				"        TODO(\"Not yet implemented\")\n" +
				"    }\n" +
				"}"
		assertEquals(expected, apply(content, result.single()))
	}

	@Test
	fun `inserts after existing members`() {
		val content =
			"""
			package p
			interface I { fun foo() }
			class C : I {
				val x = 1
			}
			""".trimIndent()
		val result = edits(content, content.indexOf("val x"))
		assertEquals(
			"""
			package p
			interface I { fun foo() }
			class C : I {
				val x = 1

				override fun foo() {
					TODO("Not yet implemented")
				}
			}
			""".trimIndent(),
			apply(content, result.single()),
		)
	}

	@Test
	fun `fills an empty body`() {
		val content =
			"""
			package p
			interface I { fun foo() }
			class C : I {}
			""".trimIndent()
		val result = edits(content, content.indexOf("class C") + 2)
		assertEquals(
			"""
			package p
			interface I { fun foo() }
			class C : I {
				override fun foo() {
					TODO("Not yet implemented")
				}
			}
			""".trimIndent(),
			apply(content, result.single()),
		)
	}

	@Test
	fun `no edit when caret is not in a class`() {
		val content =
			"""
			package p
			interface I { fun foo() }
			class C : I
			""".trimIndent()
		assertTrue(edits(content, content.indexOf("package")).isEmpty())
	}

	@Test
	fun `no edit when class already implements everything`() {
		val content =
			"""
			package p
			interface I { fun foo() }
			class C : I {
				override fun foo() {}
			}
			""".trimIndent()
		assertTrue(edits(content, content.indexOf("class C") + 2).isEmpty())
	}

	@Test
	fun `no edit for an abstract class`() {
		val content =
			"""
			package p
			interface I { fun foo() }
			abstract class C : I
			""".trimIndent()
		assertTrue(edits(content, content.indexOf("class C") + 2).isEmpty())
	}
}
