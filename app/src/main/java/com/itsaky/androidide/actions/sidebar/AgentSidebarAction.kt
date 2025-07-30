package com.itsaky.androidide.actions.sidebar

import android.content.Context
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.itsaky.androidide.R
import com.itsaky.androidide.actions.ActionItem
import com.itsaky.androidide.fragments.sidebar.AgentFragmentContainer
import com.itsaky.androidide.fragments.sidebar.GitFragmentContainer
import kotlin.reflect.KClass

class AgentSidebarAction(context: Context, override val order: Int) :
    AbstractSidebarAction() {

    override val fragmentClass: KClass<out Fragment> = AgentFragmentContainer::class
    override val id: String = "ide.editor.sidebar.agent"
    override var location: ActionItem.Location = ActionItem.Location.EDITOR_RIGHT_SIDEBAR

    companion object {
        const val ID ="ide.editor.sidebar.agent"
    }
    init {
        label = context.getString(R.string.title_agent)
        icon = ContextCompat.getDrawable(context, R.drawable.ic_ai)
    }
}