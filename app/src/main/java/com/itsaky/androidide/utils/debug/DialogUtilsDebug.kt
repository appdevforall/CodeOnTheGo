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
package com.itsaky.androidide.utils.debug

import android.content.Context
import android.content.DialogInterface
import android.widget.LinearLayout
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.itsaky.androidide.R
import com.itsaky.androidide.utils.DialogUtils

/**
 * Utility class for creating debug-specific dialogs.
 *
 * @author
 */
object DialogUtilsDebug {

    /**
     * Creates a dialog with a text field and SET/CANCEL buttons.
     *
     * @param context The context for the dialog.
     * @param title The title of the dialog.
     * @param hint The hint text for the text field.
     * @param defaultValue The default value in the text field.
     * @param onSetClick Callback invoked when SET button is clicked with the text value.
     * @param onCancelClick Optional callback invoked when CANCEL button is clicked.
     * @return The MaterialAlertDialogBuilder instance.
     */
    @JvmStatic
    @JvmOverloads
    fun newTextFieldDialog(
        context: Context,
        title: String,
        hint: String? = null,
        defaultValue: String = "",
        onSetClick: (DialogInterface, TextInputLayout, String) -> Unit,
        onCancelClick: (() -> Unit)? = null
    ): MaterialAlertDialogBuilder {

        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 0)
        }

        val textInputLayout = TextInputLayout(context).apply {
            this.hint = hint ?: context.getString(R.string.debugger_dialog_hint_enter_value)
            boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
        }

        val editText = TextInputEditText(context).apply {
            setText(defaultValue)
            if (defaultValue.isNotEmpty()) {
                selectAll()
            }
        }

        textInputLayout.addView(editText)
        layout.addView(textInputLayout)

        return DialogUtils.newMaterialDialogBuilder(context)
            .setTitle(title)
            .setView(layout)
            .setPositiveButton(context.getString(R.string.debugger_dialog_button_set)) { dialog, _ ->
                val value = editText.text?.toString() ?: ""
                onSetClick(dialog, textInputLayout, value)
            }
            .setNegativeButton(context.getString(R.string.debugger_dialog_button_cancel)) { dialog, _ ->
                dialog.dismiss()
                onCancelClick?.invoke()
            }
            .setCancelable(true)
    }
}