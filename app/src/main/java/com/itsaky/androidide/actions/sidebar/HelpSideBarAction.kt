package com.itsaky.androidide.actions.sidebar

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

import android.content.Context
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.itsaky.androidide.actions.ActionData
import com.itsaky.androidide.actions.requireContext
import com.itsaky.androidide.activities.editor.BaseEditorActivity
import com.itsaky.androidide.resources.R
import kotlin.reflect.KClass

/**
 * Sidebar action for opening the help section.
 *
 * @author Daniel Alome
 */
class HelpSideBarAction(context: Context, override val order: Int) :
    AbstractSidebarAction() {

    override val id: String = "ide.editor.sidebar.help"
    override val fragmentClass: KClass<out Fragment>? = null

    init {
        label = context.getString(R.string.action_open_help)
        icon = ContextCompat.getDrawable(context, R.drawable.ic_action_help_outlined)
    }

    override suspend fun execAction(data: ActionData): Any {
        val context = data.requireContext() as BaseEditorActivity
        context.doOpenHelp()
        return true
    }
}