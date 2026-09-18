package com.itsaky.androidide

import android.os.Build
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiSelector
import com.itsaky.androidide.activities.SplashActivity
import com.itsaky.androidide.app.configuration.IJdkDistributionProvider
import com.itsaky.androidide.helper.FakeInstalledPackages
import com.itsaky.androidide.helper.ensureOnHomeScreenBeforeCreateProject
import com.itsaky.androidide.helper.isExperimentsFlagSet
import com.itsaky.androidide.helper.selectProjectTemplate
import com.itsaky.androidide.helper.setAccessibilityEditText
import com.itsaky.androidide.helper.setExperimentsFlagForTest
import com.itsaky.androidide.helper.waitForMainHomeOrEditorUi
import com.itsaky.androidide.projects.IProjectManager
import com.itsaky.androidide.quickbuild.AndroidInstalledPackages
import com.itsaky.androidide.screens.HomeScreen.clickCreateProjectHomeScreen
import com.itsaky.androidide.screens.ProjectSettingsScreen.clickCreateProjectProjectSettings
import com.itsaky.androidide.screens.ProjectSettingsScreen.selectKotlinLanguage
import com.itsaky.androidide.screens.ProjectSettingsScreen.setProjectName
import com.itsaky.androidide.screens.QuickBuildScreen.assertQuickBuildButtonShown
import com.itsaky.androidide.screens.QuickBuildScreen.dismissFirstBuildNoticeIfShown
import com.itsaky.androidide.screens.QuickBuildScreen.tapQuickBuildButton
import com.kaspersky.kaspresso.testcases.api.testcase.TestCase
import com.kaspersky.kaspresso.testcases.core.testcontext.TestContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.appdevforall.cotg.quickbuild.domain.session.QuickBuildSessionState
import org.appdevforall.cotg.quickbuild.domain.session.SessionFailure
import org.appdevforall.cotg.quickbuild.service.deploy.ProxyAppConnections
import org.appdevforall.cotg.quickbuild.service.provision.QuickBuildClobberCheck
import org.appdevforall.cotg.quickbuild.service.session.QuickBuildSessionManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import org.koin.core.context.loadKoinModules
import org.koin.dsl.module
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

private const val EDITOR_OPEN_TIMEOUT_MS = 60_000L
private const val PACKAGE_FIELD_TIMEOUT_MS = 3_000L

// Project sync is a real Gradle sync; the same cold-CI ceiling QuickBuildSmokeTest and
// InitializationProjectAndCancelingBuildScenario use.
private const val PROJECT_SYNC_TIMEOUT_MS = 15 * 60 * 1000L
private const val PROJECT_SYNC_POLL_MS = 1_000L

// Provisioning (proxy app build + install + daemon spawn) is ALSO a real Gradle build on
// the device's single build slot, so it gets the same cold-build ceiling as project sync.
private const val PROVISIONING_READY_TIMEOUT_MS = 15 * 60 * 1000L

// A live-reload build+deploy is the fast incremental path (measured 12-50s warm-daemon in
// prior on-device runs), not a cold Gradle build - generous but well under the provisioning
// ceiling above. A build that FAILS to compile finishes sooner still, so the same ceiling
// covers waiting for a compile error.
private const val DEPLOY_TIMEOUT_MS = 180_000L

// Binder death after an `am force-stop` is an OS callback, not a build - seconds at worst.
private const val PROXY_DISCONNECT_TIMEOUT_MS = 30_000L

// How often to look for the system install dialog while provisioning runs. Must stay well
// inside CoGo's own 180 s install-confirm fail-fast so the tap lands before it gives up.
private const val INSTALL_CONFIRM_POLL_MS = 1_000L

/**
 * Kaspresso end-to-end coverage for the Quick Build pipeline (ADFA-4128): scaffold a
 * project, provision a live session, then drive saves through to proxy-app-acknowledged
 * deploys. Complements [QuickBuildSmokeTest], which covers the toolbar/dialog/banner
 * surfaces without running a build to completion.
 *
 * Determinism note shared by every test here: each pays a real provisioning cycle, so a
 * broken toolchain on the device fails at Ready rather than flaking - a genuine signal, not
 * noise. What is specific to one test is documented on that test.
 *
 * Runs after [EndToEndTest] in `OrderedTestSuite`: assumes onboarding is complete (same
 * assumption [QuickBuildSmokeTest] documents).
 */
