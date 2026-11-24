package com.itsaky.androidide.quickbuild

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.itsaky.androidide.projects.IProjectManager
import com.itsaky.androidide.projects.api.GradleProject
import com.itsaky.androidide.projects.api.Workspace
import com.itsaky.androidide.utils.Environment
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.appdevforall.cotg.quickbuild.domain.session.QuickBuildMessage
import org.appdevforall.cotg.quickbuild.service.provision.ProvisionOutcome
import org.junit.After
import org.junit.Before
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
 * [GradleQuickBuildProvisioner.provision] does for the same project. Gradle decides the opt-in per
 * app module, so the cases also pin that only the module Quick Build builds (the first app module)
 * and its parent projects count.
 */
@RunWith(RobolectricTestRunner::class)
class QuickBuildTapSqliteDebuggerCheckTest {
	@get:Rule
	val temp = TemporaryFolder()

	private val context: Context get() = ApplicationProvider.getApplicationContext()

	private val projectRoot: File get() = temp.root

	private val gradleUserHome: File by lazy { File(projectRoot, "gradle-user-home").apply { mkdirs() } }

	private var savedGradleUserHome: File? = null

	@Before
	fun pointGradleUserHomeAtTemp() {
		savedGradleUserHome = Environment.GRADLE_USER_HOME
		Environment.GRADLE_USER_HOME = gradleUserHome
	}

	@After
	fun restoreGradleUserHome() {
		Environment.GRADLE_USER_HOME = savedGradleUserHome
	}

	private fun File.props(text: String) = File(this, "gradle.properties").writeText(text)

	private fun dir(relative: String): File = File(projectRoot, relative).apply { mkdirs() }

	/** App modules `paths` (in order) under `projectRoot`, with a model that also lists [extraProjects]. */
	private fun projectOf(
		vararg paths: String,
		extraProjects: Map<String, File> = emptyMap(),
	): FakeProjectManager {
		val modules =
			paths.map { path ->
				val moduleDir = dir(path.trim(':').replace(':', '/'))
				androidModule(path = path, buildDir = File(moduleDir, "build"), projectDir = moduleDir)
			}
		val listed = modules.associate { it.path to File(it.projectDirPath) } + extraProjects
		val workspace = mockk<Workspace>()
		every { workspace.subProjects } returns
			listed.map { (path, dir) ->
				mockk<GradleProject>().also {
					every { it.path } returns path
					every { it.projectDirPath } returns dir.path
				}
			}
		return FakeProjectManager(projectRoot, workspace = workspace, appModules = modules)
	}

	private suspend fun assertTapAndProvisionerAgree(
		project: IProjectManager,
		refused: Boolean,
	) {
		assertThat(GradleQuickBuildProvisioner.refusesForSqliteDebugger(project)).isEqualTo(refused)
		assertThat(provisionerRefuses(project)).isEqualTo(refused)
	}

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
	fun `a root true that the second app module turns off still refuses the first app module`() =
		runTest {
			// Gradle skips Quick Build for :app, which inherits the root's true; :other's false is its own.
			projectRoot.props("cogo.sqliteDebugger=true\n")
			val project = projectOf(":app", ":other")
			dir("other").props("cogo.sqliteDebugger=false\n")

			assertTapAndProvisionerAgree(project, refused = true)
		}

	@Test
	fun `an opt-in in the second app module does not refuse the first`() =
		runTest {
			val project = projectOf(":app", ":other")
			dir("other").props("cogo.sqliteDebugger=true\n")

			assertTapAndProvisionerAgree(project, refused = false)
		}

	@Test
	fun `an opt-in in a parent project refuses its app module`() =
		runTest {
			val project = projectOf(":group:app")
			dir("group").props("cogo.sqliteDebugger=true\n")

			assertTapAndProvisionerAgree(project, refused = true)
		}

	@Test
	fun `a parent project's directory comes from the model, not the directory layout`() =
		runTest {
			val remapped = dir("elsewhere/g")
			remapped.props("cogo.sqliteDebugger=true\n")
			// The default layout would read group/, which has no file.
			val project = projectOf(":group:app", extraProjects = mapOf(":group" to remapped))

			assertTapAndProvisionerAgree(project, refused = true)
		}

	@Test
	fun `an app module's false overrides its parent's true`() =
		runTest {
			val project = projectOf(":group:app")
			dir("group").props("cogo.sqliteDebugger=true\n")
			dir("group/app").props("cogo.sqliteDebugger=false\n")

			assertTapAndProvisionerAgree(project, refused = false)
		}

	@Test
	fun `the root's true outranks a parent's false, as Gradle resolves it`() =
		runTest {
			projectRoot.props("cogo.sqliteDebugger=true\n")
			val project = projectOf(":group:app")
			dir("group").props("cogo.sqliteDebugger=false\n")

			assertTapAndProvisionerAgree(project, refused = true)
		}

	@Test
	fun `the root's false outranks a parent's true, as Gradle resolves it`() =
		runTest {
			projectRoot.props("cogo.sqliteDebugger=false\n")
			val project = projectOf(":group:app")
			dir("group").props("cogo.sqliteDebugger=true\n")

			assertTapAndProvisionerAgree(project, refused = false)
		}

	@Test
	fun `the Gradle user home's false overrides an app module's true`() =
		runTest {
			val project = projectOf(":app")
			dir("app").props("cogo.sqliteDebugger=true\n")
			gradleUserHome.props("cogo.sqliteDebugger=false\n")

			assertTapAndProvisionerAgree(project, refused = false)
		}

	@Test
	fun `the Gradle user home's true refuses a module that sets nothing`() =
		runTest {
			val project = projectOf(":app")
			gradleUserHome.props("cogo.sqliteDebugger=true\n")

			assertTapAndProvisionerAgree(project, refused = true)
		}

	@Test
	fun `during a sync the tap's check says no, and the tap asks as before`() {
		// The provisioner decides only after the sync publishes the modules, whose own files can turn
		// the opt-in off, so the tap cannot know yet and must not skip the question.
		File(projectRoot, "gradle.properties").writeText("cogo.sqliteDebugger=true\n")

		assertThat(GradleQuickBuildProvisioner.refusesForSqliteDebugger(unsyncedProjectManager(projectRoot))).isFalse()
	}
}
