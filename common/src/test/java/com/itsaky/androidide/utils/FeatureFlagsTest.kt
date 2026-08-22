package com.itsaky.androidide.utils

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

/**
 * Pins the [FeatureFlags] load semantics: the `loaded` latch (one disk read per process),
 * a failed read retrying on the next [FeatureFlags.initialize] instead of latching, and
 * [FeatureFlags.refresh] replacing an already-latched snapshot (the direct-boot all-false
 * snapshot must not stick).
 */
class FeatureFlagsTest {
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
	fun `initialize reads the sentinel files`() =
		runTest {
			tempFolder.newFile("CodeOnTheGo.exp")

			FeatureFlags.initialize()

			assertThat(FeatureFlags.isExperimentsEnabled).isTrue()
			assertThat(FeatureFlags.isDebugLoggingEnabled).isFalse()
			assertThat(FeatureFlags.isQuickBuildBenchEnabled).isFalse()
		}

	@Test
	fun `initialize latches - a second call does not re-read disk`() =
		runTest {
			FeatureFlags.initialize()
			assertThat(FeatureFlags.isExperimentsEnabled).isFalse()

			// The file appearing after the first read changes nothing in a running process.
			tempFolder.newFile("CodeOnTheGo.exp")
			FeatureFlags.initialize()

			assertThat(FeatureFlags.isExperimentsEnabled).isFalse()
		}

	@Test
	fun `a failed read does not latch - the next initialize retries`() =
		runTest {
			FeatureFlags.flagFileResolver = { throw IOException("storage unavailable") }
			FeatureFlags.initialize()
			assertThat(FeatureFlags.isExperimentsEnabled).isFalse()

			tempFolder.newFile("CodeOnTheGo.exp")
			FeatureFlags.flagFileResolver = { name -> File(tempFolder.root, name) }
			FeatureFlags.initialize()

			assertThat(FeatureFlags.isExperimentsEnabled).isTrue()
		}

	@Test
	fun `refresh re-reads even after initialize has latched`() =
		runTest {
			// Direct-boot analog: a genuine read that saw no files latches an all-false snapshot.
			FeatureFlags.initialize()
			assertThat(FeatureFlags.isExperimentsEnabled).isFalse()

			tempFolder.newFile("CodeOnTheGo.exp")
			FeatureFlags.refresh()

			assertThat(FeatureFlags.isExperimentsEnabled).isTrue()
		}

	@Test
	fun `refresh drops flags whose sentinel file disappeared`() =
		runTest {
			val sentinel = tempFolder.newFile("CodeOnTheGo.exp")
			FeatureFlags.initialize()
			assertThat(FeatureFlags.isExperimentsEnabled).isTrue()

			check(sentinel.delete())
			FeatureFlags.refresh()

			assertThat(FeatureFlags.isExperimentsEnabled).isFalse()
		}
}
