package org.appdevforall.cotg.quickbuild.service.session

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.appdevforall.cotg.quickbuild.data.CompileOutput
import org.appdevforall.cotg.quickbuild.data.DaemonReply
import org.appdevforall.cotg.quickbuild.data.DexOutput
import org.appdevforall.cotg.quickbuild.data.ProjectWatcher
import org.appdevforall.cotg.quickbuild.data.ProxyAppInfo
import org.appdevforall.cotg.quickbuild.data.QuickBuildProjectLayout
import org.appdevforall.cotg.quickbuild.data.QuickBuildScratch
import org.appdevforall.cotg.quickbuild.domain.ChangedFiles
import org.appdevforall.cotg.quickbuild.domain.annotations.AnnotationImpact
import org.appdevforall.cotg.quickbuild.domain.classify.BuildRoute
import org.appdevforall.cotg.quickbuild.domain.reload.BuildOutcome
import org.appdevforall.cotg.quickbuild.domain.reload.BuildRequest
import org.appdevforall.cotg.quickbuild.domain.reload.ComponentInfo
import org.appdevforall.cotg.quickbuild.domain.reload.ComponentKind
import org.appdevforall.cotg.quickbuild.domain.reload.GenerationTracker
import org.appdevforall.cotg.quickbuild.domain.telemetry.QuickBuildMetricsSink
import org.appdevforall.cotg.quickbuild.service.FakeDaemon
import org.appdevforall.cotg.quickbuild.service.FakeDeploy
import org.appdevforall.cotg.quickbuild.service.FakePaths
import org.appdevforall.cotg.quickbuild.service.MemoryGenerationStore
import org.appdevforall.cotg.quickbuild.service.deploy.DeployResult
import org.appdevforall.cotg.quickbuild.service.provision.ProvisionOutcome
import org.appdevforall.cotg.quickbuild.service.provision.ProxyAppLauncher
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class LiveSessionFactoryTest {
	@TempDir lateinit var projectRoot: File

	private val daemon = FakeDaemon()
	private val deploy = FakeDeploy()
	private val launchCalls = mutableListOf<Pair<String, String?>>()

	private lateinit var sourceFile: File

	@BeforeEach
	fun setUp() {
		val mainDir = File(projectRoot, "app/src/main")
		sourceFile =
			File(mainDir, "java/com/example/Foo.kt").apply {
				parentFile!!.mkdirs()
				writeText("class Foo")
			}
		File(mainDir, "AndroidManifest.xml").writeText("<manifest/>")
	}

	private fun factory(
		watcherFactory: QuickBuildSessionManager.WatcherFactory =
			QuickBuildSessionManager.WatcherFactory { _, _, _, _ ->
				error(
					"not used by these seams",
				)
			},
	) = LiveSessionFactory(
		daemon = daemon,
		deploy = deploy,
		scratch = QuickBuildScratch(FakePaths(projectRoot).projectScratchRoot),
		launcher =
			ProxyAppLauncher { packageName, activityClass ->
				launchCalls += packageName to activityClass
				true
			},
		metrics = QuickBuildMetricsSink.Noop,
		nowMillis = { 1000L },
		executorFactory = null,
		watcherFactory = watcherFactory,
		scope = CoroutineScope(StandardTestDispatcher()),
		onOrchestratorEvent = {},
		assetsLiveReloadable = true,
	)

	/** A watcher that observes nothing; [create]'s retention seam never starts it. */
	private object NoopWatcher : ProjectWatcher {
		override fun start(onBatch: (ChangedFiles.Known) -> Unit) = Unit

		override fun stop() = Unit
	}

	private fun proxyApp(
		schema: Int,
		components: List<ComponentInfo> = emptyList(),
		annotationProcessors: List<String> = emptyList(),
	) = ProxyAppInfo(
		proxyAppPackage = "com.example.quickbuild",
		entryActivity = "com.example.MainActivity",
		apk = File(projectRoot, "proxy-app.apk"),
		classpath = emptyList(),
		proxyClassesDir = null,
		transformedManifest = null,
		schema = schema,
		components = components,
		annotationProcessors = annotationProcessors,
	)

	private fun layout() = QuickBuildProjectLayout(projectRoot)

	private suspend fun executeCodeBuild(proxyApp: ProxyAppInfo): BuildOutcome {
		// A non-empty recompiled set, so the deploy policy actually decides.
		daemon.compileReply =
			DaemonReply.Ok(
				CompileOutput(File("/fake/classes"), changedClassFiles = listOf("com/example/Foo.class")),
			)
		val executor = factory().executorFor(proxyApp, layout(), GenerationTracker(MemoryGenerationStore()))
		return executor.execute(
			BuildRequest(
				buildId = 1,
				changes = ChangedFiles.Known(setOf(sourceFile)),
				route = BuildRoute.CodeOnly,
				// A tap: these tests read the launcher target off the recovery launch, and
				// only a tap is allowed to make one.
				userInitiated = true,
			),
		)
	}

	@Test
	fun `executorFor propagates componentInfoAvailable - a pre-v2 baseline refuses code deploys`() =
		runTest {
			val outcome = executeCodeBuild(proxyApp(schema = 0))
			assertThat(outcome).isInstanceOf(BuildOutcome.RequiresProxyAppRebuild::class.java)
			assertThat((outcome as BuildOutcome.RequiresProxyAppRebuild).detail)
				.contains("predates component metadata")
		}

	@Test
	fun `executorFor propagates componentInfoAvailable - a v2 baseline deploys the same change`() =
		runTest {
			val outcome = executeCodeBuild(proxyApp(schema = 2))
			assertThat(outcome).isInstanceOf(BuildOutcome.Success::class.java)
		}

	@Test
	fun `the session's retainedPayloads store reads what its own executor's deploys retain`() =
		runTest {
			// S8 agreement pin, reader side: create() wires the session's RetainedPayloadStore
			// and the executor's internal retention from two independent derivations of the
			// work dir. If they diverge, the manager's reconnect re-send looks where nothing
			// is ever written and every reconnect pays the forced rebuild S8 removed.
			daemon.compileReply =
				DaemonReply.Ok(
					CompileOutput(File("/fake/classes"), changedClassFiles = listOf("com/example/Foo.class")),
				)
			daemon.dexReply =
				DaemonReply.Ok(
					DexOutput(
						File(projectRoot, "built/classes.dex").apply {
							parentFile!!.mkdirs()
							writeText("dex-bytes")
						},
					),
				)
			val session =
				factory(watcherFactory = { _, _, _, _ -> NoopWatcher }).create(
					ProvisionOutcome.Success(
						proxyApp = proxyApp(schema = 2),
						proxyAppUid = 10123,
						layout = layout(),
					),
					GenerationTracker(MemoryGenerationStore()),
				)

			val outcome =
				session.executor.execute(
					BuildRequest(
						buildId = 1,
						changes = ChangedFiles.Known(setOf(sourceFile)),
						route = BuildRoute.CodeOnly,
						userInitiated = true,
					),
				)

			assertThat(outcome).isInstanceOf(BuildOutcome.Success::class.java)
			val retained = session.retainedPayloads.load()
			assertThat(retained).isNotNull()
			assertThat(retained!!.generation).isEqualTo((outcome as BuildOutcome.Success).generation)
			assertThat(retained.dexFile!!.readText()).isEqualTo("dex-bytes")
		}

	@Test
	fun `launcher activity resolves the MAIN-LAUNCHER activity's proxyClass`() =
		runTest {
			// NotConnected makes the deploy recovery relaunch, which observably carries
			// the launcher-activity target the factory resolved.
			deploy.result = DeployResult.NotConnected
			executeCodeBuild(
				proxyApp(
					schema = 2,
					components =
						listOf(
							ComponentInfo(
								ComponentKind.ACTIVITY,
								"com.example.SettingsActivity",
								proxyClass = "com.example.quickbuild.Proxy1Activity",
							),
							ComponentInfo(
								ComponentKind.ACTIVITY,
								"com.example.MainActivity",
								proxyClass = "com.example.quickbuild.Proxy0Activity",
								launcher = true,
							),
						),
				),
			)
			assertThat(launchCalls.single())
				.isEqualTo("com.example.quickbuild" to "com.example.quickbuild.Proxy0Activity")
		}

	@Test
	fun `launcher activity is null when no activity carries the launcher flag`() =
		runTest {
			deploy.result = DeployResult.NotConnected
			executeCodeBuild(
				proxyApp(
					schema = 2,
					components =
						listOf(
							ComponentInfo(
								ComponentKind.ACTIVITY,
								"com.example.SettingsActivity",
								proxyClass = "com.example.quickbuild.Proxy1Activity",
							),
						),
				),
			)
			assertThat(launchCalls.single()).isEqualTo("com.example.quickbuild" to null)
		}

	@Test
	fun `a project with no annotation processors gets Inactive annotation impact`() {
		val impact = factory().annotationImpactFor(proxyApp(schema = 2), layout())
		assertThat(impact).isEqualTo(AnnotationImpact.Inactive)
	}

	@Test
	fun `a project with annotation processors gets an active analyzer`() {
		val impact =
			factory().annotationImpactFor(
				proxyApp(schema = 2, annotationProcessors = listOf("androidx.room:room-compiler:2.6.1")),
				layout(),
			)
		assertThat(impact.active).isTrue()
	}
}
