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
 *
 * <p>
 * {@link LlmBackend} is the one type here that plugins <em>implement</em> rather than call, so it carries only what every backend can answer. Anything a backend may or may not do is a separate interface extending it -- {@link HistoryCapableBackend}, {@link ToolCallingBackend}, {@link CancellableBackend}, {@link ConfigurableBackend} -- and the consumer asks with {@code instanceof} before it calls. A capability is therefore declared by the type, not by a flag a backend can set inconsistently with the methods it overrode.
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
	interface CancellableBackend extends LlmBackend {
		/**
		 * Cancels the streaming generation currently in flight, if any.
		 */
		void cancelStreaming();
	}

	/**
	 * One turn of a conversation: what the user asked, what the model answered, or what a tool returned.
	 */
	class ChatMessage {
		/**
		 * Creates the message that carries a tool's output back into the next turn.
		 *
		 * <p>
		 * This is the return path for {@link ToolStreamCallback#onToolCall}: the consumer runs the tool, wraps the outcome here, and appends it to the history of the following request. Both correlators travel with it because providers key results differently -- by call id, or by function name -- and a backend can only forward what it was given.
		 *
		 * @param toolCallId
		 *            the {@link ToolCallRequest#callId} this result answers
		 * @param toolName
		 *            the {@link ToolCallRequest#name} that was invoked
		 * @param content
		 *            the tool's output, already rendered as text
		 * @return a message with role {@link Role#TOOL}
		 */
		@NonNull
		public static ChatMessage toolResult(@NonNull String toolCallId, @NonNull String toolName, @NonNull String content) {
			return new ChatMessage(
					Role.TOOL,
					Objects.requireNonNull(content, "content must not be null"),
					Objects.requireNonNull(toolCallId, "toolCallId must not be null"),
					Objects.requireNonNull(toolName, "toolName must not be null"));
		}

		/** The role of the message sender */
		@NonNull
		public final Role role;

		/** The text content of the message */
		@NonNull
		public final String content;

		/** The call this message answers; non-null exactly when {@link #role} is {@link Role#TOOL}. */
		@Nullable
		public final String toolCallId;

		/** The tool this message answers for; non-null exactly when {@link #role} is {@link Role#TOOL}. */
		@Nullable
		public final String toolName;

		/**
		 * Creates a chat message from a conversation participant.
		 *
		 * @param role
		 *            the role of the sender; not {@link Role#TOOL}, which needs the correlators only {@link #toolResult} supplies
		 * @param content
		 *            the message content
		 * @throws IllegalArgumentException
		 *             if role is {@link Role#TOOL}
		 */
		public ChatMessage(@NonNull Role role, @NonNull String content) {
			if (role == Role.TOOL) {
				throw new IllegalArgumentException("A TOOL message must be built with ChatMessage.toolResult(...)");
			}
			this.role = Objects.requireNonNull(role, "role must not be null");
			this.content = Objects.requireNonNull(content, "content must not be null");
			this.toolCallId = null;
			this.toolName = null;
		}

		private ChatMessage(@NonNull Role role, @NonNull String content, @NonNull String toolCallId, @NonNull String toolName) {
			this.role = role;
			this.content = content;
			this.toolCallId = toolCallId;
			this.toolName = toolName;
		}

		/** Role of the message sender */
		public enum Role {
			USER, ASSISTANT, SYSTEM, TOOL
		}
	}

	/**
	 * An {@link LlmBackend} that draws its own settings screen. Kept apart from {@code LlmBackend} so that running inference stays independent of presenting a UI: a backend with nothing to configure implements nothing, and the consumer asks with {@code instanceof} before it draws.
	 */
	interface ConfigurableBackend extends LlmBackend {
		/**
		 * Gets the fully-qualified name of the {@code Fragment} this backend contributes to draw its settings. The class must live in the backend's own plugin and declare a public no-argument constructor; the consumer loads it with the backend's classloader and mounts it wherever it presents backend settings. The name is passed as a string so this contract stays free of any dependency on Android UI types.
		 *
		 * <p>
		 * The backend owns the screen outright -- including where each value is stored, which is why nothing here describes a field or a store. A consumer cannot prefill or write a backend's settings; it can only mount them.
		 *
		 * @return the fragment class name (never null)
		 */
		@NonNull
		String getSettingsFragmentClassName();
	}

	/**
	 * An {@link LlmBackend} that renders earlier turns of a conversation.
	 *
	 * <p>
	 * Implementing this is the declaration: a backend that can only prompt single-turn does not implement it, and the consumer calls {@link LlmBackend#generateStreaming} instead of silently losing the conversation -- which reads to the user as a model that cannot follow one.
	 */
	interface HistoryCapableBackend extends LlmBackend {
		/**
		 * Generates a streaming reply for a multi-turn conversation.
		 *
		 * @param history
		 *            the conversation history
		 * @param prompt
		 *            the current prompt
		 * @param config
		 *            the generation configuration
		 * @param callback
		 *            the callback to receive tokens and completion events
		 */
		void generateStreamingWithHistory(
				@NonNull List<ChatMessage> history,
				@NonNull String prompt,
				@NonNull LlmConfig config,
				@NonNull StreamCallback callback);
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
		@NonNull
		CompletableFuture<LlmResponse> generate(@NonNull String prompt, @NonNull LlmConfig config);

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
		void generateStreaming(@NonNull String prompt, @NonNull LlmConfig config, @NonNull StreamCallback callback);

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
		@NonNull
		CompletableFuture<LlmResponse> generateWithHistory(
				@NonNull List<ChatMessage> history,
				@NonNull String prompt,
				@NonNull LlmConfig config);

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
		@NonNull
		String getId();

		/**
		 * Gets the human-readable name of this backend.
		 *
		 * @return the backend name
		 */
		@NonNull
		String getName();

		/**
		 * Gets the system prompt to send with every request to this backend, or null to accept the consumer's own.
		 *
		 * <p>
		 * Prompt wording is model-specific -- how much autonomy a model handles, how literally it copies an example -- so it belongs with the backend that knows the model, not with the consumer that knows the tools. The consumer still owns the call syntax: reproduce {@link SystemPromptRequest#toolCallSyntax} verbatim when it is present, or the replies this prompt produces will not parse.
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
		@NonNull
		public static LlmResponse failure(@NonNull String error) {
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
		@NonNull
		public static LlmResponse success(@NonNull String text, int tokens, long timeMs) {
			return new LlmResponse(true, text, null, tokens, timeMs);
		}

		/** Whether the generation was successful */
		public final boolean success;

		/** Generated text (null if not successful) */
		@Nullable
		public final String text;

		/** Error message (null if successful) */
		@Nullable
		public final String error;

		/** Number of tokens generated in the response */
		public final int tokensGenerated;

		/** Time taken to generate the response in milliseconds */
		public final long timeMs;

		public LlmResponse(boolean success, @Nullable String text, @Nullable String error,
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
		/** The tools the consumer will accept calls for, in the order to present them. Never null; empty when the conversation offers no tools. The list is unmodifiable, but only its spine is copied -- the {@link ToolDefinition}s in it are the consumer's, and a backend must not edit one. */
		@NonNull
		public final List<ToolDefinition> tools;

		/**
		 * The exact envelope the consumer parses back, to be reproduced verbatim in the prompt, or null when it parses none.
		 *
		 * <p>
		 * Null is the plain-chat case, and the case of a consumer driving {@link ToolCallingBackend} through a provider's own function calling: there is no text envelope, so a prompt must not instruct the model to emit one. Reproducing an empty envelope is the failure this type exists to prevent -- the model is told to call tools in a syntax nothing reads, and answers in prose instead.
		 */
		@Nullable
		public final String toolCallSyntax;

		/**
		 * A real path from the user's project for the prompt's examples, so they imply no layout or language the project does not have.
		 */
		@Nullable
		public final String exampleFilePath;

		/**
		 * Creates a system prompt request.
		 *
		 * @param tools
		 *            the tools to present to the model; copied, so later edits to the caller's list do not reach the request
		 * @param toolCallSyntax
		 *            the call envelope the consumer parses, or null when it parses none
		 * @param exampleFilePath
		 *            a real project path to use in examples, or null when the project has no file to point at
		 */
		public SystemPromptRequest(@Nullable List<ToolDefinition> tools, @Nullable String toolCallSyntax,
				@Nullable String exampleFilePath) {
			this.tools = tools == null
					? Collections.emptyList()
					: Collections.unmodifiableList(new ArrayList<>(tools));
			this.toolCallSyntax = toolCallSyntax;
			this.exampleFilePath = exampleFilePath;
		}
	}

	/**
	 * An {@link LlmBackend} that reports the model's tool calls as structured calls.
	 *
	 * <p>
	 * Implementing this is the declaration, and it means {@link ToolStreamCallback#onToolCall} will fire for a call the model makes. A backend that merely wants earlier turns implements {@link HistoryCapableBackend} instead: accepting tools and never calling one leaves the consumer waiting on an action the model was never able to take.
	 */
	interface ToolCallingBackend extends LlmBackend {
		/**
		 * Generates a completion with streaming output and tool calling support.
		 *
		 * @param prompt
		 *            the input prompt
		 * @param history
		 *            the conversation history, including any {@link ChatMessage#toolResult} from earlier turns (can be empty)
		 * @param config
		 *            the generation configuration
		 * @param tools
		 *            the available tools the LLM can call
		 * @param callback
		 *            the callback to receive tokens, tool calls and completion events
		 */
		void generateStreamingWithTools(
				@NonNull String prompt,
				@NonNull List<ChatMessage> history,
				@NonNull LlmConfig config,
				@NonNull List<ToolDefinition> tools,
				@NonNull ToolStreamCallback callback);
	}

	/**
	 * A tool call request made by the LLM. Represents the LLM's request to invoke a tool with specific arguments.
	 *
	 * <p>
	 * The backend that reports a call owns the instance; treat it as read-only once {@link ToolStreamCallback#onToolCall} has been given it. The fields are not final and {@link #args} is held by reference, because both shipped that way in 26.28 and tightening them would break an already-built plugin that assigns them. Rewriting one after the fact means the consumer runs a call the model did not make.
	 */
	class ToolCallRequest {
		/** Identifier correlating this call with the result the consumer sends back in {@link ChatMessage#toolResult} */
		@NonNull
		public String callId;

		/** Name of the tool to invoke; matches a {@link ToolDefinition#name} the consumer offered */
		@NonNull
		public String name;

		/** Arguments the model supplied, keyed by parameter name; null when the tool takes none */
		@Nullable
		public Map<String, Object> args;

		/**
		 * Creates a tool call request.
		 *
		 * @param callId
		 *            the identifier correlating this call with its result
		 * @param name
		 *            the name of the tool to invoke
		 * @param args
		 *            the arguments the model supplied, or null for none; held by reference, so do not edit the map afterwards
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
	 * The consumer that offers a tool owns the instance; a backend given one in {@link SystemPromptRequest#tools} must treat it as read-only. The fields are not final and {@link #parametersSchema} is held by reference, because both shipped that way in 26.28 and tightening them would break an already-built plugin that assigns them. Renaming a tool or emptying its schema after the prompt is composed leaves the consumer parsing replies against a contract it no longer offered -- and {@link SystemPromptRequest} copies only the list spine, so its copy points at these same instances.
	 */
	class ToolDefinition {
		/** The name the model must use to call this tool */
		@NonNull
		public String name;

		/** What the tool does, in wording meant for the model rather than the user */
		@NonNull
		public String description;

		/** JSON-schema-shaped description of the parameters; null when the tool takes none */
		@Nullable
		public Map<String, Object> parametersSchema;

		/**
		 * Creates a tool definition.
		 *
		 * @param name
		 *            the name the model must use to call the tool
		 * @param description
		 *            what the tool does
		 * @param parametersSchema
		 *            the parameter schema, or null when the tool takes no parameters; held by reference, so do not edit the map afterwards
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
		 * Called when the LLM makes a tool call. The consumer runs the tool and appends the outcome to the next request's history as a {@link ChatMessage#toolResult}, which carries {@link ToolCallRequest#callId} back so a turn's several calls are correlated by id rather than by position.
		 *
		 * @param request
		 *            the tool the model wants called, and the arguments it supplied
		 */
		void onToolCall(ToolCallRequest request);
	}
}
