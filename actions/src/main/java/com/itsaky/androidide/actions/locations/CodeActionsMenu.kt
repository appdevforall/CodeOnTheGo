/*
 *  This file is part of AndroidIDE.
 *
 *  AndroidIDE is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  AndroidIDE is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *   along with AndroidIDE.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.itsaky.androidide.actions.locations

import android.content.Context
import android.graphics.drawable.Drawable
import androidx.core.content.ContextCompat
import com.itsaky.androidide.actions.ActionData
import com.itsaky.androidide.actions.ActionItem
import com.itsaky.androidide.actions.ActionMenu
import com.itsaky.androidide.idetooltips.TooltipTag
import com.itsaky.androidide.resources.R
import java.util.concurrent.CopyOnWriteArraySet

/** @author Akash Yadav */
object CodeActionsMenu : ActionMenu {
	const val ID = "ide.editor.code.actions"

	// Registered/unregistered from LSP-dispatch threads (e.g. JavaCompilerSessionImpl's
	// registerCodeActions()/unregisterCodeActions(), called from ensureProjectReset()/
	// shutdown()) concurrently with the UI thread reading it every time the code-actions menu
	// is rendered (ActionMenu.prepare()/isAtLeastOneChildVisible()) -- CopyOnWriteArraySet avoids
	// both a ConcurrentModificationException on a plain set and needing external synchronization
	// around every read, at the cost of a full backing-array copy per add/remove (cheap: this
	// set is small and mutated only on session register/unregister, not per keystroke).
	override val children: MutableSet<ActionItem> = CopyOnWriteArraySet()
	override val id: String = ID

	override var label: String = "Code actions"
	override var visible = true
	override var enabled: Boolean = true
	override var icon: Drawable? = null

	override fun retrieveTooltipTag(isAlternateContext: Boolean) = TooltipTag.EDITOR_TOOLBAR_CODE_ACTIONS

	override var requiresUIThread: Boolean = false
	override var location: ActionItem.Location = ActionItem.Location.EDITOR_TEXT_ACTIONS

	override fun prepare(data: ActionData) {
		super.prepare(data)
		if (icon == null) {
			icon = ContextCompat.getDrawable(data[Context::class.java]!!, R.drawable.ic_code)
		}
	}
}
