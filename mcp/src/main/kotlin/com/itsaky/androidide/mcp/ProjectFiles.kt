package com.itsaky.androidide.mcp

import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent

private const val LAST_PROJECT_KEY = "ide_last_project"

// What the IDE stores when nothing is open. SharedPreferences escapes the angle
// brackets, so it arrives as &lt;NO_OPENED_PROJECT&gt; and only matches after
// unescaping.
private const val NO_OPENED_PROJECT = "<NO_OPENED_PROJECT>"

private val EXCLUDED_DIRECTORIES = setOf("build", ".gradle", ".git")

private const val EXCLUSION_NOTE = "build/, .gradle/ and .git/ are excluded."

// A built project holds tens of thousands of generated files, which is more than
// an agent can read or act on.
internal const val MAX_LISTED_FILES = 500

private const val NO_PROJECT_MESSAGE =
	"No project is currently open in Code On The Go, so there are no project files to list. " +
		"That is an answer, not a failure: open a project from the IDE's home screen and ask again."

private val LAST_PROJECT_PATTERN =
	Regex("""<string\s+name="$LAST_PROJECT_KEY"\s*>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)

/**
 * Returns the project path the IDE currently has open, or null when it has none.
 *
 * Null covers every flavour of "nothing open" -- prefs file absent, key absent,
 * empty value, or the sentinel -- because none of them is a failure.
 */
fun parseLastOpenedProject(xml: String): String? {
	val recorded = LAST_PROJECT_PATTERN.find(xml)?.groupValues?.get(1) ?: return null
	val path = unescapeXml(recorded).trim()
	return path.takeIf { it.isNotEmpty() && it != NO_OPENED_PROJECT }
}

private fun unescapeXml(text: String) =
	text
		.replace("&lt;", "<")
		.replace("&gt;", ">")
		.replace("&quot;", "\"")
		.replace("&apos;", "'")
		// Last, so that an escaped entity such as &amp;lt; does not become a tag.
		.replace("&amp;", "&")

// Projects live on shared storage, which run-as cannot read (the app sandbox's
// storage view is not part of what run-as enters), so this one runs as the plain
// shell user.
internal fun listProjectFilesCommand(projectPath: String): String {
	val excluded = EXCLUDED_DIRECTORIES.joinToString(" -o ") { "-name $it" }
	return "find ${shellQuoted(projectPath)} \\( $excluded \\) -prune -o -type f -print"
}

// Project names contain spaces ("MyApp Basic K"), and the command reaches the
// device as one string that its shell re-parses, so the path must arrive quoted.
private fun shellQuoted(path: String) = "'" + path.replace("'", """'\''""") + "'"

internal fun relativeProjectFiles(
	projectPath: String,
	findOutput: String,
): List<String> {
	val prefix = projectPath.trimEnd('/') + "/"
	return findOutput
		.lineSequence()
		// adb shell emits CRLF.
		.map { it.trim() }
		.filter { it.startsWith(prefix) }
		.map { it.removePrefix(prefix) }
		// find already prunes these; filtering again keeps the guarantee even if a
		// device's find ignores -prune, and makes it testable without a device.
		.filterNot { relative -> relative.split('/').any { it in EXCLUDED_DIRECTORIES } }
		.distinct()
		.sorted()
		.toList()
}

internal fun describeProjectFiles(
	projectPath: String,
	files: List<String>,
): String {
	val header = "Project: ${projectPath.trimEnd('/').substringAfterLast('/')}\nPath: $projectPath"
	if (files.isEmpty()) {
		return "$header\nNo files. $EXCLUSION_NOTE"
	}

	val shown = files.take(MAX_LISTED_FILES)
	val summary =
		if (files.size > shown.size) {
			// Silent truncation reads as "that is everything", which it is not.
			"TRUNCATED: showing the first ${shown.size} of ${files.size} files. $EXCLUSION_NOTE"
		} else {
			"${files.size} files, relative to the project root. $EXCLUSION_NOTE"
		}

	return "$header\n$summary\n\n${shown.joinToString("\n")}"
}

fun listProjectFiles(adb: Adb): CallToolResult {
	val prefs = adb.shell(readCogoPreferencesCommand())
	if (prefs.exitCode != 0) {
		return adbFailure(prefs)
	}

	val projectPath =
		parseLastOpenedProject(prefs.stdout)
			?: return CallToolResult(content = listOf(TextContent(NO_PROJECT_MESSAGE)))

	val listing = adb.shell(listProjectFilesCommand(projectPath))
	if (listing.exitCode != 0) {
		return adbFailure(listing)
	}

	val files = relativeProjectFiles(projectPath, listing.stdout)
	return CallToolResult(content = listOf(TextContent(describeProjectFiles(projectPath, files))))
}
