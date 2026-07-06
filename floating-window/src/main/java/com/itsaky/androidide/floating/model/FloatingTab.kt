

package com.itsaky.androidide.floating.model

import com.itsaky.androidide.floating.window.FloatingWindowState

/** A docked-content + window-state pair: one entry per live floating window. */
data class FloatingTab(
	val content: DockableContent,
	val state: FloatingWindowState,
) {
	val id: String
		get() = content.id
}
