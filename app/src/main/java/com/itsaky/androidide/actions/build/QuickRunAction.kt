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

package com.itsaky.androidide.actions.build

import android.content.Context
import android.graphics.ColorFilter
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import com.itsaky.androidide.actions.ActionData
import com.itsaky.androidide.actions.getContext
import com.itsaky.androidide.resources.R
import com.itsaky.androidide.utils.resolveAttr

/**
 * The 'Quick Run' and 'Cancel build' action in the editor activity.
 *
 * If a build is in progress, executing this action cancels the build. Otherwise, the selected
 * build variant is built and installed to the device.
 *
 * @author Akash Yadav
 */
class QuickRunAction(context: Context, override val order: Int) :
    AbstractRunAction(
        context = context,
        labelRes = R.string.quick_run_debug,
        iconRes = R.drawable.ic_run_outline
    ) {

    override val id: String = "ide.editor.build.quickRun"

    override fun createColorFilter(data: ActionData): ColorFilter? {
        return data.getContext()?.let {
            PorterDuffColorFilter(
                it.resolveAttr(
                    if (data.getActivity().isBuildInProgress())
                        R.attr.colorError
                    else R.attr.colorSuccess
                ), PorterDuff.Mode.SRC_ATOP
            )
        }
    }
}
