package com.itsaky.androidide.quickbuild

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.itsaky.androidide.tooling.api.GradlePluginConfig
import kotlinx.coroutines.test.runTest
import org.appdevforall.cotg.quickbuild.service.provision.ProvisionOutcome
import org.appdevforall.cotg.quickbuild.service.provision.ProxyAppRebuildOutcome
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * Which of the three proxy app builds stamps a baseline generation, pinned where the defect
 * actually lives: at the CALL SITES, and on the `-P` argument the build really receives.
 *
 * `GradleQuickBuildProvisionerStampTest` pins the [ProxyAppBuildPurpose] constants. That is the
 * mapping, not its use - a call site handed the wrong purpose, or a `stampBaseline` read that
 * stopped reaching the Gradle args, leaves those assertions green and re-creates S7 anyway:
 * an installed baseline that is no longer strictly older than every later deploy, so a
 * manifest-only rebaseline boots with the previous epoch's persisted payloads outranking it.
 * The other direction costs a generation and the whole packaging tail on every project open.
 *
 * Also guards the enum against growing a fourth value whose stamping nobody decided.
 */
@RunWith(RobolectricTestRunner::class)
class GradleQuickBuildProvisionerBaselineTest {
	@get:Rule
	val temp = TemporaryFolder()

	private val context: Context get() = ApplicationProvider.getApplicationContext()

	private val projectRoot: File get() = temp.root

	private var allocations = 0

	/** The stamped argument, as the Gradle plugin will read it. */
	private val stampedArg = "-P${GradlePluginConfig.PROPERTY_QUICK_BUILD_BASELINE_GENERATION}=$ALLOCATED"

	@Test
	fun `a provision stamps its allocated generation into the build and reports it as the baseline`() =
		runTest {
			writeSetupJson(moduleDir())
			val gradle = FakeBuildService()

			val outcome = provisioner(gradle).provision()

			assertThat(outcome).isInstanceOf(ProvisionOutcome.Success::class.java)
			// The installed app boots at this number and the session adopts it as the deployed
			// generation, so reporting 0 here would leave every later deploy looking older.
			assertThat((outcome as ProvisionOutcome.Success).baselineGeneration).isEqualTo(ALLOCATED)
			assertThat(gradle.gradleArgs).contains(stampedArg)
			assertThat(allocations).isEqualTo(1)
		}

	@Test
	fun `a proxy app rebaseline stamps too - its APK is reinstalled, so it becomes the new baseline`() =
		runTest {
			writeSetupJson(moduleDir())
			val gradle = FakeBuildService()

			val outcome = provisioner(gradle).rebuildProxyApp()

			assertThat(outcome).isInstanceOf(ProxyAppRebuildOutcome.Success::class.java)
			assertThat((outcome as ProxyAppRebuildOutcome.Success).baselineGeneration).isEqualTo(ALLOCATED)
			assertThat(gradle.gradleArgs).contains(stampedArg)
			assertThat(allocations).isEqualTo(1)
		}

	@Test
	fun `the prebuild stamps nothing - it allocates no generation and passes no argument`() =
		runTest {
			writeSetupJson(moduleDir())
			val gradle = FakeBuildService()

			provisioner(gradle).prebuildProxyApp()

			// It really did run; the assertions below are about what it did NOT do.
			assertThat(gradle.executedTasks).isEqualTo(listOf(":app:assembleDebug"))
			// This APK is never installed. Stamping it would burn a generation and re-run the
			// packaging tail the warm-up exists to pre-pay, on every single project open.
			assertThat(allocations).isEqualTo(0)
			assertThat(gradle.gradleArgs.none { it.contains(GradlePluginConfig.PROPERTY_QUICK_BUILD_BASELINE_GENERATION) })
				.isTrue()
		}

	@Test
	fun `every build purpose has a stamping decision on record - a new one must not default in`() {
		// Deliberately a data table rather than a `when`: a fourth purpose added without a
		// decision fails here, loudly, instead of inheriting whichever branch it happens to
		// fall into. Both wrong answers are invisible at runtime until a user is deploying
		// into a stale baseline.
		val decided =
			mapOf(
				ProxyAppBuildPurpose.PROVISION to true,
				ProxyAppBuildPurpose.PREBUILD to false,
				ProxyAppBuildPurpose.REBASELINE to true,
			)

		assertThat(decided.keys).containsExactlyElementsIn(ProxyAppBuildPurpose.entries)
		decided.forEach { (purpose, stamps) ->
			assertThat(purpose.stampBaseline).isEqualTo(stamps)
		}
	}

	private fun moduleDir(): File = File(projectRoot, "app").apply { mkdirs() }

	private fun provisioner(gradle: FakeBuildService): GradleQuickBuildProvisioner =
		testProvisioner(
			context,
			projectRoot,
			buildService = gradle,
			nextBaselineGeneration = {
				allocations++
				ALLOCATED
			},
		)

	private companion object {
		/** Distinctive enough that a defaulted 0 or a re-used 1 cannot pass for it. */
		const val ALLOCATED = 47L
	}
}
