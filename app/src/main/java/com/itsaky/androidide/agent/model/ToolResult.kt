package com.itsaky.androidide.agent.model

import kotlinx.serialization.Serializable

@Serializable
data class ToolResult(
    val success: Boolean,
    val message: String,
    val data: String? = null,
    val error_details: String? = null
) {
    companion object {
        fun success(message: String, data: String? = null): ToolResult {
            return ToolResult(true, message, data)
        }

        fun failure(message: String, error_details: String? = null): ToolResult {
            return ToolResult(false, message, error_details = error_details)
        }
    }

    fun toResultMap(): Map<String, Any> {
        return mapOf(
            "success" to success,
            "message" to message,
            "data" to (data ?: "")
        )
    }
}