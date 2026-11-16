package com.itsaky.androidide.plugins.manager.services

import android.os.Looper
import com.itsaky.androidide.plugins.PluginPermission
import com.itsaky.androidide.plugins.services.DebuggerEndpoint
import com.itsaky.androidide.plugins.services.DebuggerEndpointService
import org.slf4j.LoggerFactory
import java.io.File
import java.util.concurrent.CancellationException

/**
 * Where the host keeps what apps reported: the app module's `debugger_endpoint_table`. The app
 * installs an implementation with [DebuggerEndpointSources.install]; this module cannot reach the
 * table itself.
 */
fun interface DebuggerEndpointSource {
	/** Returns the endpoints stored for the project at [projectLocation], as it appears in `recent_project_table.location`. */
	fun endpointsFor(projectLocation: String): List<DebuggerEndpoint>
}

object DebuggerEndpointSources {
	@Volatile
	var current: DebuggerEndpointSource = DebuggerEndpointSource { emptyList() }
		private set

	/** Installs the app's source, or restores the empty default when [source] is null. */
	fun install(source: DebuggerEndpointSource?) {
		current = source ?: DebuggerEndpointSource { emptyList() }
	}
}

class DebuggerEndpointServiceImpl(
	private val pluginId: String,
	private val permissions: Set<PluginPermission>,
	private val projectRootProvider: () -> File?,
	private val sourceProvider: () -> DebuggerEndpointSource = { DebuggerEndpointSources.current },
	private val isMainThread: () -> Boolean = { Looper.myLooper() == Looper.getMainLooper() },
) : DebuggerEndpointService {
	override fun getEndpoints(): List<DebuggerEndpoint> {
		if (PluginPermission.DEBUGGER_ENDPOINTS !in permissions) {
			throw SecurityException(
				"Plugin $pluginId does not have required permissions: ${PluginPermission.DEBUGGER_ENDPOINTS.name}",
			)
		}

		check(!isMainThread()) { "DebuggerEndpointService.getEndpoints() reads a database; call it off the main thread" }

		val root = projectRootProvider() ?: return emptyList()
		return try {
			sourceProvider().endpointsFor(root.absolutePath)
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
