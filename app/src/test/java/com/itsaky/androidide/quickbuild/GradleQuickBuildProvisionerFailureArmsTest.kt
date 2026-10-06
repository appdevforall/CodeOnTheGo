package com.itsaky.androidide.quickbuild

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.itsaky.androidide.projects.IProjectManager
import com.itsaky.androidide.projects.ProjectManagerImpl
import com.itsaky.androidide.resources.R
import com.itsaky.androidide.tooling.api.messages.result.TaskExecutionResult
import io.mockk.coVerify
import kotlinx.coroutines.test.runTest
import org.appdevforall.cotg.quickbuild.domain.session.QuickBuildMessage
import org.appdevforall.cotg.quickbuild.service.provision.InstallOutcome
import org.appdevforall.cotg.quickbuild.service.provision.ProvisionOutcome
import org.appdevforall.cotg.quickbuild.service.provision.ProxyAppRebuildOutcome
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * Every way provisioning can refuse, and the sentence each one shows.
 *
 * The arms are the whole point of this class: the provisioner detects eight distinct
 * conditions before or instead of a Gradle failure, and each exists only so the user is told
 * the thing they can act on. They are also the easiest thing in the file to break silently -
 * a refusal that returns its neighbour's resource still compiles, still fails, and still looks
 * plausible in the flash. The user-visible cost is real: a busy Gradle slot reported as
 * "Quick Build setup failed" sends them hunting a fault in their project and retrying against
 * a condition that was never theirs to fix, when the remedy is to wait.
 *
 * Robolectric for a resource-resolving Context: each arm is asserted against the string the
 * shipped `values/strings.xml` holds, so translating a sentence does not break a test while
 * re-pointing an arm does.
 */
@RunWith(RobolectricTestRunner::class)
class GradleQuickBuildProvisionerFailureArmsTest {
	@get:Rule
	val temp = TemporaryFolder()

	private val context: Context get() = ApplicationProvider.getApplicationContext()

	private val projectRoot: File get() = temp.root

	/** `:app`'s directory, where the Gradle plugin writes the variant's setup.json. */
	private fun moduleDir(): File = File(projectRoot, "app").apply { mkdirs() }

	@Test
	fun `a plugin project is refused before anything is staged - its artifact is a cgp, not an app`() =
		runTest {
			var staged = 0
			val gradle = FakeBuildService()

			val outcome =
				testProvisioner(
					context = context,
					projectRoot = projectRoot,
					buildService = gradle,
					projectManager = pluginProjectManager(),
					stage = { _, _ -> staged++ },
				).provision()

			assertThat(failureText(outcome)).isEqualTo(context.getString(R.string.quick_build_unsupported_plugin_project))
			// The refusal is up front precisely so a plugin project never pays for the staging
			// or eats a raw Gradle TaskSelectionException from a task path that names no :app.
			assertThat(staged).isEqualTo(0)
			assertThat(gradle.executedTasks).isNull()
		}

	@Test
	fun `a slot that goes busy between the staging and the build still reads as a busy slot`() =
		runTest {
			// Free on the early read, taken on the late one: CoGo's own project sync fires on
			// exactly the gradle-file edit that invalidates a session, so the two race here in
			// ordinary use. Without the late check this arrives as the tooling server's
			// "Build is already in progress", which reads as a build failure.
			val gradle = FakeBuildService(busyAtRead = { read -> read == 2 })

			val outcome = testProvisioner(context, projectRoot, buildService = gradle).provision()

			assertThat(failureText(outcome)).isEqualTo(context.getString(R.string.quick_build_slot_busy))
			assertThat(gradle.busyReads).isEqualTo(2)
			assertThat(gradle.executedTasks).isNull()
		}

	@Test
	fun `a tap during the Gradle sync is told the sync is still running, not that the build failed`() =
		runTest {
			val gradle = FakeBuildService()

			val outcome =
				testProvisioner(
					context,
					projectRoot,
					buildService = gradle,
					projectManager = unsyncedProjectManager(projectRoot),
				).provision()

			assertThat(failureText(outcome)).isEqualTo(context.getString(R.string.quick_build_waiting_for_sync))
			assertThat(gradle.executedTasks).isNull()
		}

