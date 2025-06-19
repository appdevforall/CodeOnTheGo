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

package com.itsaky.androidide.plugins.extensions

import com.itsaky.androidide.plugins.IPlugin
import java.io.File

// Simple interface to avoid Android dependency
interface Project {
    val name: String
    val rootDir: File
    val path: String
}

interface ProjectExtension : IPlugin {
    fun canHandleProject(project: Project): Boolean
    fun getProjectTemplates(): List<ProjectTemplate>
    fun createProject(template: ProjectTemplate, config: ProjectConfig): Result<Project>
    fun getBuildActions(project: Project): List<BuildAction>
    fun onProjectOpen(project: Project) {}
    fun onProjectClose(project: Project) {}
}

data class ProjectTemplate(
    val id: String,
    val name: String,
    val description: String,
    val category: String,
    val icon: String?,
    val parameters: List<TemplateParameter>
)

data class TemplateParameter(
    val name: String,
    val displayName: String,
    val description: String,
    val type: ParameterType,
    val defaultValue: Any?,
    val required: Boolean = true,
    val choices: List<String>? = null
)

enum class ParameterType {
    STRING,
    INTEGER,
    BOOLEAN,
    CHOICE,
    FILE,
    DIRECTORY
}

data class ProjectConfig(
    val name: String,
    val location: File,
    val packageName: String?,
    val parameters: Map<String, Any>
)

data class BuildAction(
    val id: String,
    val name: String,
    val description: String,
    val icon: String?,
    val isDefault: Boolean = false,
    val execute: (Project, Map<String, Any>) -> BuildResult
)

data class BuildResult(
    val success: Boolean,
    val message: String?,
    val output: String?,
    val errors: List<String> = emptyList()
)