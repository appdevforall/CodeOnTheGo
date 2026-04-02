package com.itsaky.androidide.lsp.kotlin.completion

import com.itsaky.androidide.lsp.edits.IEditHandler
import com.itsaky.androidide.lsp.models.Command
import com.itsaky.androidide.lsp.models.CompletionItem
import com.itsaky.androidide.lsp.models.CompletionItemKind
import com.itsaky.androidide.lsp.models.ICompletionData
import com.itsaky.androidide.lsp.models.InsertTextFormat
import com.itsaky.androidide.lsp.models.MatchLevel
import com.itsaky.androidide.lsp.models.TextEdit

class KotlinCompletionItem(
	ideLabel: String,
	detail: String,
	insertText: String?,
	insertTextFormat: InsertTextFormat?,
	sortText: String?,
	command: Command?,
	completionKind: CompletionItemKind,
	matchLevel: MatchLevel,
	additionalTextEdits: List<TextEdit>?,
	data: ICompletionData?,
	editHandler: IEditHandler = BaseKotlinEditHandler()
) : CompletionItem(
	ideLabel,
	detail,
	insertText,
	insertTextFormat,
	sortText,
	command,
	completionKind,
	matchLevel,
	additionalTextEdits,
	data,
	editHandler
) {

	constructor() : this(
		"", // label
		"", // detail
		null, // insertText
		null, // insertTextFormat
		null, // sortText
		null, // command
		CompletionItemKind.NONE, // kind
		MatchLevel.NO_MATCH, // match level
		ArrayList(), // additionalEdits
		null // data
	)
}