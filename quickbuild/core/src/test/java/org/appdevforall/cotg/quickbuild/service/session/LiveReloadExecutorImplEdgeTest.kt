package org.appdevforall.cotg.quickbuild.service.session

import com.google.common.truth.Truth.assertThat
import com.google.gson.JsonParser
import kotlinx.coroutines.test.runTest
import org.appdevforall.cotg.quickbuild.data.CompileOutput
import org.appdevforall.cotg.quickbuild.data.DaemonReply
import org.appdevforall.cotg.quickbuild.data.QuickBuildProjectLayout
import org.appdevforall.cotg.quickbuild.domain.ChangedFiles
import org.appdevforall.cotg.quickbuild.domain.classify.BuildRoute
import org.appdevforall.cotg.quickbuild.domain.reload.BuildOutcome
import org.appdevforall.cotg.quickbuild.domain.reload.BuildRequest
import org.appdevforall.cotg.quickbuild.domain.reload.ComponentInfo
import org.appdevforall.cotg.quickbuild.domain.reload.ComponentKind
import org.appdevforall.cotg.quickbuild.domain.reload.DeployPolicy
import org.appdevforall.cotg.quickbuild.domain.reload.GenerationTracker
import org.appdevforall.cotg.quickbuild.service.FakeDaemon
import org.appdevforall.cotg.quickbuild.service.FakeDeploy
import org.appdevforall.cotg.quickbuild.service.MemoryGenerationStore
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Edge paths of [LiveReloadExecutorImpl] beyond [LiveReloadExecutorImplTest]'s route
 * coverage: the outer pipeline-failure guard, relink failures on the resource routes,
 * source-extension filtering, the transformed-manifest preference, and the deploy policy's
 * effect on the metadata a deploy carries.
 */
class LiveReloadExecutorImplEdgeTest {
	@TempDir lateinit var projectRoot: File

	private val daemon = FakeDaemon()
	private val deploy = FakeDeploy()
	private val store = MemoryGenerationStore()

	private lateinit var tracker: GenerationTracker
	private lateinit var sourceFile: File
	private lateinit var javaFile: File
	private lateinit var resFile: File

	@BeforeEach
	fun setUp() {
		val mainDir = File(projectRoot, "app/src/main")
		sourceFile =
			File(mainDir, "java/com/example/Foo.kt").apply {
				parentFile!!.mkdirs()
				writeText("class Foo")
			}
		javaFile = File(mainDir, "java/com/example/Legacy.java").apply { writeText("class Legacy {}") }
		resFile =
			File(mainDir, "res/values/strings.xml").apply {
				parentFile!!.mkdirs()
				writeText("<resources/>")
			}
		File(mainDir, "AndroidManifest.xml").writeText("<manifest/>")
		tracker = GenerationTracker(store)
	}

	private fun executor(
		clock: () -> Long = { 1000L },
		proxyAppManifest: File? = null,
		deployPolicy: DeployPolicy? = null,
	) = LiveReloadExecutorImpl(
		daemon = daemon,
		deploy = deploy,
		layout = QuickBuildProjectLayout(projectRoot),
		entryActivity = "com.example.MainActivity",
		generations = tracker,
		workDir = File(projectRoot, ".androidide/quickbuild"),
		proxyAppManifest = proxyAppManifest,
		deployPolicy = deployPolicy,
		clock = clock,
	)

	private fun request(
		route: BuildRoute,
		changes: ChangedFiles = ChangedFiles.Known.EMPTY,
	) = BuildRequest(buildId = 1, changes = changes, route = route)

	@Test
	fun `a pipeline throw maps to InfrastructureFailure with the exception's message`() =
		runTest {
			val outcome =
				executor(clock = { throw IllegalStateException("clock exploded") })
					.execute(request(BuildRoute.CodeOnly, ChangedFiles.Known(setOf(sourceFile))))

			assertThat(outcome).isEqualTo(BuildOutcome.InfrastructureFailure("clock exploded"))
			assertThat(deploy.calls).isEmpty()
		}

	@Test
	fun `a message-less pipeline throw falls back to the exception class name`() =
		runTest {
			val outcome =
				executor(clock = { throw IllegalStateException() })
					.execute(request(BuildRoute.CodeOnly, ChangedFiles.Known(setOf(sourceFile))))

			assertThat(outcome)
				.isEqualTo(BuildOutcome.InfrastructureFailure(IllegalStateException::class.java.name))
		}

	@Test
	fun `a resources-only relink infrastructure failure surfaces without a deploy`() =
		runTest {
			daemon.relinkReply = DaemonReply.Failed("aapt2 missing", daemonDied = false)

			val outcome =
				executor().execute(request(BuildRoute.ResourcesOnly, ChangedFiles.Known(setOf(resFile))))

			assertThat(outcome).isEqualTo(BuildOutcome.InfrastructureFailure("aapt2 missing"))
			assertThat(deploy.calls).isEmpty()
			assertThat(tracker.current).isEqualTo(0)
		}

