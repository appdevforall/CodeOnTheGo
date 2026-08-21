package org.appdevforall.cotg.quickbuild.daemon

import org.appdevforall.cotg.quickbuild.daemon.protocol.ProtocolCodec
import org.appdevforall.cotg.quickbuild.daemon.protocol.RequestRouter
import org.appdevforall.cotg.quickbuild.protocol.DaemonResponse
import org.appdevforall.cotg.quickbuild.protocol.ParseResult
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.io.PrintStream
import java.io.Writer
import java.nio.charset.StandardCharsets

/**
 * Daemon entry point for the line-delimited JSON protocol. main() keeps the real stdout for
 * responses and redirects System.out to stderr, since the in-process Kotlin compiler's own prints
 * would otherwise corrupt the protocol stream.
 *
 * Exit contract (quickbuild/README.md): build errors never exit, `shutdown` or stdin EOF exit 0,
 * only a fatal internal error exits non-zero. The compiler runs in this JVM, so its own
 * [OutOfMemoryError] and [StackOverflowError] are build errors - see [RequestRouter.isRequestFailure].
 */
object DaemonMain {
	/**
	 * Wires the process to the protocol streams and serves until shutdown or EOF.
	 *
	 * @param args ignored - the daemon is configured over the protocol, not the command line,
	 *   so a launcher need pass nothing.
	 */
	@JvmStatic
	fun main(args: Array<String>) {
		val protocolOut =
			BufferedWriter(OutputStreamWriter(FileOutputStream(FileDescriptor.out), StandardCharsets.UTF_8))
		System.setOut(PrintStream(FileOutputStream(FileDescriptor.err), true, "UTF-8"))

		logErr("started (pid=${ProcessHandle.current().pid()})")
		val service = DaemonService()
		serve(
			input = System.`in`.bufferedReader(StandardCharsets.UTF_8),
			output = protocolOut,
			router = RequestRouter(service),
		)
		// The session's tools outlive the request loop, so release them here rather than
		// leaving it to process teardown.
		service.shutdown()
		logErr("exiting")
	}

	/**
	 * Runs the request/response loop until shutdown or EOF; malformed input replies ok:false
	 * and keeps serving. Separated from process wiring so it unit-tests against in-memory
	 * streams. Single-threaded on purpose - the CoGo orchestrator serializes requests.
	 *
	 * @param input one request per line, UTF-8; a null read (EOF) ends the loop, and it is not
	 *   closed here.
	 * @param output receives one encoded response line per request, flushed after each; must be
	 *   the real stdout, never the redirected [System.out].
	 * @param router dispatches each parsed request; its [RequestRouter.Routed.ReplyThenExit]
	 *   result is what ends the loop on `shutdown`.
	 */
	fun serve(
		input: BufferedReader,
		output: Writer,
		router: RequestRouter,
	) {
		while (true) {
			val line = input.readLine() ?: return
			if (line.isBlank()) continue

			// The router guards the handlers, but parse and encode run outside it, and both
			// work on request-sized data: a pathological line, or a response carrying a
			// compile's whole changed-class list. An uncaught throw from either would leave the
			// loop and exit the JVM, which CoGo reads as daemon death - a restart cycle on every
			// save of the same file, with no diagnostic ever rendered.
			var routed: RequestRouter.Routed? = null
			val encoded =
				try {
					routed = route(line, router)
					ProtocolCodec.encode(routed.response)
				} catch (t: Throwable) {
					if (!RequestRouter.isRequestFailure(t)) throw t
					// Allocation-light on purpose: the OOM arm gets here with the failed work's
					// garbage already unreachable, and this response is a few hundred bytes.
					// The id is the request's own when only the encode failed, and the codec's
					// unknown-id sentinel when the line never parsed.
					logErr("request failed: ${t.javaClass.simpleName}")
					ProtocolCodec.encode(
						DaemonResponse.failure(
							routed?.response?.id ?: ParseResult.Malformed.UNKNOWN_ID,
							RequestRouter.describe(t),
						),
					)
				}

			output.write(encoded)
			output.write("\n")
			output.flush()

			if (routed is RequestRouter.Routed.ReplyThenExit) return
		}
	}

	/**
	 * Parses one line and routes it, or answers a line the codec rejected.
	 *
	 * @param line one request, already known to be non-blank.
	 * @param router dispatches the parsed request.
	 * @return what to reply, and whether to keep serving afterwards.
	 */
	private fun route(
		line: String,
		router: RequestRouter,
	): RequestRouter.Routed =
		when (val parsed = ProtocolCodec.parse(line)) {
			is ParseResult.Malformed -> {
				logErr("malformed request: ${parsed.message}")
				RequestRouter.Routed.Reply(
					DaemonResponse.failure(parsed.id, "malformed request: ${parsed.message}"),
				)
			}

			is ParseResult.Parsed -> {
				router.route(parsed.request)
			}
		}

	private fun logErr(message: String) {
		System.err.println("[quickbuild-daemon] $message")
	}
}
