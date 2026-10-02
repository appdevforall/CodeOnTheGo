package org.jetbrains.kotlin.cli.common.messages

fun interface MessageRenderer {
	fun render(
		severity: CompilerMessageSeverity,
		message: String,
		location: CompilerMessageSourceLocation?,
	): String

	companion object {
		@JvmField
		val PLAIN_RELATIVE_PATHS: MessageRenderer =
			MessageRenderer { severity, message, location ->
				if (location == null) {
					"${severity.presentableName}: $message"
				} else {
					"${location.path}:${location.line}:${location.column}: ${severity.presentableName}: $message"
				}
			}
	}
}
