package com.itsaky.androidide.api.commands

import com.itsaky.androidide.agent.model.ToolResult
import com.itsaky.androidide.projects.IProjectManager
import java.io.File

/**
 * A command to add or update a dependency in a Gradle build file.
 *
 * This command intelligently handles the following cases:
 * 1.  If a `dependencies` block doesn't exist, it creates one.
 * 2.  If the dependency doesn't exist, it adds it.
 * 3.  If the dependency exists with an older version, it updates it.
 * 4.  If the dependency exists with the same or a newer version, it does nothing.
 * 5.  It prevents downgrading a dependency.
 */
class AddDependencyCommand(
    private val buildFilePath: String,
    private val dependencyString: String
) {

    /**
     * Represents a parsed Gradle dependency.
     * e.g., "implementation 'com.google.code.gson:gson:2.9.0'"
     */
    private data class Dependency(
        val group: String,
        val name: String,
        val version: String,
        val configuration: String,
        val fullLine: String
    ) {
        val identifier = "$group:$name"
    }

    /**
     * A simple version comparator.
     * @return a positive integer if version1 > version2, negative if version1 < version2, and 0 if they are equal.
     */
    private fun compareVersions(v1: String, v2: String): Int {
        val parts1 = v1.split('.', '-').mapNotNull { it.toIntOrNull() }
        val parts2 = v2.split('.', '-').mapNotNull { it.toIntOrNull() }
        val maxParts = maxOf(parts1.size, parts2.size)
        for (i in 0 until maxParts) {
            val part1 = parts1.getOrElse(i) { 0 }
            val part2 = parts2.getOrElse(i) { 0 }
            if (part1 != part2) {
                return part1.compareTo(part2)
            }
        }
        return 0
    }

    /**
     * Parses a dependency string line into a Dependency object.
     * Handles both single and double quotes.
     */
    private fun parseDependency(line: String): Dependency? {
        // Regex to capture: configuration, group, name, and version
        val pattern = """^\s*(\w+)\s*(?:project\()?['"]([^:]+):([^:]+):([^'"]+)['"]\)?""".toRegex()
        val match = pattern.find(line) ?: return null

        val (config, group, name, version) = match.destructured
        return Dependency(group, name, version, config, line.trim())
    }

    fun execute(): ToolResult {
        try {
            val projectDir = IProjectManager.getInstance().projectDir
            val buildFile = File(projectDir, buildFilePath)

            if (!buildFile.exists()) {
                return ToolResult.failure("Build file not found at '$buildFilePath'")
            }

            val newDependency = parseDependency(dependencyString)
                ?: return ToolResult.failure("Invalid dependency format: '$dependencyString'")

            var content = buildFile.readText()
            val dependenciesRegex = Regex("""dependencies\s*\{""")
            val match = dependenciesRegex.find(content)

            if (match == null) {
                // If no dependencies block exists, create one.
                content += "\n\ndependencies {\n    $dependencyString\n}\n"
                buildFile.writeText(content)
                return ToolResult.success("Dependency '$dependencyString' added to '$buildFilePath'.")
            }

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
                return ToolResult.failure("Could not find the closing brace for the dependencies block.")
            }

            val dependenciesBlock = content.substring(blockStartIndex, blockEndIndex)
            val lines = dependenciesBlock.lines()
            var existingDependency: Dependency? = null
            var lineToUpdateIndex = -1

            // Find if the dependency already exists
            for ((index, line) in lines.withIndex()) {
                val parsedDep = parseDependency(line)
                if (parsedDep?.identifier == newDependency.identifier) {
                    existingDependency = parsedDep
                    lineToUpdateIndex = index
                    break
                }
            }

            if (existingDependency != null) {
                // Dependency exists, check the version
                val versionComparison =
                    compareVersions(newDependency.version, existingDependency.version)
                when {
                    versionComparison > 0 -> {
                        // New version is higher, update the line in the content.
                        val mutableLines = lines.toMutableList()
                        mutableLines[lineToUpdateIndex] = lines[lineToUpdateIndex].replace(
                            existingDependency.version,
                            newDependency.version
                        )
                        val newDependenciesBlock = mutableLines.joinToString("\n")
                        content = content.replaceRange(
                            blockStartIndex,
                            blockEndIndex,
                            newDependenciesBlock
                        )
                    }

                    versionComparison < 0 -> {
                        // Downgrade attempt, return failure.
                        return ToolResult.failure("Downgrade attempt blocked. Existing version '${existingDependency.version}' is higher than '${newDependency.version}'.")
                    }

                    else -> {
                        // Versions are the same, do nothing and return success.
                        return ToolResult.success("Dependency '${newDependency.identifier}' with version '${newDependency.version}' already exists.")
                    }
                }
            } else {
                // Dependency not found, add it before the closing brace.
                val indent =
                    lines.firstOrNull { it.trim().isNotEmpty() }?.takeWhile { it.isWhitespace() }
                        ?: "    "
                val newDependencyLine = "\n$indent${newDependency.fullLine}"
                content =
                    content.substring(0, blockEndIndex) + newDependencyLine + content.substring(
                        blockEndIndex
                    )
            }

            buildFile.writeText(content)
            return ToolResult.success("Dependencies updated in '$buildFilePath'. A project sync is recommended.")

        } catch (e: Exception) {
            return ToolResult.failure("Failed to add or update dependency in '$buildFilePath': ${e.message}")
        }
    }
}
