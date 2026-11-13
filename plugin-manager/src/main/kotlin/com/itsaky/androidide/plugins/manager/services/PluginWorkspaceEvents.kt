package com.itsaky.androidide.plugins.manager.services

import com.itsaky.androidide.plugins.services.FileSaveListener
import com.itsaky.androidide.plugins.services.ProjectLifecycleListener
import org.slf4j.LoggerFactory
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/**
 * Tells plugins when the editor opens or closes a project and when it saves a file. The app
 * reports each moment here; [IdeProjectServiceImpl] and [IdeEditorServiceImpl] register a plugin's
 * listeners, keyed by plugin id so [removeAll] drops them when the plugin unloads.
 *
 * Listeners run in order on [executor], one thread by default: the editor never waits on a plugin,
 * and every plugin hears an open before the saves that follow it and the close after them. Each
 * event is queued, and every registration added or removed, under [lock], so the queue order is the
 * order the state changed in and an event's recipients are a consistent snapshot.
 */
class PluginWorkspaceEvents internal constructor(
	private val executor: Executor,
) {
	private class Registration<L>(
		val pluginId: String,
		val listener: L,
	)

	private val projectListeners = CopyOnWriteArrayList<Registration<ProjectLifecycleListener>>()
	private val saveListeners = CopyOnWriteArrayList<Registration<FileSaveListener>>()

	private val lock = Any()

	// The project the listeners last heard open, so a re-sync of it is not reported as another open.
	private var openProjectDir: File? = null

	/**
	 * The editor opened [projectDir]. Repeats for the project already open are ignored. Returns the
	 * directory as listeners hear it, canonical; report the close with that.
	 */
	fun projectOpened(projectDir: File): File {
		val dir = normalize(projectDir)
		synchronized(lock) {
			if (dir == openProjectDir) return dir
			// Switching projects without a close report still tells listeners the old one went away.
			openProjectDir?.let { closed -> dispatch(projectListeners, "onProjectClosed") { it.onProjectClosed(closed) } }
			openProjectDir = dir
			dispatch(projectListeners, "onProjectOpened") { it.onProjectOpened(dir) }
		}
		return dir
	}

	/**
	 * The editor closed [projectDir], the directory [projectOpened] returned. It is compared as given,
	 * without resolving it on disk, because the close is reported from the main thread. Ignored
	 * unless it is the project last reported open, so an editor tearing down a project that is no
	 * longer the open one cannot close the open one.
	 */
	fun projectClosed(projectDir: File) {
		synchronized(lock) {
			if (projectDir != openProjectDir) return
			openProjectDir = null
			dispatch(projectListeners, "onProjectClosed") { it.onProjectClosed(projectDir) }
		}
	}

	/** The editor wrote [file] to disk. */
	fun fileSaved(file: File) {
		// Canonical like the project directory, so a listener can test saved.startsWith(projectDir).
		// Resolved on the listeners' thread: the caller is often the main thread.
		val saved = lazy { normalize(file) }
		synchronized(lock) {
			dispatch(saveListeners, "onFileSaved") { it.onFileSaved(saved.value) }
		}
	}

	/**
	 * Registers [listener] for [pluginId]. A listener added while a project is open hears that open
	 * first: plugins load in the background, often after the editor reopened the last project.
	 */
	internal fun addProjectLifecycleListener(
		pluginId: String,
		listener: ProjectLifecycleListener,
	) {
		synchronized(lock) {
			val registration = add(projectListeners, pluginId, listener) ?: return
			val open = openProjectDir ?: return
			dispatchTo(projectListeners, registration, "onProjectOpened") { it.onProjectOpened(open) }
		}
	}

	internal fun removeProjectLifecycleListener(
		pluginId: String,
		listener: ProjectLifecycleListener,
	) = remove(projectListeners, pluginId, listener)

	internal fun addFileSaveListener(
		pluginId: String,
		listener: FileSaveListener,
	) {
		synchronized(lock) { add(saveListeners, pluginId, listener) }
	}

	internal fun removeFileSaveListener(
		pluginId: String,
		listener: FileSaveListener,
	) = remove(saveListeners, pluginId, listener)

	/** Drops every listener [pluginId] registered. */
	internal fun removeAll(pluginId: String) {
		synchronized(lock) {
			// removeIf, not Kotlin's removeAll {}: on a RandomAccess list that edits the list index by
			// index, and invoke() reads it without the lock while it is half done.
			projectListeners.removeIf { it.pluginId == pluginId }
			saveListeners.removeIf { it.pluginId == pluginId }
		}
	}

	/** The new registration, or null when [pluginId] already registered [listener]. */
	private fun <L> add(
		registrations: CopyOnWriteArrayList<Registration<L>>,
		pluginId: String,
		listener: L,
	): Registration<L>? {
		// Per plugin: two plugins sharing one listener object each keep their own registration.
		if (registrations.any { it.pluginId == pluginId && it.listener === listener }) return null
		return Registration(pluginId, listener).also(registrations::add)
	}

	// Scoped to the plugin, so one plugin cannot unregister another's listener.
	private fun <L> remove(
		registrations: CopyOnWriteArrayList<Registration<L>>,
		pluginId: String,
		listener: L,
	) {
		synchronized(lock) { registrations.removeIf { it.pluginId == pluginId && it.listener === listener } }
	}

	private fun <L> dispatch(
		registrations: CopyOnWriteArrayList<Registration<L>>,
		event: String,
		call: (L) -> Unit,
	) {
		if (registrations.isEmpty()) return
		// The listeners registered now: one added later hears the open project through its replay
		// instead, so it never hears an open twice.
		val targets = ArrayList(registrations)
		executor.execute {
			targets.forEach { invoke(registrations, it, event, call) }
		}
	}

	private fun <L> dispatchTo(
		registrations: CopyOnWriteArrayList<Registration<L>>,
		registration: Registration<L>,
		event: String,
		call: (L) -> Unit,
	) {
		executor.execute { invoke(registrations, registration, event, call) }
	}

	// Checked when the call runs: a plugin removed after the event was queued, or while an earlier
	// listener of the same event was running, is not called.
	private fun <L> invoke(
		registrations: CopyOnWriteArrayList<Registration<L>>,
		registration: Registration<L>,
		event: String,
		call: (L) -> Unit,
	) {
		if (registration !in registrations) return
		try {
			call(registration.listener)
		} catch (e: Throwable) {
			// A plugin's bug must not stop the others hearing the event, nor kill the thread.
			log.warn("Plugin {} threw from {}", registration.pluginId, event, e)
		}
	}

	private fun normalize(file: File): File = runCatching { file.canonicalFile }.getOrDefault(file.absoluteFile)

	companion object {
		private val log = LoggerFactory.getLogger(PluginWorkspaceEvents::class.java)

		private val shared: PluginWorkspaceEvents by lazy {
			PluginWorkspaceEvents(
				Executors.newSingleThreadExecutor { runnable ->
					Thread(runnable, "plugin-workspace-events").apply { isDaemon = true }
				},
			)
		}

		@JvmStatic
		fun getInstance(): PluginWorkspaceEvents = shared
	}
}
