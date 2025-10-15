package com.itsaky.androidide.agent.repository

import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.Serializable

@InternalSerializationApi
@Serializable
data class LocalLLMToolCall(val name: String, val args: Map<String, String>)
