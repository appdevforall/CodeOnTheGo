package org.appdevforall.cotg.quickbuild.daemon

import com.google.common.truth.Truth.assertThat
import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.assertTimeoutPreemptively
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.File
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

	/**
	 * In-process, the redirect is all that can be seen: main() captures the real stdout BEFORE
	 * redirecting System.out, so both ends live in this same JVM and writing responses to the
	 * redirected System.out instead - the mutation the DaemonMain KDoc warns about - looks
	 * identical from here. It is not: responses would land on stderr and CoGo would read an
	 * empty protocol stream. Only a child process can tell the two file descriptors apart.
	 */
	@Test
	fun `responses reach the process stdout, never the redirected System out`() {
		val java = File(File(System.getProperty("java.home"), "bin"), "java")
		val process =
			ProcessBuilder(
				java.absolutePath,
				"-cp",
				System.getProperty("java.class.path"),
				DaemonMain::class.java.name,
			).start()

		try {
			assertTimeoutPreemptively(Duration.ofSeconds(60)) {
				process.outputStream.writer(Charsets.UTF_8).use { it.write("""{"id": 7, "op": "shutdown"}""" + "\n") }
				val stdout = process.inputStream.readBytes().toString(Charsets.UTF_8)
				val stderr = process.errorStream.readBytes().toString(Charsets.UTF_8)

				assertThat(process.waitFor()).isEqualTo(0)
				// One line on stdout and it IS the response: nothing else may share the stream,
				// and an EMPTY stdout is the redirect-swallowed-it failure this test exists for.
				val lines = stdout.lines().filter { it.isNotBlank() }
				assertThat(lines).hasSize(1)
				val response = JsonParser.parseString(lines.single()).asJsonObject
				assertThat(response.get("id").asLong).isEqualTo(7)
				assertThat(response.get("ok").asBoolean).isTrue()
				// The daemon's own logging went the other way, where it cannot corrupt anything.
				assertThat(stderr).contains("[quickbuild-daemon] started")
			}
		} finally {
			process.destroyForcibly()
		}
	}
}