@RunWith(AndroidJUnit4::class)
class QuickBuildPipelineTest : TestCase() {
	private val targetContext
		get() = InstrumentationRegistry.getInstrumentation().targetContext

	private var hadExperimentsFlag = false

	private val fakePackages = FakeInstalledPackages()
	private var clobberCheckOverridden = false

	@Test
	fun test_projectSetupReachesReadyAtGenerationZero() =
		before {
			enableExperimentsForTest()
		}.after {
			restoreAfterQuickBuildTest()
		}.run {
			launchAndCreateSyncedProject("qb-setup", "qbsetup")
			val readyState = tapAndAwaitReadySession()

			step("Session reached Ready at generation 0 (setup build ran, proxy app installed)") {
				assertEquals(
					"Provisioning must land a fresh project's session at generation 0",
					0L,
					readyState.generation,
				)
				assertTrue("A Ready session must carry no failure fresh out of provisioning", readyState.lastFailure == null)
			}
		}

	@Test
	fun test_saveAdvancesGenerationAndDeployIsAcknowledged() =
		before {
			enableExperimentsForTest()
		}.after {
			restoreAfterQuickBuildTest()
		}.run {
			launchAndCreateSyncedProject("qb-deploy", "qbdeploy")
			val readyGeneration = tapAndAwaitReadySession().generation

			val target = findKotlinSourceFile(openProjectDir())
			step("Write a change to a Kotlin source file via java.io - the save that fires the watcher") {
				save(target, target.readText() + "\n// ADFA-4128 quick-build save-to-deploy test marker\n")
			}

			step("Generation advances and the deploy is acknowledged by the proxy app") {
				// Deployed is only reached from SessionEvent.BuildSucceeded, which in turn
				// is only dispatched from PayloadDeployer.deployPayload after
				// DeployResult.Reloaded - the proxy app's own reportReloaded acknowledgement
				// arriving back over the binder channel. Observing this state is therefore
				// proxy-app-confirmed evidence of the deploy, without asserting anything
				// inside the proxy app's UI.
				awaitDeployPast(readyGeneration)
			}
		}

	/**
	 * Manual T3, the never-stale invariant: a save that does not compile must not move the
	 * proxy app, and the save that fixes it must.
	 *
	 * Both halves are load-bearing: the failure alone would also pass on a session that had
	 * quietly stopped building, and the recovery alone says nothing about staleness.
	 *
	 * Determinism: the absence half asserts over [GenerationWatch] rather than a sampled
	 * state, which is what makes it survive StateFlow conflation.
	 */
	@Test
	fun test_compileErrorHoldsTheGenerationThenTheFixAdvancesIt() =
		before {
			enableExperimentsForTest()
		}.after {
			restoreAfterQuickBuildTest()
		}.run {
			launchAndCreateSyncedProject("qb-error", "qberror")
			val baseline = tapAndAwaitReadySession().generation

			val target = findKotlinSourceFile(openProjectDir())
			val original = target.readText()
			val watch = GenerationWatch(baseline)
			try {
				step("Save syntactically broken Kotlin") {
					// A stray top-level closing brace is unambiguously a parse error and
					// leaves the rest of the file intact, so the fixing save below differs
					// from the original by one marker line and nothing else.
					save(target, original + "\n}\n")
				}

				step("The build fails to compile, and nothing reaches the proxy app") {
					val failed =
						awaitState("a compile error") {
							it is QuickBuildSessionState.Ready && it.lastFailure is SessionFailure.CompileError
						} as QuickBuildSessionState.Ready
					assertEquals(
						"A compile error must leave the session on the generation the proxy app already runs",
						baseline,
						failed.generation,
					)
					assertEquals(
						"No state may report a generation past the last good one while the source does not compile",
						baseline,
						watch.highest(),
					)
				}

				step("The fixing save compiles, deploys, and advances the generation") {
					// Deliberately NOT a revert to the exact original bytes: a byte-identical
					// write is the no-op route, which deploys nothing, so the recovery would
					// be indistinguishable from the pipeline having died.
					save(target, original + "\n// ADFA-4128 T3 recovery marker\n")
					val recovered = awaitDeployPast(baseline)
					assertEquals(
						"The recovering deploy must be the highest generation the session has reported",
						recovered,
						watch.highest(),
					)
				}
			} finally {
				watch.stop()
			}
		}

