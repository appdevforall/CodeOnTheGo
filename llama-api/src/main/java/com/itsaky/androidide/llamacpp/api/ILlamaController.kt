package com.itsaky.androidide.llamacpp.api

import kotlinx.coroutines.flow.Flow

/**
 * The public contract for the Llama C++ implementation.
 * This interface is shared between the main app and the implementation module.
 */
interface ILlamaController {
    suspend fun load(pathToModel: String)
    fun send(
        message: String,
        formatChat: Boolean = false,
        stop: List<String> = emptyList(),
        clearCache: Boolean = false
    ): Flow<String>

    suspend fun unload()
    fun stop()
    suspend fun clearKvCache()
}