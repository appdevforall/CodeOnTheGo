package com.itsaky.androidide.gradle

import com.android.build.gradle.internal.tasks.InstallVariantTask
import com.android.build.gradle.internal.tasks.UninstallTask
import com.google.common.truth.Truth.assertThat
import com.itsaky.androidide.tooling.api.GradlePluginConfig.PROPERTY_LOG_SENDER_ENABLED
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class InstallTaskReplacementTest {
	private lateinit var project: Project

	@BeforeEach
	fun applyPlugin() {
		project = ProjectBuilder.builder().build()
		project.extensions.extraProperties.set(PROPERTY_LOG_SENDER_ENABLED, "false")
		project.pluginManager.apply(AndroidIDEGradlePlugin::class.java)
	}

	@Test
	fun `AGP install and uninstall tasks are recognised by class, other tasks are not`() {
		val install = project.tasks.register("installDebug", InstallVariantTask::class.java).get()
		val uninstall = project.tasks.register("uninstallDebug", UninstallTask::class.java).get()
		val unrelated = project.tasks.register("installGitHooks", DefaultTask::class.java).get()

		assertThat(adbTaskReplacementMessage(install)).isEqualTo(INSTALL_TASK_UNSUPPORTED_MESSAGE)
		assertThat(adbTaskReplacementMessage(uninstall)).isEqualTo(UNINSTALL_TASK_UNSUPPORTED_MESSAGE)
		assertThat(adbTaskReplacementMessage(unrelated)).isNull()
	}

	@Test
	fun `the adb action of an install task is replaced by a single message action`() {
		val install = project.tasks.register("installDebug", InstallVariantTask::class.java).get()

		assertThat(install.actions).hasSize(1)
		install.actions.single().execute(install)
	}

	@Test
	fun `the adb action of an uninstall task is replaced by a single message action`() {
		val uninstall = project.tasks.register("uninstallDebug", UninstallTask::class.java).get()

		assertThat(uninstall.actions).hasSize(1)
		uninstall.actions.single().execute(uninstall)
	}

	@Test
	fun `instrumentation test install tasks fail with the unsupported message`() {
		val install = project.tasks.register("installDebugAndroidTest", InstallVariantTask::class.java).get()

		assertThat(install.actions).hasSize(1)
		val failure = assertThrows<GradleException> { install.actions.single().execute(install) }
		assertThat(failure).hasMessageThat().isEqualTo(ANDROID_TEST_INSTALL_UNSUPPORTED_MESSAGE)
	}

	@Test
	fun `a user task named install-something keeps its own actions`() {
		var ran = false
		val task =
			project.tasks
				.register("installGitHooks", DefaultTask::class.java) { it.doLast { ran = true } }
				.get()

		assertThat(task.actions).hasSize(1)
		task.actions.single().execute(task)
		assertThat(ran).isTrue()
	}

	@Test
	fun `task dependencies survive the replacement`() {
		val assemble = project.tasks.register("assembleDebug", DefaultTask::class.java).get()
		val install =
			project.tasks
				.register("installDebug", InstallVariantTask::class.java) { it.dependsOn(assemble) }
				.get()

		assertThat(install.taskDependencies.getDependencies(install)).containsExactly(assemble as Task)
	}
}
