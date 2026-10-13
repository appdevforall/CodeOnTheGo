package org.appdevforall.codeonthego.plugins.manager.services

import org.appdevforall.codeonthego.plugins.PluginPermission
import org.appdevforall.codeonthego.plugins.services.GradleTaskResult
import org.appdevforall.codeonthego.plugins.services.IdeBuildService
import java.util.concurrent.CompletableFuture

/**
 * One plugin's view of the shared [IdeBuildServiceImpl]. Gradle arguments (`--init-script` runs
 * any code in the IDE's daemon) and cancelling someone else's build need [PluginPermission.SYSTEM_COMMANDS],
 * as [CommandSpec.GradleTask][org.appdevforall.codeonthego.plugins.extensions.CommandSpec.GradleTask] does.
 */
class PluginBuildService(
	private val pluginId: String,
	private val permissions: Set<PluginPermission>,
	private val delegate: IdeBuildService = IdeBuildServiceImpl.getInstance(),
) : IdeBuildService by delegate {
	override fun executeTasks(
		tasks: List<String>,
		arguments: List<String>,
	): CompletableFuture<GradleTaskResult> {
		if (arguments.isNotEmpty()) requireSystemCommands("pass Gradle arguments")
		return delegate.executeTasks(tasks, arguments)
	}

	override fun cancelBuild(): CompletableFuture<Boolean> {
		requireSystemCommands("cancel a build")
		return delegate.cancelBuild()
	}

	private fun requireSystemCommands(action: String) {
		if (PluginPermission.SYSTEM_COMMANDS !in permissions) {
			throw SecurityException("Plugin $pluginId needs the SYSTEM_COMMANDS permission to $action")
		}
	}
}
