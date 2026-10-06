package org.appdevforall.codeonthego.shortcuts.groups

import android.content.Context
import org.appdevforall.codeonthego.shortcuts.ShortcutDefinition

interface ShortcutGroup {
	fun shortcuts(context: Context): List<ShortcutDefinition>
}
