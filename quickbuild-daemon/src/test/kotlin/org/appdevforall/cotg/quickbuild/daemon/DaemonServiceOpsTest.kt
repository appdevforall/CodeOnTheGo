package org.appdevforall.cotg.quickbuild.daemon

import com.google.common.truth.Truth.assertThat
import org.appdevforall.cotg.quickbuild.daemon.protocol.CompileRequest
import org.appdevforall.cotg.quickbuild.daemon.protocol.ConfigureRequest
import org.appdevforall.cotg.quickbuild.daemon.protocol.DexRequest
import org.appdevforall.cotg.quickbuild.daemon.protocol.DexStats
import org.appdevforall.cotg.quickbuild.daemon.protocol.Diagnostic
import org.appdevforall.cotg.quickbuild.daemon.protocol.RelinkRequest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIf
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * The configured-session op paths of [DaemonService]: how each op's tool result becomes a
 * protocol response - failures as ok:false with diagnostics, successes carrying the
 * artifact paths and timings the client deploys and logs from. Complements
 * DaemonServiceTest, which covers configure validation and the compile happy path.
 */
class DaemonServiceOpsTest {
	@TempDir
	lateinit var tempDir: File

	private val service = DaemonService(log = {})

	private fun configure(
		aapt2: File = TestSdk.kotlinStdlib(),
		d8Jar: File = TestSdk.kotlinStdlib(),
		androidJar: File = TestSdk.kotlinStdlib(),
		compilerPlugins: List<String> = emptyList(),
		service: DaemonService = this.service,
	) {
		val response =
			service.configure(
				ConfigureRequest(
					id = 1,
					projectRoot = tempDir.absolutePath,
					classpath = listOf(TestSdk.kotlinStdlib().absolutePath),
					outDir = File(tempDir, "out").absolutePath,
					aapt2 = aapt2.absolutePath,
					d8Jar = d8Jar.absolutePath,
					androidJar = androidJar.absolutePath,
					compilerPlugins = compilerPlugins,
				),
			)
		check(response.ok) { "fixture configure failed: ${response.diagnostics}" }
	}

	@Test
	fun `a compile failure responds ok-false with the compiler's diagnostics`() {
		configure()
		val broken = File(tempDir, "Broken.kt").apply { writeText("package demo\n\nfun broken(: Int\n") }

		val response = service.compile(CompileRequest(2, listOf(broken.absolutePath), listOf(broken.absolutePath)))

		assertThat(response.ok).isFalse()
		assertThat(response.diagnostics).isNotEmpty()
		assertThat(response.diagnostics.all { it.severity == Diagnostic.Severity.ERROR }).isTrue()
	}

	@Test
	fun `a dex failure responds ok-false with the tool's message`() {
		configure()
		val emptyDir = File(tempDir, "no-classes").apply { mkdirs() }

		val response = service.dex(DexRequest(3, listOf(emptyDir.absolutePath)))

		assertThat(response.ok).isFalse()
		assertThat(response.diagnostics.single().message).contains("no .class files")
	}

	@Test
	@EnabledIf("org.appdevforall.cotg.quickbuild.daemon.TestSdk#dexToolchainAvailable")
	fun `compile then dex produces a classes dex under the session's out dir`() {
		configure(d8Jar = TestSdk.d8Jar()!!, androidJar = TestSdk.androidJar()!!)
		val source = File(tempDir, "Hello.kt").apply { writeText("package demo\n\nfun hello() = \"hi\"\n") }
		val compile = service.compile(CompileRequest(2, listOf(source.absolutePath), listOf(source.absolutePath)))
		check(compile.ok) { "fixture compile failed: ${compile.diagnostics}" }

		val response = service.dex(DexRequest(3, listOf(compile.values["classesDir"] as String)))

		assertThat(response.ok).isTrue()
		val dexFile = File(response.values["dexFile"] as String)
		assertThat(dexFile.isFile).isTrue()
		assertThat(dexFile.name).isEqualTo("classes.dex")
		assertThat(dexFile.absolutePath).startsWith(File(tempDir, "out").absolutePath)
		// The timing/stat fields a slow row is read by.
		assertThat((response.values["durationMillis"] as Long)).isAtLeast(0)
		assertThat((response.values["stripMillis"] as Long)).isAtLeast(0)
		assertThat((response.values["d8Millis"] as Long)).isAtLeast(0)
		val stats = DexStats.fromValues { key -> (response.values[key] as? Number)?.toLong() }!!
		assertThat(stats.classFiles).isEqualTo(1)
		assertThat(stats.classBytes).isGreaterThan(0)
	}

