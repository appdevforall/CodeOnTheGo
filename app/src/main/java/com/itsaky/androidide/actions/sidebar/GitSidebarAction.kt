package com.itsaky.androidide.actions.sidebar

import android.content.Context
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.itsaky.androidide.R
import com.itsaky.androidide.actions.ActionItem
import com.itsaky.androidide.fragments.sidebar.GitFragmentContainer
import kotlin.reflect.KClass

/**
 * Sidebar action for showing the build variants of Android modules in the project.
 *
 * @author Akash Yadav
 */
class GitSidebarAction(context: Context, override val order: Int) :
    AbstractSidebarAction() {

    override val fragmentClass: KClass<out Fragment> = GitFragmentContainer::class
    override val id: String = "ide.editor.sidebar.git"
    override var location: ActionItem.Location = ActionItem.Location.EDITOR_RIGHT_SIDEBAR

    init {
        label = context.getString(R.string.title_git)
        icon = ContextCompat.getDrawable(context, R.drawable.ic_git)
    }
}