	/**
	 * Manual T4 and T5: a resources-only save and an assets-only save each reach
	 * [QuickBuildSessionState.Deployed]. One test over both routes because they share the
	 * provisioning cycle, which is the whole cost here; the assertions stay per-route.
	 *
	 * What this pins that the route's unit tests cannot: both routes end inside the proxy
	 * app's process - a resource-table swap and an asset overlay - and both have regressed
	 * there before. A proxy app that crashes on the swap never acknowledges, so it never
	 * reaches Deployed.
	 *
	 * Both files are seeded before provisioning, so each edit changes an existing
	 * resource/asset rather than adding one - the route the manual case walks.
	 */
	@Test
	fun test_resourceOnlyAndAssetOnlyEditsEachReachDeployed() =
		before {
			enableExperimentsForTest()
		}.after {
			restoreAfterQuickBuildTest()
		}.run {
			step("This device can serve a deployed asset payload") {
				// ChangeClassifier routes any asset-bearing change to a full Gradle
				// rebaseline below API 30, because the runtime's asset overlay rides
				// ResourcesLoader. Asserting Deployed there would be asserting the wrong
				// behaviour, so skip rather than lie.
				assumeTrue(
					"The assets live-reload route needs API 30+; this device is API ${Build.VERSION.SDK_INT}",
					Build.VERSION.SDK_INT >= Build.VERSION_CODES.R,
				)
			}

			launchAndCreateSyncedProject("qb-routes", "qbroutes")

			val mainSourceSet = findMainSourceSet(openProjectDir())
			val strings = File(mainSourceSet, "res/values/strings.xml")
			val asset = File(mainSourceSet, "assets/message.txt")
			step("Seed the resource and the asset the two edits will change") {
				assertTrue("Template has no ${strings.path}", strings.isFile)
				save(strings, strings.readText().replace("</resources>", "\t<string name=\"qb_route_label\">res: A</string>\n</resources>"))
				assertTrue(
					"Could not seed a string into ${strings.path} (no </resources> to anchor on?)",
					strings.readText().contains("res: A"),
				)
				save(asset, "asset: A\n")
			}

			val baseline = tapAndAwaitReadySession().generation

			var afterResources = baseline
			step("A resources-only save reaches Deployed") {
				save(strings, strings.readText().replace("res: A", "res: B"))
				afterResources = awaitDeployPast(baseline)
			}

			step("An assets-only save reaches Deployed") {
				save(asset, "asset: B\n")
				awaitDeployPast(afterResources)
			}
		}

	/**
	 * Manual T11: a real `am force-stop` of the proxy app, and the recovery from it.
	 *
	 * The recovery logic is thoroughly unit-pinned, but every one of those tests injects
	 * [org.appdevforall.cotg.quickbuild.service.deploy.DeployResult.NotConnected]. This
	 * closes the one link none of them touch: that a real force-stop presents as a lost
	 * connection rather than as a hang or a stale binder.
	 *
	 * A save that only reports the failure is the designed behaviour, not a shortfall:
	 * `PayloadDeployer.deployRecovering` refuses to launch the app for a build nobody asked
	 * for, so the relaunch-and-retry-once path defect #88 added belongs to the tap.
	 *
	 * Determinism: the disconnect is waited for, not raced. A deploy that reaches a
	 * not-yet-dead binder fails as a binder error rather than NotConnected, which would
	 * read as a defect in the recovery path instead of as this test being early.
	 */
	@Test
	fun test_forceStoppedProxyAppReportsNotRunningAndOneTapRecovers() =
		before {
			enableExperimentsForTest()
		}.after {
			restoreAfterQuickBuildTest()
		}.run {
			launchAndCreateSyncedProject("qb-kill", "qbkill")
			val baseline = tapAndAwaitReadySession().generation

			step("Force-stop the proxy app and wait for the disconnect to be observed") {
				val packageName = openProjectApplicationId()
				device.uiDevice.executeShellCommand("am force-stop $packageName")
				val disconnected =
					runBlocking {
						withTimeoutOrNull(PROXY_DISCONNECT_TIMEOUT_MS) {
							ProxyAppConnections.INSTANCE.target.first { it == null }
							true
						}
					} ?: false
				assertTrue("Force-stopping $packageName never disconnected the proxy app binder", disconnected)
			}

			val target = findKotlinSourceFile(openProjectDir())
			step("A save alone reports the app is not running and moves nothing") {
				save(target, target.readText() + "\n// ADFA-4128 T11 force-kill marker\n")
				val parked =
					awaitState("a deploy failure") {
						it is QuickBuildSessionState.Ready && it.lastFailure is SessionFailure.DeployError
					} as QuickBuildSessionState.Ready
				val message = (parked.lastFailure as SessionFailure.DeployError).message
				// PayloadDeployer.failureOf gives each DeployResult its own wording, so this
				// discriminates NotConnected from a timeout, a disconnect mid-deploy, or a
				// binder error - the wrong-shaped verdicts a force-stop must NOT produce.
				assertTrue(
					"A force-stopped proxy app must report as not running; the failure said: $message",
					message.contains("not running"),
				)
				assertEquals("A failed deploy must not move the generation", baseline, parked.generation)
			}

			step("One Quick Build tap relaunches the app and deploys") {
				tapQuickBuildButton()
				awaitDeployPast(baseline)
			}
		}

