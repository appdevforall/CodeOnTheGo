package com.itsaky.androidide.lsp.kotlin

import com.google.common.truth.Truth.assertThat
import com.itsaky.androidide.logging.provider.IdeLogRouter
import com.itsaky.androidide.lsp.kotlin.compiler.KotlinModuleLayout
import com.itsaky.androidide.lsp.kotlin.fixtures.gradleProject
import com.itsaky.androidide.projects.api.GradleProject
import com.itsaky.androidide.projects.api.Workspace
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.appdevforall.codeonthego.indexing.service.IndexRegistryClosedException
import org.appdevforall.codeonthego.indexing.service.IndexingServiceManager
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.slf4j.event.Level
import java.nio.file.Path
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.CopyOnWriteArrayList

class CompilerSessionTest {
	private val scope = CoroutineScope(SupervisorJob())
	private val lifecycle = FakeLifecycle()
	private val session = CompilerSession(scope, lifecycle)
	private val logs: MutableList<Pair<Level, String>> = CopyOnWriteArrayList()
	private val sink = IdeLogRouter.ExternalSink { level, _, message, _ -> logs += level to message }

	@Before
	fun installSink() {
		IdeLogRouter.addSink(sink)
	}

	@After
	fun tearDown() {
		IdeLogRouter.removeSink(sink)
		lifecycle.gates.values.forEach { it.complete(Unit) }
		lifecycle.awaitClosedGate?.complete(Unit)
		lifecycle.prepareGate?.complete(Unit)
		scope.cancel()
	}

	@Test
	fun `an unchanged layout keeps the compiler`(): Unit =
		runBlocking {
			session.submit(workspace(LAYOUT_A))
			awaitEvent("ready c1")
			session.submit(workspace(LAYOUT_A))
			awaitEvent("ready c1", times = 2)

			assertThat(lifecycle.builds).isEqualTo(1)
			assertThat(lifecycle.events.filter { it.startsWith("close") }).isEmpty()
			assertThat(session.current).isEqualTo("c1")
		}

	@Test
	fun `an update waits for the previous close before preparing`(): Unit =
		runBlocking {
			session.submit(workspace(LAYOUT_A))
			awaitEvent("ready c1")

			val events = lifecycle.events.toList()
			assertThat(events.indexOf("awaitClosed")).isLessThan(events.indexOf("prepare"))
		}

	@Test
	fun `a changed layout closes the old compiler before building the new one`(): Unit =
		runBlocking {
			session.submit(workspace(LAYOUT_A))
			awaitEvent("ready c1")
			session.submit(workspace(LAYOUT_B))
			awaitEvent("ready c2")

			val events = lifecycle.events.toList()
			assertThat(events.indexOf("close c1")).isLessThan(events.lastIndexOf("awaitClosed"))
			assertThat(events.lastIndexOf("awaitClosed")).isLessThan(events.indexOf("build c2 for B"))
			assertThat(session.current).isEqualTo("c2")
		}

	@Test
	fun `current is null while a rebuild is in progress`(): Unit =
		runBlocking {
			session.submit(workspace(LAYOUT_A))
			awaitEvent("ready c1")
			val gate = lifecycle.gate(build = 2)

			session.submit(workspace(LAYOUT_B))
			awaitEvent("building c2")

			assertThat(session.current).isNull()
			gate.complete(Unit)
			awaitEvent("ready c2")
		}

	@Test
	fun `updates during a build collapse to the latest`(): Unit =
		runBlocking {
			val gate = lifecycle.gate(build = 1)
			session.submit(workspace(LAYOUT_A))
			awaitEvent("building c1")

			session.submit(workspace(LAYOUT_B))
			session.submit(workspace(LAYOUT_C))
			session.submit(workspace(LAYOUT_D))
			gate.complete(Unit)
			awaitEvent("ready c2")

			assertThat(lifecycle.events.filter { it.startsWith("build ") })
				.containsExactly("build c1 for A", "build c2 for D")
				.inOrder()
		}

