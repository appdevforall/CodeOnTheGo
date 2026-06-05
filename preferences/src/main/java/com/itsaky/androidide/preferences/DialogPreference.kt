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

package com.itsaky.androidide.preferences

import androidx.preference.Preference
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.itsaky.androidide.idetooltips.TooltipManager
import com.itsaky.androidide.utils.DialogUtils
import com.itsaky.androidide.utils.applyLongPressRecursively

/**
 * A preference which shows a dialog when clicked.
 *
 * @author Akash Yadav
 */
abstract class DialogPreference : SimplePreference() {

  open val dialogTitle: Int
    get() = this.title

  open val dialogMessage: Int? = null
  open val dialogCancellable: Boolean = true
  open val tooltipTag: String = ""

  override fun onPreferenceClick(preference: Preference): Boolean {
    val dialog = DialogUtils.newMaterialDialogBuilder(preference.context)
    dialog.setTitle(this.dialogTitle)
    dialogMessage?.let { dialog.setMessage(it) }
    dialog.setCancelable(this.dialogCancellable)
    dialog.setOnCancelListener { onDialogCancelled(preference) }
    onConfigureDialog(preference, dialog)
    val alertDialog = dialog.create()
    alertDialog.show()

      alertDialog.window?.decorView?.applyLongPressRecursively {
          TooltipManager.showIdeCategoryTooltip(preference.context, it, tooltipTag)
          true
      }

    return true
  }

  protected open fun onConfigureDialog(preference: Preference,
    dialog: MaterialAlertDialogBuilder) {
  }

  /**
   * Called when the dialog is cancelled by the user (e.g. the system back button), as opposed to
   * being dismissed by an action button. The default behaviour discards any unconfirmed changes,
   * matching the dialog's cancel button.
   */
  protected open fun onDialogCancelled(preference: Preference) {
  }
}
