package com.itsaky.androidide.lsp.kotlin

import com.itsaky.androidide.lsp.kotlin.compiler.KotlinModuleLayout
import com.itsaky.androidide.projects.api.Workspace
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory

/** The steps [CompilerSession] runs for each project update, on its own thread. */
internal interface CompilerLifecycle<C : Any> {
	/** Runs on every project update, before its layout is compared. */
	fun prepare(workspace: Workspace)

	/** Returns the module layout of [workspace]. */
	fun layoutOf(workspace: Workspace): KotlinModuleLayout

	/** Waits for the previous compiler to finish closing; returns false if it is still closing. */
	fun awaitClosed(): Boolean

	/** Builds a compiler for [layout]. */
	fun build(
		workspace: Workspace,
		layout: KotlinModuleLayout,
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

	// Guards only the swap of [current] against [isShutDown]; never held while building or closing.
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
					logger.error("Kotlin project update failed", e)
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
		// prepare opens the source indexes, which a previous project's compiler may still hold open.
		if (!awaitClosed()) return
		lifecycle.prepare(workspace)
		val layout = lifecycle.layoutOf(workspace)

		val existing = current
		if (existing != null && layout == currentLayout) {
			logger.info("Kotlin module layout unchanged; keeping the analysis session")
			if (synchronized(lock) { !isShutDown }) lifecycle.onReady(workspace, existing)
			return
		}

		detachCurrent()
		if (!awaitClosed()) return

		val built = lifecycle.build(workspace, layout)
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
