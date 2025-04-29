package com.itsaky.androidide.actions.sidebar

import android.content.Context
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.itsaky.androidide.R
import com.itsaky.androidide.actions.ActionData
import com.itsaky.androidide.actions.requireContext
import com.itsaky.androidide.utils.EditorSidebarActions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.reflect.KClass

class EmailSidebarAction(context: Context, override val order: Int) : AbstractSidebarAction() {

    override val id: String = "ide.editor.sidebar.email"

    override val fragmentClass: KClass<out Fragment>? = null

    init {
        label = "email"
        icon = ContextCompat.getDrawable(context, R.drawable.ic_mail_24_outlined)
    }

    override suspend fun execAction(data: ActionData): Any {
        val context = data.requireContext()
        withContext(Dispatchers.Main) {
            EditorSidebarActions.showContactDialog(context)
        }
        return Unit
    }
}