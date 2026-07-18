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
 * Daemon entry point: line-delimited JSON over stdin/stdout, one request in flight at a
 * time (the CoGo orchestrator serializes; the loop is deliberately single-threaded).
 *
 * Stdout is protocol-only. main() captures the real stdout for responses and redirects
 * System.out to stderr - the in-process Kotlin compiler and other tooling occasionally
 * print to stdout, and a single stray line would corrupt the protocol stream.
 *
 * Exit contract (quick-build/README.md): build errors never exit; `shutdown` or stdin
 * EOF exit 0; only a fatal internal error exits non-zero (CoGo treats that as daemon
 * death and respawns).
 */
object DaemonMain {
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
	 * The request/response loop, separated from process wiring so it unit-tests against
	 * in-memory streams. Returns on shutdown or EOF; malformed input replies ok:false
	 * and keeps serving.
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
					is ParseResult.Parsed -> router.route(parsed.request)
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
