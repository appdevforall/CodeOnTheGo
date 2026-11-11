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
 * and every plugin hears an open before the saves that follow it and the close after them.
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

	/** The editor opened [projectDir]. Repeats for the project already open are ignored. */
	fun projectOpened(projectDir: File) {
		val dir = normalize(projectDir)
		val previous: File?
		synchronized(lock) {
			if (dir == openProjectDir) return
			previous = openProjectDir
			openProjectDir = dir
		}
		// Switching projects without a close report still tells listeners the old one went away.
		previous?.let { closed -> dispatch(projectListeners, "onProjectClosed") { it.onProjectClosed(closed) } }
		dispatch(projectListeners, "onProjectOpened") { it.onProjectOpened(dir) }
	}

	/** The editor closed its project. Ignored when no open was reported. */
	fun projectClosed() {
		val dir =
			synchronized(lock) {
				openProjectDir.also { openProjectDir = null }
			} ?: return
		dispatch(projectListeners, "onProjectClosed") { it.onProjectClosed(dir) }
	}

	/** The editor wrote [file] to disk. */
	fun fileSaved(file: File) {
		val saved = file.absoluteFile
		dispatch(saveListeners, "onFileSaved") { it.onFileSaved(saved) }
	}

	fun addProjectLifecycleListener(
		pluginId: String,
		listener: ProjectLifecycleListener,
	) = add(projectListeners, pluginId, listener)

	fun removeProjectLifecycleListener(listener: ProjectLifecycleListener) = remove(projectListeners, listener)

	fun addFileSaveListener(
		pluginId: String,
		listener: FileSaveListener,
	) = add(saveListeners, pluginId, listener)

	fun removeFileSaveListener(listener: FileSaveListener) = remove(saveListeners, listener)

	/** Drops every listener [pluginId] registered. */
	fun removeAll(pluginId: String) {
		projectListeners.removeAll { it.pluginId == pluginId }
		saveListeners.removeAll { it.pluginId == pluginId }
	}

	private fun <L> add(
		registrations: CopyOnWriteArrayList<Registration<L>>,
		pluginId: String,
		listener: L,
	) {
		synchronized(registrations) {
			if (registrations.none { it.listener === listener }) {
				registrations.add(Registration(pluginId, listener))
			}
		}
	}

	private fun <L> remove(
		registrations: CopyOnWriteArrayList<Registration<L>>,
		listener: L,
	) {
		registrations.removeAll { it.listener === listener }
	}

	// The listener list is read when the event runs, not when it is reported, so a plugin that
	// unloads in between is not called.
	private fun <L> dispatch(
		registrations: CopyOnWriteArrayList<Registration<L>>,
		event: String,
		call: (L) -> Unit,
	) {
		executor.execute {
			registrations.forEach { registration ->
				try {
					call(registration.listener)
				} catch (e: Throwable) {
					// A plugin's bug must not stop the others hearing the event, nor kill the thread.
					log.warn("Plugin {} threw from {}", registration.pluginId, event, e)
				}
			}
		}
	}

	private fun normalize(dir: File): File = runCatching { dir.canonicalFile }.getOrDefault(dir.absoluteFile)

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