	@Test
	fun `shutdown during a build closes the new compiler instead of publishing it`(): Unit =
		runBlocking {
			val gate = lifecycle.gate(build = 1)
			session.submit(workspace(LAYOUT_A))
			awaitEvent("building c1")

			session.shutdown()
			gate.complete(Unit)
			awaitEvent("close c1")

			assertThat(session.current).isNull()
			assertThat(lifecycle.events).doesNotContain("ready c1")
		}

	@Test
	fun `shutdown while waiting for the previous close stops the update before preparing`(): Unit =
		runBlocking {
			val gate = CompletableDeferred<Unit>()
			lifecycle.awaitClosedGate = gate
			session.submit(workspace(LAYOUT_A))
			awaitEvent("awaitClosed")

			session.shutdown()
			gate.complete(Unit)
			delay(SETTLE_MILLIS)

			assertThat(lifecycle.events).doesNotContain("prepare")
			assertThat(lifecycle.builds).isEqualTo(0)
		}

	@Test
	fun `shutdown while preparing stops the update before building`(): Unit =
		runBlocking {
			val gate = CompletableDeferred<Unit>()
			lifecycle.prepareGate = gate
			session.submit(workspace(LAYOUT_A))
			awaitEvent("prepare")

			session.shutdown()
			gate.complete(Unit)
			delay(SETTLE_MILLIS)

			assertThat(lifecycle.builds).isEqualTo(0)
		}

	@Test
	fun `a failed build is retried by the next update with the same layout`(): Unit =
		runBlocking {
			lifecycle.failingBuilds += 1
			session.submit(workspace(LAYOUT_A))
			awaitEvent("build failed c1")

			session.submit(workspace(LAYOUT_A))
			awaitEvent("ready c2")

			assertThat(session.current).isEqualTo("c2")
		}

	@Test
	fun `a skipped update is retried by the next update`(): Unit =
		runBlocking {
			lifecycle.awaitClosedResults += false
			session.submit(workspace(LAYOUT_A))
			awaitEvent("awaitClosed")

			session.submit(workspace(LAYOUT_A))
			awaitEvent("ready c1")

			assertThat(lifecycle.builds).isEqualTo(1)
			assertThat(session.current).isEqualTo("c1")
		}

	@Test
	fun `a build that hits the closed registry logs no error`(): Unit =
		runBlocking {
			lifecycle.closedBuilds += 1
			session.submit(workspace(LAYOUT_A))
			awaitEvent("build failed c1")
			delay(SETTLE_MILLIS)

			assertThat(updateFailures()).isEmpty()
			assertThat(logs.filter { it.first == Level.INFO && "Project closed" in it.second }).hasSize(1)
		}

	@Test
	fun `a wrapped closed-registry failure logs no error`(): Unit =
		runBlocking {
			lifecycle.wrappedClosedBuilds += 1
			session.submit(workspace(LAYOUT_A))
			awaitEvent("build failed c1")
			delay(SETTLE_MILLIS)

			assertThat(updateFailures()).isEmpty()
		}

	@Test
	fun `a failed build still logs an error`(): Unit =
		runBlocking {
			lifecycle.failingBuilds += 1
			session.submit(workspace(LAYOUT_A))
			awaitEvent("build failed c1")
			delay(SETTLE_MILLIS)

			assertThat(updateFailures()).hasSize(1)
		}

	private fun updateFailures() = logs.filter { it.first == Level.ERROR && "Kotlin project update failed" in it.second }

	@Test
	fun `an update binds one manager for prepare and build`(): Unit =
		runBlocking {
			session.submit(workspace(LAYOUT_A))
			awaitEvent("ready c1")

			assertThat(lifecycle.obtainedManagers).hasSize(1)
			assertThat(lifecycle.preparedWith.single()).isSameInstanceAs(lifecycle.builtWith.single())
		}

	@Test
	fun `shutdown before binding obtains no manager`(): Unit =
		runBlocking {
			val gate = CompletableDeferred<Unit>()
			lifecycle.awaitClosedGate = gate
			session.submit(workspace(LAYOUT_A))
			awaitEvent("awaitClosed")

			session.shutdown()
			gate.complete(Unit)
			delay(SETTLE_MILLIS)

			assertThat(lifecycle.obtainedManagers).isEmpty()
		}

