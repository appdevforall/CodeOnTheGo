package com.itsaky.androidide.plugins.manager.loaders

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Covers the provenance half of manifest parsing: a `.cgp` that states no revision, one that states
 * a blank revision, and one that states a real revision have to arrive as three distinct answers,
 * because the details dialog and the crash context both key off null to mean "this build predates
 * provenance".
 *
 * Both readers -- the AndroidManifest `meta-data` path in `PluginLoader.getPluginMetadata()` and
 * the `plugin.json` path in [PluginManifestParser] -- normalize through [normalizeProvenanceValue],
 * so the shared step is what is covered here. The `meta-data` read itself needs a `Bundle` and so
 * an instrumented runtime; this module's unit tests are plain JVM.
 */
class PluginProvenanceMappingTest {
	@Test
	fun anAbsentProvenanceValueStaysNull() {
		assertThat(normalizeProvenanceValue(null)).isNull()
	}

	@Test
	fun aBlankProvenanceValueBecomesNull() {
		assertThat(normalizeProvenanceValue("")).isNull()
		assertThat(normalizeProvenanceValue("   ")).isNull()
		assertThat(normalizeProvenanceValue("\t\n")).isNull()
	}

	@Test
	fun aStatedProvenanceValueIsForwardedVerbatim() {
		assertThat(normalizeProvenanceValue(REVISION)).isEqualTo(REVISION)
		assertThat(normalizeProvenanceValue("$REVISION+dirty")).isEqualTo("$REVISION+dirty")
		assertThat(normalizeProvenanceValue("unknown")).isEqualTo("unknown")
	}

	@Test
	fun aJsonManifestStatingNoProvenanceParsesToNulls() {
		val manifest = parse()

		assertThat(manifest.vcsRevision).isNull()
		assertThat(manifest.buildTimestamp).isNull()
	}

	@Test
	fun aJsonManifestStatingBlankProvenanceParsesToNulls() {
		val manifest = parse(""""vcs_revision": "   ", "build_timestamp": "",""")

		assertThat(manifest.vcsRevision).isNull()
		assertThat(manifest.buildTimestamp).isNull()
	}

	@Test
	fun aJsonManifestStatingRealProvenanceKeepsIt() {
		val manifest = parse(""""vcs_revision": "$REVISION", "build_timestamp": "$TIMESTAMP",""")

		assertThat(manifest.vcsRevision).isEqualTo(REVISION)
		assertThat(manifest.buildTimestamp).isEqualTo(TIMESTAMP)
	}

	@Test
	fun theMetadataMappingForwardsProvenance() {
		val metadata =
			parse(""""vcs_revision": "$REVISION+dirty", "build_timestamp": "$TIMESTAMP",""")
				.toPluginMetadata()

		assertThat(metadata.vcsRevision).isEqualTo("$REVISION+dirty")
		assertThat(metadata.buildTimestamp).isEqualTo(TIMESTAMP)
	}

	@Test
	fun theMetadataMappingLeavesLegacyProvenanceNull() {
		val metadata = parse().toPluginMetadata()

		assertThat(metadata.vcsRevision).isNull()
		assertThat(metadata.buildTimestamp).isNull()
	}

	private fun parse(provenance: String = ""): PluginManifest {
		val json =
			"""
			{
				"id": "com.example.plugin",
				"name": "Example",
				"version": "26.36.1",
				"description": "An example",
				"author": "Example Author",
				"main_class": "com.example.plugin.ExamplePlugin",
				"min_ide_version": "26.36",
				$provenance
				"permissions": []
			}
			""".trimIndent()

		return requireNotNull(PluginManifestParser.parseFromString(json)) {
			"the manifest fixture failed to parse"
		}
	}

	private companion object {
		const val REVISION = "a1b2c3d4e5f6"
		const val TIMESTAMP = "20260907181500"
	}
}