	@Test
	fun `a mixed route whose relink fails surfaces the failure after a green compile`() =
		runTest {
			daemon.relinkReply = DaemonReply.Failed("relink socket closed", daemonDied = true)

			val outcome =
				executor().execute(
					request(BuildRoute.CodeAndResources, ChangedFiles.Known(setOf(sourceFile, resFile))),
				)

			// The compile ran (its half is green) but nothing may deploy on a half-built payload.
			assertThat(daemon.compileCalls).hasSize(1)
			val failure = outcome as BuildOutcome.InfrastructureFailure
			assertThat(failure.message).isEqualTo("relink socket closed")
			assertThat(failure.daemonDied).isTrue()
			assertThat(deploy.calls).isEmpty()
		}

	@Test
	fun `an assets-only route with nothing packageable succeeds without a deploy`() =
		runTest {
			// The only "change" resolves under no asset root, so the packager has nothing
			// to ship - and the executor must not fabricate a payload.
			val ghost = File(projectRoot, "app/src/main/assets-old/ghost.json")

			val outcome =
				executor().execute(
					request(BuildRoute.AssetsOnly, ChangedFiles.Known(emptySet(), removed = setOf(ghost))),
				)

			assertThat(outcome).isEqualTo(BuildOutcome.Success(0, 0))
			assertThat(deploy.calls).isEmpty()
			assertThat(daemon.compileCalls).isEmpty()
		}

	@Test
	fun `changed assets ride along on a resources route`() =
		runTest {
			val asset =
				File(projectRoot, "app/src/main/assets/data/levels.json").apply {
					parentFile!!.mkdirs()
					writeText("{}")
				}

			executor().execute(
				request(BuildRoute.ResourcesOnly, ChangedFiles.Known(setOf(resFile, asset))),
			)

			assertThat(deploy.calls.single().assetsZip).isNotNull()
			assertThat(deploy.calls.single().arscFile).isNotNull()
		}

	@Test
	fun `java sources ride the changed set and non-sources are filtered out`() =
		runTest {
			val stray = File(projectRoot, "app/src/main/java/com/example/notes.txt").apply { writeText("x") }

			executor().execute(
				request(BuildRoute.CodeOnly, ChangedFiles.Known(setOf(javaFile, stray))),
			)

			assertThat(daemon.compileCalls.single().second).containsExactly(javaFile)
		}

	@Test
	fun `removed non-sources are filtered from the compiler's removed set`() =
		runTest {
			val removedJava = File(projectRoot, "app/src/main/java/com/example/Gone.java")
			val removedStray = File(projectRoot, "app/src/main/java/com/example/gone.txt")

			executor().execute(
				request(
					BuildRoute.CodeOnly,
					ChangedFiles.Known(setOf(sourceFile), removed = setOf(removedJava, removedStray)),
				),
			)

			assertThat(daemon.compileRemovedFiles.single()).containsExactly(removedJava)
		}

	@Test
	fun `relinks link against the transformed manifest when the proxy app build produced one`() =
		runTest {
			val transformed = File(projectRoot, "transformed/AndroidManifest.xml")

			executor(proxyAppManifest = transformed)
				.execute(request(BuildRoute.ResourcesOnly, ChangedFiles.Known(setOf(resFile))))

			assertThat(daemon.relinkCalls.single().manifest).isEqualTo(transformed)
		}

	@Test
	fun `a helper-only edit sends restart metadata when the app declares a service`() =
		runTest {
			// The end-to-end half of the restart rule: nothing about this edit names the
			// service, and the deploy must still carry `restart` because the payload it ships
			// redefines the service class along with everything else.
			daemon.compileReply =
				DaemonReply.Ok(
					CompileOutput(File("/fake/classes"), listOf("com/example/Helper.class")),
				)

			executor(
				deployPolicy =
					DeployPolicy(
						listOf(ComponentInfo(ComponentKind.SERVICE, "com.example.SyncService")),
					),
			).execute(request(BuildRoute.CodeOnly, ChangedFiles.Known(setOf(sourceFile))))

			val metadata = JsonParser.parseString(deploy.calls.single().metadataJson).asJsonObject
			assertThat(metadata.get("restart").asString).isEqualTo("true")
		}

	@Test
	fun `the same edit hot-swaps when the app declares no held component`() =
		runTest {
			// The negative control for the test above: same edit, same pipeline, a component
			// list with nothing a loader swap cannot update.
			daemon.compileReply =
				DaemonReply.Ok(
					CompileOutput(File("/fake/classes"), listOf("com/example/Helper.class")),
				)

			val outcome =
				executor(
					deployPolicy =
						DeployPolicy(
							listOf(
								ComponentInfo(
									ComponentKind.ACTIVITY,
									"com.example.MainActivity",
									proxyClass = "com.example.quickbuild.proxies.Proxy0Activity",
									launcher = true,
								),
							),
						),
				).execute(request(BuildRoute.CodeOnly, ChangedFiles.Known(setOf(sourceFile))))

			assertThat(outcome).isEqualTo(BuildOutcome.Success(1, 0))
			val metadata = JsonParser.parseString(deploy.calls.single().metadataJson).asJsonObject
			assertThat(metadata.has("restart")).isFalse()
		}
}
