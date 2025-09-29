package com.itsaky.androidide.api.commands

import com.itsaky.androidide.agent.model.ToolResult
import com.itsaky.androidide.projects.IProjectManager
import java.io.File

class AddDependencyCommand(
    private val buildFilePath: String,
    private val dependencyString: String
) {

    // No changes needed for the Dependency data class
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
     * [REVISED] Parses a dependency string from various formats.
     * Handles:
     * - "group:name:version"
     * - "'group:name:version'"
     * - "implementation 'group:name:version'"
     * - "api(\"group:name:version\")"
     * @return A triple of (configuration, identifier, version), or null if parsing fails.
     */
    private fun parseFlexibleDependency(input: String): Triple<String, String, String>? {
        // Pattern 1: Captures full lines like `implementation "group:name:version"`
        val fullLinePattern =
            """^\s*(\w+)\s*(?:\(?['"])([^:]+):([^:]+):([^'"]+)['"]\)?.*$""".toRegex()
        fullLinePattern.find(input)?.let {
            val (config, group, name, version) = it.destructured
            return Triple(config, "$group:$name", version)
        }

        // Pattern 2: Captures just the coordinates like `"group:name:version"`
        val coordinatesOnlyPattern = """^['"]?([^:]+):([^:]+):([^'"]+)['"]?$""".toRegex()
        coordinatesOnlyPattern.find(input)?.let {
            val (group, name, version) = it.destructured
            // Default to 'implementation' if only coordinates are provided
            return Triple("implementation", "$group:$name", version)
        }

        return null
    }

    // No changes needed for the version comparison logic
    private fun compareVersions(v1: String, v2: String): Int {
        // ... (your existing implementation is fine)
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

    private fun parseDependency(line: String): Dependency? {
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

            // [REVISED] Use the new flexible parser
            val (newConfig, newIdentifier, newVersion) = parseFlexibleDependency(dependencyString.trim())
                ?: return ToolResult.failure("Invalid dependency format provided: '$dependencyString'")

            var content = buildFile.readText()
            val dependenciesRegex = Regex("""dependencies\s*\{""")
            val match = dependenciesRegex.find(content)

            // Logic to add a dependencies block if it's missing
            if (match == null) {
                val newDependencyLine = "implementation(\"$newIdentifier:$newVersion\")"
                content += "\n\ndependencies {\n    $newDependencyLine\n}\n"
                buildFile.writeText(content)
                return ToolResult.success("Dependency '$newIdentifier:$newVersion' added to '$buildFilePath'.")
            }

            // Find the end of the dependencies block
            val blockStartIndex = match.range.last + 1
            var openBraces = 1
            var blockEndIndex = -1
            for (i in blockStartIndex until content.length) {
                if (content[i] == '{') openBraces++ else if (content[i] == '}') openBraces--
                if (openBraces == 0) {
                    blockEndIndex = i
                    break
                }
            }

            if (blockEndIndex == -1) {
                return ToolResult.failure("Could not parse the dependencies block.")
            }

            val dependenciesBlock = content.substring(blockStartIndex, blockEndIndex)
            val lines = dependenciesBlock.lines()
            var existingDependency: Dependency? = null

            // Find if the dependency already exists
            lines.forEach { line ->
                parseDependency(line)?.takeIf { it.identifier == newIdentifier }?.let {
                    existingDependency = it
                    return@forEach
                }
            }

            if (existingDependency != null) {
                val existing = existingDependency!!
                val versionComparison = compareVersions(newVersion, existing.version)
                when {
                    versionComparison > 0 -> {
                        // New version is higher, update the line
                        val updatedLine = existing.fullLine.replace(existing.version, newVersion)
                        content = content.replace(existing.fullLine, updatedLine)
                    }

                    versionComparison < 0 -> return ToolResult.failure("Downgrade blocked. Existing version '${existing.version}' is higher than '$newVersion'.")
                    else -> return ToolResult.success("Dependency '$newIdentifier' with version '$newVersion' already exists.")
                }
            } else {
                // Dependency not found, add it
                val indent =
                    lines.firstOrNull { it.trim().isNotEmpty() }?.takeWhile { it.isWhitespace() }
                        ?: "    "
                val newDependencyLine = "\n$indent$newConfig(\"$newIdentifier:$newVersion\")"
                content =
                    content.substring(0, blockEndIndex) + newDependencyLine + content.substring(
                        blockEndIndex
                    )
            }

            buildFile.writeText(content)
            return ToolResult.success("Dependencies updated in '$buildFilePath'. A project sync is recommended.")

        } catch (e: Exception) {
            return ToolResult.failure("Failed to update dependency in '$buildFilePath': ${e.message}")
        }
    }
}