package com.itsaky.androidide.actions.sidebar.models

data class ChatMessage(
    val text: String,
    val sender: Sender,
    val timestamp: Long = System.currentTimeMillis()
) {
    enum class Sender {
        USER, AGENT
    }
}