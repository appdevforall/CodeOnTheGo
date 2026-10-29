package com.itsaky.androidide.preferences

import com.google.common.truth.Truth.assertThat
import com.itsaky.androidide.preferences.internal.GitPreferences
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class GitPreferencesTest {
	@Before
	fun setUp() {
		System.setProperty("androidide.test.mode", "true")
		GitPreferences.shouldAddGlobalCommitWatermark = true
	}

	@After
	fun tearDown() {
		GitPreferences.shouldAddGlobalCommitWatermark = true
	}

	@Test
	fun `global commit watermark defaults to true`() {
		assertThat(GitPreferences.shouldAddGlobalCommitWatermark).isTrue()
	}

	@Test
	fun `global commit watermark persists false when disabled`() {
		GitPreferences.shouldAddGlobalCommitWatermark = false
		assertThat(GitPreferences.shouldAddGlobalCommitWatermark).isFalse()
	}

	@Test
	fun `global commit watermark persists true when re-enabled`() {
		GitPreferences.shouldAddGlobalCommitWatermark = false
		assertThat(GitPreferences.shouldAddGlobalCommitWatermark).isFalse()

		GitPreferences.shouldAddGlobalCommitWatermark = true
		assertThat(GitPreferences.shouldAddGlobalCommitWatermark).isTrue()
	}
}
