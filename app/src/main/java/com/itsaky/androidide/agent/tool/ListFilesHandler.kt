package com.itsaky.androidide.agent.tool

import com.itsaky.androidide.agent.model.ListFilesArgs
import com.itsaky.androidide.agent.model.ToolResult
import com.itsaky.androidide.api.IDEApiFacade
import org.slf4j.LoggerFactory

class ListFilesHandler : ToolHandler {
    override val name: String = "list_dir"

    override suspend fun invoke(args: Map<String, Any?>): ToolResult {
        val toolArgs = decodeArgs<ListFilesArgs>(args)
        logger.debug(
            "Invoking list_dir with path='{}', recursive={}",
            toolArgs.path,
            toolArgs.recursive
        )
        val result = IDEApiFacade.listFiles(toolArgs.path, toolArgs.recursive)
        if (result.success) {
            val entryCount = result.data
                ?.lineSequence()
                ?.filter { it.isNotBlank() }
                ?.count() ?: 0
            logger.debug(
                "list_dir succeeded for path='{}'. entries={}",
                toolArgs.path,
                entryCount
            )
        } else {
            logger.warn(
                "list_dir failed for path='{}'. message='{}', details='{}'",
                toolArgs.path,
                result.message,
                result.error_details
            )
        }
        return result
    }

    companion object {
        private val logger = LoggerFactory.getLogger(ListFilesHandler::class.java)
    }
}