	/**
	 * Enables the experiments flag, which is what registers the Quick Build toolbar action.
	 *
	 * Snapshots the pre-test state so the after-block restores it, rather than clearing a
	 * flag a dev device may legitimately have set. Loads JDK distributions synchronously
	 * too: on an already-provisioned device OnboardingActivity skips its async reload in
	 * test mode, so `isSetupCompleted()` stays false and the app parks on the welcome slide
	 * forever.
	 */
	private fun enableExperimentsForTest() {
		hadExperimentsFlag = isExperimentsFlagSet()
		setExperimentsFlagForTest(true)
		IJdkDistributionProvider.getInstance().loadDistributions()
	}

	/** Restores the flag, leaves no live session behind, and re-binds the real clobber check. */
	private fun restoreAfterQuickBuildTest() {
		setExperimentsFlagForTest(hadExperimentsFlag)
		runCatching { GlobalContext.get().get<QuickBuildSessionManager>().restartSession() }
		restoreRealClobberCheckIfOverridden()
	}

	/**
	 * Launches the app and drives the New Project wizard to a synced Kotlin project.
	 *
	 * @param projectName the wizard's project name; must carry the `qb-` prefix, since
	 *   on-device automation may only create `qb-*` project dirs
	 */
	private fun TestContext<Unit>.launchAndCreateSyncedProject(
		projectName: String,
		packageSuffix: String,
	) {
		step("Launch app") {
			ActivityScenario.launch(SplashActivity::class.java)
			waitForMainHomeOrEditorUi(device.uiDevice)
		}

		ensureOnHomeScreenBeforeCreateProject()

		step("Create project") {
			clickCreateProjectHomeScreen()
		}
		selectProjectTemplate("Select Empty Activity template", R.string.template_empty)
		selectKotlinLanguage()
		setProjectName(projectName)
		fixDerivedPackageName(projectName, packageSuffix)
		clickCreateProjectProjectSettings()

		dismissFirstBuildNoticeIfShown()
		assertQuickBuildButtonShown(EDITOR_OPEN_TIMEOUT_MS)

		waitForProjectSync()
	}

	/** Taps Quick Build on an empty install slot and waits out the real provisioning cycle. */
	private fun TestContext<Unit>.tapAndAwaitReadySession(): QuickBuildSessionState.Ready {
		step("Real tap starts provisioning without a clobber confirm") {
			// Slot empty: the tap must proceed straight into provisioning.
			overrideClobberCheckWithEmptySlot()
			tapQuickBuildButton()
		}

		// step() returns Unit (Kaspresso's TestContext.step signature), so the value
		// crosses the step boundary via this captured var rather than a step "result".
		var ready: QuickBuildSessionState.Ready? = null
		step("Wait for Ready") {
			ready = awaitReadyConfirmingProxyAppInstall()
		}
		return checkNotNull(ready)
	}

