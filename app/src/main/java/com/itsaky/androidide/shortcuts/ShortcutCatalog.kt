package com.itsaky.androidide.shortcuts

import android.content.Context

class ShortcutCatalog(
	private val groupProvider: ShortcutGroupProvider = ShortcutGroupProvider(),
) {

	fun all(context: Context): List<ShortcutDefinition> {
		val groups = groupProvider.all()
		return groups.flatMap { group -> group.shortcuts(context) }
	}
}