	private fun workspace(layout: KotlinModuleLayout): Workspace =
		Workspace(
			rootProject = GradleProject(gradleProject(":")),
			subProjects = emptyList(),
			syncIssues = emptyList(),
		).also { lifecycle.layouts[it] = layout }

	private suspend fun awaitEvent(
		event: String,
		times: Int = 1,
	) {
		withTimeout(5_000) {
			while (lifecycle.events.count { it == event } < times) delay(10)
		}
	}

	private class FakeLifecycle : CompilerLifecycle<String> {
		val events: MutableList<String> = CopyOnWriteArrayList()
		val layouts: MutableMap<Workspace, KotlinModuleLayout> = Collections.synchronizedMap(IdentityHashMap())
		val gates = ConcurrentHashMap<Int, CompletableDeferred<Unit>>()
		val failingBuilds: MutableSet<Int> = Collections.synchronizedSet(mutableSetOf())
		val closedBuilds: MutableSet<Int> = Collections.synchronizedSet(mutableSetOf())
		val wrappedClosedBuilds: MutableSet<Int> = Collections.synchronizedSet(mutableSetOf())
		val awaitClosedResults = ConcurrentLinkedDeque<Boolean>()

		@Volatile
		var builds = 0

		fun gate(build: Int) = CompletableDeferred<Unit>().also { gates[build] = it }

		@Volatile
		var awaitClosedGate: CompletableDeferred<Unit>? = null

		@Volatile
		var prepareGate: CompletableDeferred<Unit>? = null

		val obtainedManagers: MutableList<IndexingServiceManager> = CopyOnWriteArrayList()
		val preparedWith: MutableList<IndexingServiceManager> = CopyOnWriteArrayList()
		val builtWith: MutableList<IndexingServiceManager> = CopyOnWriteArrayList()

		override fun currentManager() = IndexingServiceManager().also { obtainedManagers += it }

		override fun prepare(
			workspace: Workspace,
			manager: IndexingServiceManager,
		) {
			preparedWith += manager
			events += "prepare"
			prepareGate?.let { runBlocking { it.await() } }
		}

		override fun layoutOf(workspace: Workspace) = layouts.getValue(workspace)

		override fun awaitClosed(): Boolean {
			events += "awaitClosed"
			awaitClosedGate?.let { runBlocking { it.await() } }
			return awaitClosedResults.pollFirst() ?: true
		}

		override fun build(
			workspace: Workspace,
			layout: KotlinModuleLayout,
			manager: IndexingServiceManager,
		): String {
			builtWith += manager
			val n = ++builds
			events += "building c$n"
			gates[n]?.let { runBlocking { it.await() } }
			if (n in failingBuilds) {
				events += "build failed c$n"
				throw IllegalStateException("build $n failed")
			}
			if (n in closedBuilds) {
				events += "build failed c$n"
				throw IndexRegistryClosedException("registry closed during build $n")
			}
			if (n in wrappedClosedBuilds) {
				events += "build failed c$n"
				throw RuntimeException("build $n failed", IndexRegistryClosedException("registry closed during build $n"))
			}
			events += "build c$n for ${LAYOUT_NAMES.getValue(layout)}"
			return "c$n"
		}

		override fun close(compiler: String) {
			events += "close $compiler"
		}

		override fun onReady(
			workspace: Workspace,
			compiler: String,
		) {
			events += "ready $compiler"
		}
	}

	private companion object {
		/** Long enough for a released worker to reach its next lifecycle call. */
		const val SETTLE_MILLIS = 200L

		fun layout(name: String) = KotlinModuleLayout(listOf(Path.of("/$name.jar")), emptySet(), emptyList())

		val LAYOUT_A = layout("A")
		val LAYOUT_B = layout("B")
		val LAYOUT_C = layout("C")
		val LAYOUT_D = layout("D")
		val LAYOUT_NAMES = mapOf(LAYOUT_A to "A", LAYOUT_B to "B", LAYOUT_C to "C", LAYOUT_D to "D")
	}
}
