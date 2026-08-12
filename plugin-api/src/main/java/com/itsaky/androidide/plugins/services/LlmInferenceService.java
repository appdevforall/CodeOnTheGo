package com.itsaky.androidide.plugins.services;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * Service for LLM inference operations. Provided by ai-core plugin.
 */
public interface LlmInferenceService {

	/**
	 * Cancels any ongoing generation operation.
	 */
	void cancelGeneration();

	/**
	 * Generates a text completion for the given prompt.
	 *
	 * @param prompt
	 *            the input prompt (must not be null)
	 * @param config
	 *            the generation configuration (must not be null)
	 * @return a future that completes with the generated response (never null)
	 */
	@NonNull
	CompletableFuture<LlmResponse> generateCompletion(@NonNull String prompt, @NonNull LlmConfig config);

	/**
	 * Generates a text completion with streaming output.
	 *
	 * @param prompt
	 *            the input prompt (must not be null)
	 * @param config
	 *            the generation configuration (must not be null)
	 * @param callback
	 *            the callback to receive tokens and completion events (must not be null)
	 */
	void generateStreaming(@NonNull String prompt, @NonNull LlmConfig config, @NonNull StreamCallback callback);

	/**
	 * Generate streaming response with tool calling support. The LLM can call tools, and the caller responds with tool results.
	 *
	 * @param prompt
	 *            the user prompt
	 * @param history
	 *            the conversation history (can be empty)
	 * @param config
	 *            the generation configuration
	 * @param tools
	 *            the available tools the LLM can call
	 * @param callback
	 *            the callback for handling tokens, tool calls, completion, and errors
	 */
	void generateStreamingWithTools(
			@NonNull String prompt,
			@NonNull List<ChatMessage> history,
			@NonNull LlmConfig config,
			@NonNull List<ToolDefinition> tools,
			@NonNull ToolStreamCallback callback);

	/**
	 * Generates a completion based on conversation history.
	 *
	 * @param history
	 *            the conversation history (must not be null)
	 * @param prompt
	 *            the current prompt (must not be null)
	 * @param config
	 *            the generation configuration (must not be null)
	 * @return a future that completes with the generated response (never null)
	 */
	@NonNull
	CompletableFuture<LlmResponse> generateWithHistory(@NonNull List<ChatMessage> history, @NonNull String prompt, @NonNull LlmConfig config);

	/**
	 * Gets all available LLM backends.
	 *
	 * @return a list of available backends (never null)
	 */
	@NonNull
	List<LlmBackend> getAvailableBackends();

	/**
	 * Gets a specific backend by identifier.
	 *
	 * @param backendId
	 *            the backend identifier (must not be null)
	 * @return the backend if found, or null if not registered
	 */
	@Nullable
	LlmBackend getBackend(@NonNull String backendId);

	/**
	 * Generates embeddings for the given text.
	 *
	 * @param text
	 *            the input text to embed (must not be null)
	 * @param backendId
	 *            the backend to use for embedding (must not be null)
	 * @return a future that completes with the embedding vector (never null)
	 */
	@NonNull
	CompletableFuture<float[]> getEmbeddings(@NonNull String text, @NonNull String backendId);

	/**
	 * Gets the id of the backend the user selected, independent of whether it is registered or currently usable.
	 *
	 * <p>
	 * Which backend is active is the router's state, not any one backend's, but a backend sometimes needs it: one that would otherwise spend seconds and gigabytes preparing itself has to know whether it is the backend about to be used. Publishing it here is what keeps a backend from having to read another plugin's preferences to find out.
	 *
	 * @return the selected backend id, or null when no selection has been expressed
	 */
	@Nullable
	default String getPreferredBackendId() {
		return null;
	}

	/**
	 * Checks if a backend is available.
	 *
	 * @param backendId
	 *            the backend identifier (must not be null)
	 * @return true if the backend is registered and available, false otherwise
	 */
	boolean isBackendAvailable(@NonNull String backendId);

