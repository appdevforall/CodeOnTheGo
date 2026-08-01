package org.appdevforall.cotg.quickbuild.service

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.appdevforall.cotg.quickbuild.data.CompileOutput
import org.appdevforall.cotg.quickbuild.data.DaemonReply
import org.appdevforall.cotg.quickbuild.data.DefaultQuickBuildProjectLayout
import org.appdevforall.cotg.quickbuild.data.ProxyAppInfo
import org.appdevforall.cotg.quickbuild.data.QuickBuildScratch
import org.appdevforall.cotg.quickbuild.domain.BuildOutcome
import org.appdevforall.cotg.quickbuild.domain.BuildRequest
import org.appdevforall.cotg.quickbuild.domain.BuildRoute
import org.appdevforall.cotg.quickbuild.domain.ChangedFiles
import org.appdevforall.cotg.quickbuild.domain.ComponentInfo
import org.appdevforall.cotg.quickbuild.domain.ComponentKind
import org.appdevforall.cotg.quickbuild.domain.GenerationTracker
import org.appdevforall.cotg.quickbuild.domain.QuickBuildMetricsSink
import org.appdevforall.cotg.quickbuild.domain.annotations.AnnotationImpact
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

	private fun factory() =
		LiveSessionFactory(
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
			watcherFactory = { _, _, _, _ -> error("not used by these seams") },
			scope = CoroutineScope(StandardTestDispatcher()),
			onOrchestratorEvent = {},
		)

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

	private fun layout() = DefaultQuickBuildProjectLayout(projectRoot)

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
