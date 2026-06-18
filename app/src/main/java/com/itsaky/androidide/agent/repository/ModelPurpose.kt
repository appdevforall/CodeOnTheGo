package com.itsaky.androidide.agent.repository

/**
 * Defines the different purposes for which local LLM models can be loaded.
 * Each purpose may require different model configurations and context parameters.
 */
enum class ModelPurpose(
    val displayName: String,
    val description: String,
    val requiresEmbeddings: Boolean = false
) {
    /**
     * Model used for chat/assistant conversations.
     * Default configuration with text generation support.
     */
    CHAT(
        displayName = "Chat Model",
        description = "For AI assistant conversations",
        requiresEmbeddings = false
    ),

    /**
     * Model used for generating embeddings for vector search.
     * Requires embeddings=true and pooling configuration.
     */
    EMBEDDINGS(
        displayName = "Embedding Model",
        description = "For semantic code search (vector search)",
        requiresEmbeddings = true
    ),

    /**
     * Model for speech-to-text transcription (future implementation).
     */
    SPEECH_TO_TEXT(
        displayName = "Speech-to-Text Model",
        description = "For voice transcription",
        requiresEmbeddings = false
    ),

    /**
     * Model for code completion suggestions (future implementation).
     */
    CODE_COMPLETION(
        displayName = "Code Completion Model",
        description = "For intelligent code suggestions",
        requiresEmbeddings = false
    );

    companion object {
        /**
         * Get the preference key for storing this model's path
         */
        fun ModelPurpose.getPreferenceKey(): String = "local_model_path_${name.lowercase()}"

        /**
         * Get the preference key for storing this model's SHA-256
         */
        fun ModelPurpose.getSha256PreferenceKey(): String = "local_model_sha256_${name.lowercase()}"
    }
}
