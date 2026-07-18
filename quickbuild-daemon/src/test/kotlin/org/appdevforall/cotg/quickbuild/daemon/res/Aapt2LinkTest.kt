package org.appdevforall.cotg.quickbuild.daemon.res

import com.google.common.truth.Truth.assertThat
import org.appdevforall.cotg.quickbuild.daemon.TestSdk
import org.appdevforall.cotg.quickbuild.daemon.protocol.Diagnostic
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIf
import org.junit.jupiter.api.io.TempDir
import java.io.File

@EnabledIf("org.appdevforall.cotg.quickbuild.daemon.TestSdk#aapt2ToolchainAvailable")
class Aapt2LinkTest {
	@TempDir
	lateinit var tempDir: File

	private lateinit var resDir: File
	private lateinit var manifest: File
	private lateinit var workDir: File

	@BeforeEach
	fun setUp() {
		resDir = File(tempDir, "res/values").apply { mkdirs() }.parentFile
		workDir = File(tempDir, "work").apply { mkdirs() }
		manifest =
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
	}

	private fun writeStrings(content: String) {
		File(resDir, "values/strings.xml").writeText(content)
	}

	@Test
	fun `relink produces a resources arsc from a valid res tree`() {
		writeStrings(
			"""
			<?xml version="1.0" encoding="utf-8"?>
			<resources>
				<string name="app_name">Quick Build Demo</string>
			</resources>
			""".trimIndent(),
		)
		val link = Aapt2Link(TestSdk.aapt2()!!, TestSdk.androidJar()!!)

		val result = link.relink(listOf(resDir), manifest, workDir)

		assertThat(result).isInstanceOf(Aapt2Link.Result.Success::class.java)
		val arsc = (result as Aapt2Link.Result.Success).resourcesArsc
		assertThat(arsc.name).isEqualTo("resources.arsc")
		assertThat(arsc.length()).isGreaterThan(0)
	}

	@Test
	fun `relink twice in the same work dir succeeds (full recompile each time)`() {
		writeStrings(
			"""
			<?xml version="1.0" encoding="utf-8"?>
			<resources>
				<string name="app_name">First</string>
			</resources>
			""".trimIndent(),
		)
		val link = Aapt2Link(TestSdk.aapt2()!!, TestSdk.androidJar()!!)
		assertThat(link.relink(listOf(resDir), manifest, workDir))
			.isInstanceOf(Aapt2Link.Result.Success::class.java)

		writeStrings(
			"""
			<?xml version="1.0" encoding="utf-8"?>
			<resources>
				<string name="app_name">Second</string>
			</resources>
			""".trimIndent(),
		)
		val result = link.relink(listOf(resDir), manifest, workDir)

		assertThat(result).isInstanceOf(Aapt2Link.Result.Success::class.java)
	}

	@Test
	fun `malformed resource xml fails with error diagnostics, not a throw`() {
		writeStrings("<resources><string name=\"app_name\">unclosed")
		val link = Aapt2Link(TestSdk.aapt2()!!, TestSdk.androidJar()!!)

		val result = link.relink(listOf(resDir), manifest, workDir)

		assertThat(result).isInstanceOf(Aapt2Link.Result.Failed::class.java)
		val diagnostics = (result as Aapt2Link.Result.Failed).diagnostics
		assertThat(diagnostics).isNotEmpty()
		assertThat(diagnostics.any { it.severity == Diagnostic.Severity.ERROR }).isTrue()
	}

	@Test
	fun `a missing aapt2 binary fails with a message, not a throw`() {
		writeStrings("<resources />")
		val link = Aapt2Link(File(tempDir, "no-such-aapt2"), TestSdk.androidJar()!!)

		val result = link.relink(listOf(resDir), manifest, workDir)

		assertThat(result).isInstanceOf(Aapt2Link.Result.Failed::class.java)
	}
}
