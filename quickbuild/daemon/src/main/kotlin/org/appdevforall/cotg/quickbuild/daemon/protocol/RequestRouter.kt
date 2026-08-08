package org.appdevforall.cotg.quickbuild.daemon.protocol

/**
 * The build ops the daemon serves. Implementations report tool failures as ok:false responses;
 * an exception that escapes anyway is caught by [RequestRouter], so a build problem can never
 * kill the process (the daemon exits only on shutdown, EOF, or a fatal internal error).
 */
interface DaemonHandlers {
	/**
	 * Builds the session state - toolchain, classpath snapshots - that the other ops reuse.
	 *
	 * @param request the session inputs; unset tool paths are discovered by the implementation.
	 * @return the response to write back, ok:false when a tool or input file is missing.
	 */
	fun configure(request: ConfigureRequest): DaemonResponse

	/**
	 * Compiles the requested sources and reports which class outputs changed.
	 *
	 * @param request the full source list plus this edit's changed and removed files.
	 * @return the response to write back, ok:false carrying diagnostics on a compile error.
	 */
	fun compile(request: CompileRequest): DaemonResponse

	/**
	 * Dexes the requested class dirs into a single `classes.dex`.
	 *
	 * @param request the class-output roots to dex, in precedence order.
	 * @return the response to write back, ok:false when d8 fails or emits no dex.
	 */
	fun dex(request: DexRequest): DaemonResponse

	/**
	 * Rebuilds the resource apk from the project's resources.
	 *
	 * @param request the res dirs, manifest, and the optional stable-ids and library inputs.
	 * @return the response to write back, ok:false carrying aapt2's diagnostics on failure.
	 */
	fun relink(request: RelinkRequest): DaemonResponse
}

/**
 * Routes a parsed request to its handler and keeps handler exceptions from escaping. Pure
 * logic, no IO, so routing and the exception backstop unit-test with scripted fakes.
 *
 * @property handlers the build ops; `ping` and `shutdown` never reach it, and anything it throws
 *   is converted to an ok:false response rather than propagated.
 */
class RequestRouter(
	private val handlers: DaemonHandlers,
) {
	/** What the main loop should do with the routed result. */
	sealed interface Routed {
		val response: DaemonResponse

		/**
		 * Reply and keep serving - the ordinary case.
		 *
		 * @property response the line to write back before reading the next request.
		 */
		data class Reply(
			override val response: DaemonResponse,
		) : Routed

		/**
		 * Reply, then exit the process cleanly (shutdown op).
		 *
		 * @property response must still be written and flushed before the loop returns.
		 */
		data class ReplyThenExit(
			override val response: DaemonResponse,
		) : Routed
	}

	/**
	 * Dispatches [request] to its handler; ping and shutdown are answered here directly.
	 *
	 * @param request an already-parsed request; malformed input never gets this far.
	 * @return [Routed.ReplyThenExit] only for `shutdown`, [Routed.Reply] for everything else.
	 */
	fun route(request: DaemonRequest): Routed =
		when (request) {
			is ShutdownRequest -> {
				Routed.ReplyThenExit(DaemonResponse.ok(request.id))
			}

			is PingRequest -> {
				Routed.Reply(
					DaemonResponse.ok(request.id, mapOf("protocolVersion" to DaemonResponse.PROTOCOL_VERSION)),
				)
			}

			is ConfigureRequest -> {
				Routed.Reply(guarded(request.id) { handlers.configure(request) })
			}

			is CompileRequest -> {
				Routed.Reply(guarded(request.id) { handlers.compile(request) })
			}

			is DexRequest -> {
				Routed.Reply(guarded(request.id) { handlers.dex(request) })
			}

			is RelinkRequest -> {
				Routed.Reply(guarded(request.id) { handlers.relink(request) })
			}
		}

	/**
	 * Turns any exception from a handler into an ok:false response.
	 *
	 * @param id the request id to echo, so a failed call is still correlatable by the caller.
	 * @param body the handler call to run; may throw anything short of an [Error].
	 * @return the handler's own response, or a synthesized failure naming the exception class.
	 */
	private inline fun guarded(
		id: Long,
		body: () -> DaemonResponse,
	): DaemonResponse =
		try {
			body()
		} catch (e: Exception) {
			DaemonResponse.failure(id, "internal: ${e.javaClass.simpleName}: ${e.message}")
		}
}
