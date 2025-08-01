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

package com.itsaky.androidide.plugins.manager.services

import com.itsaky.androidide.plugins.extensions.IProject
import com.itsaky.androidide.plugins.extensions.IModule
import com.itsaky.androidide.plugins.extensions.ProjectType
import com.itsaky.androidide.plugins.extensions.ModuleType
import com.itsaky.androidide.plugins.extensions.SourceSet
import java.io.File

/**
 * AndroidIDE-specific implementation of ProjectProvider that integrates with
 * the actual AndroidIDE project management system.
 */
class AndroidIdeProjectProvider : IdeProjectServiceImpl.ProjectProvider {

    override fun getCurrentProject(): IProject? {
        return try {
            // Use the same approach as MainActivity.openProject() - access ProjectManagerImpl directly
            val projectManagerClass = Class.forName("com.itsaky.androidide.projects.ProjectManagerImpl")
            val getInstanceMethod = projectManagerClass.getMethod("getInstance")
            val projectManager = getInstanceMethod.invoke(null)
            
            // Get project directory and root project using the same interface as IProjectManager
            val projectDirMethod = projectManagerClass.getMethod("getProjectDir")
            val rootProjectMethod = projectManagerClass.getMethod("getRootProject")
            
            val projectDir = projectDirMethod.invoke(projectManager) as? File
            val rootProject = rootProjectMethod.invoke(projectManager)
            
            if (rootProject != null && projectDir != null) {
                // Get project name from the root project
                val rootProjectField = rootProject.javaClass.getField("rootProject")
                val gradleRootProject = rootProjectField.get(rootProject)
                val nameField = gradleRootProject.javaClass.getField("name")
                val projectName = nameField.get(gradleRootProject) as String
                
                AndroidIdeProject(
                    name = projectName,
                    rootDir = projectDir,
                    type = when {
                        hasAndroidModule(projectDir) -> ProjectType.ANDROID_APP
                        else -> ProjectType.GRADLE_PLUGIN
                    }
                )
            } else {
                null
            }
        } catch (_: Exception) {
            // If project manager access fails, return null
            null
        }
    }

    override fun getAllProjects(): List<IProject> {
        // AndroidIDE typically works with one project at a time
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
            AndroidIdeProject(
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
 * Implementation of IProject for AndroidIDE projects
 */
private class AndroidIdeProject(
    override val name: String,
    override val rootDir: File,
    override val type: ProjectType
) : IProject {

    override fun getModules(): List<IModule> {
        val modules = mutableListOf<IModule>()
        
        // Check for app module
        val appDir = File(rootDir, "app")
        if (appDir.exists() && appDir.isDirectory) {
            modules.add(AndroidIdeModule(
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
 * Implementation of IModule for AndroidIDE modules
 */
private class AndroidIdeModule(
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