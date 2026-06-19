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
        public String backendId;
        public String modelName;
        public float temperature = 0.7f;
        public int maxTokens = 2048;
        public List<String> stopSequences;
        public String systemPrompt;
        public Map<String, Object> extraParams;

        public LlmConfig(String backendId) {
            this.backendId = backendId;
        }
    }

    /**
     * LLM response
     */
    class LlmResponse {
        public final boolean success;
        public final String text;
        public final String error;
        public final int tokensGenerated;
        public final long timeMs;

        public LlmResponse(boolean success, String text, String error,
                          int tokensGenerated, long timeMs) {
            this.success = success;
            this.text = text;
            this.error = error;
            this.tokensGenerated = tokensGenerated;
            this.timeMs = timeMs;
        }

        public static LlmResponse success(String text, int tokens, long timeMs) {
            return new LlmResponse(true, text, null, tokens, timeMs);
        }

        public static LlmResponse failure(String error) {
            return new LlmResponse(false, null, error, 0, 0);
        }
    }

    /**
     * Callback for streaming responses
     */
    interface StreamCallback {
        void onToken(String token);
        void onComplete(LlmResponse response);
        void onError(String error);
    }

    /**
     * Message in a conversation
     */
    class ChatMessage {
        public enum Role { USER, ASSISTANT, SYSTEM }

        public final Role role;
        public final String content;

        public ChatMessage(Role role, String content) {
            this.role = role;
            this.content = content;
        }
    }

    /**
     * LLM backend provider
     */
    interface LlmBackend {
        String getId();
        String getName();
        boolean isAvailable();
        CompletableFuture<LlmResponse> generate(String prompt, LlmConfig config);
        void generateStreaming(String prompt, LlmConfig config, StreamCallback callback);
        CompletableFuture<LlmResponse> generateWithHistory(
            List<ChatMessage> history,
            String prompt,
            LlmConfig config
        );
    }

    void registerBackend(@NonNull LlmBackend backend);
    void unregisterBackend(@NonNull String backendId);
    @NonNull List<LlmBackend> getAvailableBackends();
    @Nullable LlmBackend getBackend(@NonNull String backendId);
    @NonNull CompletableFuture<LlmResponse> generateCompletion(@NonNull String prompt, @NonNull LlmConfig config);
    void generateStreaming(@NonNull String prompt, @NonNull LlmConfig config, @NonNull StreamCallback callback);
    @NonNull CompletableFuture<LlmResponse> generateWithHistory(@NonNull List<ChatMessage> history, @NonNull String prompt, @NonNull LlmConfig config);
    @NonNull CompletableFuture<float[]> getEmbeddings(@NonNull String text, @NonNull String backendId);
    boolean isBackendAvailable(@NonNull String backendId);
    void cancelGeneration();
}
