package org.appdevforall.cotg.quickbuild.daemon.res

import com.google.common.truth.Truth.assertThat
import com.google.gson.JsonParser
import org.appdevforall.cotg.quickbuild.daemon.protocol.ProtocolCodec
import org.appdevforall.cotg.quickbuild.protocol.DaemonResponse
import org.appdevforall.cotg.quickbuild.protocol.Diagnostic
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Aapt2Link's output verification and diagnostic parsing, driven by scripted fake aapt2
 * binaries: what happens when aapt2 exits 0 but produced garbage, and how its stderr
 * lines map to the protocol's diagnostics. No real toolchain needed - the fakes let these
 * run (and pin behavior) on any POSIX host.
 */
class Aapt2LinkEdgeTest {
	@TempDir
	lateinit var tempDir: File

	private lateinit var resDir: File
	private lateinit var manifest: File
	private lateinit var workDir: File

	@BeforeEach
	fun setUp() {
		resDir = File(tempDir, "res/values").apply { mkdirs() }.parentFile
		File(resDir, "values/strings.xml").writeText("<resources />")
		workDir = File(tempDir, "work").apply { mkdirs() }
		manifest = File(tempDir, "AndroidManifest.xml").apply { writeText("<manifest />") }
	}

	private fun fakeAapt2(script: String): File =
		File(tempDir, "fake-aapt2").apply {
			writeText("#!/bin/sh\n$script\n")
			check(setExecutable(true)) { "could not mark fake aapt2 executable" }
		}

	@Test
	fun `a second resource root fails the relink instead of overwriting silently`() {
		val second = File(tempDir, "res2/values").apply { mkdirs() }.parentFile
		File(second, "values/strings.xml").writeText("<resources />")
		// "exit 1" proves the guard runs before aapt2 does: if the check were missing this
		// would fail with an aapt2 compile diagnostic, not the message asserted below.
		val link = Aapt2Link(fakeAapt2("exit 1"), File(tempDir, "android.jar"))

		val result = link.relink(listOf(resDir, second), manifest, workDir)

		// aapt2 names each .flat after the resource's path within ITS root, so two roots
		// holding values/strings.xml write the same .flat and the last one silently wins.
		val diagnostics = (result as Aapt2Link.Result.Failed).diagnostics
		assertThat(diagnostics.single().severity).isEqualTo(Diagnostic.Severity.ERROR)
		assertThat(diagnostics.single().message).contains("one resource root, got 2")
	}

	@Test
	fun `link exiting 0 without producing an output fails instead of shipping nothing`() {
		val link = Aapt2Link(fakeAapt2("exit 0"), File(tempDir, "android.jar"))

		val result = link.relink(listOf(resDir), manifest, workDir)

		assertThat(result).isInstanceOf(Aapt2Link.Result.Failed::class.java)
		val diagnostics = (result as Aapt2Link.Result.Failed).diagnostics
		assertThat(diagnostics.single().severity).isEqualTo(Diagnostic.Severity.ERROR)
		assertThat(diagnostics.single().message).contains("no resources.arsc")
	}

	@Test
	fun `a linked apk without a resource table fails instead of shipping a broken payload`() {
		// The whole apk is the payload; an entry-less table means the runtime cannot load
		// it, so exit-0-with-garbage must fail loudly (class KDoc: malformed despite 0).
		val tableless = File(tempDir, "tableless.zip")
		ZipOutputStream(tableless.outputStream()).use { zip ->
			zip.putNextEntry(ZipEntry("res/dummy.txt"))
			zip.write("no table here".toByteArray())
			zip.closeEntry()
		}
		// The fake link copies the prepared no-arsc zip to aapt2's -o argument ($3).
		val script = "if [ \"\$1\" = \"link\" ]; then cp '${tableless.absolutePath}' \"\$3\"; fi\nexit 0"
		val link = Aapt2Link(fakeAapt2(script), File(tempDir, "android.jar"))

		val result = link.relink(listOf(resDir), manifest, workDir)

		assertThat(result).isInstanceOf(Aapt2Link.Result.Failed::class.java)
		assertThat((result as Aapt2Link.Result.Failed).diagnostics.single().message).contains("no resources.arsc")
	}

	@Test
	fun `warning-only aapt2 output gains a fallback error so a failure is never silent`() {
		val script =
			"echo 'res/values/strings.xml:4: warning: dubious value'\n" +
				"echo 'warning: general advice'\n" +
				"exit 1"
		val link = Aapt2Link(fakeAapt2(script), File(tempDir, "android.jar"))

		val result = link.relink(listOf(resDir), manifest, workDir)

		assertThat(result).isInstanceOf(Aapt2Link.Result.Failed::class.java)
		val diagnostics = (result as Aapt2Link.Result.Failed).diagnostics
		val located = diagnostics.single { it.severity == Diagnostic.Severity.WARNING && it.file != null }
		assertThat(located.file).isEqualTo("res/values/strings.xml")
		assertThat(located.line).isEqualTo(4)
		assertThat(located.message).isEqualTo("dubious value")
		val unlocated = diagnostics.single { it.severity == Diagnostic.Severity.WARNING && it.file == null }
		assertThat(unlocated.message).isEqualTo("general advice")
		// aapt2 failed but reported no ERROR line: the fallback must supply one, or the
		// client would render a "failed" response containing only warnings.
		val errors = diagnostics.filter { it.severity == Diagnostic.Severity.ERROR }
		assertThat(errors).hasSize(1)
		assertThat(errors.single().message).contains("aapt2 compile failed")
	}

