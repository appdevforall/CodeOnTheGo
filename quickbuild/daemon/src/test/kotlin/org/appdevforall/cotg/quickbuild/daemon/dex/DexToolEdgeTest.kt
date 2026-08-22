package org.appdevforall.cotg.quickbuild.daemon.dex

import com.google.common.truth.Truth.assertThat
import com.google.gson.JsonParser
import org.appdevforall.cotg.quickbuild.daemon.TestSdk
import org.appdevforall.cotg.quickbuild.daemon.compile.JavaCompileStep
import org.appdevforall.cotg.quickbuild.daemon.protocol.ProtocolCodec
import org.appdevforall.cotg.quickbuild.protocol.DaemonResponse
import org.appdevforall.cotg.quickbuild.protocol.DexStats
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIf
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** The `final` bit in a dex `class_def_item`'s access flags. */
private const val ACC_FINAL = 0x10

/** DexTool failure surfacing and result defaults beyond DexToolTest's happy paths. */
class DexToolEdgeTest {
	@TempDir
	lateinit var tempDir: File

	private fun compileTinyClass(): File = compile("Tiny", "public class Tiny", "classes")

	private fun compile(
		name: String,
		declaration: String,
		outputDirName: String,
	): File {
		val source =
			File(tempDir, "$name.java").apply {
				writeText("package demo;\n\n$declaration {\n\tpublic int two() { return 2; }\n}\n")
			}
		val classesDir = File(tempDir, outputDirName).apply { mkdirs() }
		val result = JavaCompileStep.compile(listOf(source), emptyList(), classesDir)
		check(result.success) { "fixture compile failed: ${result.diagnostics}" }
		return classesDir
	}