	/**
	 * Registers an LLM backend with the service.
	 *
	 * @param backend
	 *            the backend to register (must not be null)
	 */
	void registerBackend(@NonNull LlmBackend backend);

	/**
	 * Unregisters an LLM backend from the service.
	 *
	 * @param backendId
	 *            the backend identifier (must not be null)
	 */
	void unregisterBackend(@NonNull String backendId);

	/**
	 * A backend whose in-flight streaming generation can be cancelled (Stop pressed).
	 */
	interface CancellableBackend {
		/**
		 * Cancels the streaming generation currently in flight, if any.
		 */
		void cancelStreaming();
	}

	/**
	 * Message in a conversation
	 */
	class ChatMessage {
		/** The role of the message sender */
		public final Role role;

		/** The text content of the message */
		public final String content;

		/**
		 * Creates a chat message.
		 *
		 * @param role
		 *            the role of the sender
		 * @param content
		 *            the message content
		 */
		public ChatMessage(Role role, String content) {
			this.role = role;
			this.content = content;
		}

		/** Role of the message sender */
		public enum Role {
			USER, ASSISTANT, SYSTEM
		}
	}

	/**
	 * Specification of a single backend configuration field. Lets the app draw a backend's settings without knowing its API.
	 */
	class ConfigFieldSpec {
		/** Key the value is stored under (e.g. "api_key", "model_path") */
		public final String key;

		/** Human-readable label for the field */
		public final String label;

		/** How the field should be rendered */
		public final ConfigFieldType type;

		/** Whether a value must be supplied */
		public final boolean required;

		/** Default value, or null if there is none */
		public final String defaultValue;

		/** Selectable options; empty unless {@code type} is {@link ConfigFieldType#DROPDOWN}. Never null, never mutable. */
		public final List<String> options;

		/**
		 * Creates a configuration field spec.
		 *
		 * @param key
		 *            the key the value is stored under
		 * @param label
		 *            the human-readable label
		 * @param type
		 *            how the field should be rendered
		 * @param required
		 *            whether a value must be supplied
		 * @param defaultValue
		 *            the default value, or null if there is none
		 * @param options
		 *            the selectable options, only used when type is DROPDOWN; copied, so later edits to the caller's list do not reach the spec
		 */
		public ConfigFieldSpec(@NonNull String key, @NonNull String label, @NonNull ConfigFieldType type,
				boolean required, @Nullable String defaultValue, @Nullable List<String> options) {
			this.key = Objects.requireNonNull(key, "key must not be null");
			this.label = Objects.requireNonNull(label, "label must not be null");
			this.type = Objects.requireNonNull(type, "type must not be null");
			this.required = required;
			this.defaultValue = defaultValue;
			this.options = options == null
					? Collections.emptyList()
					: Collections.unmodifiableList(new ArrayList<>(options));
		}
	}

	/**
	 * Type of a backend configuration field, determining how the app renders it.
	 */
	enum ConfigFieldType {
		TEXT, PASSWORD, FILE_PICKER, DROPDOWN, BOOLEAN
	}

	/**
	 * An {@link LlmBackend} that has settings for the user to fill in. Kept apart from {@code LlmBackend} so that running inference stays independent of drawing a settings screen: a backend with nothing to configure implements nothing, and the consumer asks with {@code instanceof} before it draws.
	 */
	interface ConfigurableBackend {
		/**
		 * Gets the fields the app must draw to configure this backend.
		 *
		 * @return the configuration field specs; empty if the settings screen comes from {@link #getSettingsFragmentClassName()} instead
		 */
		@NonNull
		List<ConfigFieldSpec> getConfigSpecs();

