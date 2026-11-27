package com.itsaky.androidide.utils

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * A copy made by the Custom IDE Builder empties `feedback_email` to turn feedback off (ADFA-7313).
 * [FeedbackManager.recipientOrNull] decides that, and every feedback button keys off its answer.
 */
class FeedbackRecipientTest {
	@Test
	fun `an address is kept`() {
		assertThat(FeedbackManager.recipientOrNull("feedback@appdevforall.org")).isEqualTo("feedback@appdevforall.org")
	}

	@Test
	fun `an address is trimmed`() {
		assertThat(FeedbackManager.recipientOrNull("  someone@example.org\n")).isEqualTo("someone@example.org")
	}

	@Test
	fun `an empty value means no feedback address`() {
		assertThat(FeedbackManager.recipientOrNull("")).isNull()
	}

	@Test
	fun `a blank value means no feedback address`() {
		assertThat(FeedbackManager.recipientOrNull(" \t\n")).isNull()
	}
}
