package com.itsaky.androidide.plugins.services;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Service for LLM inference operations.
 * Provided by ai-core plugin.
 */
public interface LlmInferenceService {

    /**
     * Configuration for LLM generation
     */
    class LlmConfig {
        /** The LLM backend identifier (e.g., "openai", "local"). Must not be null. */
        public String backendId;

        /** The name of the model to use for generation */
        public String modelName;

        /** Temperature for generation (0.0-1.0). Default 0.7f provides balanced creativity and coherence. */
        public float temperature = 0.7f;

        /** Maximum number of tokens to generate. Default 2048 balances response length and resource usage. */
        public int maxTokens = 2048;

        /** Optional sequences that signal end of generation */
        public List<String> stopSequences;

        /** Optional system prompt to guide model behavior */
        public String systemPrompt;

        /** Optional backend-specific parameters */
        public Map<String, Object> extraParams;

        /**
         * Creates a configuration for LLM generation.
         *
         * @param backendId the LLM backend identifier (must not be null). The backend must be
         *                 registered with the service.
         * @throws IllegalArgumentException if backendId is null
         */
        public LlmConfig(String backendId) {
            if (backendId == null) {
                throw new IllegalArgumentException("backendId must not be null");
            }
            this.backendId = backendId;
        }
    }

    /**
     * LLM response
     */
    class LlmResponse {
        /** Whether the generation was successful */
        public final boolean success;

        /** Generated text (null if not successful) */
        public final String text;

        /** Error message (null if successful) */
        public final String error;

        /** Number of tokens generated in the response */
        public final int tokensGenerated;

        /** Time taken to generate the response in milliseconds */
        public final long timeMs;

        public LlmResponse(boolean success, String text, String error,
                          int tokensGenerated, long timeMs) {
            this.success = success;
            this.text = text;
            this.error = error;
            this.tokensGenerated = tokensGenerated;
            this.timeMs = timeMs;
        }

        /**
         * Creates a successful response.
         *
         * @param text the generated text
         * @param tokens the number of tokens generated
         * @param timeMs the time taken in milliseconds
         * @return a successful LlmResponse
         */
        public static LlmResponse success(String text, int tokens, long timeMs) {
            return new LlmResponse(true, text, null, tokens, timeMs);
        }

        /**
         * Creates a failed response.
         *
         * @param error the error message describing why generation failed
         * @return a failed LlmResponse
         */
        public static LlmResponse failure(String error) {
            return new LlmResponse(false, null, error, 0, 0);
        }
    }

    /**
     * Callback for streaming responses
     */
    interface StreamCallback {
        /**
         * Called when a token is received.
         *
         * @param token the generated token
         */
        void onToken(String token);

        /**
         * Called when generation is complete.
         *
         * @param response the complete response
         */
        void onComplete(LlmResponse response);

        /**
         * Called when an error occurs.
         *
         * @param error the error message
         */
        void onError(String error);
    }

    /**
     * Message in a conversation
     */
    class ChatMessage {
        /** Role of the message sender */
        public enum Role { USER, ASSISTANT, SYSTEM }

        /** The role of the message sender */
        public final Role role;

        /** The text content of the message */
        public final String content;

        /**
         * Creates a chat message.
         *
         * @param role the role of the sender
         * @param content the message content
         */
        public ChatMessage(Role role, String content) {
            this.role = role;
            this.content = content;
        }
    }

    /**
     * LLM backend provider
     */
    interface LlmBackend {
        /**
         * Gets the unique identifier for this backend.
         *
         * @return the backend identifier
         */
        String getId();

        /**
         * Gets the human-readable name of this backend.
         *
         * @return the backend name
         */
        String getName();

        /**
         * Checks if this backend is available for use.
         *
         * @return true if the backend is available, false otherwise
         */
        boolean isAvailable();

        /**
         * Generates a completion for the given prompt.
         *
         * @param prompt the input prompt
         * @param config the generation configuration
         * @return a future that completes with the generated response
         */
        CompletableFuture<LlmResponse> generate(String prompt, LlmConfig config);

        /**
         * Generates a completion with streaming output.
         *
         * @param prompt the input prompt
         * @param config the generation configuration
         * @param callback the callback to receive tokens and completion events
         */
        void generateStreaming(String prompt, LlmConfig config, StreamCallback callback);

        /**
         * Generates a completion based on conversation history.
         *
         * @param history the conversation history
         * @param prompt the current prompt
         * @param config the generation configuration
         * @return a future that completes with the generated response
         */
        CompletableFuture<LlmResponse> generateWithHistory(
            List<ChatMessage> history,
            String prompt,
            LlmConfig config
        );
    }

    /**
     * Registers an LLM backend with the service.
     *
     * @param backend the backend to register (must not be null)
     */
    void registerBackend(@NonNull LlmBackend backend);

    /**
     * Unregisters an LLM backend from the service.
     *
     * @param backendId the backend identifier (must not be null)
     */
    void unregisterBackend(@NonNull String backendId);

    /**
     * Gets all available LLM backends.
     *
     * @return a list of available backends (never null)
     */
    @NonNull List<LlmBackend> getAvailableBackends();

    /**
     * Gets a specific backend by identifier.
     *
     * @param backendId the backend identifier (must not be null)
     * @return the backend if found, or null if not registered
     */
    @Nullable LlmBackend getBackend(@NonNull String backendId);

    /**
     * Generates a text completion for the given prompt.
     *
     * @param prompt the input prompt (must not be null)
     * @param config the generation configuration (must not be null)
     * @return a future that completes with the generated response (never null)
     */
    @NonNull CompletableFuture<LlmResponse> generateCompletion(@NonNull String prompt, @NonNull LlmConfig config);

    /**
     * Generates a text completion with streaming output.
     *
     * @param prompt the input prompt (must not be null)
     * @param config the generation configuration (must not be null)
     * @param callback the callback to receive tokens and completion events (must not be null)
     */
    void generateStreaming(@NonNull String prompt, @NonNull LlmConfig config, @NonNull StreamCallback callback);

    /**
     * Generates a completion based on conversation history.
     *
     * @param history the conversation history (must not be null)
     * @param prompt the current prompt (must not be null)
     * @param config the generation configuration (must not be null)
     * @return a future that completes with the generated response (never null)
     */
    @NonNull CompletableFuture<LlmResponse> generateWithHistory(@NonNull List<ChatMessage> history, @NonNull String prompt, @NonNull LlmConfig config);

    /**
     * Generates embeddings for the given text.
     *
     * @param text the input text to embed (must not be null)
     * @param backendId the backend to use for embedding (must not be null)
     * @return a future that completes with the embedding vector (never null)
     */
    @NonNull CompletableFuture<float[]> getEmbeddings(@NonNull String text, @NonNull String backendId);

    /**
     * Checks if a backend is available.
     *
     * @param backendId the backend identifier (must not be null)
     * @return true if the backend is registered and available, false otherwise
     */
    boolean isBackendAvailable(@NonNull String backendId);

    /**
     * Cancels any ongoing generation operation.
     */
    void cancelGeneration();
}
