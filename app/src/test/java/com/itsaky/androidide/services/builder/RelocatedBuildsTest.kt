package com.itsaky.androidide.services.builder

import com.google.common.truth.Truth.assertThat
import com.itsaky.androidide.tooling.api.messages.BuildId
import com.itsaky.androidide.tooling.api.messages.BuildRunType
import com.itsaky.androidide.tooling.api.messages.result.BuildInfo
import org.junit.Test
import java.io.File

class RelocatedBuildsTest {
	private val relocating = BuildOutputLocation.gradleArgs(File("/no_backup/gradle-build/app-1234"))

	private fun info(
		id: Long,
		runType: BuildRunType,
	) = BuildInfo(BuildId("session", id, runType), emptyList())

	@Test
	fun `a relocated build is reported as relocated when it completes`() {
		val builds = RelocatedBuilds()
		val build = info(1, BuildRunType.TaskRun)

		builds.prepared(build, relocating)

		assertThat(builds.completed(build.buildId)).isTrue()
	}

	@Test
	fun `a relocated sync is reported as relocated when it completes`() {
		// The tooling server reports a sync's completion with the sync's own ID, like a build's.
		val builds = RelocatedBuilds()
		val sync = info(2, BuildRunType.ProjectSync)

		builds.prepared(sync, relocating)

		assertThat(builds.completed(sync.buildId)).isTrue()
	}

	@Test
	fun `a build that writes to the project folder is not`() {
		val builds = RelocatedBuilds()
		val build = info(3, BuildRunType.TaskRun)

		builds.prepared(build, listOf("--parallel", "--watch-fs"))

		assertThat(builds.completed(build.buildId)).isFalse()
	}

	@Test
	fun `each build is reported once and then forgotten`() {
		val builds = RelocatedBuilds()
		val build = info(4, BuildRunType.TaskRun)
		builds.prepared(build, relocating)

		assertThat(builds.completed(build.buildId)).isTrue()
		assertThat(builds.completed(build.buildId)).isFalse()
		assertThat(builds.completed(info(5, BuildRunType.TaskRun).buildId)).isFalse()
	}
}
