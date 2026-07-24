package org.appdevforall.cotg.quickbuild.daemon.dex

import com.google.common.truth.Truth.assertThat
import org.appdevforall.cotg.quickbuild.daemon.TestSdk
import org.appdevforall.cotg.quickbuild.daemon.compile.JavaCompileStep
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIf
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Guarded by a host SDK: build-tools' d8.jar carries the same com.android.tools.r8.D8
 * the device-provisioned r8.jar does, so this exercises the exact reflective path.
 */
@EnabledIf("org.appdevforall.cotg.quickbuild.daemon.TestSdk#dexToolchainAvailable")
class DexToolTest {
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
	fun `dexes compiled classes into a valid classes dex`() {
		val classesDir = compileTinyClass()
		val outDir = File(tempDir, "dex")

		DexTool(TestSdk.d8Jar()!!, TestSdk.androidJar()!!, minApi = 30).use { tool ->
			val result = tool.dex(listOf(classesDir), outDir)

			assertThat(result).isInstanceOf(DexTool.Result.Success::class.java)
			val dexFile = (result as DexTool.Result.Success).dexFile
			assertThat(dexFile.name).isEqualTo("classes.dex")
			assertThat(dexFile.length()).isGreaterThan(0)
			// The dex magic: "dex\n" then the version.
			val magic = dexFile.readBytes().take(4).toByteArray()
			assertThat(magic).isEqualTo(byteArrayOf(0x64, 0x65, 0x78, 0x0a))
		}
	}

	@Test
	fun `empty classes dirs fail with a message, not a throw`() {
		val emptyDir = File(tempDir, "empty").apply { mkdirs() }

		DexTool(TestSdk.d8Jar()!!, TestSdk.androidJar()!!, minApi = 30).use { tool ->
			val result = tool.dex(listOf(emptyDir), File(tempDir, "dex"))

			assertThat(result).isInstanceOf(DexTool.Result.Failed::class.java)
			assertThat((result as DexTool.Result.Failed).message).contains("no .class files")
		}
	}

	@Test
	fun `an unusable d8 jar fails with a message, not a throw`() {
		val bogusJar = File(tempDir, "bogus.jar").apply { writeText("not a jar") }
		val classesDir = compileTinyClass()

		DexTool(bogusJar, TestSdk.androidJar()!!, minApi = 30).use { tool ->
			val result = tool.dex(listOf(classesDir), File(tempDir, "dex"))

			assertThat(result).isInstanceOf(DexTool.Result.Failed::class.java)
		}
	}
}
