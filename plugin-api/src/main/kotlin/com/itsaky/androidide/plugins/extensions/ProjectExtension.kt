

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
    ANDROID_APP, GRADLE_PLUGIN
}

enum class ModuleType {
    ANDROID_APP
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