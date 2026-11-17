package com.itsaky.androidide.plugins.services;

import androidx.annotation.NonNull;
import java.util.List;

/**
 * Tells a plugin where the SQLite debugger servers of the open project's apps are listening, so a plugin such as an MCP client can connect without the user typing a port or token.
 *
 * <p>
 * The host registers this service for every plugin; a call needs the {@link com.itsaky.androidide.plugins.PluginPermission#DEBUGGER_ENDPOINTS} permission and throws {@link SecurityException} without it, like the other permission-gated services.
 *
 * <p>
 * Calls are synchronous, as in {@link IdeLogService} and {@link IdeProjectService}, rather than returning a {@link java.util.concurrent.CompletableFuture} as the services that start long work do: the answer is a small read of the IDE's own database, not an operation with a lifecycle. That read is disk I/O, so call from a background thread: on the main thread the call throws {@link IllegalStateException}.
 */
public interface DebuggerEndpointService {

	/**
	 * Contract revision, bumped whenever a member is added here. It marks the revision and does not negotiate one; see {@link ToolSourceRegistry#CONTRACT_VERSION}.
	 */
	int CONTRACT_VERSION = 1;

	/**
	 * Gets the SQLite debugger endpoints of the project open in the editor, one per app that has reported. Read fresh on every call, so call it again after a connection fails: a relaunched app has a new port and token.
	 *
	 * <p>
	 * An app the IDE installed or launched but that has not yet reported is not listed, and neither is an app that has since been uninstalled. An endpoint can still be stale: when the app's process has died, its last reported port and token stay here until the app reports again, so treat {@link DebuggerEndpoint#getUpdatedAtMillis} (when it last reported) as a hint, and when a connection is refused, call this again and reconnect.
	 *
	 * @return the endpoints (never null; empty when no project is open in the editor, as on the home screen, or no app has reported)
	 * @throws SecurityException
	 *             if the plugin lacks the {@code debugger.endpoints} permission
	 * @throws IllegalStateException
	 *             if called on the main thread, or if the IDE's database could not be read. A failed read is not the same as no apps: keep what you had and try again later
	 */
	@NonNull
	List<DebuggerEndpoint> getEndpoints();
}
