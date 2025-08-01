package com.itsaky.androidide.models

sealed class AgentState {
    object Idle : AgentState()
    object Processing : AgentState()
}