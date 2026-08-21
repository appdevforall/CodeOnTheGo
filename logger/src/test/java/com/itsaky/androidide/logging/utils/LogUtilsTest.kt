package com.itsaky.androidide.logging.utils

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/**
 * Pins the two properties Quick Build's `QB-` logcat tag convention depends on: a hyphen
 * survives the sanitiser, and a name at or under [LogUtils.MAX_TAG_LENGTH] is not trimmed.
 */
@RunWith(JUnit4::class)
class LogUtilsTest {
	@Test
	fun `a hyphenated tag at the length limit survives unchanged`() {
		val tag = "QB-DaemonController"

		assertThat(tag.length).isAtMost(LogUtils.MAX_TAG_LENGTH)
		assertThat(LogUtils.processLogTag(tag)).isEqualTo(tag)
	}

	@Test
	fun `a tag exactly at the limit survives unchanged`() {
		val tag = "x".repeat(LogUtils.MAX_TAG_LENGTH)

		assertThat(LogUtils.processLogTag(tag)).isEqualTo(tag)
	}

	@Test
	fun `an over-length tag keeps its tail behind a double-dot prefix`() {
		val tag = "QuickBuildSessionManager"

		val processed = LogUtils.processLogTag(tag)

		assertThat(tag.length).isGreaterThan(LogUtils.MAX_TAG_LENGTH)
		assertThat(processed).hasLength(LogUtils.MAX_TAG_LENGTH)
		assertThat(processed).isEqualTo("..ckBuildSessionManager")
	}

	@Test
	fun `characters outside the allowed set become underscores`() {
		assertThat(LogUtils.processLogTag("QB Session!")).isEqualTo("QB_Session_")
	}

	@Test
	fun `a null tag stays null`() {
		assertThat(LogUtils.processLogTag(null)).isNull()
	}
}
