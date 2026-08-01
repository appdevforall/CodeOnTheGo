package org.appdevforall.cotg.quickbuild.daemon

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Discovery over the messy SDK layouts real devices carry: junk entries, stray files,
 * non-numeric directory names, and version strings of different lengths. Discovery must
 * skip what it cannot use and still order versions numerically, never lexically.
 */
class ToolchainDiscoveryEdgeTest {
	@TempDir
	lateinit var sdkRoot: File

	private fun discoveryWithSdkRoot(): ToolchainDiscovery =
		ToolchainDiscovery(env = { name -> if (name == "ANDROID_HOME") sdkRoot.absolutePath else null })

	private fun buildToolsWithAapt2(version: String): File =
		File(sdkRoot, "build-tools/$version").apply {
			mkdirs()
			File(this, "aapt2").writeText("stub")
		}

	@Test
	fun `a blank ANDROID_HOME is as missing as an unset one`() {
		val discovery = ToolchainDiscovery(env = { "" })

		val aapt2 = discovery.resolveAapt2()

		assertThat(aapt2).isInstanceOf(ToolchainDiscovery.Resolution.Missing::class.java)
		assertThat((aapt2 as ToolchainDiscovery.Resolution.Missing).message).contains("ANDROID_HOME")
	}

	@Test
	fun `platform discovery skips junk entries and non-numeric levels sort below real ones`() {
		File(sdkRoot, "platforms/notandroid").mkdirs()
		// Jarless platform dir: name matches, content does not.
		File(sdkRoot, "platforms/android-99").mkdirs()
		// Non-numeric level with a jar: must lose to any real numeric level.
		File(sdkRoot, "platforms/android-Baklava").apply { mkdirs() }.also { File(it, "android.jar").writeText("stub") }
		File(sdkRoot, "platforms/android-34").apply { mkdirs() }.also { File(it, "android.jar").writeText("stub") }
		File(sdkRoot, "platforms/android-36").apply { mkdirs() }.also { File(it, "android.jar").writeText("stub") }

		val androidJar = discoveryWithSdkRoot().resolveAndroidJar()

		assertThat(androidJar).isInstanceOf(ToolchainDiscovery.Resolution.Found::class.java)
		assertThat((androidJar as ToolchainDiscovery.Resolution.Found).path)
			.isEqualTo(File(sdkRoot, "platforms/android-36/android.jar").absolutePath)
	}

	@Test
	fun `an SDK without a build-tools directory at all is missing, not a crash`() {
		val aapt2 = discoveryWithSdkRoot().resolveAapt2()

		assertThat(aapt2).isInstanceOf(ToolchainDiscovery.Resolution.Missing::class.java)
		assertThat((aapt2 as ToolchainDiscovery.Resolution.Missing).message).contains("aapt2")
	}

	@Test
	fun `build-tools discovery ignores stray files and sorts versions numerically, not lexically`() {
		// Lexically "9.0.0" > "35.0.0" - the numeric comparator must invert that.
		buildToolsWithAapt2("9.0.0")
		val newest = buildToolsWithAapt2("35.0.0")
		// A stray FILE where a version dir belongs must be skipped, not treated as a dir.
		File(sdkRoot, "build-tools/36.0.0").writeText("not a directory")
		// Non-numeric names parse as all-zero components and lose to any real version
		// (two of them, so the comparator sees a non-numeric part on each side).
		buildToolsWithAapt2("junk")
		buildToolsWithAapt2("zunk")

		val aapt2 = discoveryWithSdkRoot().resolveAapt2()

		assertThat(aapt2).isInstanceOf(ToolchainDiscovery.Resolution.Found::class.java)
		assertThat((aapt2 as ToolchainDiscovery.Resolution.Found).path).isEqualTo(File(newest, "aapt2").absolutePath)
	}

	@Test
	fun `an SDK carrying only non-numeric build-tools versions still resolves one`() {
		// Both sides of a comparison can be non-numeric; discovery must survive it and
		// pick one rather than crash or report the tool missing when it exists.
		val a = buildToolsWithAapt2("junk")
		val b = buildToolsWithAapt2("zunk")

		val aapt2 = discoveryWithSdkRoot().resolveAapt2()

		assertThat(aapt2).isInstanceOf(ToolchainDiscovery.Resolution.Found::class.java)
		assertThat(listOf(File(a, "aapt2").absolutePath, File(b, "aapt2").absolutePath))
			.contains((aapt2 as ToolchainDiscovery.Resolution.Found).path)
	}

	@Test
	fun `a longer version with a nonzero tail beats its shorter prefix`() {
		buildToolsWithAapt2("35")
		val newest = buildToolsWithAapt2("35.0.1")

		val aapt2 = discoveryWithSdkRoot().resolveAapt2()

		assertThat(aapt2).isInstanceOf(ToolchainDiscovery.Resolution.Found::class.java)
		assertThat((aapt2 as ToolchainDiscovery.Resolution.Found).path).isEqualTo(File(newest, "aapt2").absolutePath)
	}

	@Test
	fun `version components pad with zero so a shorter version can tie or win`() {
		// "35", "35.0" and "35.0.0": component-wise all read 35,0,0 - a three-way tie,
		// and any is an acceptable pick; "34.9.9" must lose despite being longer.
		val tieA = buildToolsWithAapt2("35.0")
		val tieB = buildToolsWithAapt2("35.0.0")
		val tieC = buildToolsWithAapt2("35")
		buildToolsWithAapt2("34.9.9")

		val aapt2 = discoveryWithSdkRoot().resolveAapt2()

		assertThat(aapt2).isInstanceOf(ToolchainDiscovery.Resolution.Found::class.java)
		val path = (aapt2 as ToolchainDiscovery.Resolution.Found).path
		assertThat(
			listOf(
				File(tieA, "aapt2").absolutePath,
				File(tieB, "aapt2").absolutePath,
				File(tieC, "aapt2").absolutePath,
			),
		).contains(path)
	}
}
