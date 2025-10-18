package com.itsaky.androidide.agent.tool

import android.util.Log
import com.itsaky.androidide.agent.model.SearchProjectArgs
import com.itsaky.androidide.agent.model.ToolResult
import com.itsaky.androidide.api.IDEApiFacade

class SearchProjectHandler : ToolHandler {
    override val name: String = "search_project"

    override suspend fun invoke(args: Map<String, Any?>): ToolResult {
        val toolArgs = decodeArgs<SearchProjectArgs>(args)
        Log.d(
            TAG,
            "Invoking search_project query='${toolArgs.query}', path='${toolArgs.path}', maxResults=${toolArgs.maxResults}, ignoreCase=${toolArgs.ignoreCase}"
        )
        val result = IDEApiFacade.searchProject(
            query = toolArgs.query,
            path = toolArgs.path,
            maxResults = toolArgs.maxResults,
            ignoreCase = toolArgs.ignoreCase
        )
        if (result.success) {
            val matchCount = result.exploration?.matchCount ?: result.data?.lineSequence()?.count()
            Log.d(
                TAG,
                "search_project succeeded query='${toolArgs.query}' matches=$matchCount"
            )
        } else {
            Log.w(
                TAG,
                "search_project failed query='${toolArgs.query}'. message='${result.message}', details='${result.error_details}'"
            )
        }
        return result
    }

    companion object {
        private const val TAG = "SearchProjectHandler"
    }
}