		/**
		 * Gets the fully-qualified name of a {@code Fragment} this backend contributes to draw its own settings. The class must live in the backend's own plugin and declare a public no-argument constructor; the consumer loads it with the backend's classloader and mounts it wherever it presents backend settings. The name is passed as a string so this contract stays free of any dependency on Android UI types.
		 *
		 * <p>
		 * Overriding this supersedes {@link #getConfigSpecs()} as the source of the settings UI, so a backend whose configuration is more than a list of fields -- live model catalogs, credential checks, engine status -- owns that screen instead of flattening it into field specs.
		 *
		 * @return the fragment class name, or null to be configured from {@link #getConfigSpecs()} instead
		 */
		@Nullable
		default String getSettingsFragmentClassName() {
			return null;
		}
	}

	/**
	 * LLM backend provider
	 */
	interface LlmBackend {
		/**
		 * Generates a completion for the given prompt.
		 *
		 * @param prompt
		 *            the input prompt
		 * @param config
		 *            the generation configuration
		 * @return a future that completes with the generated response
		 */
		CompletableFuture<LlmResponse> generate(String prompt, LlmConfig config);

		/**
		 * Generates a completion with streaming output.
		 *
		 * @param prompt
		 *            the input prompt
		 * @param config
		 *            the generation configuration
		 * @param callback
		 *            the callback to receive tokens and completion events
		 */
		void generateStreaming(String prompt, LlmConfig config, StreamCallback callback);

		/**
		 * Generates a streaming reply for a multi-turn conversation. Implement this together with {@link #supportsHistory()}, which callers are expected to consult first; the default throws rather than answering the last turn alone, because a backend that quietly forgets the conversation reads to the user as a model that cannot follow one. A caller that prefers single-turn to nothing calls {@link #generateStreaming} itself once {@link #supportsHistory()} says no.
		 *
		 * @param history
		 *            the conversation history
		 * @param prompt
		 *            the current prompt
		 * @param config
		 *            the generation configuration
		 * @param callback
		 *            the callback to receive tokens and completion events
		 * @throws UnsupportedOperationException
		 *             if this backend does not support multi-turn history
		 */
		default void generateStreamingWithHistory(
				List<ChatMessage> history,
				String prompt,
				LlmConfig config,
				StreamCallback callback) {
			throw new UnsupportedOperationException("Multi-turn history is not supported by backend: " + getId());
		}

		/**
		 * Generates a completion with streaming output and tool calling support. Implement this together with {@link #supportsTools()}, which callers are expected to consult first; the default throws rather than streaming plain text, because tools that are accepted and never called leave the caller waiting on an action the model was never able to take.
		 *
		 * @param prompt
		 *            the input prompt
		 * @param history
		 *            the conversation history (can be empty)
		 * @param config
		 *            the generation configuration
		 * @param tools
		 *            the available tools the LLM can call
		 * @param callback
		 *            the callback to receive tokens, tool calls and completion events
		 * @throws UnsupportedOperationException
		 *             if this backend does not support tool calling
		 */
		default void generateStreamingWithTools(
				String prompt,
				List<ChatMessage> history,
				LlmConfig config,
				List<ToolDefinition> tools,
				ToolStreamCallback callback) {
			throw new UnsupportedOperationException("Tool calling is not supported by backend: " + getId());
		}

		/**
		 * Generates a completion based on conversation history.
		 *
		 * @param history
		 *            the conversation history
		 * @param prompt
		 *            the current prompt
		 * @param config
		 *            the generation configuration
		 * @return a future that completes with the generated response
		 */
		CompletableFuture<LlmResponse> generateWithHistory(
				List<ChatMessage> history,
				String prompt,
				LlmConfig config);

