package org.appdevforall.codeonthego.layouteditor.editor.callers.text

import android.content.Context
import android.view.View
import com.google.android.material.textfield.TextInputLayout
import org.appdevforall.codeonthego.layouteditor.managers.ProjectManager
import org.appdevforall.codeonthego.layouteditor.managers.ValuesManager
import org.appdevforall.codeonthego.layouteditor.tools.ValuesResourceParser

object TextInputLayoutCaller {

    @JvmStatic
    fun setHint(target: View, value: String, context: Context) {
        var finalValue = value
        if (finalValue.startsWith("@string/")) {
            val project = ProjectManager.instance.openedProject ?: return
            finalValue = ValuesManager.getValueFromResources(
                ValuesResourceParser.TAG_STRING, finalValue, project.stringsPath
            )
        }
        (target as TextInputLayout).hint = finalValue
    }

    @JvmStatic
    fun setHintEnabled(target: View, value: String, context: Context) {
        (target as TextInputLayout).isHintEnabled = value.toBoolean()
    }

    @JvmStatic
    fun setErrorEnabled(target: View, value: String, context: Context) {
        (target as TextInputLayout).isErrorEnabled = value.toBoolean()
    }

    @JvmStatic
    fun setCounterEnabled(target: View, value: String, context: Context) {
        (target as TextInputLayout).isCounterEnabled = value.toBoolean()
    }
}