	@Test
	fun `a diagnostic flood is capped with a marker naming how many were elided`() {
		// A broken resource pass can name every file in the project; the whole list rides
		// one protocol line into a phone-screen panel, so it is bounded the way DexTool's
		// output is (MAX_DIAGNOSTIC_CHARS) - first 50 entries plus a "+K more" marker.
		val script =
			"i=1\n" +
				"while [ \$i -le 60 ]; do\n" +
				"  echo \"res/values/strings.xml:\$i: error: boom \$i\"\n" +
				"  i=\$((i+1))\n" +
				"done\n" +
				"exit 1"
		val link = Aapt2Link(fakeAapt2(script), File(tempDir, "android.jar"))

		val result = link.relink(listOf(resDir), manifest, workDir)

		assertThat(result).isInstanceOf(Aapt2Link.Result.Failed::class.java)
		val diagnostics = (result as Aapt2Link.Result.Failed).diagnostics
		assertThat(diagnostics).hasSize(51)
		// The first parsed entries survive in order; the marker accounts for the rest.
		assertThat(diagnostics.first().message).isEqualTo("boom 1")
		assertThat(diagnostics[49].message).isEqualTo("boom 50")
		assertThat(diagnostics.last().message).isEqualTo("+10 more aapt2 diagnostics elided")
		assertThat(diagnostics.last().severity).isEqualTo(Diagnostic.Severity.ERROR)
	}

	@Test
	fun `an empty compiled dir that cannot be deleted does not fail the reset`() {
		// Only LEFTOVER ENTRIES can leak stale .flat files into the link. An empty
		// res-compiled that survives deleteRecursively (read-only parent) is harmless and
		// must fall through to the aapt2 run - whose own failure is then the result.
		File(workDir, "res-compiled").mkdirs()
		check(workDir.setWritable(false)) { "could not make work dir read-only" }
		try {
			val link = Aapt2Link(fakeAapt2("echo 'error: kaboom'\nexit 1"), File(tempDir, "android.jar"))

			val result = link.relink(listOf(resDir), manifest, workDir)

			assertThat(result).isInstanceOf(Aapt2Link.Result.Failed::class.java)
			val messages = (result as Aapt2Link.Result.Failed).diagnostics.map { it.message }
			assertThat(messages).containsExactly("kaboom")
		} finally {
			workDir.setWritable(true)
		}
	}

	@Test
	fun `a named but missing stable-ids file fails the relink instead of silently linking unpinned`() {
		// Class KDoc rule 1: stable-ids is what pins type ids to the baseline manifest's fixed
		// numeric ids. A stale path (AGP moved the intermediate between versions) must not
		// degrade to an unpinned link that exits 0 here and fails only on device as a crash or
		// the wrong resource - and aapt2 must not even run.
		val ranMarker = File(tempDir, "aapt2-ran")
		val link = Aapt2Link(fakeAapt2("touch '${ranMarker.absolutePath}'\nexit 0"), File(tempDir, "android.jar"))
		val missing = File(tempDir, "no-such-stableIds.txt")

		val result = link.relink(listOf(resDir), manifest, workDir, stableIds = missing)

		assertThat(result).isInstanceOf(Aapt2Link.Result.Failed::class.java)
		val diagnostic = (result as Aapt2Link.Result.Failed).diagnostics.single()
		assertThat(diagnostic.severity).isEqualTo(Diagnostic.Severity.ERROR)
		assertThat(diagnostic.message).contains("stable-ids")
		assertThat(diagnostic.message).contains(missing.absolutePath)
		assertThat(ranMarker.exists()).isFalse()
	}

	@Test
	fun `a wedged aapt2 is killed at the timeout instead of hanging the daemon loop`() {
		// `exec`, so the sleeping process IS the child: a wrapping shell would leave a
		// grandchild holding the stdout pipe open, and the output drain would outlive the kill.
		val link = Aapt2Link(fakeAapt2("exec sleep 60"), File(tempDir, "android.jar"), timeoutMillis = 300)

		val startedAt = System.currentTimeMillis()
		val result = link.relink(listOf(resDir), manifest, workDir)
		val elapsedMillis = System.currentTimeMillis() - startedAt

		assertThat(result).isInstanceOf(Aapt2Link.Result.Failed::class.java)
		val diagnostic = (result as Aapt2Link.Result.Failed).diagnostics.single()
		assertThat(diagnostic.severity).isEqualTo(Diagnostic.Severity.ERROR)
		assertThat(diagnostic.message).contains("timed out")
		// The whole point: relink RETURNS, rather than blocking the single-threaded daemon loop
		// for the full sleep and leaving ping and shutdown unanswerable.
		assertThat(elapsedMillis).isLessThan(30_000L)
	}