	@Test
	fun `a project with no Android module says so, rather than reporting a build that never ran`() =
		runTest {
			val gradle = FakeBuildService()

			val outcome =
				testProvisioner(
					context,
					projectRoot,
					buildService = gradle,
					projectManager = FakeProjectManager(projectRoot, appModules = emptyList()),
				).provision()

			assertThat(failureText(outcome)).isEqualTo(context.getString(R.string.quick_build_no_app_module))
			assertThat(gradle.executedTasks).isNull()
		}

	@Test
	fun `a release variant is refused by name before the build, and the name is in the message`() =
		runTest {
			val gradle = FakeBuildService()

			val outcome =
				testProvisioner(
					context,
					projectRoot,
					buildService = gradle,
					projectManager = FakeProjectManager(projectRoot, appModules = listOf(androidModule(variantName = "demoRelease"))),
				).provision()

			// The variant name is the actionable half: the remedy is to open Build Variants and
			// pick a different one, which is useless advice without knowing which is selected.
			assertThat(failureText(outcome))
				.isEqualTo(context.getString(R.string.quick_build_non_debuggable_variant, "demoRelease"))
			assertThat(gradle.executedTasks).isNull()
		}

	@Test
	fun `a build that wrote no setup json names the variant that produced none`() =
		runTest {
			moduleDir()

			val outcome = testProvisioner(context, projectRoot).provision()

			// A successful build with no Quick Build setup all but names its cause - the Gradle
			// plugin only configures debuggable variants - so this must not fall back to the
			// generic "setup failed".
			assertThat(failureText(outcome))
				.isEqualTo(context.getString(R.string.quick_build_variant_setup_missing, "debug"))
		}

	@Test
	fun `a Gradle build that failed falls back to the generic setup-failure text`() =
		runTest {
			val gradle =
				FakeBuildService(result = TaskExecutionResult(false, TaskExecutionResult.Failure.BUILD_FAILED))

			val outcome = testProvisioner(context, projectRoot, buildService = gradle).provision()

			// Gradle's own reason goes to Build Output; the flash carries the generic sentence
			// that points there. No narrator is wired here, which is the no-quote case.
			assertThat(failureText(outcome)).isEqualTo(context.getString(R.string.quick_build_setup_failed))
			assertThat(gradle.executedTasks).isEqualTo(listOf(":app:assembleDebug"))
		}

	@Test
	fun `an absent build service and an unreadable setup json land on the same generic text`() =
		runTest {
			// The two siblings of the failed build: nothing to run the build with, and a report
			// that ran but cannot be read. Both are bugs in CoGo or the plugin rather than
			// anything the user can act on, so both get the generic sentence rather than advice.
			val noService = testProvisioner(context, projectRoot, buildService = null).provision()

			assertThat(failureText(noService)).isEqualTo(context.getString(R.string.quick_build_setup_failed))

			writeUnparseableSetupJson(moduleDir())
			val unreadableReport = testProvisioner(context, projectRoot).provision()

			assertThat(failureText(unreadableReport)).isEqualTo(context.getString(R.string.quick_build_setup_failed))
		}

	@Test
	fun `a successful build with no launchable Activity is refused before anything is installed`() =
		runTest {
			writeSetupJson(moduleDir(), entryActivity = null)
			val installer = recordingInstaller()

			val outcome = testProvisioner(context, projectRoot, installer = installer).provision()

			assertThat(failureText(outcome)).isEqualTo(context.getString(R.string.quick_build_no_launchable_activity))
			// Knowable only after the build, so the guard has to hold on the install side too:
			// an APK with nothing to launch must not reach the device.
			coVerify(exactly = 0) { installer.ensureInstalled(any(), any()) }
		}

