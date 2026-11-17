package com.itsaky.androidide.plugins.manager.services

import android.os.Looper
import com.itsaky.androidide.plugins.PluginPermission
import com.itsaky.androidide.plugins.services.DebuggerEndpoint
import com.itsaky.androidide.plugins.services.DebuggerEndpointService
import org.slf4j.LoggerFactory
import java.io.File
import java.util.Collections
import java.util.EnumSet
import java.util.concurrent.CancellationException

/**
 * Where the host keeps what apps reported: the app module's `debugger_endpoint_table`. The app
 * installs one with [com.itsaky.androidide.plugins.manager.core.PluginManager.installDebuggerEndpointSource];
 * this module cannot reach the table itself.
 */
fun interface DebuggerEndpointSource {
	/** Returns the endpoints stored for the project at [projectLocation], as it appears in `recent_project_table.location`. */
	fun endpointsFor(projectLocation: String): List<DebuggerEndpoint>
}

/**
 * The [DebuggerEndpointService] the plugin manager registers for each plugin, with that plugin's permissions.
 *
 * Plugins run in Code on the Go's process, and their class loader's parent is the host's, so the
 * permission check declares intent: it cannot stop a plugin that reaches host objects by reflection.
 * What it does close is every path short of that. The source is held privately by the plugin manager,
 * which installs it once, and this service keeps its own unmodifiable copy of the permissions, so
 * changing the set it was built from grants nothing.
 */
internal class DebuggerEndpointServiceImpl(
	private val pluginId: String,
	permissions: Set<PluginPermission>,
	private val projectRootProvider: () -> File?,
	/** Null until the app installs one. */
	private val sourceProvider: () -> DebuggerEndpointSource?,
	private val isMainThread: () -> Boolean = { Looper.myLooper() == Looper.getMainLooper() },
) : DebuggerEndpointService {
	private val permissions: Set<PluginPermission> =
		Collections.unmodifiableSet(EnumSet.noneOf(PluginPermission::class.java).apply { addAll(permissions) })

	override fun getEndpoints(): List<DebuggerEndpoint> {
		if (PluginPermission.DEBUGGER_ENDPOINTS !in permissions) {
			throw SecurityException(
				"Plugin $pluginId does not have required permissions: ${PluginPermission.DEBUGGER_ENDPOINTS.name}",
			)
		}

		check(!isMainThread()) { "DebuggerEndpointService.getEndpoints() reads a database; call it off the main thread" }

		val root = projectRootProvider() ?: return emptyList()
		val source = sourceProvider() ?: return emptyList()
		return try {
			source.endpointsFor(root.absolutePath)
		} catch (e: InterruptedException) {
			Thread.currentThread().interrupt()
			throw e
		} catch (e: CancellationException) {
			throw e
		} catch (e: Exception) {
			log.warn("getEndpoints failed for plugin {}", pluginId, e)
			throw IllegalStateException("Could not read the debugger endpoints: ${e.message}", e)
		}
	}

	private companion object {
		private val log = LoggerFactory.getLogger(DebuggerEndpointServiceImpl::class.java)
	}
}
