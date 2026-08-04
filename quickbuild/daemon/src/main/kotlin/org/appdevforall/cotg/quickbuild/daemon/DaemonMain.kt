package org.appdevforall.cotg.quickbuild.daemon

import org.appdevforall.cotg.quickbuild.daemon.protocol.DaemonResponse
import org.appdevforall.cotg.quickbuild.daemon.protocol.ParseResult
import org.appdevforall.cotg.quickbuild.daemon.protocol.ProtocolCodec
import org.appdevforall.cotg.quickbuild.daemon.protocol.RequestRouter
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.io.PrintStream
import java.io.Writer
import java.nio.charset.StandardCharsets

/**
 * Daemon entry point: serves line-delimited JSON over stdin/stdout, one request at a time.
 *
 * Stdout is protocol-only. main() captures the real stdout for responses and redirects
 * System.out to stderr, because the in-process Kotlin compiler and other tooling print to
 * stdout and one stray line would corrupt the protocol stream.
 *
 * Exit contract (quickbuild/core/README.md): build errors never exit; `shutdown` or stdin EOF
 * exit 0; only a fatal internal error exits non-zero, which CoGo treats as daemon death.
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
		serve(
			input = System.`in`.bufferedReader(StandardCharsets.UTF_8),
			output = protocolOut,
			router = RequestRouter(DaemonService()),
		)
		logErr("exiting")
	}

	/**
	 * Runs the request/response loop until shutdown or EOF; malformed input replies ok:false
	 * and keeps serving. Separated from process wiring so it unit-tests against in-memory
	 * streams. Single-threaded on purpose - the CoGo orchestrator serializes requests.
	 *
	 * @param input one request per line, UTF-8; a null read (EOF) ends the loop. Not closed here.
	 * @param output receives one encoded response line per request, flushed after each. Must be
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

			val routed =
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

			output.write(ProtocolCodec.encode(routed.response))
			output.write("\n")
			output.flush()

			if (routed is RequestRouter.Routed.ReplyThenExit) return
		}
	}

	private fun logErr(message: String) {
		System.err.println("[quickbuild-daemon] $message")
	}
}
