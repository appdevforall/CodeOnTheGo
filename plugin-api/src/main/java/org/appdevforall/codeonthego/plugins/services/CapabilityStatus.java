package org.appdevforall.codeonthego.plugins.services;

/**
 * Whether something the agent depends on can be used right now: a {@link LlmInferenceService.StatusReportingBackend}, a {@link ToolSourceRegistry.StatusReportingToolSource} or one of its {@link ToolSourceRegistry.ToolGroup}s.
 *
 * <p>
 * One enum for both contracts, so a consumer that shows backends and tool sources side by side reads one vocabulary. More states may be added later, so a consumer must handle a constant it does not know; treating it as {@link #DEGRADED} is the safe reading.
 */
public enum CapabilityStatus {
	/** Working, or not checked by its provider: a request or tool call is expected to go through. */
	AVAILABLE,
	/** Configured and being checked or connected; not failed, but a request may not go through yet. */
	CONNECTING,
	/** Configured but not working -- unreachable, refusing, misconfigured. It keeps its place so the user sees what is broken. */
	DEGRADED
}
