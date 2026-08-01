package org.appdevforall.cotg.quickbuild.daemon.dex

import com.google.common.truth.Truth.assertThat
import org.appdevforall.cotg.quickbuild.daemon.TestSdk
import org.appdevforall.cotg.quickbuild.daemon.compile.JavaCompileStep
import org.appdevforall.cotg.quickbuild.daemon.protocol.DexStats
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
	fun `a success constructed without timings reports zeros and an unmeasured stats row`() {
		val success = DexTool.Result.Success(File("/dex/classes.dex"))

		assertThat(success.dexFile.name).isEqualTo("classes.dex")
		assertThat(success.stripMillis).isEqualTo(0)
		assertThat(success.d8Millis).isEqualTo(0)
		assertThat(success.stats).isEqualTo(DexStats(classFiles = 0, classBytes = 0))
	}
}
