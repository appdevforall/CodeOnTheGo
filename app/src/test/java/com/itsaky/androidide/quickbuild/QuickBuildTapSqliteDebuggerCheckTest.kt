package com.itsaky.androidide.quickbuild

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.itsaky.androidide.projects.IProjectManager
import kotlinx.coroutines.test.runTest
import org.appdevforall.cotg.quickbuild.domain.session.QuickBuildMessage
import org.appdevforall.cotg.quickbuild.service.provision.ProvisionOutcome
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * [GradleQuickBuildProvisioner.refusesForSqliteDebugger], which a Quick Build tap reads to skip the
 * "Replace the installed app?" question. Skipping it is safe only when the provisioner will refuse
 * before it installs anything, so each case checks that the tap's answer agrees with what
 * [GradleQuickBuildProvisioner.provision] does for the same project.
 */
@RunWith(RobolectricTestRunner::class)
class QuickBuildTapSqliteDebuggerCheckTest {
	@get:Rule
	val temp = TemporaryFolder()

	private val context: Context get() = ApplicationProvider.getApplicationContext()

	private val projectRoot: File get() = temp.root

	private fun appModuleDir(): File = File(projectRoot, "app").apply { mkdirs() }

	private fun publishedProject(): FakeProjectManager {
		val moduleDir = appModuleDir()
		return FakeProjectManager(
			projectRoot,
			appModules = listOf(androidModule(buildDir = File(moduleDir, "build"), projectDir = moduleDir)),
		)
	}

	private suspend fun provisionerRefuses(projectManager: IProjectManager): Boolean =
		testProvisioner(context = context, projectRoot = projectRoot, projectManager = projectManager)
			.provision() == ProvisionOutcome.Failure(QuickBuildMessage.DisabledBySqliteDebugger)

	@Test
	fun `a root opt-in is refused, by the tap's check and by the provisioner`() =
		runTest {
			File(projectRoot, "gradle.properties").writeText("cogo.sqliteDebugger=true\n")
			val project = publishedProject()

			assertThat(GradleQuickBuildProvisioner.refusesForSqliteDebugger(project)).isTrue()
			assertThat(provisionerRefuses(project)).isTrue()
		}

	@Test
	fun `an opt-in in the app module's gradle properties is refused`() =
		runTest {
			File(appModuleDir(), "gradle.properties").writeText("cogo.sqliteDebugger=true\n")
			val project = publishedProject()

			assertThat(GradleQuickBuildProvisioner.refusesForSqliteDebugger(project)).isTrue()
			assertThat(provisionerRefuses(project)).isTrue()
		}

	@Test
	fun `a root opt-in that the app module turns off is not refused, so the tap still asks`() =
		runTest {
			File(projectRoot, "gradle.properties").writeText("cogo.sqliteDebugger=true\n")
			File(appModuleDir(), "gradle.properties").writeText("cogo.sqliteDebugger=false\n")
			val project = publishedProject()

			assertThat(GradleQuickBuildProvisioner.refusesForSqliteDebugger(project)).isFalse()
			assertThat(provisionerRefuses(project)).isFalse()
		}

	@Test
	fun `a project without the opt-in is not refused`() =
		runTest {
			val project = publishedProject()

			assertThat(GradleQuickBuildProvisioner.refusesForSqliteDebugger(project)).isFalse()
			assertThat(provisionerRefuses(project)).isFalse()
		}

	@Test
	fun `during a sync the tap's check says no, and the tap asks as before`() {
		// The provisioner decides only after the sync publishes the modules, whose own files can turn
		// the opt-in off, so the tap cannot know yet and must not skip the question.
		File(projectRoot, "gradle.properties").writeText("cogo.sqliteDebugger=true\n")

		assertThat(GradleQuickBuildProvisioner.refusesForSqliteDebugger(unsyncedProjectManager(projectRoot))).isFalse()
	}
}
