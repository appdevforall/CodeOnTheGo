package com.itsaky.androidide.mcp

import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent

internal fun isCogoInstalled(adb: Adb): CallToolResult {
	val result = adb.shell("pm", "list", "packages", COGO_PACKAGE)

	// A failed adb call means we do not know, which is not the same as "not
	// installed" - report it as an error rather than a negative answer.
	if (result.exitCode != 0) {
		return adbFailure(result)
	}

	// `pm list packages <filter>` matches substrings, so com.itsaky.androidide.debug
	// would satisfy a query for com.itsaky.androidide. Compare the parsed name
	// exactly. adb shell may emit CRLF, so trim before comparing.
	val installed =
		result.stdout
			.lineSequence()
			.map { it.trim() }
			.filter { it.startsWith("package:") }
			.map { it.removePrefix("package:") }
			.any { it == COGO_PACKAGE }

	val message =
		if (installed) {
			"Code On The Go ($COGO_PACKAGE) is installed."
		} else {
			"Code On The Go ($COGO_PACKAGE) is NOT installed."
		}

	return CallToolResult(content = listOf(TextContent(message)))
}
