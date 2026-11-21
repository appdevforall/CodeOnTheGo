package com.itsaky.androidide.gradle.sqlite

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.objectweb.asm.Type

/**
 * [SqliteRewriter.mergeCursorValues] on its own. The build-level tests cannot pin the `renamedCursorArray` tag: the
 * analysis carries the first branch's value to a store anyway, so a merge that wrongly drops or keeps the tag still
 * passes them.
 */
class SqliteRewriterMergeTest {
	// BasicInterpreter types every array reference as Object, so both merged values have the same type, as in a real frame
	private val objectType = Type.getObjectType("java/lang/Object")
	private val sqliteCursorArray = Type.getType("[Lio/requery/android/database/sqlite/SQLiteCursor;")
	private val subclassArray = Type.getType("[Lp/MyCursor;")

	private fun tagged(tag: Type?) = SqliteRewriter.StaticCursorValue(objectType, false, null, false, true, tag)

	private fun merged(
		a: SqliteRewriter.StaticCursorValue,
		b: SqliteRewriter.StaticCursorValue,
	) = (SqliteRewriter.mergeCursorValues(a, b) as SqliteRewriter.StaticCursorValue).renamedCursorArray

	@Test
	fun `two values tagged with the same dimension keep the tag`() {
		assertThat(merged(tagged(sqliteCursorArray), tagged(sqliteCursorArray))).isEqualTo(sqliteCursorArray)
	}

	@Test
	fun `values tagged with different dimensions drop the tag`() {
		val twoDimensions = Type.getType("[[Lio/requery/android/database/sqlite/SQLiteCursor;")
		assertThat(merged(tagged(sqliteCursorArray), tagged(twoDimensions))).isNull()
		assertThat(merged(tagged(twoDimensions), tagged(sqliteCursorArray))).isNull()
	}

	@Test
	fun `a value without a tag drops the tag`() {
		assertThat(merged(tagged(sqliteCursorArray), tagged(null))).isNull()
		assertThat(merged(tagged(null), tagged(sqliteCursorArray))).isNull()
		assertThat(merged(tagged(null), tagged(null))).isNull()
	}

	@Test
	fun `an SQLiteCursor array and a subclass array of the same dimension keep the tag`() {
		assertThat(merged(tagged(sqliteCursorArray), tagged(subclassArray))).isEqualTo(sqliteCursorArray)
		assertThat(merged(tagged(subclassArray), tagged(sqliteCursorArray))).isEqualTo(subclassArray)
	}
}
