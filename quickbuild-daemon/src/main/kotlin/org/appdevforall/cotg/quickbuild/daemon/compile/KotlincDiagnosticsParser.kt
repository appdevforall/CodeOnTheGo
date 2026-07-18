package org.appdevforall.cotg.quickbuild.daemon.compile

import org.appdevforall.cotg.quickbuild.daemon.protocol.Diagnostic

/**
 * Parses kotlinc-rendered messages (as delivered through the BTA [KotlinLogger]) into
 * the protocol's structured shape so the IDE can jump to file:line. Renderers vary a
 * little across compiler versions ("file:1:2 message", "file:1:2: error: message"), so
 * the location prefix is matched leniently and anything unparseable degrades to a
 * location-less diagnostic - a build error must never be dropped because its text
 * surprised us.
 */
object KotlincDiagnosticsParser {
	// <path>.kt:<line>:<column> optionally followed by ":", optionally "error:"/"warning:".
	private val LOCATION =
		Regex("""^(.+?\.(?:kt|kts|java)):(\d+):(\d+):?\s+(?:(error|warning):\s*)?(.*)$""", RegexOption.DOT_MATCHES_ALL)

	/**
	 * @param severity the severity implied by the logger channel the message arrived on
	 *   (error() -> ERROR, warn() -> WARNING); an explicit "error:"/"warning:" prefix in
	 *   the text wins when present.
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
