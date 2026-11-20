package com.itsaky.androidide.tooling.impl.serial

import com.android.builder.model.v2.ide.AbstractArtifact
import com.android.builder.model.v2.ide.ArtifactDependencies
import com.android.builder.model.v2.ide.BasicArtifact
import com.android.builder.model.v2.ide.BasicVariant
import com.android.builder.model.v2.ide.SourceProvider
import com.android.builder.model.v2.ide.SourceSetContainer
import com.android.builder.model.v2.ide.Variant
import com.android.builder.model.v2.models.BasicAndroidProject
import com.android.builder.model.v2.models.VariantDependencies
import com.itsaky.androidide.project.AndroidModels
import com.itsaky.androidide.project.VariantArtifactSources
import com.itsaky.androidide.projects.models.ArtifactScope
import org.gradle.tooling.model.UnsupportedMethodException

// The map keys AGP's model gives the components the single-artifact getters return.
private val UNIT_TEST_TYPE = ArtifactScope.UNIT_TEST.type
private val ANDROID_TEST_TYPE = ArtifactScope.ANDROID_TEST.type

/** One source set's or variant's components as AGP's model reports them, before they are keyed by scope. */
private class ComponentsByType<T : Any>(
	val main: T?,
	val testFixtures: T?,
	val hostTests: Map<String, T>,
	val deviceTests: Map<String, T>,
) {
	fun keyedByScope(): Map<String, T> =
		buildMap {
			main?.let { put(ArtifactScope.Main.key, it) }
			testFixtures?.let { put(ArtifactScope.TestFixtures.key, it) }
			hostTests.forEach { (type, component) -> put(ArtifactScope.HostTest(type).key, component) }
			deviceTests.forEach { (type, component) -> put(ArtifactScope.DeviceTest(type).key, component) }
		}
}

/**
 * AGP's per-type test map, or null on AGP 7.3 to 8.3, which predate it: the Tooling API proxies a
 * missing model method by throwing [UnsupportedMethodException].
 */
private inline fun <T> testMap(get: () -> Map<String, T>): Map<String, T>? =
	try {
		get()
	} catch (e: UnsupportedMethodException) {
		null
	}

/** The single pre-map test component, as a one-entry map under [type]. */
private fun <T : Any> legacyTests(
	type: String,
	component: T?,
): Map<String, T> = component?.let { mapOf(type to it) }.orEmpty()

/** This container's source providers by scope key. */
fun SourceSetContainer.scopedSourceProviders(): Map<String, SourceProvider> =
	ComponentsByType(
		main = sourceProvider,
		testFixtures = testFixturesSourceProvider,
		hostTests =
			testMap { hostTestSourceProviders }
				?: legacyTests(UNIT_TEST_TYPE, @Suppress("DEPRECATION") unitTestSourceProvider),
		deviceTests =
			testMap { deviceTestSourceProviders }
				?: legacyTests(ANDROID_TEST_TYPE, @Suppress("DEPRECATION") androidTestSourceProvider),
	).keyedByScope()

/** The dependencies of each of the variant's components by scope key. */
fun VariantDependencies.scopedArtifacts(): Map<String, ArtifactDependencies> =
	ComponentsByType(
		main = mainArtifact,
		testFixtures = testFixturesArtifact,
		hostTests = testMap { hostTestArtifacts } ?: legacyTests(UNIT_TEST_TYPE, @Suppress("DEPRECATION") unitTestArtifact),
		deviceTests =
			testMap { deviceTestArtifacts }
				?: legacyTests(ANDROID_TEST_TYPE, @Suppress("DEPRECATION") androidTestArtifact),
	).keyedByScope()

private fun Variant.scopedArtifacts(): Map<String, AbstractArtifact> =
	ComponentsByType<AbstractArtifact>(
		main = mainArtifact,
		testFixtures = testFixturesArtifact,
		hostTests = testMap { hostTestArtifacts } ?: legacyTests(UNIT_TEST_TYPE, @Suppress("DEPRECATION") unitTestArtifact),
		deviceTests =
			testMap { deviceTestArtifacts }
				?: legacyTests(ANDROID_TEST_TYPE, @Suppress("DEPRECATION") androidTestArtifact),
	).keyedByScope()

private fun BasicVariant.scopedArtifacts(): Map<String, BasicArtifact> =
	ComponentsByType(
		main = mainArtifact,
		testFixtures = testFixturesArtifact,
		hostTests = testMap { hostTestArtifacts } ?: legacyTests(UNIT_TEST_TYPE, @Suppress("DEPRECATION") unitTestArtifact),
		deviceTests =
			testMap { deviceTestArtifacts }
				?: legacyTests(ANDROID_TEST_TYPE, @Suppress("DEPRECATION") androidTestArtifact),
	).keyedByScope()

/**
 * Per scope key, the source providers AGP layers over that scope's default source set for the
 * variant named [variantName], in the order AGP applies them: the build type, each product flavor,
 * the multi-flavor provider and the variant's own provider. Empty when the basic model does not
 * list the variant.
 */
private fun BasicAndroidProject.variantLayers(variantName: String): Map<String, List<SourceProvider>> {
	val variant = variants.firstOrNull { it.name == variantName } ?: return emptyMap()
	val buildType = buildTypeSourceSets.firstOrNull { it.sourceProvider?.name == variant.buildType }
	val flavors = variant.productFlavors.mapNotNull { flavor -> productFlavorSourceSets.firstOrNull { it.sourceProvider?.name == flavor } }
	val containerLayers = (listOfNotNull(buildType) + flavors).map { it.scopedSourceProviders() }
	val artifacts = variant.scopedArtifacts()

	return (containerLayers.flatMap { it.keys } + artifacts.keys).distinct().associateWith { key ->
		containerLayers.mapNotNull { it[key] } +
			listOfNotNull(artifacts[key]?.multiFlavorSourceProvider, artifacts[key]?.variantSourceProvider)
	}
}

/** Per scope key, the sources each of [variant]'s components adds over its default source set. */
fun BasicAndroidProject.variantArtifactSources(variant: Variant): Map<String, AndroidModels.VariantArtifactSources> {
	val generated = variant.scopedArtifacts().mapValues { (_, artifact) -> artifact.generatedSourceFolders.map { it.absolutePath } }
	val layers = variantLayers(variant.name)
	return (generated.keys + layers.keys).associateWith { key ->
		VariantArtifactSources(
			variantSourceProvidersList = layers[key].orEmpty().map { it.asProtoModel() },
			generatedSourceFolderPathsList = generated[key].orEmpty(),
		)
	}
}
