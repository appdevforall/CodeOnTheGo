package com.itsaky.androidide.gradle.quickbuild

import com.android.build.api.variant.BuiltArtifact
import com.android.build.api.variant.FilterConfiguration
import com.android.build.api.variant.VariantOutputConfiguration
import com.google.common.truth.Truth.assertThat
import org.gradle.api.GradleException
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.File

/**
 * [QuickBuildProxyAppReportTask.selectUniversalApk] against faked AGP built-artifact metadata:
 * the report must hand CoGo the one APK every device can install, never an arbitrary split.
 */
class QuickBuildProxyAppReportTaskTest {
	private val apkDirectory = File("/build/outputs/apk/debug")

	private fun artifact(
		outputFile: String,
		vararg filters: FilterConfiguration,
	): BuiltArtifact =
		object : BuiltArtifact {
			override val outputFile = outputFile
			override val versionCode: Int? = null
			override val versionName: String? = null
			override val outputType =
				if (filters.isEmpty()) {
					VariantOutputConfiguration.OutputType.SINGLE
				} else {
					VariantOutputConfiguration.OutputType.ONE_OF_MANY
				}
			override val filters = filters.toList()
		}

	private fun abiFilter(abi: String): FilterConfiguration =
		object : FilterConfiguration {
			override val filterType = FilterConfiguration.FilterType.ABI
			override val identifier = abi
		}

	@Test
	fun `the universal apk is selected over split apks whatever the metadata order`() {
		// With ABI splits on, AGP's metadata lists the splits alongside the universal APK in no
		// contractual order - taking the first element installs an APK that may not match the
		// device's ABI at all.
		val elements =
			listOf(
				artifact("/apks/app-arm64-v8a-debug.apk", abiFilter("arm64-v8a")),
				artifact("/apks/app-universal-debug.apk"),
				artifact("/apks/app-armeabi-v7a-debug.apk", abiFilter("armeabi-v7a")),
			)

		val selected = QuickBuildProxyAppReportTask.selectUniversalApk(elements, apkDirectory)

		assertThat(selected).isEqualTo("/apks/app-universal-debug.apk")
	}

	@Test
	fun `a single unfiltered apk - the normal case - is selected`() {
		val selected =
			QuickBuildProxyAppReportTask.selectUniversalApk(
				listOf(artifact("/apks/app-debug.apk")),
				apkDirectory,
			)

		assertThat(selected).isEqualTo("/apks/app-debug.apk")
	}

	@Test
	fun `an all-splits output fails naming the enabled splits instead of guessing`() {
		// Guessing a split "works" on the build host and fails only at install time on a
		// mismatched device; the failure has to happen here, with the splits named.
		val elements =
			listOf(
				artifact("/apks/app-arm64-v8a-debug.apk", abiFilter("arm64-v8a")),
				artifact("/apks/app-armeabi-v7a-debug.apk", abiFilter("armeabi-v7a")),
			)

		val failure =
			assertThrows<GradleException> {
				QuickBuildProxyAppReportTask.selectUniversalApk(elements, apkDirectory)
			}

		assertThat(failure).hasMessageThat().contains("ABI=arm64-v8a")
		assertThat(failure).hasMessageThat().contains("ABI=armeabi-v7a")
		assertThat(failure).hasMessageThat().contains(apkDirectory.path)
	}
}
