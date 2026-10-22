package com.itsaky.androidide.utils

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ExperimentsTest {
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
	fun `every experiment is on when the flag file exists`() =
		runTest {
			tempFolder.newFile("CodeOnTheGo.exp")
			FeatureFlags.initialize()

			assertThat(Experiments.enabled).isTrue()
			Experiment.entries.forEach { assertWithMessage(it.name).that(Experiments.isOn(it)).isTrue() }
		}

	@Test
	fun `every experiment is off without the flag file`() =
		runTest {
			FeatureFlags.initialize()

			assertThat(Experiments.enabled).isFalse()
			Experiment.entries.forEach { assertWithMessage(it.name).that(Experiments.isOn(it)).isFalse() }
		}

	// The nightly stale-experiments job greps Experiment.kt for this pattern.
	@Test
	fun `every jira key is a single ADFA key`() {
		Experiment.entries.forEach { assertThat(it.jiraKey).matches("ADFA-[0-9]+") }
	}
}
