package org.appdevforall.codeonthego.lsp.kotlin.completion

import org.appdevforall.codeonthego.lsp.edits.IEditHandler
import org.appdevforall.codeonthego.lsp.models.Command
import org.appdevforall.codeonthego.lsp.models.CompletionItem
import org.appdevforall.codeonthego.lsp.models.CompletionItemKind
import org.appdevforall.codeonthego.lsp.models.ICompletionData
import org.appdevforall.codeonthego.lsp.models.InsertTextFormat
import org.appdevforall.codeonthego.lsp.models.MatchLevel
import org.appdevforall.codeonthego.lsp.models.TextEdit

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