	/**
	 * Waits for the session to reach [QuickBuildSessionState.Ready], tapping the system
	 * package-installer's confirm button whenever it appears.
	 *
	 * Provisioning installs the proxy app through Android's installer UI, which requires a
	 * human tap. Left unanswered, CoGo's own install-confirm fail-fast gives up after 180 s
	 * and drops the session back out of provisioning, so an unattended run MUST drive that
	 * dialog or it can never reach Ready.
	 *
	 * The Flow is collected on a background coroutine (so a fast Ready -> Building warm-compile
	 * transition can't be missed the way polling `state.value` would miss it) while this, the
	 * instrumentation thread, keeps sole ownership of UiAutomator.
	 */
	private fun TestContext<Unit>.awaitReadyConfirmingProxyAppInstall(): QuickBuildSessionState.Ready {
		val d = device.uiDevice
		val ready = AtomicReference<QuickBuildSessionState.Ready?>(null)
		val scope = CoroutineScope(Dispatchers.Default)
		val collector =
			scope.launch {
				val state = sessionManager().state.first { it is QuickBuildSessionState.Ready }
				ready.set(state as QuickBuildSessionState.Ready)
			}
		try {
			val deadline = System.currentTimeMillis() + PROVISIONING_READY_TIMEOUT_MS
			while (ready.get() == null && System.currentTimeMillis() < deadline) {
				val confirm =
					d.findObject(
						UiSelector()
							.packageNameMatches(".*packageinstaller.*|.*permissioncontroller.*")
							.textMatches("(?i)install"),
					)
				if (confirm.exists()) {
					runCatching { confirm.click() }
				}
				Thread.sleep(INSTALL_CONFIRM_POLL_MS)
			}
		} finally {
			collector.cancel()
		}
		return ready.get()
			?: error("Session never reached Ready; last state was ${sessionManager().state.value}")
	}

	/**
	 * Waits for a deploy that moves the proxy app past [previousGeneration], and returns the
	 * generation it landed on - the floor for a caller chaining several saves.
	 */
	private fun awaitDeployPast(previousGeneration: Long): Long {
		val deployed =
			awaitState("a deploy past generation $previousGeneration") {
				it is QuickBuildSessionState.Deployed && it.generation > previousGeneration
			} as QuickBuildSessionState.Deployed
		return deployed.generation
	}

	/**
	 * Waits for the first session state matching [predicate], failing with the state the
	 * session was actually sitting in rather than a bare timeout.
	 *
	 * @param what names the awaited state in the failure message
	 */
	private fun awaitState(
		what: String,
		predicate: (QuickBuildSessionState) -> Boolean,
	): QuickBuildSessionState {
		val state =
			runBlocking {
				withTimeoutOrNull(DEPLOY_TIMEOUT_MS) { sessionManager().state.first(predicate) }
			}
		assertNotNull(
			"Session never reached $what within $DEPLOY_TIMEOUT_MS ms; last state was ${sessionManager().state.value}",
			state,
		)
		return checkNotNull(state)
	}

	/**
	 * Background record of the highest generation the session has reported the proxy app to
	 * be running, from [baseline] onwards.
	 *
	 * Robust to [kotlinx.coroutines.flow.StateFlow] conflation rather than at its mercy:
	 * every live state carries the running generation forward
	 * ([QuickBuildSessionState.Ready.generation],
	 * [QuickBuildSessionState.Building.deployedGeneration], and so on), so an advance whose
	 * own emission is conflated away is still visible in the state that follows it.
	 */
	private inner class GenerationWatch(
		baseline: Long,
	) {
		private val highest = AtomicLong(baseline)
		private val scope = CoroutineScope(Dispatchers.Default)
		private val collector =
			scope.launch {
				sessionManager().state.collect { state ->
					runningGenerationOf(state)?.let { generation ->
						highest.updateAndGet { seen -> maxOf(seen, generation) }
					}
				}
			}

		fun highest(): Long = highest.get()

		fun stop() {
			collector.cancel()
		}
	}

	/** The generation the proxy app runs in [state], or null for a state with no live app. */
	private fun runningGenerationOf(state: QuickBuildSessionState): Long? =
		when (state) {
			is QuickBuildSessionState.Ready -> state.generation

			is QuickBuildSessionState.Building -> state.deployedGeneration

			is QuickBuildSessionState.Deployed -> state.generation

			is QuickBuildSessionState.Invalidated -> state.deployedGeneration

			is QuickBuildSessionState.Degraded -> state.deployedGeneration

			is QuickBuildSessionState.Idle,
			is QuickBuildSessionState.Prebuilding,
			is QuickBuildSessionState.Provisioning,
			-> null
		}

	private fun sessionManager(): QuickBuildSessionManager = GlobalContext.get().get()

