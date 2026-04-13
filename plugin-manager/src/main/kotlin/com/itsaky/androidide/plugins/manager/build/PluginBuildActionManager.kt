package com.itsaky.androidide.plugins.manager.build

import com.itsaky.androidide.plugins.extensions.BuildActionCategory
import com.itsaky.androidide.plugins.extensions.BuildActionExtension
import com.itsaky.androidide.plugins.extensions.CommandResult
import com.itsaky.androidide.plugins.extensions.CommandSpec
import com.itsaky.androidide.plugins.extensions.PluginBuildAction
import com.itsaky.androidide.plugins.extensions.ToolbarActionIds
import com.itsaky.androidide.plugins.manager.loaders.ManifestBuildAction
import com.itsaky.androidide.plugins.manager.loaders.PluginManifest
import com.itsaky.androidide.plugins.services.CommandExecution
import com.itsaky.androidide.plugins.services.IdeCommandService
import android.util.Log
import java.util.concurrent.ConcurrentHashMap

class PluginBuildActionManager private constructor() {

    private val pluginExtensions = ConcurrentHashMap<String, BuildActionExtension>()
    private val manifestActions = ConcurrentHashMap<String, List<PluginBuildAction>>()
    private val pluginNames = ConcurrentHashMap<String, String>()
    private val activeExecutions = ConcurrentHashMap<String, CommandExecution>()

    companion object {
        private const val TAG = "PluginBuildActionManager"

        @Volatile
        private var INSTANCE: PluginBuildActionManager? = null

        fun getInstance(): PluginBuildActionManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: PluginBuildActionManager().also { INSTANCE = it }
            }
        }
    }

    fun registerPlugin(pluginId: String, pluginName: String, extension: BuildActionExtension) {
        pluginExtensions[pluginId] = extension
        pluginNames[pluginId] = pluginName
    }

    fun registerManifestActions(pluginId: String, pluginName: String, manifest: PluginManifest) {
        if (manifest.buildActions.isEmpty()) return

        pluginNames[pluginId] = pluginName
        manifestActions[pluginId] = manifest.buildActions.map { it.toPluginBuildAction() }
    }

    fun getAllBuildActions(): List<RegisteredBuildAction> {
        val actions = mutableListOf<RegisteredBuildAction>()

        for ((pluginId, extension) in pluginExtensions) {
            val name = pluginNames[pluginId] ?: pluginId
            runCatching {
                extension.getBuildActions().forEach { action ->
                    actions.add(RegisteredBuildAction(pluginId, name, action))
                }
            }.onFailure { e ->
                Log.w(TAG, "Failed to get build actions from plugin $pluginId", e)
            }
        }

        for ((pluginId, pluginActions) in manifestActions) {
            if (pluginExtensions.containsKey(pluginId)) continue
            val name = pluginNames[pluginId] ?: pluginId
            pluginActions.forEach { action ->
                actions.add(RegisteredBuildAction(pluginId, name, action))
            }
        }

        return actions
    }

    fun getHiddenActionIds(): Set<String> {
        val hidden = mutableSetOf<String>()

        for ((_, extension) in pluginExtensions) {
            runCatching {
                val requested = extension.toolbarActionsToHide()
                hidden.addAll(requested.intersect(ToolbarActionIds.ALL))
            }.onFailure { e ->
                Log.w(TAG, "Failed to get hidden action ids from plugin", e)
            }
        }

        return hidden
    }

    fun executeAction(
        pluginId: String,
        actionId: String,
        commandService: IdeCommandService
    ): CommandExecution? {
        val action = findAction(pluginId, actionId) ?: return null
        val extension = pluginExtensions[pluginId]

        extension?.onActionStarted(actionId)

        return runCatching {
            commandService.executeCommand(action.command, action.timeoutMs)
        }.onSuccess { execution ->
            activeExecutions[executionKey(pluginId, actionId)] = execution
        }.onFailure { e ->
            extension?.onActionCompleted(actionId, CommandResult.Failure(-1, "", "", e.message, 0))
        }.getOrThrow()
    }

    fun notifyActionCompleted(pluginId: String, actionId: String, result: CommandResult) {
        activeExecutions.remove(executionKey(pluginId, actionId))
        pluginExtensions[pluginId]?.onActionCompleted(actionId, result)
    }

    fun isActionRunning(pluginId: String, actionId: String): Boolean {
        return activeExecutions.containsKey(executionKey(pluginId, actionId))
    }

    fun hasActiveExecutions(): Boolean = activeExecutions.isNotEmpty()

    fun cancelAction(pluginId: String, actionId: String): Boolean {
        val key = executionKey(pluginId, actionId)
        return activeExecutions.remove(key)?.let {
            it.cancel()
            true
        } ?: false
    }

    fun cleanupPlugin(pluginId: String) {
        activeExecutions.entries.removeAll { (key, execution) ->
            if (key.startsWith("$pluginId:")) {
                execution.cancel()
                true
            } else false
        }
        pluginExtensions.remove(pluginId)
        manifestActions.remove(pluginId)
        pluginNames.remove(pluginId)
    }

    private fun executionKey(pluginId: String, actionId: String) = "$pluginId:$actionId"

    private fun findAction(pluginId: String, actionId: String): PluginBuildAction? {
        val fromExtension = pluginExtensions[pluginId]?.let { ext ->
            runCatching {
                ext.getBuildActions().find { it.id == actionId }
            }.onFailure { e ->
                Log.w(TAG, "Failed to find action $actionId in plugin $pluginId", e)
            }.getOrNull()
        }
        return fromExtension ?: manifestActions[pluginId]?.find { it.id == actionId }
    }
}

data class RegisteredBuildAction(
    val pluginId: String,
    val pluginName: String,
    val action: PluginBuildAction
)

private fun ManifestBuildAction.toPluginBuildAction(): PluginBuildAction {
    val spec = when {
        gradleTask != null -> CommandSpec.GradleTask(gradleTask, arguments)
        command != null -> CommandSpec.ShellCommand(
            executable = command,
            arguments = arguments,
            workingDirectory = workingDirectory,
            environment = environment
        )
        else -> throw IllegalArgumentException("ManifestBuildAction must have either 'command' or 'gradle_task'")
    }

    val cat = try {
        BuildActionCategory.valueOf(category.uppercase())
    } catch (_: IllegalArgumentException) {
        BuildActionCategory.CUSTOM
    }

    return PluginBuildAction(
        id = id,
        name = name,
        description = description,
        category = cat,
        command = spec,
        timeoutMs = timeoutMs
    )
}
