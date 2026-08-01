package org.appdevforall.cotg.quickbuild.daemon.res

import com.google.common.truth.Truth.assertThat
import org.appdevforall.cotg.quickbuild.daemon.protocol.Diagnostic
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
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
	fun `a success constructed without timings reports them as zero`() {
		val success = Aapt2Link.Result.Success(File("/work/linked-res.apk"))

		assertThat(success.resourceApk.name).isEqualTo("linked-res.apk")
		assertThat(success.compileMillis).isEqualTo(0)
		assertThat(success.linkMillis).isEqualTo(0)
	}
}
