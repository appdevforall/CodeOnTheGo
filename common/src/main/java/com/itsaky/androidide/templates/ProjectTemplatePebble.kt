package com.itsaky.androidide.templates

import io.pebbletemplates.pebble.PebbleEngine
import io.pebbletemplates.pebble.lexer.Syntax
import io.pebbletemplates.pebble.loader.StringLoader

/**
 * The Pebble settings of Code on the Go's project templates, shared with the SQL templates the SQLite debugger's
 * `.read` renders (ADFA-7302), so both read a template the same way.
 */
object ProjectTemplatePebble {
	const val PRINT_OPEN = "\${{"
	const val PRINT_CLOSE = "}}"
	const val EXECUTE_OPEN = "\${%"
	const val EXECUTE_CLOSE = "%}"
	const val COMMENT_OPEN = "\${#"
	const val COMMENT_CLOSE = "#}"

	fun syntax(): Syntax =
		Syntax
			.Builder()
			.setPrintOpenDelimiter(PRINT_OPEN)
			.setPrintCloseDelimiter(PRINT_CLOSE)
			.setExecuteOpenDelimiter(EXECUTE_OPEN)
			.setExecuteCloseDelimiter(EXECUTE_CLOSE)
			.setCommentOpenDelimiter(COMMENT_OPEN)
			.setCommentCloseDelimiter(COMMENT_CLOSE)
			.build()

	/** A template is its own text ([StringLoader]), an unknown variable is an error, and newline trimming is Pebble's default (on). */
	fun engineBuilder(): PebbleEngine.Builder =
		PebbleEngine
			.Builder()
			.loader(StringLoader())
			.strictVariables(true)
			.syntax(syntax())
}
