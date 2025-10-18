package com.itsaky.androidide.agent

import java.util.UUID

enum class MessageStatus {
    SENT, LOADING, ERROR, COMPLETED
}

data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val text: String,
    val sender: Sender,
    var status: MessageStatus = MessageStatus.SENT,
    val timestamp: Long = System.currentTimeMillis(),
    val durationMs: Long? = null
)

enum class Sender {
    USER, AGENT, SYSTEM, TOOL
}