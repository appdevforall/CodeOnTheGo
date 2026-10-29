package com.itsaky.androidide.editor.language.outline

interface OutlineProvider {
	fun supports(fileExtension: String): Boolean

	suspend fun outlineOf(
		fileExtension: String,
		text: CharSequence,
	): List<OutlineSymbol>
}
