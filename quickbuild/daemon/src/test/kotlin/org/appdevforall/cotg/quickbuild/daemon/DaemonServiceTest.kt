package org.appdevforall.cotg.quickbuild.daemon

import com.google.common.truth.Truth.assertThat
import org.appdevforall.cotg.quickbuild.protocol.CompileRequest
import org.appdevforall.cotg.quickbuild.protocol.CompileStats
import org.appdevforall.cotg.quickbuild.protocol.ConfigureRequest
import org.appdevforall.cotg.quickbuild.protocol.DaemonResponse
import org.appdevforall.cotg.quickbuild.protocol.DexRequest
import org.appdevforall.cotg.quickbuild.protocol.RelinkRequest
import org.appdevforall.cotg.quickbuild.protocol.ResponseKeys
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

	@Test
	fun `configure success stamps the protocol version`() {
		val stdlib = TestSdk.kotlinStdlib()
		val response =
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

		assertThat(response.ok).isTrue()
		assertThat(response.values["protocolVersion"]).isEqualTo(DaemonResponse.PROTOCOL_VERSION)
	}

	@Test
	fun `configure reports the scratch tree's filesystem`() {
		// Session-constant context for every later timing: per-file work costs ~52x more on
		// FUSE-backed emulated storage than on a real one (measured under ADFA-4128).
		val stdlib = TestSdk.kotlinStdlib()
		val response =
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

		assertThat(response.ok).isTrue()
		val fsType = response.values[ResponseKeys.SCRATCH_FS_TYPE] as String
		// The value is host-dependent (apfs here, f2fs/fuse on device); what must hold is
		// that a real type was resolved rather than the unknown fallback.
		assertThat(fsType).isNotEmpty()
		assertThat(fsType).isNotEqualTo("unknown")
	}

	@Test
	fun `compile reports the phases kotlinMillis and javaMillis do not cover`() {
		val stdlib = TestSdk.kotlinStdlib()
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
		val source = File(tempDir, "Hello.kt").apply { writeText("package demo\n\nfun hello() = \"hi\"\n") }

		val first = service.compile(CompileRequest(2, listOf(source.absolutePath), listOf(source.absolutePath)))
		source.writeText("package demo\n\nfun hello() = \"hello\"\n")
		val second = service.compile(CompileRequest(3, listOf(source.absolutePath), listOf(source.absolutePath)))

		val firstStats = CompileStats.fromValues { key -> (first.values[key] as? Number)?.toLong() }!!
		assertThat(firstStats.allSources).isEqualTo(1)
		assertThat(firstStats.javaSources).isEqualTo(0)
		assertThat(firstStats.kotlinToCompile).isEqualTo(1)
		assertThat(firstStats.changedClasses).isEqualTo(1)
		// The cold build of the session - the distinction that keeps a first build from
		// being read as a per-edit cost.
		assertThat(firstStats.compileOrdinal).isEqualTo(1)
		assertThat(firstStats.preSnapMillis).isAtLeast(0)
		assertThat(firstStats.postSnapMillis).isAtLeast(0)

		val secondStats = CompileStats.fromValues { key -> (second.values[key] as? Number)?.toLong() }!!
		assertThat(secondStats.compileOrdinal).isEqualTo(2)
	}

	@Test
	fun `a fresh configure restarts the compile ordinal`() {
		// A respawn re-pays the cold cost, so its next compile is a cold build again.
		val stdlib = TestSdk.kotlinStdlib()
		val configure = {
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
		}
		val source = File(tempDir, "Hello.kt").apply { writeText("package demo\n\nfun hello() = \"hi\"\n") }
		val compile = { id: Long ->
			service.compile(CompileRequest(id, listOf(source.absolutePath), listOf(source.absolutePath)))
		}

		configure()
		compile(2)
		compile(3)
		configure()
		val afterReconfigure = compile(4)

		val stats = CompileStats.fromValues { key -> (afterReconfigure.values[key] as? Number)?.toLong() }!!
		assertThat(stats.compileOrdinal).isEqualTo(1)
	}

	@Test
	fun `configure without aapt2, d8Jar or androidJar fails naming each unsupplied path`() {
		val response =
			service.configure(
				ConfigureRequest(
					id = 1,
					projectRoot = tempDir.absolutePath,
					classpath = emptyList(),
					outDir = File(tempDir, "out").absolutePath,
				),
			)

		// The daemon never guesses a tool path, so an omission has to say which field is
		// missing - the alternative is a silently wrong SDK that only fails on device.
		assertThat(response.ok).isFalse()
		val messages = response.diagnostics.map { it.message }
		assertThat(messages).hasSize(3)
		assertThat(messages.any { it.contains("aapt2") }).isTrue()
		assertThat(messages.any { it.contains("d8Jar") }).isTrue()
		assertThat(messages.any { it.contains("androidJar") }).isTrue()
		assertThat(messages.all { it.contains("not supplied") }).isTrue()
	}

	@Test
	fun `configure with a blank tool path is treated as unsupplied, not as a missing file`() {
		val stdlib = TestSdk.kotlinStdlib()

		val response =
			service.configure(
				ConfigureRequest(
					id = 1,
					projectRoot = tempDir.absolutePath,
					classpath = emptyList(),
					outDir = File(tempDir, "out").absolutePath,
					aapt2 = "",
					d8Jar = stdlib.absolutePath,
					androidJar = stdlib.absolutePath,
				),
			)

		assertThat(response.ok).isFalse()
		val messages = response.diagnostics.map { it.message }
		assertThat(messages).hasSize(1)
		assertThat(messages.single()).contains("aapt2")
		assertThat(messages.single()).contains("not supplied")
	}
}
