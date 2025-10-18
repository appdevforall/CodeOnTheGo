package com.itsaky.androidide.agent.tool

import android.util.Log
import com.itsaky.androidide.agent.model.ListFilesArgs
import com.itsaky.androidide.agent.model.ToolResult
import com.itsaky.androidide.api.IDEApiFacade

class ListFilesHandler : ToolHandler {
    override val name: String = "list_dir"

    override suspend fun invoke(args: Map<String, Any?>): ToolResult {
        val toolArgs = decodeArgs<ListFilesArgs>(args)
        Log.d(
            TAG,
            "Invoking list_dir with path='${toolArgs.path}', recursive=${toolArgs.recursive}"
        )
        val result = IDEApiFacade.listFiles(toolArgs.path, toolArgs.recursive)
        if (result.success) {
            val entryCount = result.data
                ?.lineSequence()
                ?.filter { it.isNotBlank() }
                ?.count() ?: 0
            Log.d(
                TAG,
                "list_dir succeeded for path='${toolArgs.path}'. entries=$entryCount"
            )
        } else {
            Log.w(
                TAG,
                "list_dir failed for path='${toolArgs.path}'. message='${result.message}', details='${result.error_details}'"
            )
        }
        return result
    }

    companion object {
        private const val TAG = "ListFilesHandler"
    }
}