	private fun overrideClobberCheckWithEmptySlot() {
		loadKoinModules(module { single<QuickBuildClobberCheck> { QuickBuildClobberCheck(fakePackages) } })
		clobberCheckOverridden = true
		fakePackages.installed = false
	}

	private fun restoreRealClobberCheckIfOverridden() {
		if (clobberCheckOverridden) {
			// Re-bind the real PackageManager-backed check so later tests see production
			// behavior instead of the fake.
			loadKoinModules(
				module {
					single { QuickBuildClobberCheck(AndroidInstalledPackages(targetContext)) }
				},
			)
		}
	}

	private fun TestContext<Unit>.fixDerivedPackageName(
		projectName: String,
		packageSuffix: String,
	) {
		step("Fix the auto-derived package name (hyphen is not a valid package char)") {
			// appNameToPackageName derives "com.example.$projectName", which fails the
			// PACKAGE constraint and silently blocks the Create button. Overwrite it.
			val d = device.uiDevice
			val derived = d.findObject(UiSelector().text("com.example.$projectName"))
			check(derived.waitForExists(PACKAGE_FIELD_TIMEOUT_MS)) { "Auto-derived package field not found" }
			setAccessibilityEditText("com.example.$projectName", "com.example.$packageSuffix", "package name")
			d.waitForIdle()
		}
	}

	private fun TestContext<Unit>.waitForProjectSync() {
		step("Wait for project sync (real applicationId available)") {
			// The clobber gate and the real proxy app build both need the selected
			// variant's applicationId, which only exists after the project's Gradle sync
			// completes. Same ceiling as the existing init scenario; polls a state seam
			// instead of UI text.
			val deadline = System.currentTimeMillis() + PROJECT_SYNC_TIMEOUT_MS
			var appId: String? = null
			while (System.currentTimeMillis() < deadline && appId == null) {
				appId = selectedVariantApplicationId()
				if (appId == null) {
					Thread.sleep(PROJECT_SYNC_POLL_MS)
				}
			}
			check(appId != null) { "Project sync never produced an applicationId" }
		}
	}

	/**
	 * The open project's real applicationId - which is also the proxy app's package, since
	 * the plugin writes `proxyAppId` as the project's own applicationId (that is what makes
	 * Quick Build and Standard Run contend for one install slot).
	 */
	private fun openProjectApplicationId(): String = selectedVariantApplicationId() ?: error("No applicationId; the project has not synced")

	private fun selectedVariantApplicationId(): String? =
		runCatching {
			IProjectManager
				.getInstance()
				.getAndroidAppModules()
				.firstOrNull()
				?.getSelectedVariant()
				?.mainArtifact
				?.applicationId
		}.getOrNull()
			?.takeIf { it.isNotBlank() }

	private fun openProjectDir(): File {
		val dir = File(IProjectManager.getInstance().projectDirPath)
		assertTrue("No open project directory", dir.isDirectory)
		return dir
	}

	/**
	 * Writes [content] the way CoGo's own editor saves - an in-place truncate and write on
	 * the same path, per `WatchFilter`'s KDoc - so the on-device watcher sees a plain
	 * content change rather than the rename a temp-file-plus-move would produce.
	 */
	private fun save(
		target: File,
		content: String,
	) {
		target.parentFile?.mkdirs()
		FileOutputStream(target, false).use { stream ->
			stream.write(content.toByteArray(Charsets.UTF_8))
		}
	}

	/** First non-build Kotlin source file under [projectDir] - the wizard's MainActivity.kt. */
	private fun findKotlinSourceFile(projectDir: File): File =
		projectDir
			.walkTopDown()
			.firstOrNull { file -> file.isFile && file.extension == "kt" && !file.isUnderBuildDir(projectDir) }
			?: error("No Kotlin source file found under $projectDir")

	/** The app module's `src/main` directory, which roots both `res/` and `assets/`. */
	private fun findMainSourceSet(projectDir: File): File =
		projectDir
			.walkTopDown()
			.firstOrNull { file ->
				file.isDirectory &&
					file.name == "main" &&
					file.parentFile?.name == "src" &&
					!file.isUnderBuildDir(projectDir)
			} ?: error("No src/main source set found under $projectDir")

	private fun File.isUnderBuildDir(projectDir: File): Boolean =
		relativeTo(projectDir)
			.path
			.split(File.separatorChar)
			.any { it == "build" }
}
