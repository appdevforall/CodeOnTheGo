package com.itsaky.androidide.agent.tool

import com.itsaky.androidide.agent.model.SearchProjectArgs
import com.itsaky.androidide.agent.model.ToolResult
import com.itsaky.androidide.api.IDEApiFacade
import org.slf4j.LoggerFactory

class SearchProjectHandler : ToolHandler {
    override val name: String = "search_project"

    override suspend fun invoke(args: Map<String, Any?>): ToolResult {
        val toolArgs = decodeArgs<SearchProjectArgs>(args)
        logger.debug(
            "Invoking search_project query='{}', path='{}', maxResults={}, ignoreCase={}",
            toolArgs.query,
            toolArgs.path,
            toolArgs.maxResults,
            toolArgs.ignoreCase
        )
        val result = IDEApiFacade.searchProject(
            query = toolArgs.query,
            path = toolArgs.path,
            maxResults = toolArgs.maxResults,
            ignoreCase = toolArgs.ignoreCase
        )
        if (result.success) {
            val matchCount = result.exploration?.matchCount
                ?: result.data?.lineSequence()?.count()
            logger.debug(
                "search_project succeeded query='{}'. matches={}",
                toolArgs.query,
                matchCount
            )
        } else {
            logger.warn(
                "search_project failed query='{}'. message='{}', details='{}'",
                toolArgs.query,
                result.message,
                result.error_details
            )
        }
        return result
    }

    companion object {
        private val logger = LoggerFactory.getLogger(SearchProjectHandler::class.java)
    }
}
