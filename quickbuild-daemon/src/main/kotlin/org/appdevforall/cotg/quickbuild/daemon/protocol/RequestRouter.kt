package org.appdevforall.cotg.quickbuild.daemon.protocol

/**
 * The build ops the daemon serves. Implementations report tool failures as ok:false
 * responses; an exception that escapes anyway is caught by [RequestRouter] and turned
 * into an ok:false response so a build problem can never kill the process (the README
 * contract: the daemon exits only on shutdown, EOF, or a fatal internal error).
 */
interface DaemonHandlers {
	fun configure(request: ConfigureRequest): DaemonResponse

	fun compile(request: CompileRequest): DaemonResponse

	fun dex(request: DexRequest): DaemonResponse

	fun relink(request: RelinkRequest): DaemonResponse
}

/**
 * Routes a parsed request to its handler. Pure logic - no IO - so routing and the
 * exception backstop unit-test with scripted fakes.
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

	fun route(request: DaemonRequest): Routed =
		when (request) {
			is ShutdownRequest -> Routed.ReplyThenExit(DaemonResponse.ok(request.id))
			is PingRequest -> Routed.Reply(DaemonResponse.ok(request.id))
			is ConfigureRequest -> Routed.Reply(guarded(request.id) { handlers.configure(request) })
			is CompileRequest -> Routed.Reply(guarded(request.id) { handlers.compile(request) })
			is DexRequest -> Routed.Reply(guarded(request.id) { handlers.dex(request) })
			is RelinkRequest -> Routed.Reply(guarded(request.id) { handlers.relink(request) })
		}

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
