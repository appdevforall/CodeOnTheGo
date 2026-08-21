@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package org.appdevforall.cotg.quickbuild.service.session

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.appdevforall.cotg.quickbuild.data.ProjectWatcher
import org.appdevforall.cotg.quickbuild.data.ProxyAppInfo
import org.appdevforall.cotg.quickbuild.data.QuickBuildProjectLayout
import org.appdevforall.cotg.quickbuild.domain.ChangedFiles
import org.appdevforall.cotg.quickbuild.domain.annotations.AnnotationImpact
import org.appdevforall.cotg.quickbuild.domain.annotations.SwitchableAnnotationImpact
import org.appdevforall.cotg.quickbuild.domain.classify.ChangeClassifier
import org.appdevforall.cotg.quickbuild.domain.reload.BuildOutcome
import org.appdevforall.cotg.quickbuild.domain.reload.BuildRequest
import org.appdevforall.cotg.quickbuild.domain.reload.GenerationTracker
import org.appdevforall.cotg.quickbuild.domain.reload.LiveReloadExecutor
import org.appdevforall.cotg.quickbuild.domain.reload.LiveReloadOrchestrator
import org.appdevforall.cotg.quickbuild.domain.watch.WatchFilter
import org.appdevforall.cotg.quickbuild.service.MemoryGenerationStore
import org.appdevforall.cotg.quickbuild.service.deploy.RetainedPayloadStore
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Every ProxyAppInfo-derived piece of a session moves to the new baseline together.
 * Leave one behind and the deploy policy keeps routing on provisioning-time facts - a
 * service the rebuild just proxied would hot-swap and leave its live instance stale -
 * which is invisible in a green build and only shows up as a wrong deploy on device.
 */
class LiveSessionAdoptBaselineTest {
	@TempDir lateinit var projectRoot: File

	private class RecordingExecutor : LiveReloadExecutor {
		val requests = mutableListOf<BuildRequest>()
		var userInitiatedMarks = 0

		override suspend fun execute(request: BuildRequest): BuildOutcome {
			requests += request
			return BuildOutcome.Success(generation = 1, durationMillis = 0)
		}

		override fun markCurrentBuildUserInitiated() {
			userInitiatedMarks++
		}
	}

	private class NoopWatcher : ProjectWatcher {
		override fun start(onBatch: (ChangedFiles.Known) -> Unit) = Unit

		override fun stop() = Unit
	}

	private class FixedAnnotationImpact(
		override val active: Boolean,
	) : AnnotationImpact {
		override fun escalation(changedCodeFiles: List<File>): String? = null
	}

	private fun proxyApp(pkg: String) =
		ProxyAppInfo(
			proxyAppPackage = pkg,
			entryActivity = "com.example.MainActivity",
			apk = File(projectRoot, "proxy-app.apk"),
			classpath = emptyList(),
			proxyClassesDir = null,
			transformedManifest = null,
			schema = 2,
			components = emptyList(),
			annotationProcessors = emptyList(),
		)

	private fun session(scope: kotlinx.coroutines.CoroutineScope): LiveSession {
		val executor = SwitchableExecutor(RecordingExecutor())
		return LiveSession(
			proxyApp = proxyApp("com.example.old"),
			layout = QuickBuildProjectLayout(projectRoot),
			tracker = GenerationTracker(MemoryGenerationStore()),
			filter = WatchFilter(listOf(projectRoot)),
			orchestrator = LiveReloadOrchestrator(executor, ChangeClassifier(), scope) {},
			watcher = NoopWatcher(),
			executor = executor,
			annotationImpact = SwitchableAnnotationImpact(FixedAnnotationImpact(active = false)),
			retainedPayloads = RetainedPayloadStore.forWorkDir(File(projectRoot, "work")),
		)
	}

