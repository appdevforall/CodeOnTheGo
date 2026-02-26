package com.itsaky.androidide.agent.tool

import com.itsaky.androidide.agent.api.AgentDependencies
import com.itsaky.androidide.agent.model.ListFilesArgs
import com.itsaky.androidide.agent.model.ToolResult
import com.itsaky.androidide.projects.IProjectManager
import org.slf4j.LoggerFactory
import java.io.File

class ListFilesHandler : ToolHandler {
    override val name: String = "list_files"

    override suspend fun invoke(args: Map<String, Any?>): ToolResult {
        val toolArgs = decodeArgs<ListFilesArgs>(args)
        val normalizedPath = normalizePath(toolArgs.path)
        logger.debug(
            "Invoking list_files with path='{}', recursive={}",
            normalizedPath,
            toolArgs.recursive
        )
        val baseDir = runCatching { IProjectManager.getInstance().projectDir }
            .getOrNull()
            ?.canonicalFile
        if (baseDir == null) {
            return ToolResult.failure("Path escapes project root")
        }

        val targetDir = when (normalizedPath.trim()) {
            "", ".", "./" -> baseDir
            else -> File(baseDir, normalizedPath).canonicalFile
        }
        val basePath = baseDir.path
        val targetPath = targetDir.path
        val isInside =
            targetPath == basePath || targetPath.startsWith(basePath + File.separator)
        if (!isInside) {
            return ToolResult.failure("Path escapes project root")
        }

        val safePath = if (targetPath == basePath) {
            ""
        } else {
            targetDir.relativeTo(baseDir).path
        }

        val result = AgentDependencies.requireToolingApi()
            .listFiles(safePath, toolArgs.recursive)
        if (result.success) {
            val entryCount = result.data
                ?.lineSequence()
                ?.filter { it.isNotBlank() }
                ?.count() ?: 0
            logger.debug(
                "list_files succeeded for path='{}'. entries={}",
                normalizedPath,
                entryCount
            )
        } else {
            logger.warn(
                "list_files failed for path='{}'. message='{}', details='{}'",
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
