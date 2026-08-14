package com.itsaky.androidide.plugins.services;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Registry through which plugins contribute tools to the IDE's AI agent.
 *
 * <p>
 * The registry itself is implemented by the plugin that owns the agent (ai-core) and published under this type in {@link SharedServices}; the host defines the contract only. A contributing plugin resolves the registry on {@code activate()}, registers a {@link ToolSource}, and unregisters on {@code deactivate()} -- the same lifecycle a model backend follows with {@link LlmInferenceService#registerBackend}. When the agent plugin is not installed the lookup returns null and a provider registers nothing, which is the same clean degradation the backends already rely on.
 *
 * <p>
 * Values crossing this boundary must be JDK types ({@code String}, {@code Boolean}, {@code Integer}, {@code Double}, {@code List}, {@code Map}). Each plugin is loaded by its own class loader with the host as parent, so a class packaged in one {@code .cgp} is not resolvable from another; only types loaded by the host -- this interface and the JDK -- are common ground. A type duplicated into each plugin instead compiles cleanly and then fails on device with {@code ClassCastException}, because each loader defines its own copy.
 *
 * <p>
 * Every member here is an interface rather than a value class on purpose: {@code plugin-api} is additive-only, and adding a property to a class removes the constructor signature already-published plugins were built against. Java {@code default} methods let this contract grow without touching an implementor. The cost is a small concrete class on each side.
 */
public interface ToolSourceRegistry {

	/**
	 * Contract revision. A source built against a newer revision than the agent understands is rejected rather than half-supported.
	 */
	int CONTRACT_VERSION = 1;

	/**
	 * Gets every registered source, in registration order.
	 *
	 * @return the registered sources (never null)
	 */
	@NonNull
	List<ToolSource> getToolSources();

	/**
	 * Signals that a provider's tool list has changed and must be read again -- an MCP server connected, a user toggled a tool off. The agent re-reads {@link ToolSource#listTools} and rebuilds whatever it derives from it.
	 *
	 * @param providerId
	 *            the {@link ToolSource#getProviderId} whose tools changed; unknown ids are ignored
	 */
	void notifyToolsChanged(@NonNull String providerId);

	/**
	 * Adds a source's tools to the agent, replacing any source already registered under the same {@link ToolSource#getProviderId}. Re-registration is how a provider recovers after the agent plugin restarts.
	 *
	 * @param source
	 *            the source to register (must not be null)
	 */
	void registerToolSource(@NonNull ToolSource source);

	/**
	 * Removes a source previously passed to {@link #registerToolSource}, matched by instance identity rather than by id -- a plugin can only remove the source object it holds, so one {@code .cgp} cannot unregister another's tools.
	 *
	 * @param source
	 *            the source to remove; a source that is not registered is ignored
	 */
	void unregisterToolSource(@NonNull ToolSource source);

	/**
	 * One call to a tool, constructed by the agent.
	 */
	interface ToolInvocation {

		/**
		 * Gets the arguments, keyed by schema property name.
		 *
		 * <p>
		 * The registry implementation must hand each source a copy holding JDK value types only ({@code String}, {@code Boolean}, {@code Integer}, {@code Double}, {@code List}, {@code Map}), recursively -- a value of any other type is rejected or coerced before the call is dispatched, never passed through. Two obligations follow from the class loader split: an object defined by the agent's loader is not resolvable from a source's, and a map shared across the boundary would let either side mutate what the other reads.
		 *
		 * @return the arguments (never null; empty when the tool takes none)
		 */
		@NonNull
		Map<String, Object> getArguments();

		/**
		 * Gets the identifier of this call for the lifetime of the run; the key for {@link ToolSource#cancel}.
		 *
		 * @return the call identifier (never null)
		 */
		@NonNull
		String getCallId();

		/**
		 * Gets the absolute path of the open project's root.
		 *
		 * @return the project root, or null when no project is open
		 */
		@Nullable
		default String getProjectRoot() {
			return null;
		}

		/**
		 * Gets the tool's own {@link ToolSpec#getName}, without the agent's namespace prefix.
		 *
		 * @return the tool name (never null)
		 */
		@NonNull
		String getToolName();
	}

	/**
	 * The result of one call.
	 *
	 * <p>
	 * A failing outcome must say why. When {@link #isSuccess} returns false, at least one of {@link #getErrorMessage} and {@link #getOutput} has to carry the detail -- the message for the user, the output for the model. Both is better; neither leaves the model with an unexplained refusal, which it retries.
	 */
	interface ToolOutcome {

		/**
		 * Gets one user-facing sentence explaining a failure.
		 *
		 * @return the error message, or null when {@link #isSuccess} is true or the failure is already explained by {@link #getOutput}
		 */
		@Nullable
		default String getErrorMessage() {
			return null;
		}

		/**
		 * Gets the result as text for the model. The agent truncates it, so put the answer first.
		 *
		 * @return the output (never null)
		 */
		@NonNull
		String getOutput();

		/**
		 * Checks whether the tool did what was asked. A false outcome is reported to the model.
		 *
		 * @return true if the call succeeded, false otherwise
		 */
		boolean isSuccess();
	}

	/**
	 * A plugin's contribution of one or more agent tools.
	 *
	 * <p>
	 * Implementations must not throw across this boundary: the agent treats a throwing source as absent, so a failing {@code .cgp} costs the user its tools rather than the whole agent.
	 */
	interface ToolSource {

		/**
		 * Best-effort cancellation of an in-flight {@link #invoke}, matched by {@link ToolInvocation#getCallId}. Called when the user stops the agent run.
		 *
		 * <p>
		 * Best-effort covers how much work is undone, not whether the future settles: the {@link CompletableFuture} that {@code invoke} returned must still reach a terminal state. Complete it exceptionally with a {@link java.util.concurrent.CancellationException} once the work stops, or normally if it had already finished when the cancel arrived. A future left pending strands the agent's continuation until its own timeout fires.
		 *
		 * @param callId
		 *            the call to cancel; unknown ids are ignored
		 */
		default void cancel(@NonNull String callId) {}

		/**
		 * Gets the human-readable source name, shown wherever tool provenance is surfaced.
		 *
		 * @return the display name (never null)
		 */
		@NonNull
		String getDisplayName();

		/**
		 * Gets this source's stable identity, conventionally the contributing plugin's {@code plugin.id}.
		 *
		 * @return the provider identifier (never null)
		 */
		@NonNull
		String getProviderId();

		/**
		 * Runs one tool. Must return promptly and complete the future off the caller's thread; the agent awaits it and never blocks the UI thread on it.
		 *
		 * @param invocation
		 *            the call to run (must not be null)
		 * @return a future that completes with the outcome (never null)
		 */
		@NonNull
		CompletableFuture<ToolOutcome> invoke(@NonNull ToolInvocation invocation);

		/**
		 * Gets the tools currently offered. Called on registration and after {@link ToolSourceRegistry#notifyToolsChanged}; must be cheap and must not block on the network.
		 *
		 * @return the tools this source offers (never null)
		 */
		@NonNull
		List<ToolSpec> listTools();
	}

	/**
	 * One tool a {@link ToolSource} offers.
	 */
	interface ToolSpec {

		/**
		 * Gets what the tool does, in one or two sentences -- this reaches the model's prompt.
		 *
		 * @return the description (never null)
		 */
		@NonNull
		String getDescription();

		/**
		 * Gets this tool's name, unique within its source. The agent namespaces it before exposing it to the model.
		 *
		 * @return the tool name (never null)
		 */
		@NonNull
		String getName();

		/**
		 * Gets the JSON schema for the arguments: a JSON Schema object -- {@code "type": "object"} with {@code "properties"} and {@code "required"} -- expressed in the JDK value types {@link ToolInvocation#getArguments} accepts, so it needs no conversion on the way to a model.
		 *
		 * @return the parameter schema; empty means untyped, flat string arguments, which is what the current tool-call protocol supports
		 */
		@NonNull
		default Map<String, Object> getParametersSchema() {
			return Collections.emptyMap();
		}

		/**
		 * Checks whether the tool is free of side effects, allowing the agent to run it concurrently.
		 *
		 * @return true if the tool only reads, false otherwise
		 */
		default boolean isReadOnly() {
			return false;
		}

		/**
		 * Checks whether the user must approve each call.
		 *
		 * <p>
		 * Defaults to true, inverted relative to the agent's own tools: those are contained by the agent's path guard before a handler runs, while a tool contributed by a third party -- or proxied from a remote server -- is contained by nothing. The safe default is to ask.
		 *
		 * @return true if each call needs user approval, false otherwise
		 */
		default boolean requiresApproval() {
			return true;
		}
	}
}
