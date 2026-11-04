package org.appdevforall.cotg.quickbuild.daemon.protocol

import org.appdevforall.cotg.quickbuild.protocol.CompileRequest
import org.appdevforall.cotg.quickbuild.protocol.ConfigureRequest
import org.appdevforall.cotg.quickbuild.protocol.DaemonRequest
import org.appdevforall.cotg.quickbuild.protocol.DaemonResponse
import org.appdevforall.cotg.quickbuild.protocol.DexRequest
import org.appdevforall.cotg.quickbuild.protocol.PingRequest
import org.appdevforall.cotg.quickbuild.protocol.RelinkRequest
import org.appdevforall.cotg.quickbuild.protocol.ResponseKeys
import org.appdevforall.cotg.quickbuild.protocol.ShutdownRequest

/**
 * The build ops the daemon serves. Implementations report tool failures as ok:false responses;
 * a throw that escapes anyway is caught by [RequestRouter] when it is a failure of the request
 * rather than of the process ([RequestRouter.isRequestFailure]), so a build problem can never
 * kill it (the daemon exits only on shutdown, EOF, or a fatal internal error).
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
					DaemonResponse.ok(request.id, mapOf(ResponseKeys.PROTOCOL_VERSION to DaemonResponse.PROTOCOL_VERSION)),
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
	 * Turns a handler failure into an ok:false response, including the two [Error]s the
	 * in-process compiler throws on the user's own source.
	 *
	 * @param id the request id to echo, so a failed call is still correlatable by the caller.
	 * @param body the handler call to run; a throw that [isRequestFailure] rejects propagates.
	 * @return the handler's own response, or a synthesized failure naming what went wrong.
	 */
	private inline fun guarded(
		id: Long,
		body: () -> DaemonResponse,
	): DaemonResponse =
		try {
			body()
		} catch (t: Throwable) {
			if (!isRequestFailure(t)) throw t
			DaemonResponse.failure(id, describe(t))
		}

	companion object {
		/**
		 * Text for an [OutOfMemoryError], pre-built so the failure path allocates no string.
		 *
		 * Catching an OOM and carrying on is only sound while the unwind allocates almost
		 * nothing: the compiler's own garbage is unreachable by the time this is read, so the
		 * small response below is affordable, and anything larger would not be.
		 */
		private const val OUT_OF_MEMORY =
			"the compiler ran out of memory on this change. Try a smaller edit, or restart the " +
				"Quick Build session for a fresh compiler."

		/** Text for a [StackOverflowError], pre-built for the same reason as [OUT_OF_MEMORY]. */
		private const val STACK_OVERFLOW =
			"the compiler ran out of stack on this change - an expression or type here nests too " +
				"deeply for it."

		/**
		 * Whether a throw is a failure of the requested work rather than a broken process.
		 *
		 * The compiler runs in this JVM, so an out-of-memory or a parser stack overflow is an
		 * outcome of compiling the user's source - a build error, which the exit contract
		 * (see `DaemonMain`) says must never exit. A `LinkageError` is a genuine internal fault
		 * and still exits, so the two are named rather than [Error] caught wholesale.
		 *
		 * @param t what escaped the handler.
		 * @return true to reply ok:false and keep serving, false to let it kill the process.
		 */
		fun isRequestFailure(t: Throwable): Boolean = t is Exception || t is OutOfMemoryError || t is StackOverflowError

		/**
		 * Renders a request failure as the one diagnostic the reply carries.
		 *
		 * @param t a throw [isRequestFailure] accepted.
		 * @return user-facing text for the two compiler [Error]s, else the exception's class
		 *   and message, which are for whoever reads the Build Output of an internal fault.
		 */
		fun describe(t: Throwable): String =
			when (t) {
				is OutOfMemoryError -> OUT_OF_MEMORY
				is StackOverflowError -> STACK_OVERFLOW
				else -> "internal: ${t.javaClass.simpleName}: ${t.message}"
			}
	}
}