	@Test
	fun `the refusals are eight different sentences, so no two conditions read alike`() {
		val arms =
			listOf(
				R.string.quick_build_unsupported_plugin_project,
				R.string.quick_build_slot_busy,
				R.string.quick_build_waiting_for_sync,
				R.string.quick_build_no_app_module,
				R.string.quick_build_non_debuggable_variant,
				R.string.quick_build_variant_setup_missing,
				R.string.quick_build_setup_failed,
				R.string.quick_build_no_launchable_activity,
			).map { context.getString(it, "demoDebug") }

		// The per-arm assertions above pin each arm to its own resource; this pins the
		// resources themselves apart, so a copy-paste in strings.xml cannot quietly collapse
		// two distinguishable conditions into one sentence the user cannot act on.
		assertThat(arms.toSet()).hasSize(8)
	}

	@Test
	fun `an unshowable install dialog on the FIRST provision is swapped for tap guidance`() =
		runTest {
			writeSetupJson(moduleDir())
			val installer =
				recordingInstaller(
					InstallOutcome.ConfirmationNotGiven(
						QuickBuildMessage.ReinstallReturnToCoGo,
						InstallOutcome.ConfirmationNotGiven.Reason.DIALOG_NOT_SHOWN,
					),
				)

			val outcome = testProvisioner(context, projectRoot, installer = installer).provision()

			// A provision failure lands the session in Idle, where returning to CoGo does
			// nothing - so the installer's own "return to CoGo to confirm" is a dead end here,
			// and only a fresh tap makes progress.
			assertThat(failureText(outcome)).isEqualTo(context.getString(R.string.quick_build_reinstall_tap_again))
		}

	@Test
	fun `the same unshowable dialog on a proxy app REBUILD keeps its own message and stays retryable`() =
		runTest {
			writeSetupJson(moduleDir())
			val installer =
				recordingInstaller(
					InstallOutcome.ConfirmationNotGiven(
						QuickBuildMessage.ReinstallReturnToCoGo,
						InstallOutcome.ConfirmationNotGiven.Reason.DIALOG_NOT_SHOWN,
					),
				)

			val outcome = testProvisioner(context, projectRoot, installer = installer).rebuildProxyApp()

			// The rebuild parks rather than tearing down, so returning to CoGo really does
			// auto-retry - the guidance the provision path has to swap out is right here.
			assertThat(outcome).isInstanceOf(ProxyAppRebuildOutcome.InstallNotConfirmed::class.java)
			assertThat((outcome as ProxyAppRebuildOutcome.InstallNotConfirmed).message)
				.isEqualTo(QuickBuildMessage.ReinstallReturnToCoGo)
		}

	@Test
	fun `a busy slot parks a proxy app rebuild instead of spending its retry budget on a failure`() =
		runTest {
			val gradle = FakeBuildService(busyAtRead = { true })

			val outcome = testProvisioner(context, projectRoot, buildService = gradle).rebuildProxyApp()

			// Nothing ran, so there is nothing to report and nothing to charge: the session
			// parks and a later trigger runs it. Reporting a Failure here would both show an
			// error banner for a non-error and burn one of the bounded auto-retries.
			assertThat(outcome).isEqualTo(ProxyAppRebuildOutcome.BuildSlotBusy)
			assertThat(gradle.executedTasks).isNull()
		}

	/** The text the session would flash, or a failure naming what came back instead. */
	private fun failureText(outcome: ProvisionOutcome): String {
		assertThat(outcome).isInstanceOf(ProvisionOutcome.Failure::class.java)
		return (outcome as ProvisionOutcome.Failure).message.resolve(context)
	}

	/**
	 * A real [ProjectManagerImpl] flagged as a plugin project.
	 *
	 * `isPluginProject()` casts to the impl and reads an `internal` field of the `:projects`
	 * module, so no hand-written [IProjectManager] can ever report one. Setting the real field
	 * is cheaper than widening production visibility for a test.
	 */
	private fun pluginProjectManager(): IProjectManager =
		ProjectManagerImpl().also { impl ->
			impl.projectPath = projectRoot.path
			ProjectManagerImpl::class.java
				.getDeclaredField("pluginProjectCached")
				.apply { isAccessible = true }
				.set(impl, true)
		}
}
