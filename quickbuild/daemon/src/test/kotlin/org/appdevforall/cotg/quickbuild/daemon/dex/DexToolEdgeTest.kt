package org.appdevforall.cotg.quickbuild.daemon.dex

import com.google.common.truth.Truth.assertThat
import com.google.gson.JsonParser
import org.appdevforall.cotg.quickbuild.daemon.TestSdk
import org.appdevforall.cotg.quickbuild.daemon.compile.JavaCompileStep
import org.appdevforall.cotg.quickbuild.daemon.protocol.DaemonResponse
import org.appdevforall.cotg.quickbuild.daemon.protocol.DexStats
import org.appdevforall.cotg.quickbuild.daemon.protocol.ProtocolCodec
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIf
import org.junit.jupiter.api.io.TempDir
import java.io.File

/** DexTool failure surfacing and result defaults beyond DexToolTest's happy paths. */
class DexToolEdgeTest {
	@TempDir
	lateinit var tempDir: File

	private fun compileTinyClass(): File {
		val source =
			File(tempDir, "Tiny.java").apply {
				writeText("package demo;\n\npublic class Tiny {\n\tpublic int two() { return 2; }\n}\n")
			}
		val classesDir = File(tempDir, "classes").apply { mkdirs() }
		val result = JavaCompileStep.compile(listOf(source), emptyList(), classesDir)
		check(result.success) { "fixture compile failed: ${result.diagnostics}" }
		return classesDir
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
