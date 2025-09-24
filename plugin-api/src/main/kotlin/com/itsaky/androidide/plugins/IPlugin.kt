
package com.itsaky.androidide.plugins

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

interface IPlugin {
    fun initialize(context: PluginContext): Boolean
    fun activate(): Boolean
    fun deactivate(): Boolean
    fun dispose()
}

@Parcelize
data class PluginMetadata(
    val id: String,
    val name: String,
    val version: String,
    val description: String,
    val author: String,
    val minIdeVersion: String,
    val permissions: List<String> = emptyList(),
    val dependencies: List<String> = emptyList()
) : Parcelable

enum class PluginPermission(val key: String, val description: String) {
    FILESYSTEM_READ("filesystem.read", "Read files from project directory"),
    FILESYSTEM_WRITE("filesystem.write", "Write files to project directory"),
    NETWORK_ACCESS("network.access", "Access network resources"),
    SYSTEM_COMMANDS("system.commands", "Execute system commands"),
    IDE_SETTINGS("ide.settings", "Modify IDE settings"),
    PROJECT_STRUCTURE("project.structure", "Modify project structure")
}

data class PluginInfo(
    val metadata: PluginMetadata,
    val isEnabled: Boolean,
    val isLoaded: Boolean,
    val loadError: String? = null
)