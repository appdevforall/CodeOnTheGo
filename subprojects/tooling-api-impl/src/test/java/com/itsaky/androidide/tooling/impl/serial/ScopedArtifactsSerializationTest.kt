package com.itsaky.androidide.tooling.impl.serial

import com.android.builder.model.v2.ide.AbstractArtifact
import com.android.builder.model.v2.ide.AndroidArtifact
import com.android.builder.model.v2.ide.ArtifactDependencies
import com.android.builder.model.v2.ide.GraphItem
import com.android.builder.model.v2.ide.JavaArtifact
import com.android.builder.model.v2.ide.SourceProvider
import com.android.builder.model.v2.ide.SourceSetContainer
import com.android.builder.model.v2.ide.Variant
import com.android.builder.model.v2.models.VariantDependencies
import com.android.builder.model.v2.models.Versions
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import org.gradle.tooling.model.UnsupportedMethodException
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import java.io.File

/**
 * Every component's source providers, compile graph and generated sources reach the proto model
 * keyed by scope (ADFA-7115), from AGP's per-type test maps or, on models that predate them, from
 * the single-artifact getters.
 */
@Suppress("DEPRECATION")
@RunWith(JUnit4::class)
class ScopedArtifactsSerializationTest {
	@Test
	fun `req 2 - host and device test source providers become prefixed scope keys`() {
		val container =
			container(
				hostTests =
					mapOf(
						"_unit_test_" to provider("/p/src/test/java"),
						"_screenshot_test_" to provider("/p/src/screenshotTest/java"),
					),
				deviceTests = mapOf("_android_test_" to provider("/p/src/androidTest/java")),
			)

		val proto = container.asProtoModel()

		assertThat(proto.sourceProvidersMap.keys)
			.containsExactly("main", "host:_unit_test_", "host:_screenshot_test_", "device:_android_test_")
		assertThat(proto.sourceProvidersMap.getValue("host:_screenshot_test_").javaDirsList)
			.containsExactly("/p/src/screenshotTest/java")
	}

	@Test
	fun `req 4 - each scope keeps its own compile graph`() {
		val dependencies =
			dependencies(
				hostTests = mapOf("_unit_test_" to artifact("junit")),
				deviceTests = mapOf("_android_test_" to artifact("espresso")),
				fixtures = artifact("fixtures-lib"),
			)

		val proto = dependencies.asProtoModel()

		assertThat(
			proto.artifactsMap
				.getValue("main")
				.compileGraph.keyList,
		).containsExactly("main-lib")
		assertThat(
			proto.artifactsMap
				.getValue("host:_unit_test_")
				.compileGraph.keyList,
		).containsExactly("junit")
		assertThat(
			proto.artifactsMap
				.getValue("device:_android_test_")
				.compileGraph.keyList,
		).containsExactly("espresso")
		assertThat(
			proto.artifactsMap
				.getValue("testFixtures")
				.compileGraph.keyList,
		).containsExactly("fixtures-lib")
	}

	@Test
	fun `AGP 7_3 fallback - singular getters fill the same keys when the maps are unsupported`() {
		val legacyContainer: SourceSetContainer =
			mockk {
				every { sourceProvider } returns provider("/p/src/main/java")
				every { testFixturesSourceProvider } returns null
				every { hostTestSourceProviders } throws UnsupportedMethodException("no hostTestSourceProviders")
				every { deviceTestSourceProviders } throws UnsupportedMethodException("no deviceTestSourceProviders")
				every { unitTestSourceProvider } returns provider("/p/src/test/java")
				every { androidTestSourceProvider } returns provider("/p/src/androidTest/java")
			}
		val legacyDependencies: VariantDependencies =
			mockk {
				every { name } returns "debug"
				every { mainArtifact } returns artifact("main-lib")
				every { libraries } returns emptyMap()
				every { testFixturesArtifact } returns null
				every { hostTestArtifacts } throws UnsupportedMethodException("no hostTestArtifacts")
				every { deviceTestArtifacts } throws UnsupportedMethodException("no deviceTestArtifacts")
				every { unitTestArtifact } returns artifact("junit")
				every { androidTestArtifact } returns artifact("espresso")
			}

		assertThat(legacyContainer.asProtoModel().sourceProvidersMap.keys)
			.containsExactly("main", "host:_unit_test_", "device:_android_test_")
		assertThat(legacyDependencies.asProtoModel().artifactsMap.keys)
			.containsExactly("main", "host:_unit_test_", "device:_android_test_")
	}

	@Test
	fun `req 2 - each component's generated source folders are kept under its key`() {
		val unitTest = generating<JavaArtifact>("/p/build/generated/ksp/debugUnitTest")
		val androidTest = generating<AndroidArtifact>("/p/build/generated/ksp/debugAndroidTest")
		val variant: Variant =
			mockk(relaxed = true) {
				every { name } returns "debug"
				every { mainArtifact } returns generating("/p/build/generated/ksp/debug")
				every { testFixturesArtifact } returns null
				every { hostTestArtifacts } returns mapOf("_unit_test_" to unitTest)
				every { deviceTestArtifacts } returns mapOf("_android_test_" to androidTest)
			}
		val versions: Versions = mockk { every { agp } returns "8.8.2" }

		val proto = variant.asProtoModel(mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), versions)

		assertThat(proto.artifactSourcesMap.getValue("main").generatedSourceFolderPathsList)
			.containsExactly("/p/build/generated/ksp/debug")
		assertThat(proto.artifactSourcesMap.getValue("host:_unit_test_").generatedSourceFolderPathsList)
			.containsExactly("/p/build/generated/ksp/debugUnitTest")
		assertThat(proto.artifactSourcesMap.getValue("device:_android_test_").generatedSourceFolderPathsList)
			.containsExactly("/p/build/generated/ksp/debugAndroidTest")
	}

	/** An artifact whose only generated source folder is [dir]. */
	private inline fun <reified T : AbstractArtifact> generating(dir: String): T =
		mockk(relaxed = true) { every { generatedSourceFolders } returns listOf(File(dir)) }

	private fun container(
		hostTests: Map<String, SourceProvider> = emptyMap(),
		deviceTests: Map<String, SourceProvider> = emptyMap(),
		fixtures: SourceProvider? = null,
	): SourceSetContainer =
		mockk {
			every { sourceProvider } returns provider("/p/src/main/java")
			every { testFixturesSourceProvider } returns fixtures
			every { hostTestSourceProviders } returns hostTests
			every { deviceTestSourceProviders } returns deviceTests
		}

	private fun dependencies(
		hostTests: Map<String, ArtifactDependencies> = emptyMap(),
		deviceTests: Map<String, ArtifactDependencies> = emptyMap(),
		fixtures: ArtifactDependencies? = null,
	): VariantDependencies =
		mockk {
			every { name } returns "debug"
			every { mainArtifact } returns artifact("main-lib")
			every { libraries } returns emptyMap()
			every { testFixturesArtifact } returns fixtures
			every { hostTestArtifacts } returns hostTests
			every { deviceTestArtifacts } returns deviceTests
		}

	private fun provider(javaDir: String): SourceProvider =
		mockk {
			every { javaDirectories } returns listOf(File(javaDir))
			every { kotlinDirectories } returns emptyList()
			every { resDirectories } returns emptyList()
		}

	private fun artifact(key: String): ArtifactDependencies {
		val item: GraphItem =
			mockk {
				every { this@mockk.key } returns key
				every { requestedCoordinates } returns null
				every { dependencies } returns emptyList()
			}
		return mockk {
			every { compileDependencies } returns listOf(item)
			every { unresolvedDependencies } returns emptyList()
		}
	}
}
