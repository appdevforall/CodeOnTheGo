package com.itsaky.androidide.lsp.kotlin.format

import com.facebook.ktfmt.format.Formatter
import com.facebook.ktfmt.format.ParseError
import com.itsaky.androidide.lsp.models.CodeFormatException
import com.itsaky.androidide.lsp.models.CodeFormatResult

internal object KotlinCodeFormatter {
	fun format(content: CharSequence): CodeFormatResult {
		val formatted =
			try {
				Formatter.format(Formatter.KOTLINLANG_FORMAT, content.toString())
			} catch (e: ParseError) {
				val strippedShebangLines = if (content.startsWith("#!")) 1 else 0
				val line = e.lineColumn.line + 1 + strippedShebangLines
				throw CodeFormatException("$line:${e.lineColumn.column + 1}: ${e.errorDescription}", e)
			}
		return CodeFormatResult.forWholeContent(content, formatted)
	}
}
