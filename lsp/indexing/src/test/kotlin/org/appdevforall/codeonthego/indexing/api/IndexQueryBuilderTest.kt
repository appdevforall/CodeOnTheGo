package org.appdevforall.codeonthego.indexing.api

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
class IndexQueryBuilderTest {
	@Test
	fun `anyOf is unaffected by later mutation of the caller's collection`() {
		val values = mutableListOf("CLASS")

		val query = indexQuery { anyOf("kind", values) }
		values.add("INTERFACE")

		assertThat(query.anyOf["kind"]).containsExactly("CLASS")
	}
}
