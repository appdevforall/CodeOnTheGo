package org.appdevforall.codeonthego.indexing.jvm

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.itsaky.androidide.memprof.Memprof
import com.itsaky.androidide.projects.api.GradleProject
import com.itsaky.androidide.projects.api.ModuleProject
import com.itsaky.androidide.projects.api.Workspace
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.appdevforall.codeonthego.indexing.service.IndexRegistry
import org.appdevforall.codeonthego.indexing.service.IndexingProgressTracker
import org.appdevforall.codeonthego.indexing.service.IndexingState
import org.jetbrains.org.objectweb.asm.ClassWriter
import org.jetbrains.org.objectweb.asm.Opcodes
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** A library pass that submits JARs is profiled as the "Index libraries" phase. */
@RunWith(RobolectricTestRunner::class)
class JvmLibraryIndexingServicePhaseTest {
	@get:Rule
	val temp = TemporaryFolder()

	private val context = ApplicationProvider.getApplicationContext<Context>()
	private val sink = RecordingMemprofSink()
	private val tracker = IndexingProgressTracker()

	@Before
	fun setUp() {
		Memprof.sink = sink
	}

	@After
	fun tearDown() {
		Memprof.sink = null
		context.deleteDatabase(JvmSymbolIndex.DB_NAME_DEFAULT)
	}

	@Test
	fun `a library pass that submits JARs ends the Index libraries phase with its JAR count`() {
		val service = libraryService(jar("a.jar"), jar("b.jar"))

		service.use { runBlocking { refreshAndAwait(it) } }

		assertThat(sink.phases.map { listOf(it.title, it.marker, it.details, it.ended) })
			.containsExactly(listOf("Index libraries", "library_index_complete", mapOf("jars" to 2L), true))
	}

	@Test
	fun `a library pass with nothing new to index records no phase`() {
		val service = libraryService(jar("a.jar"))

		service.use {
			runBlocking {
				refreshAndAwait(it)
				it.refresh().join()
			}
		}

		assertThat(sink.phases).hasSize(1)
	}

	@Test
	fun `a library pass that only folds into another pass's running jobs records no phase`() {
		val fifo = File(temp.root, "blocking.jar")
		assumeTrue(ProcessBuilder("mkfifo", fifo.path).start().waitFor() == 0)

		/*
		 * The first pass is the one initialization starts. Opening the FIFO blocks its scan until a
		 * writer opens it, so its job is still running when the second pass submits the same JAR. The
		 * third workspace read happens only once the second pass has released the mutex, so it marks
		 * that pass as fully submitted.
		 */
		val reads = AtomicInteger(0)
		val firstPassSubmitting = CountDownLatch(1)
		val secondPassSubmitted = CountDownLatch(1)
		val workspace = workspaceOf(fifo.path)
		val service =
			JvmLibraryIndexingService(context, tracker, workspaceSupplier = {
				when (reads.incrementAndGet()) {
					1 -> workspace.also { firstPassSubmitting.countDown() }
					2 -> workspace
					else -> null.also { secondPassSubmitted.countDown() }
				}
			})

		service.use {
			runBlocking {
				it.initialize(IndexRegistry())
				assertThat(firstPassSubmitting.await(5, TimeUnit.SECONDS)).isTrue()
				val others = listOf(it.refresh(), it.refresh())
				assertThat(secondPassSubmitted.await(5, TimeUnit.SECONDS)).isTrue()

				FileOutputStream(fifo).close()
				withTimeout(10_000) {
					others.joinAll()
					awaitIdle()
				}
			}
		}

		assertThat(sink.phases).hasSize(1)
	}

	@Test
	fun `the Index libraries phase is abandoned when its pass fails`() {
		runCatching { indexLibrariesPhase(jarCount = 1) { error("optimize failed") } }

		assertThat(sink.phases.map { it.abandoned }).containsExactly(true)
	}

	/**
	 * Initializes [service] and waits for the pass that starts on its own to finish.
	 *
	 * Subscribing to [tracker] before calling [initialize][JvmLibraryIndexingService.initialize]
	 * means the wait observes that pass actually start, so a tracker already back to
	 * [IndexingState.Idle] cannot be mistaken for one that never ran a pass at all.
	 */
	private suspend fun refreshAndAwait(service: JvmLibraryIndexingService) =
		coroutineScope {
			val initPass =
				async(start = CoroutineStart.UNDISPATCHED) {
					tracker.state.first { it is IndexingState.Indexing }
					awaitIdle()
				}
			service.initialize(IndexRegistry())
			withTimeout(10_000) { initPass.await() }
		}

	private suspend fun awaitIdle() {
		tracker.state.first { it is IndexingState.Idle }
	}

	private fun libraryService(vararg jars: String): JvmLibraryIndexingService {
		val workspace = workspaceOf(*jars)
		return JvmLibraryIndexingService(context, tracker, workspaceSupplier = { workspace })
	}

	private fun workspaceOf(vararg jars: String): Workspace {
		val module =
			mockk<ModuleProject> {
				every { path } returns ":app"
				every { getCompileClasspaths(excludeSourceGeneratedClassPath = true) } returns jars.map(::File).toSet()
			}
		val root = mockk<GradleProject> { every { path } returns ":" }
		return mockk<Workspace> {
			every { rootProject } returns root
			every { subProjects } returns listOf(module)
		}
	}

	/** Writes a JAR holding one public class, which indexes cleanly and so records its fingerprint. */
	private fun jar(name: String): String {
		val internalName = "p/${name.substringBefore('.').uppercase()}"
		val bytes =
			ClassWriter(0)
				.apply {
					visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC or Opcodes.ACC_SUPER, internalName, null, "java/lang/Object", null)
					visitEnd()
				}.toByteArray()
		val jar = File(temp.root, name)
		ZipOutputStream(jar.outputStream()).use { out ->
			out.putNextEntry(ZipEntry("$internalName.class"))
			out.write(bytes)
			out.closeEntry()
		}
		return jar.absolutePath
	}
}
