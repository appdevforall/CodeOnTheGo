package com.itsaky.androidide.tooling.impl.serial

import com.android.builder.model.v2.ide.BasicArtifact
import com.android.builder.model.v2.ide.BasicVariant
import com.android.builder.model.v2.ide.SourceProvider
import com.android.builder.model.v2.ide.SourceSetContainer
import com.android.builder.model.v2.ide.Variant
import com.android.builder.model.v2.models.BasicAndroidProject
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import java.io.File

/**
 * [variantArtifactSources] picks, per scope, exactly the providers AGP layers over that scope's
 * default source set for one variant, in AGP's order. Build-type and flavor containers are matched
 * by the name AGP gives their main provider, so the other build type's container never leaks in.
 */
@RunWith(JUnit4::class)
class VariantSourceProvidersTest {
	private val debug = container("debug", unitTest = "testDebug")
	private val release = container("release", unitTest = "testRelease")
	private val free = container("free", unitTest = "testFree")
	private val paid = container("paid", unitTest = "testPaid")

	@Test
	fun `req 1 - main layers build type, flavor, multi-flavor and variant providers in AGP order`() {
		val project =
			project(basicVariant("freeDebug", buildType = "debug", flavors = listOf("free"), main = artifact("freeMulti", "freeDebug")))

		val sources = project.variantArtifactSources(variant("freeDebug"))

		assertThat(sources.getValue("main").variantSourceProvidersList.map { it.javaDirsList.single() })
			.containsExactly(dir("debug"), dir("free"), dir("freeMulti"), dir("freeDebug"))
			.inOrder()
	}

	@Test
	fun `req 1 - a variant without flavors carries only its build type and its own provider`() {
		val project = project(basicVariant("debug", buildType = "debug", flavors = emptyList(), main = artifact(null, "debug-own")))

		val sources = project.variantArtifactSources(variant("debug"))

		assertThat(sources.getValue("main").variantSourceProvidersList.map { it.javaDirsList.single() })
			.containsExactly(dir("debug"), dir("debug-own"))
			.inOrder()
	}

	@Test
	fun `req 2 - a test artifact carries its own variant layers`() {
		val project =
			project(
				basicVariant(
					"freeDebug",
					buildType = "debug",
					flavors = listOf("free"),
					main = artifact(null, null),
					unitTest = artifact("testFreeMulti", "testFreeDebug"),
				),
			)

		val sources = project.variantArtifactSources(variant("freeDebug"))

		assertThat(sources.getValue("host:_unit_test_").variantSourceProvidersList.map { it.javaDirsList.single() })
			.containsExactly(dir("testDebug"), dir("testFree"), dir("testFreeMulti"), dir("testFreeDebug"))
			.inOrder()
	}

	@Test
	fun `req 1 - a variant the basic model does not list has no variant layers`() {
		val project = project(basicVariant("debug", buildType = "debug", flavors = emptyList(), main = artifact(null, null)))

		val sources = project.variantArtifactSources(variant("release"))

		assertThat(sources.values.flatMap { it.variantSourceProvidersList }).isEmpty()
	}

	private fun dir(name: String) = "/p/src/$name/java"

	private fun provider(name: String): SourceProvider =
		mockk {
			every { this@mockk.name } returns name
			every { javaDirectories } returns listOf(File(dir(name)))
			every { kotlinDirectories } returns emptyList()
			every { resDirectories } returns emptyList()
		}

	private fun container(
		name: String,
		unitTest: String,
	): SourceSetContainer =
		mockk {
			every { sourceProvider } returns provider(name)
			every { testFixturesSourceProvider } returns null
			every { hostTestSourceProviders } returns mapOf("_unit_test_" to provider(unitTest))
			every { deviceTestSourceProviders } returns emptyMap()
		}

	private fun artifact(
		multi: String?,
		own: String?,
	): BasicArtifact =
		mockk {
			every { multiFlavorSourceProvider } returns multi?.let(::provider)
			every { variantSourceProvider } returns own?.let(::provider)
		}

	private fun basicVariant(
		name: String,
		buildType: String,
		flavors: List<String>,
		main: BasicArtifact,
		unitTest: BasicArtifact? = null,
	): BasicVariant =
		mockk {
			every { this@mockk.name } returns name
			every { this@mockk.buildType } returns buildType
			every { productFlavors } returns flavors
			every { mainArtifact } returns main
			every { testFixturesArtifact } returns null
			every { hostTestArtifacts } returns unitTest?.let { mapOf("_unit_test_" to it) }.orEmpty()
			every { deviceTestArtifacts } returns emptyMap()
		}

	/** A full-model variant with no generated sources, so only the basic model's layers show. */
	private fun variant(name: String): Variant =
		mockk(relaxed = true) {
			every { this@mockk.name } returns name
			every { testFixturesArtifact } returns null
			every { hostTestArtifacts } returns emptyMap()
			every { deviceTestArtifacts } returns emptyMap()
		}

	private fun project(vararg variants: BasicVariant): BasicAndroidProject =
		mockk {
			every { this@mockk.variants } returns variants.toList()
			every { buildTypeSourceSets } returns listOf(release, debug)
			every { productFlavorSourceSets } returns listOf(paid, free)
		}
}
