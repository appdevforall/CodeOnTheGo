package com.itsaky.androidide.agent.tool

import com.itsaky.androidide.agent.model.SearchProjectArgs
import com.itsaky.androidide.agent.model.ToolResult
import com.itsaky.androidide.api.IDEApiFacade

class SearchProjectHandler : ToolHandler {
    override val name: String = "search_project"

    override suspend fun invoke(args: Map<String, Any?>): ToolResult {
        val toolArgs = decodeArgs<SearchProjectArgs>(args)
        return IDEApiFacade.searchProject(
            query = toolArgs.query,
            path = toolArgs.path,
            maxResults = toolArgs.maxResults,
            ignoreCase = toolArgs.ignoreCase
        )
    }
}
