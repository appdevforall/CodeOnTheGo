package org.appdevforall.cotg.quickbuild.daemon

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class ToolchainDiscoveryTest {
	@TempDir
	lateinit var sdkRoot: File

	private fun discoveryWithSdkRoot(): ToolchainDiscovery =
		ToolchainDiscovery(env = { name -> if (name == "ANDROID_HOME") sdkRoot.absolutePath else null })

	private fun buildToolsVersion(version: String): File =
		File(sdkRoot, "build-tools/$version").apply { mkdirs() }

	private fun platform(apiLevel: String): File = File(sdkRoot, "platforms/android-$apiLevel").apply { mkdirs() }

	@Test
	fun `picks the newest build-tools version and platform that actually have the tool`() {
		val older = buildToolsVersion("34.0.0")
		File(older, "aapt2").writeText("stub")
		File(older, "lib").mkdirs()
		File(older, "lib/d8.jar").writeText("stub")

		val newer = buildToolsVersion("35.0.0")
		File(newer, "aapt2").writeText("stub")
		File(newer, "lib").mkdirs()
		File(newer, "lib/d8.jar").writeText("stub")

		platform("34").also { File(it, "android.jar").writeText("stub") }
		platform("36").also { File(it, "android.jar").writeText("stub") }

		val discovery = discoveryWithSdkRoot()

		val aapt2 = discovery.resolveAapt2()
		val d8Jar = discovery.resolveD8Jar()
		val androidJar = discovery.resolveAndroidJar()

		assertThat(aapt2).isInstanceOf(ToolchainDiscovery.Resolution.Found::class.java)
		assertThat((aapt2 as ToolchainDiscovery.Resolution.Found).path).isEqualTo(File(newer, "aapt2").absolutePath)
		assertThat(d8Jar).isInstanceOf(ToolchainDiscovery.Resolution.Found::class.java)
		assertThat((d8Jar as ToolchainDiscovery.Resolution.Found).path).isEqualTo(File(newer, "lib/d8.jar").absolutePath)
		assertThat(androidJar).isInstanceOf(ToolchainDiscovery.Resolution.Found::class.java)
		assertThat((androidJar as ToolchainDiscovery.Resolution.Found).path)
			.isEqualTo(File(sdkRoot, "platforms/android-36/android.jar").absolutePath)
	}

	@Test
	fun `unset ANDROID_HOME fails with a message naming it`() {
		val discovery = ToolchainDiscovery(env = { null })

		val aapt2 = discovery.resolveAapt2()

		assertThat(aapt2).isInstanceOf(ToolchainDiscovery.Resolution.Missing::class.java)
		assertThat((aapt2 as ToolchainDiscovery.Resolution.Missing).message).contains("ANDROID_HOME")
	}

	@Test
	fun `empty build-tools fails with a message naming the missing tool`() {
		File(sdkRoot, "build-tools").mkdirs()

		val discovery = discoveryWithSdkRoot()
		val aapt2 = discovery.resolveAapt2()

		assertThat(aapt2).isInstanceOf(ToolchainDiscovery.Resolution.Missing::class.java)
		assertThat((aapt2 as ToolchainDiscovery.Resolution.Missing).message).contains("aapt2")
	}

	@Test
	fun `a build-tools version missing the specific tool is skipped in favor of one that has it`() {
		// 35.0.0 is newer but lacks d8.jar; 34.0.0 is older but has it - d8 discovery
		// must not just take the newest directory, it must verify the tool is there.
		val newerButIncomplete = buildToolsVersion("35.0.0")
		File(newerButIncomplete, "aapt2").writeText("stub")

		val older = buildToolsVersion("34.0.0")
		File(older, "lib").mkdirs()
		File(older, "lib/d8.jar").writeText("stub")

		val d8Jar = discoveryWithSdkRoot().resolveD8Jar()

		assertThat(d8Jar).isInstanceOf(ToolchainDiscovery.Resolution.Found::class.java)
		assertThat((d8Jar as ToolchainDiscovery.Resolution.Found).path).isEqualTo(File(older, "lib/d8.jar").absolutePath)
	}

	@Test
	fun `no platforms directory fails naming androidJar`() {
		val discovery = discoveryWithSdkRoot()

		val androidJar = discovery.resolveAndroidJar()

		assertThat(androidJar).isInstanceOf(ToolchainDiscovery.Resolution.Missing::class.java)
		assertThat((androidJar as ToolchainDiscovery.Resolution.Missing).message).contains("androidJar")
	}
}
