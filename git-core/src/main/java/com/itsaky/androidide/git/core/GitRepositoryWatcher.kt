package com.itsaky.androidide.git.core

import android.os.FileObserver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.io.Closeable
import java.io.File
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Watches a Git repository directory for file system changes and notifies listeners after
 * events settle.
 *
 * External Git operations (e.g., from the Terminal) directly alter files in the `.git` directory
 * (`index`, `HEAD`, `refs`, etc.). This watcher observes those mutations via [FileObserver] and
 * collapses bursts of events using [debounceDelay].
 *
 * @param gitDir the `.git` directory to watch.
 * @param scope coroutine scope where the debouncing and change callback run.
 * @param debounceDelay quiet period required before invoking [onGitStateChanged]. Defaults to 1000ms.
 * @param context coroutine context where the change callback executes. Defaults to [Dispatchers.Main.immediate].
 * @param onGitStateChanged callback invoked when changes settle.
 */
class GitRepositoryWatcher(
	val gitDir: File,
	private val scope: CoroutineScope,
	val debounceDelay: Duration = DEFAULT_DEBOUNCE_DELAY,
	private val context: CoroutineContext = Dispatchers.Main.immediate,
	private val onGitStateChanged: suspend () -> Unit,
) : Closeable {
	companion object {
		val DEFAULT_DEBOUNCE_DELAY: Duration = 1000.milliseconds
		private val log = LoggerFactory.getLogger(GitRepositoryWatcher::class.java)

		private const val EVENT_MASK =
			FileObserver.CREATE or
				FileObserver.DELETE or
				FileObserver.DELETE_SELF or
				FileObserver.MODIFY or
				FileObserver.MOVED_TO or
				FileObserver.MOVED_FROM or
				FileObserver.CLOSE_WRITE
	}

	private val observers = mutableListOf<FileObserver>()
	private val observersLock = Any()
	private val changeEvents =
		MutableSharedFlow<Unit>(
			extraBufferCapacity = 1,
			onBufferOverflow = BufferOverflow.DROP_OLDEST,
		)

	private var pipelineJob: Job? = null

	@Volatile
	var isRunning: Boolean = false
		private set

	@Volatile
	var isPaused: Boolean = false

	/**
	 * Starts watching [gitDir] and begins the debounced event pipeline.
	 */
	fun start() {
		if (isRunning) return
		isRunning = true
		startPipeline()
		registerObservers()
	}

	/**
	 * Stops watching and cancels the active debounce pipeline.
	 */
	fun stop() {
		isRunning = false
		pipelineJob?.cancel()
		pipelineJob = null
		synchronized(observersLock) {
			observers.forEach { observer ->
				try {
					observer.stopWatching()
				} catch (e: Exception) {
					log.debug("Error stopping FileObserver", e)
				}
			}
			observers.clear()
		}
	}

	override fun close() = stop()

	/**
	 * Pauses event processing. Changes occurring while paused are dropped.
	 */
	fun pause() {
		isPaused = true
	}

	/**
	 * Resumes event processing.
	 */
	fun resume() {
		isPaused = false
	}

	/**
	 * Manually signals a change event, resetting the debounce timer.
	 */
	fun notifyChanged() {
		if (!isRunning || isPaused) return
		changeEvents.tryEmit(Unit)
	}

	/**
	 * Internal event handler triggered by [FileObserver] or test mocks.
	 */
	internal fun handleFileEvent(
		event: Int,
		path: String?,
	) {
		if (path == null) return
		if (!isRunning || isPaused) return
		if (event and EVENT_MASK != 0) {
			notifyChanged()
		}
	}

	@OptIn(FlowPreview::class)
	private fun startPipeline() {
		pipelineJob?.cancel()
		pipelineJob =
			scope.launch(context) {
				changeEvents
					.asSharedFlow()
					.debounce(debounceDelay)
					.collect {
						if (isRunning && !isPaused) {
							try {
								onGitStateChanged()
							} catch (e: CancellationException) {
								throw e
							} catch (e: Throwable) {
								log.error("Failed to execute onGitStateChanged callback", e)
							}
						}
					}
			}
	}

	private fun registerObservers() {
		synchronized(observersLock) {
			observers.forEach { it.stopWatching() }
			observers.clear()

			if (!gitDir.isDirectory) return

			val dirsToWatch = mutableListOf(gitDir)
			val refsDir = File(gitDir, "refs")
			if (refsDir.isDirectory) {
				dirsToWatch.add(refsDir)
				val headsDir = File(refsDir, "heads")
				if (headsDir.isDirectory) {
					dirsToWatch.add(headsDir)
				}
			}

			for (dir in dirsToWatch) {
				val observer = newObserver(dir)
				observers.add(observer)
				try {
					observer.startWatching()
				} catch (e: Exception) {
					log.warn("Failed to watch git directory: {}", dir.absolutePath, e)
				}
			}
		}
	}

	@Suppress("DEPRECATION")
	private fun newObserver(dir: File): FileObserver =
		object : FileObserver(dir.absolutePath, EVENT_MASK) {
			override fun onEvent(
				event: Int,
				path: String?,
			) {
				handleFileEvent(event, path)
			}
		}
}
