package com.itsaky.androidide.shortcuts.groups

import android.content.Context
import com.itsaky.androidide.shortcuts.ShortcutDefinition

interface ShortcutGroup {
	fun shortcuts(context: Context): List<ShortcutDefinition>
}
