package com.itsaky.androidide.fragments.git

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class GitCommitWatermarkTest {
	private val fragment = GitBottomSheetFragment()
	private val watermark = "Made with Code on the Go (appdevforall.org)"

	@Test
	fun `formatCommitMessage appends watermark to summary and description`() {
		val summary = "feat: add user profile"
		val description = "Detailed description of user profile implementation."

		val result = fragment.formatCommitMessage(summary, description, watermark)

		assertThat(result).isEqualTo("$summary\n\n$description\n\n$watermark")
	}

	@Test
	fun `formatCommitMessage appends watermark when description is null or empty`() {
		val summary = "fix: resolve crash on startup"

		val resultNullDesc = fragment.formatCommitMessage(summary, null, watermark)
		assertThat(resultNullDesc).isEqualTo("$summary\n\n$watermark")

		val resultEmptyDesc = fragment.formatCommitMessage(summary, "", watermark)
		assertThat(resultEmptyDesc).isEqualTo("$summary\n\n$watermark")

		val resultWhitespaceDesc = fragment.formatCommitMessage(summary, "   \n\n  ", watermark)
		assertThat(resultWhitespaceDesc).isEqualTo("$summary\n\n$watermark")
	}

	@Test
	fun `formatCommitMessage omits watermark when watermark is null or empty`() {
		val summary = "chore: update dependencies"
		val description = "Bump gradle plugins"

		val resultNull = fragment.formatCommitMessage(summary, description, null)
		assertThat(resultNull).isEqualTo("$summary\n\n$description")

		val resultEmpty = fragment.formatCommitMessage(summary, description, "")
		assertThat(resultEmpty).isEqualTo("$summary\n\n$description")

		val resultBlank = fragment.formatCommitMessage(summary, description, "   ")
		assertThat(resultBlank).isEqualTo("$summary\n\n$description")
	}

	@Test
	fun `formatCommitMessage deduplicates when summary already contains watermark`() {
		val summary = "docs: Made with Code on the Go (appdevforall.org)"
		val description = "Updated readme with attribution."

		val result = fragment.formatCommitMessage(summary, description, watermark)

		assertThat(result).isEqualTo("$summary\n\n$description")
		assertThat(result.split(watermark).size - 1).isEqualTo(1)
	}

	@Test
	fun `formatCommitMessage deduplicates when description already contains watermark`() {
		val summary = "feat: new feature"
		val description = "Some notes\nMade with Code on the Go (appdevforall.org)"

		val result = fragment.formatCommitMessage(summary, description, watermark)

		assertThat(result).isEqualTo("$summary\n\n$description")
		assertThat(result.split(watermark).size - 1).isEqualTo(1)
	}

	@Test
	fun `formatCommitMessage deduplicates case-insensitively`() {
		val summary = "feat: new feature"
		val description = "notes\nmade with code on the go (appdevforall.org)"

		val result = fragment.formatCommitMessage(summary, description, watermark)

		assertThat(result).isEqualTo("$summary\n\n$description")
	}
}
