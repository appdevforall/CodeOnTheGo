package com.itsaky.androidide.agent.model

import kotlinx.serialization.Serializable

@Serializable
data class ToolResult(
    val success: Boolean,
    val message: String,
    val data: String? = null,
    val error_details: String? = null,
    val exploration: ExplorationMetadata? = null
) {
    companion object {
        fun success(
            message: String,
            data: String? = null,
            exploration: ExplorationMetadata? = null
        ): ToolResult {
            return ToolResult(true, message, data, exploration = exploration)
        }

        fun failure(
            message: String,
            error_details: String? = null,
            data: String? = null,
            exploration: ExplorationMetadata? = null
        ): ToolResult {
            return ToolResult(false, message, data, error_details, exploration)
        }
    }

    fun toResultMap(): Map<String, Any> {
        val result = mutableMapOf<String, Any>(
            "success" to success,
            "message" to message
        )
        data?.let { result["data"] = it }
        error_details?.let { result["error_details"] = it }
        exploration?.let { result["exploration"] = it.toMap() }
        return result
    }
}
