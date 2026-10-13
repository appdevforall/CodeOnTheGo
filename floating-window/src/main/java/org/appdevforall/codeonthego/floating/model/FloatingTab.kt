

package org.appdevforall.codeonthego.floating.model

import org.appdevforall.codeonthego.floating.window.FloatingWindowState

/** A docked-content + window-state pair: one entry per live floating window. */
data class FloatingTab(
	val content: DockableContent,
	val state: FloatingWindowState,
) {
	val id: String
		get() = content.id
}
