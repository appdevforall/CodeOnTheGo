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

package com.itsaky.androidide.actions.etc

import android.content.Context
import androidx.core.content.ContextCompat
import com.itsaky.androidide.actions.ActionData
import com.itsaky.androidide.actions.EditorActivityAction
import com.itsaky.androidide.actions.build.AbstractCancellableRunAction.Companion.isBuildInProgress
import com.itsaky.androidide.idetooltips.TooltipTag
import com.itsaky.androidide.resources.R

/** @author Akash Yadav */
class FindAction() : EditorActivityAction() {

    override var requiresUIThread: Boolean = true
    override var order: Int = 0
    override var tooltipTag: String = TooltipTag.EDITOR_TOOLBAR_FIND

    constructor(context: Context, order: Int) : this() {
        this.label = context.getString(R.string.menu_find)
        this.icon = ContextCompat.getDrawable(context, R.drawable.ic_search)
        this.order = order
    }

    override val id: String = "ide.editor.find"

    override fun prepare(data: ActionData) {
        super.prepare(data)
        enabled = data.getActivity().isBuildInProgress().not()
    }

    override suspend fun execAction(data: ActionData): Boolean {
        val context = data.getActivity() ?: return false
        val dialog = context.findActionDialog(data)

        return run {
            dialog.show()
            true
        }
    }
}
