package com.itsaky.androidide.api.commands

import com.itsaky.androidide.data.model.ToolResult
import com.itsaky.androidide.projects.IProjectManager
import java.io.File

/**
 * A command to add a dependency string to a Gradle build file.
 * It intelligently finds the `dependencies` block and inserts the new line.
 */
class AddDependencyCommand(
    private val buildFilePath: String,
    private val dependencyString: String
) {
    fun execute(): ToolResult {
        return try {
            val projectDir = IProjectManager.getInstance().projectDir
            val buildFile = File(projectDir, buildFilePath)

            if (!buildFile.exists()) {
                return ToolResult.failure("Build file not found at '$buildFilePath'")
            }

            var content = buildFile.readText()
            val dependenciesRegex = Regex("""dependencies\s*\{""")
            val match = dependenciesRegex.find(content)

            if (match == null) {
                // If no dependencies block exists, create one at the end of the file.
                content += "\n\ndependencies {\n    $dependencyString\n}\n"
            } else {
                // Find the end of the dependencies block by counting braces
                val blockStartIndex = match.range.last + 1
                var openBraces = 1
                var blockEndIndex = -1

                for (i in blockStartIndex until content.length) {
                    when (content[i]) {
                        '{' -> openBraces++
                        '}' -> openBraces--
                    }
                    if (openBraces == 0) {
                        blockEndIndex = i
                        break
                    }
                }

                if (blockEndIndex == -1) {
                    return ToolResult.failure("Could not find the closing brace for the dependencies block in '$buildFilePath'.")
                }

                // Insert the new dependency on a new line just before the closing brace
                val newDependencyLine = "\n    $dependencyString" // Default to 4-space indent
                content = content.substring(
                    0,
                    blockEndIndex
                ) + newDependencyLine + "\n" + content.substring(blockEndIndex)
            }

            buildFile.writeText(content)
            ToolResult.success("Dependency '$dependencyString' was added to '$buildFilePath'. A project sync is recommended.")
        } catch (e: Exception) {
            ToolResult.failure("Failed to add dependency to '$buildFilePath': ${e.message}")
        }
    }
}