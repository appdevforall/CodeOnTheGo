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
				throw CodeFormatException("${e.lineColumn.line + 1}:${e.lineColumn.column + 1}: ${e.errorDescription}", e)
			}
		return CodeFormatResult.forWholeContent(content, formatted)
	}
}
