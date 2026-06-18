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

    suspend fun countTokens(text: String): Int

    suspend fun unload()
    fun stop()
    suspend fun clearKvCache()

    /**
     * Generate an embedding vector for the given text.
     *
     * @param text The text to generate an embedding for
     * @return A float array representing the embedding vector
     */
    suspend fun generateEmbedding(text: String): FloatArray

    /**
     * Get the dimension of embeddings produced by the current model.
     *
     * @return The embedding dimension
     */
    suspend fun getEmbeddingDimension(): Int
}
