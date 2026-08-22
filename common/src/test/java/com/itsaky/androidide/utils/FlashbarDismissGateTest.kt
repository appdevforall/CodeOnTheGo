package com.itsaky.androidide.utils

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Pins the flag gate on indefinite-error-bar touch dismissal: flag-off users keep the
 * pre-existing Dismiss-button-only behavior (an accidental brush must not dismiss an
 * unread error); tap-anywhere/swipe dismissal is experiments-only until it ships on its
 * own sign-off. Without the gate in [indefiniteErrorBarDismissesOnTouch], the flag-off
 * test goes red.
 */
class FlashbarDismissGateTest {
	@get:Rule
	val tempFolder = TemporaryFolder()

	private lateinit var originalResolver: (String) -> File

	@Before
	fun setUp() {
		originalResolver = FeatureFlags.flagFileResolver
		FeatureFlags.resetForTest()
		FeatureFlags.flagFileResolver = { name -> File(tempFolder.root, name) }
	}

	@After
	fun tearDown() {
		FeatureFlags.flagFileResolver = originalResolver
		FeatureFlags.resetForTest()
	}

	@Test
	fun `flag-off - indefinite error bars do not dismiss on touch`() =
		runTest {
			FeatureFlags.initialize()

			assertThat(indefiniteErrorBarDismissesOnTouch()).isFalse()
		}

	@Test
	fun `experiments on - indefinite error bars dismiss on touch`() =
		runTest {
			tempFolder.newFile("CodeOnTheGo.exp")
			FeatureFlags.initialize()

			assertThat(indefiniteErrorBarDismissesOnTouch()).isTrue()
		}
}
