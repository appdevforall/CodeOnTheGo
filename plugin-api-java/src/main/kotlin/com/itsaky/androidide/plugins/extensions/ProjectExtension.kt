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

interface ProjectExtension : IPlugin {
    fun canHandle(project: IProject): Boolean
    fun getProjectTemplates(): List<ProjectTemplate>
    fun createProject(template: ProjectTemplate, config: ProjectConfig): Result<IProject>
    fun getBuildActions(): List<BuildAction>
}

interface IProject {
    val name: String
    val rootDir: File
    val type: ProjectType
    fun getModules(): List<IModule>
    fun getBuildFiles(): List<File>
}

interface IModule {
    val name: String
    val type: ModuleType
    val projectDir: File
    fun getSourceSets(): List<SourceSet>
}

enum class ProjectType {
    ANDROID_APP, ANDROID_LIBRARY, JAVA_LIBRARY, KOTLIN_LIBRARY, GRADLE_PLUGIN
}

enum class ModuleType {
    ANDROID_APP, ANDROID_LIBRARY, JAVA_LIBRARY, KOTLIN_LIBRARY
}

data class SourceSet(
    val name: String,
    val srcDirs: List<File>,
    val resourceDirs: List<File>
)

data class ProjectTemplate(
    val id: String,
    val name: String,
    val description: String,
    val category: String,
    val icon: String? = null,
    val minApiLevel: Int? = null,
    val language: ProjectLanguage = ProjectLanguage.JAVA
)

enum class ProjectLanguage {
    JAVA, KOTLIN, BOTH
}

data class ProjectConfig(
    val name: String,
    val packageName: String,
    val targetDir: File,
    val minSdkVersion: Int,
    val targetSdkVersion: Int,
    val language: ProjectLanguage,
    val additionalOptions: Map<String, Any> = emptyMap()
)

data class BuildAction(
    val id: String,
    val name: String,
    val description: String,
    val icon: String? = null,
    val isAsync: Boolean = true,
    val execute: (project: IProject, params: Map<String, Any>) -> BuildResult
)

data class BuildResult(
    val success: Boolean,
    val message: String? = null,
    val artifacts: List<BuildArtifact> = emptyList(),
    val duration: Long = 0
)

data class BuildArtifact(
    val name: String,
    val path: File,
    val type: ArtifactType
)

enum class ArtifactType {
    APK, AAR, JAR, BUNDLE
}