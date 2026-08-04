package org.appdevforall.cotg.quickbuild.daemon.protocol

/**
 * The build ops the daemon serves. Implementations report tool failures as ok:false responses;
 * an exception that escapes anyway is caught by [RequestRouter], so a build problem can never
 * kill the process (the daemon exits only on shutdown, EOF, or a fatal internal error).
 */
interface DaemonHandlers {
	/** Builds the session state - toolchain, classpath snapshots - that the other ops reuse. */
	fun configure(request: ConfigureRequest): DaemonResponse

	/** Compiles the requested sources and reports which class outputs changed. */
	fun compile(request: CompileRequest): DaemonResponse

	/** Dexes the requested class dirs into a single `classes.dex`. */
	fun dex(request: DexRequest): DaemonResponse

	/** Rebuilds the resource apk from the project's resources. */
	fun relink(request: RelinkRequest): DaemonResponse
}

/**
 * Routes a parsed request to its handler and keeps handler exceptions from escaping. Pure
 * logic, no IO, so routing and the exception backstop unit-test with scripted fakes.
 */
class RequestRouter(
	private val handlers: DaemonHandlers,
) {
	/** What the main loop should do with the routed result. */
	sealed interface Routed {
		val response: DaemonResponse

		data class Reply(
			override val response: DaemonResponse,
		) : Routed

		/** Reply, then exit the process cleanly (shutdown op). */
		data class ReplyThenExit(
			override val response: DaemonResponse,
		) : Routed
	}

	/** Dispatches [request] to its handler; ping and shutdown are answered here directly. */
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

	/** Turns any exception from a handler into an ok:false response. */
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
