/*
 *  This file is part of AndroidIDE.
 *
 *  AndroidIDE is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  AndroidIDE is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *   along with AndroidIDE.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.itsaky.androidide.plugins

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

interface IPlugin {
    val metadata: PluginMetadata
    
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