	@Test
	fun `a relink failure responds ok-false with error diagnostics`() {
		// The stdlib jar stands in for aapt2: it exists (passes configure) but cannot be
		// executed, so the relink's aapt2 compile step fails and must surface as a
		// response, never a throw.
		configure()
		val resDir = File(tempDir, "res/values").apply { mkdirs() }.parentFile
		File(resDir, "values/strings.xml").writeText("<resources />")
		val manifest = File(tempDir, "AndroidManifest.xml").apply { writeText("<manifest />") }
		val stableIds = File(tempDir, "stableIds.txt").apply { writeText("demo:string/app_name = 0x7f010000") }

		// stableIds and libraryResources ride through to the tool even on a failing run.
		val response =
			service.relink(
				RelinkRequest(
					4,
					listOf(resDir.absolutePath),
					manifest.absolutePath,
					stableIds = stableIds.absolutePath,
					libraryResources = listOf(File(tempDir, "lib.flat").absolutePath),
				),
			)

		assertThat(response.ok).isFalse()
		assertThat(response.diagnostics).isNotEmpty()
		assertThat(response.diagnostics.any { it.severity == Diagnostic.Severity.ERROR }).isTrue()
	}

	@Test
	@EnabledIf("org.appdevforall.cotg.quickbuild.daemon.TestSdk#aapt2ToolchainAvailable")
	fun `a relink success carries the linked resource apk and the aapt2 phase timings`() {
		configure(aapt2 = TestSdk.aapt2()!!, androidJar = TestSdk.androidJar()!!)
		val resDir = File(tempDir, "res/values").apply { mkdirs() }.parentFile
		File(resDir, "values/strings.xml").writeText(
			"""
			<?xml version="1.0" encoding="utf-8"?>
			<resources>
				<string name="app_name">Quick Build Demo</string>
			</resources>
			""".trimIndent(),
		)
		val manifest =
			File(tempDir, "AndroidManifest.xml").apply {
				writeText(
					"""
					<?xml version="1.0" encoding="utf-8"?>
					<manifest xmlns:android="http://schemas.android.com/apk/res/android"
						package="demo.quickbuild">
						<application android:label="@string/app_name" />
					</manifest>
					""".trimIndent(),
				)
			}

		val response = service.relink(RelinkRequest(5, listOf(resDir.absolutePath), manifest.absolutePath))

		assertThat(response.ok).isTrue()
		// Wire name kept as "resourcesArsc" for protocol stability; payload is the full apk.
		val resourceApk = File(response.values["resourcesArsc"] as String)
		assertThat(resourceApk.isFile).isTrue()
		assertThat(resourceApk.length()).isGreaterThan(0)
		assertThat(resourceApk.absolutePath).startsWith(File(tempDir, "out").absolutePath)
		assertThat((response.values["durationMillis"] as Long)).isAtLeast(0)
		assertThat((response.values["aapt2CompileMillis"] as Long)).isAtLeast(0)
		assertThat((response.values["aapt2LinkMillis"] as Long)).isAtLeast(0)
	}

	@Test
	fun `configure accepts session-fixed compiler plugins that exist on disk`() {
		// The jar's content is irrelevant at configure time - only existence is validated;
		// a MISSING plugin path must fail configure like any other missing input.
		configure(compilerPlugins = listOf(TestSdk.kotlinStdlib().absolutePath))

		val missing =
			service.configure(
				ConfigureRequest(
					id = 9,
					projectRoot = tempDir.absolutePath,
					classpath = emptyList(),
					outDir = File(tempDir, "out").absolutePath,
					aapt2 = TestSdk.kotlinStdlib().absolutePath,
					d8Jar = TestSdk.kotlinStdlib().absolutePath,
					androidJar = TestSdk.kotlinStdlib().absolutePath,
					compilerPlugins = listOf(File(tempDir, "no-such-plugin.jar").absolutePath),
				),
			)

		assertThat(missing.ok).isFalse()
		assertThat(missing.diagnostics.single().message).contains("no-such-plugin.jar")
	}

	@Test
	fun `the default logger writes session lines to stderr, not stdout`() {
		// Stdout is protocol-only (README): a stray log line there would corrupt the
		// stream. The default log sink must therefore be stderr.
		val defaultLogService = DaemonService()
		val originalOut = System.out
		val capturedOut = java.io.ByteArrayOutputStream()
		try {
			System.setOut(java.io.PrintStream(capturedOut, true, "UTF-8"))
			val response =
				defaultLogService.configure(
					ConfigureRequest(
						id = 1,
						projectRoot = tempDir.absolutePath,
						classpath = emptyList(),
						outDir = File(tempDir, "out").absolutePath,
						aapt2 = TestSdk.kotlinStdlib().absolutePath,
						d8Jar = TestSdk.kotlinStdlib().absolutePath,
						androidJar = TestSdk.kotlinStdlib().absolutePath,
					),
				)
			assertThat(response.ok).isTrue()
		} finally {
			System.setOut(originalOut)
		}
		assertThat(capturedOut.toString("UTF-8")).isEmpty()
	}
}
