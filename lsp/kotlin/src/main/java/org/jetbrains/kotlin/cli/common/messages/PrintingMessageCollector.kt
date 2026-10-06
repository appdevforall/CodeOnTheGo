package org.jetbrains.kotlin.cli.common.messages

import java.io.PrintStream

class PrintingMessageCollector(
	private val errStream: PrintStream,
	private val messageRenderer: MessageRenderer,
	private val verbose: Boolean,
) : MessageCollector {
	private var hasErrors = false

	override fun clear() = Unit

	override fun report(
		severity: CompilerMessageSeverity,
		message: String,
		location: CompilerMessageSourceLocation?,
	) {
		if (!verbose && severity in CompilerMessageSeverity.VERBOSE) return
		hasErrors = hasErrors || severity.isError
		errStream.println(messageRenderer.render(severity, message, location))
	}

	override fun hasErrors(): Boolean = hasErrors
}
