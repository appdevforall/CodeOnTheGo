package com.itsaky.androidide.mcp

import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.delay
import java.util.Base64

const val MAIN_ACTIVITY = "$COGO_PACKAGE/.activities.MainActivity"

private const val AUTO_OPEN_KEY = "idepref_general_autoOpenProjects"

private const val AUTO_OPEN_ELEMENT = """<boolean name="$AUTO_OPEN_KEY" value="false" />"""

private const val PREFS_HEADER = """<?xml version='1.0' encoding='utf-8' standalone='yes' ?>"""

/**
 * Returns [existingXml] with auto-open-project disabled, creating the document if
 * it is absent.
 *
 * tryOpenLastProject() falls back to the most recently modified project when no
 * last project is recorded, so clearing ide_last_project would not be enough --
 * this boolean is the only thing that reliably prevents the jump to the editor.
 *
 * The edit is element-wise, not line-wise, and so is safe to repeat: an earlier
 * version deleted whole lines on-device and destroyed the `<map>` tag whenever a
 * previous run had left it sharing a line with the boolean.
 */
fun withAutoOpenDisabled(existingXml: String): String {
	val body = existingXml.trim()
	if (body.isEmpty() || !body.contains("<map")) {
		return "$PREFS_HEADER\n<map>\n    $AUTO_OPEN_ELEMENT\n</map>\n"
	}

	val updated =
		body
			.replace(Regex("""<map\s*/>"""), "<map>\n</map>")
			.replace(Regex("""\s*<boolean\s+name="$AUTO_OPEN_KEY"[^>]*/>"""), "")
			.replace("</map>", "    $AUTO_OPEN_ELEMENT\n</map>")

	// Trailing newline kept consistent with the created-from-scratch document, so
	// feeding this function its own output is a no-op.
	return updated.trimEnd() + "\n"
}

// base64 so the XML survives adb's argv-joining and the device shell intact.
private fun writePreferencesCommand(xml: String): String {
	val encoded = Base64.getEncoder().encodeToString(xml.toByteArray())
	return runAs("mkdir -p shared_prefs && echo $encoded | base64 -d > $COGO_PREFS_PATH")
}

internal suspend fun cogoHome(
	adb: Adb,
	attempts: Int,
	delayMillis: Long,
): CallToolResult {
	// Stop first: a running app holds its preferences in memory and would write
	// them back over our edit when it exits.
	adb.shell("am", "force-stop", COGO_PACKAGE).let {
		if (it.exitCode != 0) return adbFailure(it)
	}
	val existingPrefs = adb.shell(readCogoPreferencesCommand())
	if (existingPrefs.exitCode != 0) {
		return adbFailure(existingPrefs)
	}
	adb.shell(writePreferencesCommand(withAutoOpenDisabled(existingPrefs.stdout))).let {
		if (it.exitCode != 0) return adbFailure(it)
	}
	// Explicit component: debug builds ship a second LAUNCHER activity, so a
	// category-based launch is ambiguous.
	adb.shell("am", "start", "-n", MAIN_ACTIVITY).let {
		if (it.exitCode != 0) return adbFailure(it)
	}

	var resumed: String? = null
	repeat(attempts) { attempt ->
		if (attempt > 0) {
			delay(delayMillis)
		}
		val dump = adb.shell("dumpsys", "activity", "activities")
		if (dump.exitCode != 0) {
			return adbFailure(dump)
		}
		resumed = resumedActivity(dump.stdout)
		if (resumed == MAIN_ACTIVITY) {
			return CallToolResult(
				content = listOf(TextContent("Code On The Go is on its home screen (MainActivity).")),
			)
		}
	}

	val where = resumed ?: "nothing (the app never reached the foreground)"
	return CallToolResult(
		content = listOf(TextContent("Launched Code On The Go, but the foreground activity is $where, not the home screen.")),
		isError = true,
	)
}

private fun resumedActivity(dumpsys: String): String? =
	dumpsys
		.lineSequence()
		.firstOrNull { it.contains("topResumedActivity=") }
		?.substringAfter("topResumedActivity=")
		?.split(" ", "}")
		?.firstOrNull { it.contains("/") }
