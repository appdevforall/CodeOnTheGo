package com.itsaky.androidide.shortcuts

data class ShortcutDefinition(
	val id: String,
	val title: String,
	val category: ShortcutCategory,
	val bindings: List<KeyShortcut>,
	val contexts: Set<ShortcutContext>,
	val actionId: String,
)
