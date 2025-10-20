package com.itsaky.androidide.agent.tool

data class ToolApprovalResponse(
    val approved: Boolean,
    val denialMessage: String? = null
)

interface ToolApprovalManager {
    suspend fun ensureApproved(
        toolName: String,
        handler: ToolHandler,
        args: Map<String, Any?>
    ): ToolApprovalResponse
}
