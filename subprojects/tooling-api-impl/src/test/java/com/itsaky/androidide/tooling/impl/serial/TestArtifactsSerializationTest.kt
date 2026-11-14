package com.itsaky.androidide.tooling.impl.serial

import com.android.builder.model.v2.ide.AbstractArtifact
import com.android.builder.model.v2.ide.ArtifactDependencies
import com.android.builder.model.v2.ide.GraphItem
import com.android.builder.model.v2.ide.SourceProvider
import com.android.builder.model.v2.ide.SourceSetContainer
import com.android.builder.model.v2.ide.Variant
import com.android.builder.model.v2.models.VariantDependencies
import com.android.builder.model.v2.models.Versions
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import java.io.File

/**
 * The unit test and android test source providers and compile graphs reach the proto model, so
 * the IDE can give test sources their own scopes (ADFA-7115).
 */
@Suppress("DEPRECATION")
@RunWith(JUnit4::class)
class TestArtifactsSerializationTest {
	@Test
	fun `serializes unit test and android test source providers`() {
		val container: SourceSetContainer =
			mockk {
				every { sourceProvider } returns provider("/p/src/main/java")
				every { unitTestSourceProvider } returns provider("/p/src/test/java")
				every { androidTestSourceProvider } returns provider("/p/src/androidTest/java")
			}

		val proto = container.asProtoModel()

		assertThat(proto.sourceProvider.javaDirsList).containsExactly("/p/src/main/java")
		assertThat(proto.unitTestSourceProvider.javaDirsList).containsExactly("/p/src/test/java")
		assertThat(proto.androidTestSourceProvider.javaDirsList).containsExactly("/p/src/androidTest/java")
	}

	@Test
	fun `a module without test source sets leaves the test providers unset`() {
		val container: SourceSetContainer =
			mockk {
				every { sourceProvider } returns provider("/p/src/main/java")
				every { unitTestSourceProvider } returns null
				every { androidTestSourceProvider } returns null
			}

		val proto = container.asProtoModel()

		assertThat(proto.hasUnitTestSourceProvider()).isFalse()
		assertThat(proto.hasAndroidTestSourceProvider()).isFalse()
	}

	@Test
	fun `serializes unit test and android test compile graphs`() {
		val dependencies: VariantDependencies =
			mockk {
				every { name } returns "debug"
				every { mainArtifact } returns artifact("main-lib")
				every { libraries } returns emptyMap()
				every { unitTestArtifact } returns artifact("junit")
				every { androidTestArtifact } returns artifact("espresso")
			}

		val proto = dependencies.asProtoModel()

		assertThat(proto.mainArtifact.compileGraph.keyList).containsExactly("main-lib")
		assertThat(proto.unitTestArtifact.compileGraph.keyList).containsExactly("junit")
		assertThat(proto.androidTestArtifact.compileGraph.keyList).containsExactly("espresso")
	}

	@Test
	fun `a variant without test artifacts leaves the test graphs unset`() {
		val dependencies: VariantDependencies =
			mockk {
				every { name } returns "debug"
				every { mainArtifact } returns artifact("main-lib")
				every { libraries } returns emptyMap()
				every { unitTestArtifact } returns null
				every { androidTestArtifact } returns null
			}

		val proto = dependencies.asProtoModel()

		assertThat(proto.hasUnitTestArtifact()).isFalse()
		assertThat(proto.hasAndroidTestArtifact()).isFalse()
	}

	@Test
	fun `serializes each test artifact's generated source folders`() {
		val variant: Variant =
			mockk(relaxed = true) {
				every { name } returns "debug"
				every { unitTestArtifact } returns generating("/p/build/generated/ksp/debugUnitTest")
				every { androidTestArtifact } returns generating("/p/build/generated/ksp/debugAndroidTest")
			}
		val versions: Versions = mockk { every { agp } returns "8.8.2" }

		val proto = variant.asProtoModel(mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), versions)

		assertThat(proto.unitTestGeneratedSourceFolderPathsList).containsExactly("/p/build/generated/ksp/debugUnitTest")
		assertThat(proto.androidTestGeneratedSourceFolderPathsList).containsExactly("/p/build/generated/ksp/debugAndroidTest")
	}

	/** A test artifact whose only generated source folder is [dir]. */
	private inline fun <reified T : AbstractArtifact> generating(dir: String): T =
		mockk(relaxed = true) { every { generatedSourceFolders } returns listOf(File(dir)) }

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
