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

    /**
     * Extract embeddings from the last encoded text.
     * Used for vector search / semantic similarity tasks with encoder models.
     * Call this after sending text to an encoder model.
     * @return Float array containing the embedding vector, or empty array if not available
     */
    suspend fun getEmbeddings(): FloatArray

    /**
     * Encode text and immediately extract embeddings in one atomic operation.
     * This is the preferred method for encoder models used for vector search.
     * @param text The text to encode
     * @return Float array containing the embedding vector, or empty array if failed
     */
    suspend fun encodeForEmbeddings(text: String): FloatArray

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
