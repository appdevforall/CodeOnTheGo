package com.itsaky.androidide.activities

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** About leaves out a row whose string is empty, so a Custom IDE Builder copy can hide it (ADFA-7313). */
class AboutSocialRowsTest {
	private fun row(
		id: Int,
		value: String,
	) = SocialRow(id, icon = 0, title = 0, value = value)

	@Test
	fun `rows with a value are all kept, in order`() {
		val rows = listOf(row(0, "https://www.appdevforall.org/"), row(1, "info@appdevforall.org"), row(2, "https://t.me/x"))

		assertThat(visibleRows(rows)).containsExactlyElementsIn(rows).inOrder()
	}

	@Test
	fun `an empty value hides its row`() {
		val rows = listOf(row(0, ""), row(1, "info@appdevforall.org"), row(2, "https://t.me/x"))

		assertThat(visibleRows(rows).map { it.id }).containsExactly(1, 2).inOrder()
	}

	@Test
	fun `a blank value hides its row`() {
		val rows = listOf(row(0, "https://www.appdevforall.org/"), row(1, " \n"), row(2, "https://t.me/x"))

		assertThat(visibleRows(rows).map { it.id }).containsExactly(0, 2).inOrder()
	}

	@Test
	fun `a value is trimmed`() {
		assertThat(visibleRows(listOf(row(0, "  https://www.appdevforall.org/ \n"))).single().value)
			.isEqualTo("https://www.appdevforall.org/")
	}
}
