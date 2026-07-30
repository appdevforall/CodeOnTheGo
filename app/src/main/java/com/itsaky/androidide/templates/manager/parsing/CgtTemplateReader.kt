package com.itsaky.androidide.templates.manager.parsing

import com.itsaky.androidide.templates.manager.models.TemplateMetadata
import org.json.JSONObject
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * Pure parser for Code On The Go template (`.cgt`) archives. A `.cgt` is a zip that may
 * bundle one or more templates, each described by a `<path>/template/template.json` entry.
 *
 * Kept free of Android/IDE dependencies so it can be unit-tested directly.
 */
object CgtTemplateReader {
	private const val TEMPLATE_JSON_SUFFIX = "/template/template.json"

	/**
	 * Reads every `<path>/template/template.json` entry from a `.cgt` zip [input] and returns
	 * one [TemplateMetadata] per entry (empty if the archive contains none). The stream is
	 * consumed and closed.
	 */
	fun readTemplates(input: InputStream): List<TemplateMetadata> {
		val templates = mutableListOf<TemplateMetadata>()
		ZipInputStream(input).use { zip ->
			while (true) {
				val entry = zip.nextEntry ?: break
				if (!entry.isDirectory && entry.name.endsWith(TEMPLATE_JSON_SUFFIX)) {
					val json = JSONObject(zip.readBytes().toString(Charsets.UTF_8))
					templates.add(
						TemplateMetadata(
							name = json.optString("name"),
							description = json.optString("description"),
							version = json.optString("version"),
							optionalTags = parseOptionalTags(json),
						),
					)
				}
				zip.closeEntry()
			}
		}
		return templates
	}

	/**
	 * Collects the tags declared under `parameters.optional`, each rendered as
	 * "<tag> (<identifier>)" when the entry carries an identifier, else just "<tag>".
	 */
	fun parseOptionalTags(json: JSONObject): List<String> {
		val optional =
			json.optJSONObject("parameters")?.optJSONObject("optional")
				?: return emptyList()
		val tags = mutableListOf<String>()
		val keys = optional.keys()
		while (keys.hasNext()) {
			val key = keys.next()
			val identifier = optional.optJSONObject(key)?.optString("identifier").orEmpty()
			tags.add(if (identifier.isNotBlank()) "$key ($identifier)" else key)
		}
		return tags
	}
}
