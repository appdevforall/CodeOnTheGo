package com.itsaky.androidide.plugins.manager.services

import com.google.common.truth.Truth.assertThat
import com.itsaky.androidide.plugins.services.GradleTaskInfo
import com.itsaky.androidide.project.GradleModels
import org.junit.Test

class IdeBuildServiceImplGetTasksTest {
	private fun task(
		projectPath: String,
		name: String,
		group: String? = null,
		description: String? = null,
	): GradleModels.GradleTask =
		GradleModels.GradleTask
			.newBuilder()
			.setName(name)
			.setPath(if (projectPath == ":") ":$name" else "$projectPath:$name")
			.setProjectPath(projectPath)
			.setIsPublic(group != null)
			.apply { group?.let(::setGroup) }
			.apply { description?.let(::setDescription) }
			.build()

	private fun project(
		path: String,
		vararg tasks: GradleModels.GradleTask,
	): GradleModels.GradleProject =
		GradleModels.GradleProject
			.newBuilder()
			.setName(path.trimStart(':').ifEmpty { "root" })
			.setPath(path)
			.addAllTask(tasks.toList())
			.build()

	@Test
	fun givenNoSyncedBuild_whenListingTasks_thenReturnsEmpty() {
		assertThat(IdeBuildServiceImpl.tasksOf(null)).isEmpty()
	}

	@Test
	fun givenRootAndModuleTasks_whenListingTasks_thenReturnsRootTasksFirst() {
		val build =
			GradleModels.GradleBuild
				.newBuilder()
				.setRootProject(project(":", task(":", "printGreeting", "custom", "Prints a greeting")))
				.addSubProject(project(":app", task(":app", "testDebugUnitTest", "verification", "Run unit tests")))
				.build()

		assertThat(IdeBuildServiceImpl.tasksOf(build))
			.containsExactly(
				GradleTaskInfo(":printGreeting", "printGreeting", ":", "custom", "Prints a greeting"),
				GradleTaskInfo(":app:testDebugUnitTest", "testDebugUnitTest", ":app", "verification", "Run unit tests"),
			).inOrder()
	}

	@Test
	fun givenTaskWithoutGroupOrDescription_whenListingTasks_thenReportsNullNotEmpty() {
		val build =
			GradleModels.GradleBuild
				.newBuilder()
				.addSubProject(project(":app", task(":app", "compileDebugKotlin"), task(":app", "lint", "", " ")))
				.build()

		val tasks = IdeBuildServiceImpl.tasksOf(build)

		assertThat(tasks.map { it.group }).containsExactly(null, null)
		assertThat(tasks.map { it.description }).containsExactly(null, null)
	}

	@Test
	fun givenRootAlsoInSubProjects_whenListingTasks_thenEachTaskIsListedOnce() {
		val root = project(":", task(":", "secretHandshake", description = "Prints a greeting"))
		val build =
			GradleModels.GradleBuild
				.newBuilder()
				.setRootProject(root)
				.addSubProject(root)
				.addSubProject(project(":app", task(":app", "secretHandshake", description = "Prints a greeting")))
				.build()

		assertThat(IdeBuildServiceImpl.tasksOf(build).map { it.path })
			.containsExactly(":secretHandshake", ":app:secretHandshake")
			.inOrder()
	}

	@Test
	fun givenBuildWithoutRootProject_whenListingTasks_thenReturnsModuleTasksOnly() {
		val build =
			GradleModels.GradleBuild
				.newBuilder()
				.addSubProject(project(":lib", task(":lib", "assemble", "build")))
				.build()

		assertThat(IdeBuildServiceImpl.tasksOf(build).map { it.path }).containsExactly(":lib:assemble")
	}
}
