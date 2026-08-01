package org.appdevforall.cotg.quickbuild.daemon

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Assertions.assertTimeoutPreemptively
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.time.Duration

/**
 * The process entry point's exit and stream contracts (README): `shutdown` and stdin EOF
 * end the loop instead of hanging, and System.out gets redirected away from the protocol
 * stream before serving. The serve loop itself is covered stream-by-stream in
 * DaemonLoopTest; these run the real main() wiring around it.
 */
class DaemonMainTest {
	private fun runMain(stdin: String) {
		val originalIn = System.`in`
		val originalOut = System.out
		try {
			System.setIn(ByteArrayInputStream(stdin.toByteArray(Charsets.UTF_8)))
			// The exit contract is "returns", and the failure mode is "hangs forever
			// waiting on stdin" - so the assertion is a hard timeout around main().
			assertTimeoutPreemptively(Duration.ofSeconds(30)) { DaemonMain.main(emptyArray()) }
			// Stdout is protocol-only: anything the compiler prints via System.out must
			// have been redirected off the protocol stream.
			assertThat(System.out).isNotSameInstanceAs(originalOut)
		} finally {
			System.setIn(originalIn)
			System.setOut(originalOut)
		}
	}

	@Test
	fun `main serves until shutdown, then exits the loop`() {
		runMain("""{"id": 1, "op": "shutdown"}""" + "\n")
	}

	@Test
	fun `main exits cleanly on stdin EOF without any request`() {
		runMain("")
	}
}
