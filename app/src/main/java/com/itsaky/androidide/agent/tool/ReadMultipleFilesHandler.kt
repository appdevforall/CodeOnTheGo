package com.itsaky.androidide.agent.tool

import com.itsaky.androidide.agent.model.ReadMultipleFilesArgs
import com.itsaky.androidide.agent.model.ToolResult
import com.itsaky.androidide.projects.IProjectManager
import java.io.File

class ReadMultipleFilesHandler : ToolHandler {
    override val name: String = "read_multiple_files"

    override suspend fun invoke(args: Map<String, Any?>): ToolResult {
        val toolArgs = decodeArgs<ReadMultipleFilesArgs>(args)
        if (toolArgs.paths.isEmpty()) {
            return ToolResult.failure("The 'paths' parameter cannot be empty.")
        }

        val results = mutableMapOf<String, String>()
        var allSuccessful = true
        val projectDir = IProjectManager.getInstance().projectDir

        for (path in toolArgs.paths) {
            try {
                val file = File(projectDir, path)
                if (file.exists() && file.isFile) {
                    results[path] = file.readText()
                } else {
                    results[path] = "ERROR: File not found or is a directory."
                    allSuccessful = false
                }
            } catch (e: Exception) {
                results[path] = "ERROR: Could not read file - ${e.message}"
                allSuccessful = false
            }
        }

        val dataJson = encodeStringMap(results)

        return if (allSuccessful) {
            ToolResult.success("Successfully read ${toolArgs.paths.size} files.", dataJson)
        } else {
            ToolResult.failure("One or more files could not be read.", dataJson)
        }
    }
}
