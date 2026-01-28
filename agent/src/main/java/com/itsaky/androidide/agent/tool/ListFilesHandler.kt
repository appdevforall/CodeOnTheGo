package com.itsaky.androidide.agent.tool

import com.itsaky.androidide.agent.api.AgentDependencies
import com.itsaky.androidide.agent.model.ListFilesArgs
import com.itsaky.androidide.agent.model.ToolResult
import org.slf4j.LoggerFactory

class ListFilesHandler : ToolHandler {
    override val name: String = "list_dir"

    override suspend fun invoke(args: Map<String, Any?>): ToolResult {
        val toolArgs = decodeArgs<ListFilesArgs>(args)
        val normalizedPath = normalizePath(toolArgs.path)
        logger.debug(
            "Invoking list_dir with path='{}', recursive={}",
            normalizedPath,
            toolArgs.recursive
        )
        val result = AgentDependencies.requireToolingApi()
            .listFiles(normalizedPath, toolArgs.recursive)
        if (result.success) {
            val entryCount = result.data
                ?.lineSequence()
                ?.filter { it.isNotBlank() }
                ?.count() ?: 0
            logger.debug(
                "list_dir succeeded for path='{}'. entries={}",
                normalizedPath,
                entryCount
            )
        } else {
            logger.warn(
                "list_dir failed for path='{}'. message='{}', details='{}'",
                normalizedPath,
                result.message,
                result.error_details
            )
        }
        return result
    }

    private fun normalizePath(path: String): String {
        val trimmed = path.trim()
        if (trimmed.isEmpty()) return ""
        val lowered = trimmed.lowercase()
        return when (lowered) {
            ".", "./", "root", "project", "project root", "root project", "project_root" -> ""
            else -> trimmed
        }
    }

    companion object {
        private val logger = LoggerFactory.getLogger(ListFilesHandler::class.java)
    }
}
