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
	private val projectA = "/storage/emulated/0/CodeOnTheGoProjects/ProjectA"
	private val projectB = "/storage/emulated/0/CodeOnTheGoProjects/ProjectB"

	@Before
	fun setUp() {
		System.setProperty("androidide.test.mode", "true")
		GitPreferences.shouldAddGlobalCommitWatermark = true
		GitPreferences.enableProjectWatermark(projectA, true)
		GitPreferences.enableProjectWatermark(projectB, true)
	}

	@After
	fun tearDown() {
		GitPreferences.shouldAddGlobalCommitWatermark = true
		GitPreferences.enableProjectWatermark(projectA, true)
		GitPreferences.enableProjectWatermark(projectB, true)
	}

	@Test
	fun `project watermark defaults to true`() {
		val newProject = "/storage/emulated/0/CodeOnTheGoProjects/NewProject"
		assertThat(GitPreferences.isProjectWatermarkEnabled(newProject)).isTrue()
	}

	@Test
	fun `project watermark persists false when opted out`() {
		GitPreferences.enableProjectWatermark(projectA, false)
		assertThat(GitPreferences.isProjectWatermarkEnabled(projectA)).isFalse()
	}

	@Test
	fun `project watermark persists true when re-enabled`() {
		GitPreferences.enableProjectWatermark(projectA, false)
		assertThat(GitPreferences.isProjectWatermarkEnabled(projectA)).isFalse()

		GitPreferences.enableProjectWatermark(projectA, true)
		assertThat(GitPreferences.isProjectWatermarkEnabled(projectA)).isTrue()
	}

	@Test
	fun `project watermark settings are isolated per project`() {
		GitPreferences.enableProjectWatermark(projectA, false)
		GitPreferences.enableProjectWatermark(projectB, true)

		assertThat(GitPreferences.isProjectWatermarkEnabled(projectA)).isFalse()
		assertThat(GitPreferences.isProjectWatermarkEnabled(projectB)).isTrue()
	}

	@Test
	fun `project watermark handles null or blank project path gracefully`() {
		assertThat(GitPreferences.isProjectWatermarkEnabled(null)).isTrue()
		assertThat(GitPreferences.isProjectWatermarkEnabled("")).isTrue()
		assertThat(GitPreferences.isProjectWatermarkEnabled("   ")).isTrue()

		// Setting with null or blank should not throw or corrupt
		GitPreferences.enableProjectWatermark(null, false)
		GitPreferences.enableProjectWatermark("", false)
		GitPreferences.enableProjectWatermark("   ", false)

		assertThat(GitPreferences.isProjectWatermarkEnabled(null)).isTrue()
	}

	@Test
	fun `isWatermarkEnabled returns true only when both global and project are enabled`() {
		// Global: true, Project: true -> true
		GitPreferences.shouldAddGlobalCommitWatermark = true
		GitPreferences.enableProjectWatermark(projectA, true)
		assertThat(GitPreferences.isWatermarkEnabled(projectA)).isTrue()

		// Global: true, Project: false -> false (Scenario: Global setting respects project opt-out)
		GitPreferences.enableProjectWatermark(projectA, false)
		assertThat(GitPreferences.isWatermarkEnabled(projectA)).isFalse()

		// Global: false, Project: true -> false (Scenario: Global opt-out)
		GitPreferences.shouldAddGlobalCommitWatermark = false
		GitPreferences.enableProjectWatermark(projectA, true)
		assertThat(GitPreferences.isWatermarkEnabled(projectA)).isFalse()

		// Global: false, Project: false -> false
		GitPreferences.enableProjectWatermark(projectA, false)
		assertThat(GitPreferences.isWatermarkEnabled(projectA)).isFalse()
	}

	@Test
	fun `getProjectWatermarkKey formats correctly`() {
		val key = GitPreferences.getProjectWatermarkKey("  /path/to/repo  ")
		assertThat(key).isEqualTo("${GitPreferences.PROJECT_WATERMARK_PREFIX}/path/to/repo")
	}

	@Test
	fun `equivalent project paths resolve to the same preference key`() {
		val canonicalPath = "/storage/emulated/0/CodeOnTheGoProjects/ProjectA"
		val redundantPath = "/storage/emulated/0/./CodeOnTheGoProjects/ProjectA"
		val traversalPath = "/storage/emulated/0/CodeOnTheGoProjects/../CodeOnTheGoProjects/ProjectA"

		assertThat(GitPreferences.getProjectWatermarkKey(redundantPath))
			.isEqualTo(GitPreferences.getProjectWatermarkKey(canonicalPath))
		assertThat(GitPreferences.getProjectWatermarkKey(traversalPath))
			.isEqualTo(GitPreferences.getProjectWatermarkKey(canonicalPath))

		// Opt out via redundant path, verify read via canonical path
		GitPreferences.enableProjectWatermark(redundantPath, false)
		assertThat(GitPreferences.isProjectWatermarkEnabled(canonicalPath)).isFalse()

		// Re-enable via traversal path, verify read via canonical path
		GitPreferences.enableProjectWatermark(traversalPath, true)
		assertThat(GitPreferences.isProjectWatermarkEnabled(canonicalPath)).isTrue()
	}
}