	@Test
	fun `adoptBaseline moves proxyApp, layout, both delegates and the deployed generation together`() =
		runTest {
			val session = session(backgroundScope)
			session.lastDeployedGeneration = 7L

			val newLayout = QuickBuildProjectLayout(File(projectRoot, "rebuilt").apply { mkdirs() })
			val newExecutor = RecordingExecutor()
			val newAnnotationImpact = FixedAnnotationImpact(active = true)

			session.adoptBaseline(
				proxyApp("com.example.new"),
				newLayout,
				newExecutor,
				newAnnotationImpact,
				baselineGeneration = 9L,
			)

			assertThat(session.proxyApp.proxyAppPackage).isEqualTo("com.example.new")
			assertThat(session.layout).isSameInstanceAs(newLayout)
			assertThat(session.executor.delegate).isSameInstanceAs(newExecutor)
			assertThat(session.annotationImpact.delegate).isSameInstanceAs(newAnnotationImpact)
			// The reinstalled baseline boots at its stamp (9), so anything deployed to the
			// old epoch (7) is gone and a reconnect at 9 reads in-sync.
			assertThat(session.lastDeployedGeneration).isEqualTo(9L)
		}

	@Test
	fun `adoptBaseline drops the retained payload - the old baseline's bytes must not replay onto the new one`() =
		runTest {
			val session = session(backgroundScope)
			val dex = File(projectRoot, "built.dex").apply { writeText("old-baseline-dex") }
			session.retainedPayloads.retain(7L, dex, null, null, "{}")

			session.adoptBaseline(
				proxyApp("com.example.new"),
				QuickBuildProjectLayout(projectRoot),
				RecordingExecutor(),
				FixedAnnotationImpact(active = false),
				baselineGeneration = 9L,
			)

			// A reconnect below the new baseline must fall through to the forced rebuild;
			// re-sending retention from the old baseline would resurrect superseded code.
			assertThat(session.retainedPayloads.load()).isNull()
		}

	@Test
	fun `markCurrentBuildUserInitiated reaches the delegate, before and after a baseline swap`() =
		runTest {
			val session = session(backgroundScope)
			val oldExecutor = session.executor.delegate as RecordingExecutor

			session.executor.markCurrentBuildUserInitiated()

			// The interface gives this a no-op default, so a SwitchableExecutor that forgets to
			// override it absorbs the call and the real executor never learns the build was
			// promoted: the tap's deploy then refuses to relaunch a closed proxy app.
			assertThat(oldExecutor.userInitiatedMarks).isEqualTo(1)

			val newExecutor = RecordingExecutor()
			session.adoptBaseline(
				proxyApp("com.example.new"),
				QuickBuildProjectLayout(projectRoot),
				newExecutor,
				FixedAnnotationImpact(active = false),
				baselineGeneration = 0L,
			)
			session.executor.markCurrentBuildUserInitiated()

			assertThat(newExecutor.userInitiatedMarks).isEqualTo(1)
			assertThat(oldExecutor.userInitiatedMarks).isEqualTo(1)
		}

	@Test
	fun `a batch held across the rebuild is released to the NEW executor, not the old one`() =
		runTest {
			val session = session(backgroundScope)
			val oldExecutor = session.executor.delegate as RecordingExecutor
			val changed = ChangedFiles.Known(setOf(File(projectRoot, "app/src/main/java/A.kt")))
			session.orchestrator.onProxyAppRebuildStarted()
			session.orchestrator.onFilesChanged(changed)
			runCurrent()
			// Held, not built: the rebuild owns the device while it runs.
			assertThat(oldExecutor.requests).isEmpty()

			val newExecutor = RecordingExecutor()
			session.adoptBaseline(
				proxyApp("com.example.new"),
				QuickBuildProjectLayout(projectRoot),
				newExecutor,
				FixedAnnotationImpact(active = false),
				baselineGeneration = 0L,
			)
			runCurrent()

			// adoptBaseline has to release the hold; drop its onBaselineReset and the batch
			// sits in pending forever, so the user's edit never builds after a rebuild.
			assertThat(newExecutor.requests.single().changes).isEqualTo(changed)
			assertThat(oldExecutor.requests).isEmpty()
		}
}