		/**
		 * Gets the sampling temperature this backend works best at, or null to accept the consumer's own.
		 *
		 * <p>
		 * A backend driven by a constrained grammar wants a near-greedy value so it copies arguments rather than inventing them; a cloud model following a high-autonomy prompt usually wants more room. Neither figure is the consumer's to guess.
		 *
		 * <p>
		 * Boxed so that "no preference" is expressible. {@link LlmConfig#temperature} is a primitive, so a consumer must null-check before it assigns: {@code config.temperature = backend.getDefaultTemperature()} unboxes null and throws.
		 *
		 * @return the preferred temperature, or null for the consumer's default
		 */
		@Nullable
		default Float getDefaultTemperature() {
			return null;
		}

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
		 * Gets the system prompt to send with every request to this backend, or null to accept the consumer's own.
		 *
		 * <p>
		 * Prompt wording is model-specific -- how much autonomy a model handles, how literally it copies an example -- so it belongs with the backend that knows the model, not with the consumer that knows the tools. The consumer still owns the call syntax: reproduce {@link SystemPromptRequest#toolCallSyntax} verbatim, or the replies this prompt produces will not parse.
		 *
		 * @param request
		 *            the tool contract and example material to compose against
		 * @return the system prompt, or null to use the consumer's default
		 */
		@Nullable
		default String getSystemPrompt(@NonNull SystemPromptRequest request) {
			return null;
		}

		/**
		 * Checks if this backend is available for use.
		 *
		 * @return true if the backend is available, false otherwise
		 */
		boolean isAvailable();

		/**
		 * Whether this backend renders earlier turns of a conversation, and so overrides {@link #generateStreamingWithHistory}. Answer for the backend, not for the model behind it: a backend that can only prompt single-turn says false here and lets the caller decide what to do about it.
		 *
		 * @return true if multi-turn history is supported, false otherwise
		 */
		default boolean supportsHistory() {
			return false;
		}

		/**
		 * Whether this backend executes tool calls, and so overrides {@link #generateStreamingWithTools}.
		 *
		 * @return true if tool calling is supported, false otherwise
		 */
		default boolean supportsTools() {
			return false;
		}
	}

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
		 * @param backendId
		 *            the LLM backend identifier (must not be null). The backend must be registered with the service.
		 * @throws IllegalArgumentException
		 *             if backendId is null
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
		/**
		 * Creates a failed response.
		 *
		 * @param error
		 *            the error message describing why generation failed
		 * @return a failed LlmResponse
		 */
		public static LlmResponse failure(String error) {
			return new LlmResponse(false, null, error, 0, 0);
		}

		/**
		 * Creates a successful response.
		 *
		 * @param text
		 *            the generated text
		 * @param tokens
		 *            the number of tokens generated
		 * @param timeMs
		 *            the time taken in milliseconds
		 * @return a successful LlmResponse
		 */
		public static LlmResponse success(String text, int tokens, long timeMs) {
			return new LlmResponse(true, text, null, tokens, timeMs);
		}

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
	}

	/**
	 * Callback for streaming responses
	 */
	interface StreamCallback {
		/**
		 * Called when generation is complete.
		 *
		 * @param response
		 *            the complete response
		 */
		void onComplete(LlmResponse response);

		/**
		 * Called when an error occurs.
		 *
		 * @param error
		 *            the error message
		 */
		void onError(String error);

		/**
		 * Called when a token is received.
		 *
		 * @param token
		 *            the generated token
		 */
		void onToken(String token);
	}

	/**
	 * What a backend is given to compose a system prompt in {@link LlmBackend#getSystemPrompt}.
	 *
	 * <p>
	 * The consumer supplies the tool contract; the backend supplies the wording. That split matters: the consumer is the side that parses the model's reply, so a backend that invents its own call syntax produces output nothing reads back -- and it fails silently, as a model that answers in prose rather than calling a tool.
	 */
	class SystemPromptRequest {
		/** The tools the consumer will accept calls for, in the order to present them. Never null, never mutable. */
		public final List<ToolDefinition> tools;

		/** The exact envelope the consumer parses back, to be reproduced verbatim in the prompt. */
		public final String toolCallSyntax;

		/**
		 * A real path from the user's project for the prompt's examples, so they imply no layout or language the project does not have.
		 */
		public final String exampleFilePath;

