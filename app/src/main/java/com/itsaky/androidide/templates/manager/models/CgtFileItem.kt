package com.itsaky.androidide.templates.manager.models

import java.io.File

/**
 * One `<path>/template/template.json` entry parsed out of a `.cgt` archive. A single `.cgt`
 * file can bundle more than one of these (see [CgtFileItem.templates]) - e.g. a plugin's
 * archive offering several related project templates.
 */
data class TemplateMetadata(
	val name: String,
	val description: String,
	val version: String,
	/** Tags declared under parameters.optional in template.json, e.g. "language (LANGUAGE)". */
	val optionalTags: List<String> = emptyList(),
)

/**
 * Where a `.cgt` file came from, inferred from its filename convention (there is no stable
 * template ID: [com.itsaky.androidide.templates.Template.templateId] is a random UUID
 * regenerated on every reload). Matches the convention used by
 * `IdeTemplateServiceImpl`/`PluginProjectManager` when they write into `Environment.TEMPLATES_DIR`.
 */
enum class TemplateProvenance {
	/** The IDE's bundled `core.cgt`. */
	BUNDLED,

	/** Registered by a plugin (`plugin_<pluginId>_*.cgt`). */
	PLUGIN,

	/** Anything else - user-imported via this screen or manually copied in. */
	USER,
}

/**
 * One `.cgt` file discovered on disk, backing a single card in the Templates tab. [templates]
 * holds every template the archive bundles (see [TemplateMetadata]); [installed] is true when
 * [file] lives in `Environment.TEMPLATES_DIR` (the store Gradle reads templates from) rather
 * than the Downloads folder, and [provenance] (see [TemplateProvenance]) says who put it there.
 */
data class CgtFileItem(
	val file: File,
	val name: String,
	val templates: List<TemplateMetadata>,
	val installed: Boolean,
	val provenance: TemplateProvenance,
)

/** The first template's metadata, used to populate the card's title/description/version. */
val CgtFileItem.primaryTemplate: TemplateMetadata
	get() = templates.firstOrNull() ?: TemplateMetadata(name = "", description = "", version = "")

/** True when this .cgt file bundles more than one template. */
val CgtFileItem.hasMultipleTemplates: Boolean
	get() = templates.size > 1

/** [CgtFileItem.name] without the redundant ".cgt" extension, for display only. */
val CgtFileItem.displayName: String
	get() = if (name.endsWith(".cgt", ignoreCase = true)) name.dropLast(4) else name

/**
 * Formats a version for the card's version chip, matching the host Plugin Manager:
 * a "v" prefix, and versions with more than three dot-segments truncated to the first
 * three plus an ellipsis. Blank versions render as an empty string.
 */
fun versionLabel(version: String): String {
	if (version.isBlank()) return ""
	val segments = version.split('.')
	return if (segments.size > 3) "v${segments.take(3).joinToString(".")}..." else "v$version"
}
