package com.itsaky.androidide.lsp.kotlin

import com.itsaky.androidide.lsp.kotlin.compiler.KotlinModuleLayout
import com.itsaky.androidide.projects.api.Workspace
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import org.appdevforall.codeonthego.indexing.service.IndexRegistryClosedException
import org.appdevforall.codeonthego.indexing.service.IndexingServiceManager
import org.slf4j.LoggerFactory

/** The steps [CompilerSession] runs for each project update, on its own thread. */
internal interface CompilerLifecycle<C : Any> {
	/** Returns the indexing manager of the current project session. */
	fun currentManager(): IndexingServiceManager

	/** Runs on every project update, before its layout is compared. */
	fun prepare(
		workspace: Workspace,
		manager: IndexingServiceManager,
	)

	/** Returns the module layout of [workspace]. */
	fun layoutOf(workspace: Workspace): KotlinModuleLayout

	/** Waits for the previous compiler to finish closing; returns false if it is still closing. */
	fun awaitClosed(): Boolean

	/** Builds a compiler for [layout] against [manager]'s indexes. */
	fun build(
		workspace: Workspace,
		layout: KotlinModuleLayout,
		manager: IndexingServiceManager,
	): C

	/** Closes [compiler] without blocking the caller. */
	fun close(compiler: C)

	/** Runs after every project update that left [compiler] serving [workspace]. */
	fun onReady(
		workspace: Workspace,
		compiler: C,
	)
}

/**
 * Keeps one compiler serving the current project, rebuilding it only when the module layout changes.
 *
 * Project updates (a sync, or `generateSources` after a resource edit) are queued and handled one at
 * a time on a single thread; updates that arrive while one is handled collapse into the latest. A
 * rebuild closes the old compiler before building the new one, so [current] is null meanwhile.
 */
internal class CompilerSession<C : Any>(
	scope: CoroutineScope,
	private val lifecycle: CompilerLifecycle<C>,
	dispatcher: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(1),
) {
	private companion object {
		private val logger = LoggerFactory.getLogger(CompilerSession::class.java)
	}

	private val updates = Channel<Workspace>(Channel.CONFLATED)

	/** Guards only the swap of [current] against [isShutDown]; never held while building or closing. */
	private val lock = Any()
	private var isShutDown = false

	/** Read and written only by the worker. */
	private var currentLayout: KotlinModuleLayout? = null

	/** The compiler serving the project, or null before the first build and during a rebuild. */
	@Volatile
	var current: C? = null
		private set

	private val worker =
		scope.launch(dispatcher) {
			for (workspace in updates) {
				try {
					update(workspace)
				} catch (e: CancellationException) {
					throw e
				} catch (e: Throwable) {
					if (e.isProjectClosed()) {
						logger.info("Project closed during a Kotlin update; dropping it")
					} else {
						logger.error("Kotlin project update failed", e)
					}
				}
			}
		}

	/** Queues a project update; never blocks. */
	fun submit(workspace: Workspace) {
		updates.trySend(workspace)
	}

	/** Stops handling updates and closes the current compiler, and any compiler a running build returns. */
	fun shutdown() {
		val closing =
			synchronized(lock) {
				isShutDown = true
				current.also { current = null }
			}
		updates.close()
		worker.cancel()
		closing?.let(lifecycle::close)
	}

	private fun update(workspace: Workspace) {
		/*
		 * prepare opens the source indexes, which a previous project's compiler may still hold open.
		 * The update cannot be cancelled while it blocks, so shutdown is re-checked after each wait:
		 * past it, prepare would revive the closed project's indexing state.
		 *
		 * The manager is taken once, under the same lock as that check: the editor stops this session
		 * before it destroys the project, so an update past the check only ever sees the closing
		 * session's manager, never the next one's.
		 */
		if (!awaitClosed()) return
		val manager = synchronized(lock) { if (isShutDown) null else lifecycle.currentManager() } ?: return
		lifecycle.prepare(workspace, manager)
		val layout = lifecycle.layoutOf(workspace)
		if (isStopped()) return

		val existing = current
		if (existing != null && layout == currentLayout) {
			logger.info("Kotlin module layout unchanged; keeping the analysis session")
			if (!isStopped()) lifecycle.onReady(workspace, existing)
			return
		}

		detachCurrent()
		if (!awaitClosed() || isStopped()) return

		val built = lifecycle.build(workspace, layout, manager)
		val published =
			synchronized(lock) {
				if (!isShutDown) current = built
				!isShutDown
			}
		if (!published) {
			lifecycle.close(built)
			return
		}

		currentLayout = layout
		lifecycle.onReady(workspace, built)
	}

	private fun isStopped() = synchronized(lock) { isShutDown }

	private fun Throwable.isProjectClosed() = generateSequence(this) { it.cause }.any { it is IndexRegistryClosedException }

	private fun awaitClosed(): Boolean {
		if (lifecycle.awaitClosed()) return true
		logger.error("Skipping Kotlin setup: the previous compiler is still closing")
		return false
	}

	private fun detachCurrent() {
		currentLayout = null
		val old = synchronized(lock) { current.also { current = null } } ?: return
		lifecycle.close(old)
	}
}
