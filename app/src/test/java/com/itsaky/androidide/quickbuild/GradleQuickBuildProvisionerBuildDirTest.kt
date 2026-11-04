package com.itsaky.androidide.quickbuild

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.appdevforall.cotg.quickbuild.service.provision.ProvisionOutcome
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * The proxy app report is read from the module's build directory as the synced project model
 * gives it. With build output relocated off the project folder (ADFA-4930) that directory is
 * not `<module>/build`, which may still hold a report from before the relocation.
 */
@RunWith(RobolectricTestRunner::class)
class GradleQuickBuildProvisionerBuildDirTest {
	@get:Rule
	val temp = TemporaryFolder()

	private val context: Context get() = ApplicationProvider.getApplicationContext()

	private val projectRoot: File get() = File(temp.root, "project")

	private val moduleDir: File get() = File(projectRoot, "app").apply { mkdirs() }

	private val relocatedBuildDir: File get() = File(temp.root, "no_backup/gradle-build/project-1234/main/app-5678")

	@Test
	fun `a report under a relocated build directory is found`() =
		runTest {
			writeSetupJson(moduleDir, buildDir = relocatedBuildDir)

			val outcome = provisioner().provision()

			assertThat(outcome).isInstanceOf(ProvisionOutcome.Success::class.java)
		}

	@Test
	fun `a stale report left in the project folder is not read`() =
		runTest {
			writeSetupJson(moduleDir, entryActivity = "com.example.demo.StaleActivity")
			writeSetupJson(moduleDir, buildDir = relocatedBuildDir)

			val outcome = provisioner().provision()

			assertThat((outcome as ProvisionOutcome.Success).proxyApp.entryActivity)
				.isEqualTo("com.example.demo.MainActivity")
		}

	private fun provisioner(): GradleQuickBuildProvisioner =
		testProvisioner(
			context,
			projectRoot,
			projectManager = FakeProjectManager(projectRoot, appModules = listOf(androidModule(buildDir = relocatedBuildDir))),
		)
}