	@Test
	fun `a success without timings encodes as numeric zeros the client reads back as measured`() {
		// "0 means unmeasured, never -1 and never a string" is a wire contract, so assert it on
		// the wire: the same keys DaemonService.relink writes, through the real encoder, read
		// back the way DaemonProcessClient reads them (JSON-number guard, else null).
		val success = Aapt2Link.Result.Success(File("/work/linked-res.apk"))

		val encoded =
			ProtocolCodec.encode(
				DaemonResponse.ok(
					id = 7L,
					values =
						mapOf(
							"resourcesArsc" to success.resourceApk.absolutePath,
							"aapt2CompileMillis" to success.compileMillis,
							"aapt2LinkMillis" to success.linkMillis,
						),
				),
			)

		val json = JsonParser.parseString(encoded).asJsonObject
		val readLong = { key: String -> json.get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asLong }
		assertThat(readLong("aapt2CompileMillis")).isEqualTo(0L)
		assertThat(readLong("aapt2LinkMillis")).isEqualTo(0L)
		assertThat(json.get("resourcesArsc").asString).endsWith("linked-res.apk")
	}

	@Test
	fun `the watchdog reports a timeout only when it actually killed a live process`() {
		// Process.waitFor(timeout) also returns false for a child that exited just after the wait
		// expired, and destroyForcibly then no-ops - so reading the wait alone fails a link that
		// succeeded. A rare spurious relink failure is the hardest kind to diagnose from a report.
		val finished = ProcessBuilder("/bin/sh", "-c", "exit 0").start()
		finished.waitFor()

		assertThat(Aapt2Link.killIfAlive(finished)).isFalse()

		val running = ProcessBuilder("/bin/sh", "-c", "exec sleep 30").start()
		try {
			assertThat(Aapt2Link.killIfAlive(running)).isTrue()
			assertThat(running.waitFor()).isNotEqualTo(0)
		} finally {
			running.destroyForcibly()
		}
	}

	/**
	 * The verdict, not just the helper: a link whose child exited just after the deadline must
	 * not be reported as timed out.
	 *
	 * The neighbouring test pins [Aapt2Link.killIfAlive] in isolation, which held under the
	 * pre-fix code too because nothing consulted it. This pins the pairing that decides the
	 * outcome. The losing timing cannot be produced with a real process - an already-exited
	 * child makes `waitFor(timeout)` return true immediately - so the process is a stub.
	 *
	 * Goes red if the kill check is dropped from the verdict and the expired wait is trusted
	 * on its own.
	 */
	@Test
	fun `a child that exited just after the deadline is not reported as timed out`() {
		val exitedAfterTheWait = StubProcess(waitExpired = true, alive = false)
		val stillRunning = StubProcess(waitExpired = true, alive = true)
		val finishedInTime = StubProcess(waitExpired = false, alive = false)

		assertThat(Aapt2Link.watchdogTimedOut(exitedAfterTheWait, 1L)).isFalse()
		assertThat(exitedAfterTheWait.killed).isFalse()

		assertThat(Aapt2Link.watchdogTimedOut(stillRunning, 1L)).isTrue()
		assertThat(stillRunning.killed).isTrue()

		assertThat(Aapt2Link.watchdogTimedOut(finishedInTime, 1L)).isFalse()
		assertThat(finishedInTime.killed).isFalse()
	}

	/**
	 * A process whose wait result and liveness are set independently, which no real process
	 * lets a test do.
	 *
	 * @property waitExpired what the timed wait reports; false means the child finished first.
	 * @property alive whether the child is still running when the kill is attempted.
	 */
	private class StubProcess(
		private val waitExpired: Boolean,
		private val alive: Boolean,
	) : Process() {
		/** Whether [destroyForcibly] was reached, which is what a real kill would be. */
		var killed: Boolean = false
			private set

		override fun getOutputStream(): OutputStream = OutputStream.nullOutputStream()

		override fun getInputStream(): InputStream = InputStream.nullInputStream()

		override fun getErrorStream(): InputStream = InputStream.nullInputStream()

		override fun waitFor(): Int = 0

		override fun waitFor(
			timeout: Long,
			unit: TimeUnit,
		): Boolean = !waitExpired

		override fun exitValue(): Int = 0

		override fun destroy() = Unit

		override fun destroyForcibly(): Process {
			killed = true
			return this
		}

		override fun isAlive(): Boolean = alive
	}
}
