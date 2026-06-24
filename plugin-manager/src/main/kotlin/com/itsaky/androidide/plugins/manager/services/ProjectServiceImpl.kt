package com.itsaky.androidide.plugins.manager.services

import com.itsaky.androidide.plugins.services.IdeProjectService
import com.itsaky.androidide.plugins.services.IdeProjectService.ProjectOperationResult
import java.io.File

class ProjectServiceImpl(
    private val projectRoot: File
) : IdeProjectService {

    override fun addDependency(buildFilePath: String, dependencyString: String): ProjectOperationResult {
        // Validate build file
        if (!buildFilePath.endsWith("build.gradle") && !buildFilePath.endsWith("build.gradle.kts")) {
            return ProjectOperationResult.failure("Invalid build file. Must be build.gradle or build.gradle.kts")
        }

        val buildFile = File(projectRoot, buildFilePath)
        if (!buildFile.exists()) {
            return ProjectOperationResult.failure("Build file does not exist: $buildFilePath")
        }

        return try {
            val content = buildFile.readText()

            // Find dependencies block
            val dependenciesRegex = Regex("dependencies\\s*\\{")
            val match = dependenciesRegex.find(content)

            if (match == null) {
                return ProjectOperationResult.failure("dependencies block not found in build file")
            }

            // Insert dependency after the opening brace
            val insertIndex = match.range.last + 1
            val newContent = content.substring(0, insertIndex) +
                "\n    $dependencyString" +
                content.substring(insertIndex)

            buildFile.writeText(newContent)
            ProjectOperationResult.success("Dependency added successfully", null)
        } catch (e: Exception) {
            ProjectOperationResult.failure("Failed to add dependency: ${e.message}")
        }
    }

    override fun triggerGradleSync(): ProjectOperationResult {
        // In a real implementation, this would trigger the actual Gradle sync
        // For now, return success as a stub
        return ProjectOperationResult.success("Gradle sync triggered", null)
    }

    override fun isBuildRunning(): ProjectOperationResult {
        // In a real implementation, check actual build status
        // For now, return false as a stub
        return ProjectOperationResult.success("Build status retrieved", "false")
    }

    override fun runApp(): ProjectOperationResult {
        // In a real implementation, trigger actual app run
        // For now, return success as a stub
        return ProjectOperationResult.success("App run initiated", null)
    }

    override fun getBuildOutput(): ProjectOperationResult {
        // In a real implementation, read actual build output
        // For now, return empty output as a stub
        return ProjectOperationResult.success("Build output retrieved", "")
    }
}
