package org.appdevforall.cotg.quickbuild.daemon.compile

import org.appdevforall.cotg.quickbuild.daemon.protocol.Diagnostic

/**
 * Turns kotlinc's rendered log messages into structured diagnostics, so the IDE can jump to
 * file:line. Renderers vary across compiler versions ("file:1:2 message", "file:1:2: error:
 * message"), so the location prefix is matched leniently and anything unrecognized degrades
 * to a location-less diagnostic rather than being dropped.
 */
object KotlincDiagnosticsParser {
	// <path>.kt:<line>:<column> optionally followed by ":", optionally "error:"/"warning:".
	private val LOCATION =
		Regex("""^(.+?\.(?:kt|kts|java)):(\d+):(\d+):?\s+(?:(error|warning):\s*)?(.*)$""", RegexOption.DOT_MATCHES_ALL)

	/**
	 * Parses one compiler message into a diagnostic, with location when the text carries one.
	 *
	 * @param message one rendered compiler message; may span several lines, and is trimmed here.
	 * @param severity the severity implied by the logger channel the message arrived on
	 *   (error() -> ERROR, warn() -> WARNING); an explicit "error:"/"warning:" prefix in the
	 *   text wins over it.
	 * @return a diagnostic with file/line/column when the text carried a location, and the
	 *   whole trimmed message with none when it did not - input is never dropped.
	 */
	fun parse(
		message: String,
		severity: Diagnostic.Severity,
	): Diagnostic {
		val match =
			LOCATION.find(message.trim())
				?: return Diagnostic(severity, message.trim())
		val (file, line, column, severityWord, text) = match.destructured
		val effectiveSeverity =
			when (severityWord) {
				"error" -> Diagnostic.Severity.ERROR
				"warning" -> Diagnostic.Severity.WARNING
				else -> severity
			}
		return Diagnostic(
			severity = effectiveSeverity,
			message = text.trim(),
			// kotlinc 2.x renders locations as file:// URIs; the IDE jump-to-editor
			// path (and the protocol example) wants a plain filesystem path.
			file = file.removePrefix("file://"),
			line = line.toIntOrNull(),
			column = column.toIntOrNull(),
		)
	}
}
