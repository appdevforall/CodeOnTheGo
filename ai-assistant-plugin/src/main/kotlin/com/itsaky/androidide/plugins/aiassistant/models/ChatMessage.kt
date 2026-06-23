package com.itsaky.androidide.plugins.aiassistant.models

import java.util.UUID

/**
 * Status of a chat message.
 */
enum class MessageStatus {
    /** Message successfully sent/received */
    SENT,
    /** Message is being generated */
    LOADING,
    /** Message generation failed */
    ERROR,
    /** Message generation completed */
    COMPLETED
}

/**
 * Sender of a chat message.
 */
enum class Sender {
    /** Message from the user */
    USER,
    /** Message from the AI agent */
    AGENT,
    /** System notification message */
    SYSTEM,
    /** Tool execution message */
    TOOL
}

/**
 * Represents a single chat message in the conversation.
 */
data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val text: String,
    val sender: Sender,
    var status: MessageStatus = MessageStatus.SENT,
    val timestamp: Long = System.currentTimeMillis(),
    val durationMs: Long? = null
)
