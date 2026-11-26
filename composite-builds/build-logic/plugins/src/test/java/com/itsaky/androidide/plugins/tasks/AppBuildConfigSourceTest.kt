package com.itsaky.androidide.plugins.tasks

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Compares [appBuildConfigSource] with the BuildConfig.java that AGP 8.8.2 generated for :app
 * before ADFA-6954, minus VERSION_CODE and VERSION_NAME. Everything from `package` on must match.
 */
class AppBuildConfigSourceTest {
	private val abis = mapOf("ABI_ARMEABI_V7A" to "armeabi-v7a", "ABI_ARM64_V8A" to "arm64-v8a")

	private fun source(
		debuggable: Boolean,
		buildType: String,
		flavor: String = "v8",
	) = appBuildConfigSource("com.itsaky.androidide", debuggable, "com.itsaky.androidide", buildType, flavor, abis)

	private fun fromPackage(source: String) = source.substring(source.indexOf("package "))

	private fun agp(
		debug: String,
		buildType: String,
	) = listOf(
		"package com.itsaky.androidide;",
		"",
		"public final class BuildConfig {",
		"  public static final boolean DEBUG = $debug;",
		"  public static final String APPLICATION_ID = \"com.itsaky.androidide\";",
		"  public static final String BUILD_TYPE = \"$buildType\";",
		"  public static final String FLAVOR = \"v8\";",
		"  // Field from default config.",
		"  public static final String ABI_ARM64_V8A = \"arm64-v8a\";",
		"  // Field from default config.",
		"  public static final String ABI_ARMEABI_V7A = \"armeabi-v7a\";",
		"}",
		"",
	).joinToString("\n")

	@Test
	fun `a debuggable variant matches AGP's debug BuildConfig`() {
		assertThat(fromPackage(source(debuggable = true, buildType = "debug")))
			.isEqualTo(agp("Boolean.parseBoolean(\"true\")", "debug"))
	}

	@Test
	fun `a release variant gets the constant false, like AGP's`() {
		assertThat(fromPackage(source(debuggable = false, buildType = "release")))
			.isEqualTo(agp("false", "release"))
	}

	@Test
	fun `no version fields`() {
		val text = source(debuggable = true, buildType = "debug")
		assertThat(text).doesNotContain("VERSION_CODE =")
		assertThat(text).doesNotContain("VERSION_NAME =")
	}

	@Test
	fun `no FLAVOR field without a flavor, like AGP`() {
		assertThat(source(debuggable = true, buildType = "debug", flavor = "")).doesNotContain("FLAVOR")
	}

	@Test
	fun `string values are escaped as Java literals`() {
		val text = appBuildConfigSource("p", true, "a\"b\\c", "debug", "", emptyMap())
		assertThat(text).contains("APPLICATION_ID = \"a\\\"b\\\\c\";")
	}
}
