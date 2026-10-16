package com.itsaky.androidide.tooling.impl.serial

import com.android.builder.model.v2.ide.BasicArtifact
import com.android.builder.model.v2.ide.BasicVariant
import com.android.builder.model.v2.ide.SourceProvider
import com.android.builder.model.v2.ide.SourceSetContainer
import com.android.builder.model.v2.models.BasicAndroidProject
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/**
 * [variantSourceProviders] picks, from the basic model, exactly the providers AGP layers over
 * `main` for one variant, in AGP's order. Build-type and flavor containers are matched by the
 * name AGP gives their provider, so the other build type's container never leaks in.
 */
@RunWith(JUnit4::class)
class VariantSourceProvidersTest {
	private val debug = provider("debug")
	private val release = provider("release")
	private val free = provider("free")
	private val paid = provider("paid")
	private val multiFlavor = provider("free")
	private val freeDebug = provider("freeDebug")

	@Test
	fun `layers build type, flavor, multi-flavor and variant providers in AGP order`() {
		val project =
			project(
				variant("freeDebug", buildType = "debug", flavors = listOf("free"), multi = multiFlavor, own = freeDebug),
			)

		assertThat(project.variantSourceProviders("freeDebug"))
			.containsExactly(debug, free, multiFlavor, freeDebug)
			.inOrder()
	}

	@Test
	fun `a variant without flavors carries only its build type and its own provider`() {
		val own = provider("debug-own")
		val project = project(variant("debug", buildType = "debug", flavors = emptyList(), multi = null, own = own))

		assertThat(project.variantSourceProviders("debug"))
			.containsExactly(debug, own)
			.inOrder()
	}

	@Test
	fun `a variant the basic model does not list yields no providers`() {
		val project = project(variant("debug", buildType = "debug", flavors = emptyList(), multi = null, own = null))

		assertThat(project.variantSourceProviders("release")).isEmpty()
	}

	private fun provider(name: String): SourceProvider = mockk { every { this@mockk.name } returns name }

	private fun container(provider: SourceProvider): SourceSetContainer = mockk { every { sourceProvider } returns provider }

	private fun variant(
		name: String,
		buildType: String,
		flavors: List<String>,
		multi: SourceProvider?,
		own: SourceProvider?,
	): BasicVariant {
		val artifact: BasicArtifact =
			mockk {
				every { multiFlavorSourceProvider } returns multi
				every { variantSourceProvider } returns own
			}
		return mockk {
			every { this@mockk.name } returns name
			every { this@mockk.buildType } returns buildType
			every { productFlavors } returns flavors
			every { mainArtifact } returns artifact
		}
	}

	private fun project(vararg variants: BasicVariant): BasicAndroidProject =
		mockk {
			every { this@mockk.variants } returns variants.toList()
			every { buildTypeSourceSets } returns listOf(container(release), container(debug))
			every { productFlavorSourceSets } returns listOf(container(paid), container(free))
		}
}
