package com.itsaky.androidide.mcp

import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent

private const val TEMPLATES_DIR = "files/home/.cg/templates"

// core.cgt ships with the app; plugins add their own plugin_<id>_*.cgt beside it.
private const val ARCHIVE_SUFFIX = ".cgt"

private const val INDEX_MEMBER = "templates.json"

// `ls` exits 1 on an absent directory, and the directory only appears once
// onboarding has unpacked the templates - so guard it rather than read a
// pre-onboarding device as an unreachable one.
private val LIST_ARCHIVES_COMMAND = runAs("ls $TEMPLATES_DIR 2>/dev/null || true")

private const val NOTHING_INSTALLED =
	"No project templates are installed yet: $TEMPLATES_DIR is empty or missing. " +
		"Code On The Go unpacks its templates during first-run onboarding, so this normally means " +
		"onboarding has not finished rather than that anything is broken."

// template.json is not strict JSON - it carries unquoted keys such as
// `{identifier: "APP_NAME"}`, which JSONObject and kotlinx.serialization both
// reject. The fields we want are quoted, so match them directly. The leading
// quote keeps "name" from matching "appName".
private fun stringFieldPattern(field: String) = Regex("\"$field\"\\s*:\\s*\"([^\"]*)\"")

private val PATH_PATTERN = stringFieldPattern("path")

private val NAME_PATTERN = stringFieldPattern("name")

private val DESCRIPTION_PATTERN = stringFieldPattern("description")

/** Template directory names listed by an archive's `templates.json`, in declared order. */
fun parseTemplateIndex(json: String): List<String> =
	PATH_PATTERN
		.findAll(json)
		.map { it.groupValues[1].trim() }
		.filter { it.isNotEmpty() }
		.toList()

/** Display name from a `template.json`, or null when it declares none. */
fun parseTemplateName(json: String): String? = firstStringField(NAME_PATTERN, json)

/** Description from a `template.json`, or null when it declares none. */
fun parseTemplateDescription(json: String): String? = firstStringField(DESCRIPTION_PATTERN, json)

// Both fields lead template.json, so the first match is the top-level one rather
// than anything nested under "parameters".
private fun firstStringField(
	pattern: Regex,
	json: String,
): String? =
	pattern
		.find(json)
		?.groupValues
		?.get(1)
		?.trim()
		?.ifEmpty { null }

private fun readMemberCommand(
	archive: String,
	member: String,
) = runAs("unzip -p $TEMPLATES_DIR/$archive $member")

fun listTemplates(adb: Adb): CallToolResult {
	val listing = adb.shell(LIST_ARCHIVES_COMMAND)
	if (listing.exitCode != 0) {
		return adbFailure(listing)
	}

	// adb shell emits CRLF; an untrimmed name would build `unzip -p .../core.cgt\r`.
	val archives =
		listing.stdout
			.lineSequence()
			.map { it.trim() }
			.filter { it.endsWith(ARCHIVE_SUFFIX) }
			.toList()
	if (archives.isEmpty()) {
		return CallToolResult(content = listOf(TextContent(NOTHING_INSTALLED)))
	}

	val described = mutableListOf<String>()
	for (archive in archives) {
		val index = adb.shell(readMemberCommand(archive, INDEX_MEMBER))
		if (index.exitCode != 0) {
			return adbFailure(index)
		}
		for (path in parseTemplateIndex(index.stdout)) {
			val template = adb.shell(readMemberCommand(archive, "$path/template/template.json"))
			if (template.exitCode != 0) {
				return adbFailure(template)
			}
			described += describe(path, template.stdout)
		}
	}

	if (described.isEmpty()) {
		return CallToolResult(
			content =
				listOf(
					TextContent(
						"Found ${archives.joinToString(", ")} in $TEMPLATES_DIR, but no archive declares any templates.",
					),
				),
		)
	}

	val plural = if (described.size == 1) "template" else "templates"
	val heading = "Code On The Go has ${described.size} project $plural installed:"
	return CallToolResult(content = listOf(TextContent((listOf(heading) + described).joinToString("\n"))))
}

// The directory name is the fallback label: a template with no declared name is
// still selectable, so naming nothing would be worse than naming it awkwardly.
private fun describe(
	path: String,
	templateJson: String,
): String {
	val name = parseTemplateName(templateJson) ?: path
	val description = parseTemplateDescription(templateJson)
	return if (description == null) name else "$name - $description"
}
