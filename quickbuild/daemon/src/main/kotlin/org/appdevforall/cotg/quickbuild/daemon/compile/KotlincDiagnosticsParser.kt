package org.appdevforall.cotg.quickbuild.daemon.compile

import org.appdevforall.cotg.quickbuild.protocol.Diagnostic

/**
 * Turns kotlinc's rendered log messages into structured diagnostics, so the IDE can jump to
 * file:line. Renderers vary across compiler versions ("file:1:2 message", "file:1:2: error:
 * message"), so the location prefix is matched leniently and anything unrecognized degrades
 * to a location-less diagnostic rather than being dropped.
 */
object KotlincDiagnosticsParser {
	// <path>.kt:<line>:<column> optionally followed by ":", optionally "error:"/"warning:".
	// Matched against the message's FIRST LINE only: `.` must not cross a newline here, or a
	// multi-line message whose location sits on a later line has its first line swallowed into
	// the file group - losing the primary error text and yielding a path no editor can open.
	private val LOCATION =
		Regex("""^(.+?\.(?:kt|kts|java)):(\d+):(\d+):?\s+(?:(error|warning):\s*)?(.*)$""")

	/**
	 * Parses one compiler message into a diagnostic, with location when the text carries one.
	 *
	 * @param message one rendered compiler message, trimmed here; only its first line can carry a
	 *   location, any further lines being kept as message body.
	 * @param severity the severity implied by the logger channel the message arrived on
	 *   (error() -> ERROR, warn() -> WARNING); an explicit "error:"/"warning:" prefix in the
	 *   text wins over it.
	 * @return a diagnostic with file/line/column when the first line carried a location, and the
	 *   whole trimmed message with none when it did not - input is never dropped.
	 */
	fun parse(
		message: String,
		severity: Diagnostic.Severity,
	): Diagnostic {
		val trimmed = message.trim()
		val firstLine = trimmed.substringBefore('\n')
		val body = trimmed.substringAfter('\n', missingDelimiterValue = "")
		val match =
			LOCATION.find(firstLine)
				?: return Diagnostic(severity, trimmed)
		val (file, line, column, severityWord, text) = match.destructured
		val effectiveSeverity =
			when (severityWord) {
				"error" -> Diagnostic.Severity.ERROR
				"warning" -> Diagnostic.Severity.WARNING
				else -> severity
			}
		return Diagnostic(
			severity = effectiveSeverity,
			message = if (body.isEmpty()) text.trim() else (text.trim() + "\n" + body).trim(),
			// kotlinc 2.x renders locations as file:// URIs; the IDE jump-to-editor
			// path (and the protocol example) wants a plain filesystem path.
			file = file.removePrefix("file://"),
			line = line.toIntOrNull(),
			column = column.toIntOrNull(),
		)
	}
}