		/**
		 * Creates a system prompt request.
		 *
		 * @param tools
		 *            the tools to present to the model; copied, so later edits to the caller's list do not reach the request
		 * @param toolCallSyntax
		 *            the call envelope the consumer parses
		 * @param exampleFilePath
		 *            a real project path to use in examples, or null when the project has no file to point at
		 */
		public SystemPromptRequest(@Nullable List<ToolDefinition> tools, @NonNull String toolCallSyntax,
				@Nullable String exampleFilePath) {
			this.tools = tools == null
					? Collections.emptyList()
					: Collections.unmodifiableList(new ArrayList<>(tools));
			this.toolCallSyntax = Objects.requireNonNull(toolCallSyntax, "toolCallSyntax must not be null");
			this.exampleFilePath = exampleFilePath;
		}
	}

	/**
	 * A tool call request made by the LLM. Represents the LLM's request to invoke a tool with specific arguments.
	 *
	 * <p>
	 * The backend that reports the call owns the instance; the consumer receiving it in {@link ToolStreamCallback#onToolCall} must treat it, and {@link #args}, as read-only. The fields are not final only because this type shipped before the capability contract did -- see {@code docs/plugin-api.md} on breaking changes.
	 */
	class ToolCallRequest {
		/** Identifier correlating this call with the result the consumer sends back */
		public String callId;

		/** Name of the tool to invoke; matches a {@link ToolDefinition#name} the consumer offered */
		public String name;

		/** Arguments the model supplied, keyed by parameter name. Held by reference, so callers must not mutate it after publishing the call. */
		public Map<String, Object> args;

		/**
		 * Creates a tool call request.
		 *
		 * @param callId
		 *            the identifier correlating this call with its result
		 * @param name
		 *            the name of the tool to invoke
		 * @param args
		 *            the arguments the model supplied; stored by reference, not copied
		 */
		public ToolCallRequest(@NonNull String callId, @NonNull String name, @Nullable Map<String, Object> args) {
			this.callId = callId;
			this.name = name;
			this.args = args;
		}
	}

	/**
	 * Tool definition for structured function calling. Defines a tool that the LLM can invoke.
	 *
	 * <p>
	 * The consumer composing the request owns the instance; a backend given one in {@link SystemPromptRequest#tools} must treat it, and {@link #parametersSchema}, as read-only. Mutating either after the prompt is composed makes the consumer parse replies against a schema it no longer offered. The fields are not final only because this type shipped before the capability contract did -- see {@code docs/plugin-api.md} on breaking changes.
	 */
	class ToolDefinition {
		/** The name the model must use to call this tool */
		public String name;

		/** What the tool does, in wording meant for the model rather than the user */
		public String description;

		/** JSON-schema-shaped description of the parameters. Held by reference, so callers must not mutate it after publishing the definition. */
		public Map<String, Object> parametersSchema;

		/**
		 * Creates a tool definition.
		 *
		 * @param name
		 *            the name the model must use to call the tool
		 * @param description
		 *            what the tool does
		 * @param parametersSchema
		 *            the parameter schema; stored by reference, not copied
		 */
		public ToolDefinition(@NonNull String name, @NonNull String description,
				@Nullable Map<String, Object> parametersSchema) {
			this.name = name;
			this.description = description;
			this.parametersSchema = parametersSchema;
		}
	}

	/**
	 * Callback for streaming responses with tool calling support. Handles tokens, tool calls, completion, and errors.
	 */
	interface ToolStreamCallback {
		/**
		 * Called when generation is complete.
		 *
		 * @param response
		 *            the complete response
		 */
		void onComplete(LlmResponse response);

		/**
		 * Called when an error occurs.
		 *
		 * @param error
		 *            the error message
		 */
		void onError(String error);

		/**
		 * Called when a text token is received.
		 *
		 * @param token
		 *            the generated token
		 */
		void onToken(String token);

		/**
		 * Called when the LLM makes a tool call. The consumer runs the tool and reports the result back, correlating it by {@link ToolCallRequest#callId}.
		 *
		 * @param request
		 *            the tool the model wants called, and the arguments it supplied
		 */
		void onToolCall(ToolCallRequest request);
	}
}
