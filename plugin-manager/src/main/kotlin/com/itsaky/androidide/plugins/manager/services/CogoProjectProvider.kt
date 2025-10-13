

package com.itsaky.androidide.plugins.manager.services

import com.itsaky.androidide.plugins.extensions.IProject
import com.itsaky.androidide.plugins.extensions.IModule
import com.itsaky.androidide.plugins.extensions.ProjectType
import com.itsaky.androidide.plugins.extensions.ModuleType
import com.itsaky.androidide.plugins.extensions.SourceSet
import com.itsaky.androidide.preferences.internal.GeneralPreferences
import java.io.File

/**
 * Cogo-specific implementation of ProjectProvider that integrates with
 * the actual Cogo project management system.
 */
class CogoProjectProvider : IdeProjectServiceImpl.ProjectProvider {

    override fun getCurrentProject(): IProject? {
        return try {
            // Get the current project path from preferences, same approach as MainActivity
            val projectPath = GeneralPreferences.lastOpenedProject
            
            if (projectPath.isNotEmpty() && projectPath != GeneralPreferences.NO_OPENED_PROJECT) {
                val projectDir = File(projectPath)
                if (projectDir.exists()) {
                    CogoProject(
                        name = projectDir.name,
                        rootDir = projectDir,
                        type = when {
                            hasAndroidModule(projectDir) -> ProjectType.ANDROID_APP
                            else -> ProjectType.GRADLE_PLUGIN
                        }
                    )
                } else {
                    null
                }
            } else {
                null
            }
        } catch (_: Exception) {
            // If preferences access fails, return null
            null
        }
    }

    override fun getAllProjects(): List<IProject> {
        // COGO typically works with one project at a time
        // Return the current project as a single-item list
        val currentProject = getCurrentProject()
        return if (currentProject != null) {
            listOf(currentProject)
        } else {
            emptyList()
        }
    }

    override fun getProjectByPath(path: File): IProject? {
        if (!path.exists() || !path.isDirectory) {
            return null
        }
        
        // Check if the given path matches the current project's path
        val currentProject = getCurrentProject()
        if (currentProject != null && currentProject.rootDir.absolutePath == path.absolutePath) {
            return currentProject
        }
        
        // If not the current project, check if it's a valid Android project
        return if (hasAndroidModule(path)) {
            CogoProject(
                name = path.name,
                rootDir = path,
                type = ProjectType.ANDROID_APP
            )
        } else {
            null
        }
    }

    private fun hasAndroidModule(dir: File): Boolean {
        val hasGradleFiles = listOf("build.gradle.kts", "build.gradle", "settings.gradle.kts", "settings.gradle")
            .any { File(dir, it).exists() }
        val hasAppModule = File(dir, "app").let { it.exists() && it.isDirectory }
        
        return hasGradleFiles && hasAppModule
    }
}

/**
 * Implementation of IProject for COGO projects
 */
private class CogoProject(
    override val name: String,
    override val rootDir: File,
    override val type: ProjectType
) : IProject {

    override fun getModules(): List<IModule> {
        val modules = mutableListOf<IModule>()
        
        // Check for app module
        val appDir = File(rootDir, "app")
        if (appDir.exists() && appDir.isDirectory) {
            modules.add(CogoModule(
                name = "app",
                type = ModuleType.ANDROID_APP,
                projectDir = appDir
            ))
        }
        
        // TODO: Scan for additional modules in settings.gradle
        
        return modules
    }

    override fun getBuildFiles(): List<File> {
        val buildFiles = mutableListOf<File>()
        
        // Root build files
        listOf("build.gradle.kts", "build.gradle", "settings.gradle.kts", "settings.gradle").forEach { fileName ->
            val file = File(rootDir, fileName)
            if (file.exists()) {
                buildFiles.add(file)
            }
        }
        
        // Module build files
        getModules().forEach { module ->
            listOf("build.gradle.kts", "build.gradle").forEach { fileName ->
                val file = File(module.projectDir, fileName)
                if (file.exists()) {
                    buildFiles.add(file)
                }
            }
        }
        
        return buildFiles
    }
}

/**
 * Implementation of IModule for Cogo modules
 */
private class CogoModule(
    override val name: String,
    override val type: ModuleType,
    override val projectDir: File
) : IModule {

    override fun getSourceSets(): List<SourceSet> {
        val sourceSets = mutableListOf<SourceSet>()
        
        // Main source set
        val mainSrcDir = File(projectDir, "src/main/java")
        val mainKotlinDir = File(projectDir, "src/main/kotlin")
        val mainResDir = File(projectDir, "src/main/res")
        
        val mainSrcDirs = mutableListOf<File>()
        if (mainSrcDir.exists()) mainSrcDirs.add(mainSrcDir)
        if (mainKotlinDir.exists()) mainSrcDirs.add(mainKotlinDir)
        
        val mainResDirs = mutableListOf<File>()
        if (mainResDir.exists()) mainResDirs.add(mainResDir)
        
        if (mainSrcDirs.isNotEmpty()) {
            sourceSets.add(SourceSet(
                name = "main",
                srcDirs = mainSrcDirs,
                resourceDirs = mainResDirs
            ))
        }
        
        // Test source set
        val testSrcDir = File(projectDir, "src/test/java")
        val testKotlinDir = File(projectDir, "src/test/kotlin")
        
        val testSrcDirs = mutableListOf<File>()
        if (testSrcDir.exists()) testSrcDirs.add(testSrcDir)
        if (testKotlinDir.exists()) testSrcDirs.add(testKotlinDir)
        
        if (testSrcDirs.isNotEmpty()) {
            sourceSets.add(SourceSet(
                name = "test",
                srcDirs = testSrcDirs,
                resourceDirs = emptyList()
            ))
        }
        
        return sourceSets
    }
}