	/**
	 * The class-level access flags of every `class_def_item` in a dex, read out of the header:
	 * `class_defs_size`/`class_defs_off` at 0x60/0x64, then `access_flags` one uint into each
	 * 32-byte item. Little-endian, as the format specifies.
	 */
	private fun dexClassAccessFlags(dexFile: File): List<Int> {
		val dex = ByteBuffer.wrap(dexFile.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
		val classDefs = dex.getInt(0x60)
		val classDefsOffset = dex.getInt(0x64)
		return (0 until classDefs).map { index -> dex.getInt(classDefsOffset + index * 32 + 4) }
	}

	@Test
	@EnabledIf("org.appdevforall.cotg.quickbuild.daemon.TestSdk#dexToolchainAvailable")
	fun `a d8 compilation failure surfaces d8's own message, not a throw`() {
		val classesDir = compileTinyClass()

		// A missing library archive makes D8 itself fail (CompilationFailedException
		// through the reflective call) - the daemon must relay the cause's message.
		DexTool(TestSdk.d8Jar()!!, File(tempDir, "no-such-android.jar"), minApi = 30).use { tool ->
			val result = tool.dex(listOf(classesDir), File(tempDir, "dex"))

			assertThat(result).isInstanceOf(DexTool.Result.Failed::class.java)
			assertThat((result as DexTool.Result.Failed).message).contains("d8 failed")
		}
	}

	@Test
	fun `a dex left by an earlier run is cleared by this one, before d8 is reached`() {
		// Asserted on a run that bails on empty input, so d8 never starts: the r8 jars measured
		// here clear stale dex files themselves, which makes an end-to-end assertion pass whether
		// or not this code clears anything. The dex count after the run is the only signal that
		// d8 split the payload, so that clearing cannot be left to the device's build-tools.
		val outDir = File(tempDir, "dex").apply { mkdirs() }
		val stale = File(outDir, "classes2.dex").apply { writeText("stale") }

		DexTool(File(tempDir, "unopened-d8.jar"), File(tempDir, "unopened-android.jar"), minApi = 30).use { tool ->
			val result = tool.dex(listOf(File(tempDir, "empty").apply { mkdirs() }), outDir)

			assertThat(result).isInstanceOf(DexTool.Result.Failed::class.java)
			assertThat(stale.exists()).isFalse()
		}
	}

	@Test
	@EnabledIf("org.appdevforall.cotg.quickbuild.daemon.TestSdk#dexToolchainAvailable")
	fun `a run whose payload fits one dex leaves exactly that one dex behind`() {
		val classesDir = compileTinyClass()
		val outDir = File(tempDir, "dex").apply { mkdirs() }
		File(outDir, "classes2.dex").writeText("what a bigger earlier payload left")

		DexTool(TestSdk.d8Jar()!!, TestSdk.androidJar()!!, minApi = 30).use { tool ->
			val result = tool.dex(listOf(classesDir), outDir)

			// Success is only reachable on a single dex, so a leftover second one would have to
			// fail the run rather than ride along into the deploy.
			assertThat(result).isInstanceOf(DexTool.Result.Success::class.java)
			assertThat(outDir.listFiles { file -> file.name.endsWith(".dex") }!!.map { it.name })
				.containsExactly("classes.dex")
		}
	}

	@Test
	@EnabledIf("org.appdevforall.cotg.quickbuild.daemon.TestSdk#dexToolchainAvailable")
	fun `the emitted dex carries no final class, so a proxy can extend it`() {
		// The gen-0 baseline shipped these classes opened by the gradle-plugin's ClassOpener, and
		// the dex verifier enforces superclass finality at load time: a payload that kept
		// ACC_FINAL would fail to load under the Proxy*Activity extending it. Asserted on the dex
		// d8 emitted rather than on FinalStripper, because what is untested is whether DexTool
		// runs the strip at all.
		val classesDir = compile("TinyFinal", "public final class TinyFinal", "final-classes")

		DexTool(TestSdk.d8Jar()!!, TestSdk.androidJar()!!, minApi = 30).use { tool ->
			val result = tool.dex(listOf(classesDir), File(tempDir, "dex")) as DexTool.Result.Success

			val accessFlags = dexClassAccessFlags(result.dexFile)
			// Without this the "none are final" assertion below passes on an empty dex.
			assertThat(accessFlags).isNotEmpty()
			assertThat(accessFlags.filter { it and ACC_FINAL != 0 }).isEmpty()
		}
	}

	/**
	 * Compiles a minimal fake of r8's public API into a directory the [DexTool] class loader
	 * loads like a jar (a directory URL). The fake's `D8.run` reports one error diagnostic
	 * through the `DiagnosticsHandler` the command was built with - if any - then throws
	 * `CompilationFailedException` with the same near-useless message real d8 uses. Real d8 is
	 * toolchain-gated; this pins the diagnostics plumbing on any host, over the exact
	 * reflective surface DexTool drives.
	 */
	private fun compileFakeD8(): File {
		val srcDir = File(tempDir, "fake-d8-src/com/android/tools/r8").apply { mkdirs() }

		fun source(
			name: String,
			content: String,
		): File = File(srcDir, name).apply { writeText(content) }
		val sources =
			listOf(
				source(
					"Diagnostic.java",
					"package com.android.tools.r8;\n\npublic interface Diagnostic {\n\tString getDiagnosticMessage();\n}\n",
				),
				source(
					"DiagnosticsHandler.java",
					"package com.android.tools.r8;\n\npublic interface DiagnosticsHandler {\n" +
						"\tdefault void error(Diagnostic diagnostic) {}\n\n" +
						"\tdefault void warning(Diagnostic diagnostic) {}\n\n" +
						"\tdefault void info(Diagnostic diagnostic) {}\n}\n",
				),
				source(
					"CompilationFailedException.java",
					"package com.android.tools.r8;\n\npublic class CompilationFailedException extends Exception {\n" +
						"\tpublic CompilationFailedException(String message) {\n\t\tsuper(message);\n\t}\n}\n",
				),
				source(
					"OutputMode.java",
					"package com.android.tools.r8;\n\npublic enum OutputMode {\n\tDexIndexed\n}\n",
				),
				source(
					"D8Command.java",
					"package com.android.tools.r8;\n\n" +
						"import java.nio.file.Path;\n" +
						"import java.util.Collection;\n\n" +
						"public class D8Command {\n" +
						"\tfinal DiagnosticsHandler handler;\n\n" +
						"\tD8Command(DiagnosticsHandler handler) {\n\t\tthis.handler = handler;\n\t}\n\n" +
						"\tpublic static Builder builder() {\n\t\treturn new Builder(null);\n\t}\n\n" +
						"\tpublic static Builder builder(DiagnosticsHandler handler) {\n\t\treturn new Builder(handler);\n\t}\n\n" +
						"\tpublic static class Builder {\n" +
						"\t\tprivate final DiagnosticsHandler handler;\n\n" +
						"\t\tBuilder(DiagnosticsHandler handler) {\n\t\t\tthis.handler = handler;\n\t\t}\n\n" +
						"\t\tpublic Builder addProgramFiles(Collection<Path> files) {\n\t\t\treturn this;\n\t\t}\n\n" +
						"\t\tpublic Builder addLibraryFiles(Collection<Path> files) {\n\t\t\treturn this;\n\t\t}\n\n" +
						"\t\tpublic Builder setMinApiLevel(int minApi) {\n\t\t\treturn this;\n\t\t}\n\n" +
						"\t\tpublic Builder setOutput(Path path, OutputMode mode) {\n\t\t\treturn this;\n\t\t}\n\n" +
						"\t\tpublic D8Command build() {\n\t\t\treturn new D8Command(handler);\n\t\t}\n\t}\n}\n",
				),
				source(
					"D8.java",
					"package com.android.tools.r8;\n\n" +
						"public class D8 {\n" +
						"\tpublic static void run(D8Command command) throws CompilationFailedException {\n" +
						"\t\tif (command.handler != null) {\n" +
						"\t\t\tcommand.handler.error(new Diagnostic() {\n" +
						"\t\t\t\t@Override\n" +
						"\t\t\t\tpublic String getDiagnosticMessage() {\n" +
						"\t\t\t\t\treturn \"Type demo.Tiny is defined multiple times\";\n" +
						"\t\t\t\t}\n" +
						"\t\t\t});\n" +
						"\t\t}\n" +
						"\t\tthrow new CompilationFailedException(\"Compilation failed to complete\");\n" +
						"\t}\n}\n",
				),
			)
		val classesDir = File(tempDir, "fake-d8-classes").apply { mkdirs() }
		val result = JavaCompileStep.compile(sources, emptyList(), classesDir)
		check(result.success) { "fake d8 compile failed: ${result.diagnostics}" }
		return classesDir
	}

	@Test
	fun `a d8 failure surfaces d8's own error diagnostics, not only the generic message`() {
		// Real d8's CompilationFailedException message is just "Compilation failed to complete";
		// the actual reason (duplicate class, bad class file version, ...) only ever reaches the
		// DiagnosticsHandler. Without one installed it lands on the daemon's stderr, which the
		// client merely logs - the user sees a failure with no cause.
		val fakeD8 = compileFakeD8()
		val classesDir = compileTinyClass()

		DexTool(fakeD8, File(tempDir, "android.jar"), minApi = 30).use { tool ->
			val result = tool.dex(listOf(classesDir), File(tempDir, "dex"))

			assertThat(result).isInstanceOf(DexTool.Result.Failed::class.java)
			val message = (result as DexTool.Result.Failed).message
			assertThat(message).contains("d8 failed")
			assertThat(message).contains("Compilation failed to complete")
			assertThat(message).contains("Type demo.Tiny is defined multiple times")
		}
	}

	@Test
	fun `a payload d8 split across several dex files fails instead of shipping half of it`() {
		// The split decision is asserted directly: d8 only splits past 64K method references,
		// which is not a payload a unit test can build. Reaching Success here would deploy
		// classes.dex alone and surface as NoClassDefFoundError against a green build.
		val outDir = File(tempDir, "dex")

		val reason =
			DexTool.dexFailureReason(
				listOf(File(outDir, "classes.dex"), File(outDir, "classes2.dex")),
				outDir,
			)

		assertThat(reason).isNotNull()
		assertThat(reason).contains("classes2.dex")
		// The message has to tell the user what to do instead, not just what went wrong.
		assertThat(reason).contains("standard build")
	}

	@Test
	fun `a clean d8 exit that wrote no dex at all still fails`() {
		val outDir = File(tempDir, "dex")

		assertThat(DexTool.dexFailureReason(emptyList(), outDir)).contains("no classes.dex")
		// Exactly one dex is the only deployable answer.
		assertThat(DexTool.dexFailureReason(listOf(File(outDir, "classes.dex")), outDir)).isNull()
	}

	@Test
	fun `a success without timings encodes as numeric zeros the client reads back as measured`() {
		// "0 means unmeasured, never -1 and never a string" is a wire contract, so assert it on
		// the wire: the same keys DaemonService.dex writes, through the real encoder, read back
		// the way DaemonProcessClient reads them (JSON-number guard, else null).
		val success = DexTool.Result.Success(File("/dex/classes.dex"))

		val encoded =
			ProtocolCodec.encode(
				DaemonResponse.ok(
					id = 7L,
					values =
						mapOf(
							"dexFile" to success.dexFile.absolutePath,
							"stripMillis" to success.stripMillis,
							"d8Millis" to success.d8Millis,
						) + success.stats.toValues(),
				),
			)

		val json = JsonParser.parseString(encoded).asJsonObject
		val readLong = { key: String -> json.get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asLong }
		assertThat(readLong("stripMillis")).isEqualTo(0L)
		assertThat(readLong("d8Millis")).isEqualTo(0L)
		// Present-and-zero, not absent: null here would tell the client this daemon predates
		// the stats group and the row would be dropped rather than read as a measured zero.
		assertThat(DexStats.fromValues(readLong)).isEqualTo(DexStats(classFiles = 0, classBytes = 0))
		assertThat(json.get("dexFile").asString).endsWith("classes.dex")
	}
}
