package com.itsaky.androidide.plugins.services;

import androidx.annotation.NonNull;
import java.util.List;

/**
 * Tells a plugin where the SQLite debugger servers of the open project's apps are listening, so a plugin such as an MCP client can connect without the user typing a port or token.
 *
 * <p>
 * What a plugin gets: the token is the only credential a SQLite debugger server checks, so a plugin with this service's permission can query the databases of the open project's apps through it. The server opens them read-only in this version; a later version may allow writes. Declare the permission only if the plugin needs that access.
 *
 * <p>
 * The host registers this service for every plugin; a call needs the {@link com.itsaky.androidide.plugins.PluginPermission#DEBUGGER_ENDPOINTS} permission and throws {@link SecurityException} without it, like the other permission-gated services. Plugins run in Code on the Go's process, so the permission declares what a plugin means to do: it cannot stop a plugin that reaches into the host by reflection, nor one that takes another plugin's service from the public {@code PluginManager.getInstance().getLoadedPlugin(id).context.services} or {@code PluginFragmentHelper.getServiceRegistry} (tracked in ADFA-7194).
 *
 * <p>
 * Calls are synchronous, as in {@link IdeLogService} and {@link IdeProjectService}, rather than returning a {@link java.util.concurrent.CompletableFuture} as the services that start long work do: the answer is a small read of the IDE's own database, not an operation with a lifecycle. That read is disk I/O, so call from a background thread: on the main thread the call throws {@link IllegalStateException}. This is stricter than those two services on purpose: they answer on any thread, but a database read on the main thread can stall the UI.
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
	 * @return the endpoints, in a list the caller cannot modify (never null; empty when no project is open in the editor, as on the home screen, or no app has reported)
	 * @throws SecurityException
	 *             if the plugin lacks the {@code debugger.endpoints} permission
	 * @throws IllegalStateException
	 *             if called on the main thread, if the IDE's database could not be read, or if the IDE could not set up access to the endpoints when it started (its log says why). A failed read is not the same as no apps: keep what you had and try again later
	 * @throws java.util.concurrent.CancellationException
	 *             if the plugin has been disabled or unloaded, until it is enabled again; also when a call already reading sees it disabled, or when the read itself is cancelled
	 * @throws InterruptedException
	 *             if the calling thread is interrupted while it waits for the read. The thread's interrupt status is set again before this is thrown, so code further up still sees it
	 */
	@NonNull
	List<DebuggerEndpoint> getEndpoints() throws InterruptedException;
}
