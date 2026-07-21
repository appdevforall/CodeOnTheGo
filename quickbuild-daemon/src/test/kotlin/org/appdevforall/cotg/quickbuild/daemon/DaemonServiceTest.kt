package org.appdevforall.cotg.quickbuild.daemon

import com.google.common.truth.Truth.assertThat
import org.appdevforall.cotg.quickbuild.daemon.protocol.CompileRequest
import org.appdevforall.cotg.quickbuild.daemon.protocol.ConfigureRequest
import org.appdevforall.cotg.quickbuild.daemon.protocol.DexRequest
import org.appdevforall.cotg.quickbuild.daemon.protocol.RelinkRequest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class DaemonServiceTest {
	@TempDir
	lateinit var tempDir: File

	private val service = DaemonService(log = {})

	@Test
	fun `build ops before configure fail with a clear message`() {
		val compile = service.compile(CompileRequest(1, emptyList(), emptyList()))
		val dex = service.dex(DexRequest(2, emptyList()))
		val relink = service.relink(RelinkRequest(3, emptyList(), "/M.xml"))

		for (response in listOf(compile, dex, relink)) {
			assertThat(response.ok).isFalse()
			assertThat(response.diagnostics.single().message).contains("configure")
		}
	}

	@Test
	fun `configure with missing files fails and names them`() {
		val response =
			service.configure(
				ConfigureRequest(
					id = 1,
					projectRoot = tempDir.absolutePath,
					classpath = listOf(File(tempDir, "no-such.jar").absolutePath),
					outDir = File(tempDir, "out").absolutePath,
					aapt2 = File(tempDir, "no-such-aapt2").absolutePath,
					d8Jar = File(tempDir, "no-such-r8.jar").absolutePath,
					androidJar = File(tempDir, "no-such-android.jar").absolutePath,
				),
			)

		assertThat(response.ok).isFalse()
		assertThat(response.diagnostics.single().message).contains("no-such.jar")
		assertThat(response.diagnostics.single().message).contains("no-such-aapt2")
	}

	@Test
	fun `configure then compile runs the real pipeline`() {
		val stdlib = TestSdk.kotlinStdlib()
		// aapt2/d8Jar/androidJar only need to exist for configure; use the stdlib jar
		// as a stand-in so this test runs without an Android SDK.
		val configure =
			service.configure(
				ConfigureRequest(
					id = 1,
					projectRoot = tempDir.absolutePath,
					classpath = listOf(stdlib.absolutePath),
					outDir = File(tempDir, "out").absolutePath,
					aapt2 = stdlib.absolutePath,
					d8Jar = stdlib.absolutePath,
					androidJar = stdlib.absolutePath,
				),
			)
		assertThat(configure.ok).isTrue()

		val source = File(tempDir, "Hello.kt").apply { writeText("package demo\n\nfun hello() = \"hi\"\n") }
		val compile =
			service.compile(CompileRequest(2, listOf(source.absolutePath), listOf(source.absolutePath)))

		assertThat(compile.ok).isTrue()
		val classesDir = File(compile.values["classesDir"] as String)
		assertThat(File(classesDir, "demo/HelloKt.class").isFile).isTrue()
		assertThat(compile.values["durationMillis"]).isNotNull()
		// The deploy-policy signal: this run's emitted class files.
		assertThat(compile.values["classesChanged"]).isEqualTo(listOf("demo/HelloKt.class"))
	}
}
