package com.itsaky.androidide.utils

import android.content.Context
import com.itsaky.androidide.actions.ActionItem
import com.itsaky.androidide.actions.ActionsRegistry
import com.itsaky.androidide.actions.main.CloneRepositoryAction
import com.itsaky.androidide.actions.main.CreateProjectAction
import com.itsaky.androidide.actions.main.DeleteProjectAction
import com.itsaky.androidide.actions.main.DonateAction
import com.itsaky.androidide.actions.main.DocsAction
import com.itsaky.androidide.actions.main.OpenProjectAction
import com.itsaky.androidide.actions.main.OpenTerminalAction
import com.itsaky.androidide.actions.main.PreferencesAction

/**
 * Takes care of registering actions to the actions registry for the main screen.
 */
object MainScreenActions {

    @JvmStatic
    fun register(context: Context) {
        clear()
        val registry = ActionsRegistry.getInstance()
        registry.registerAction(CreateProjectAction(context))
        registry.registerAction(OpenProjectAction(context))
        registry.registerAction(CloneRepositoryAction(context))
        registry.registerAction(DeleteProjectAction(context))
        registry.registerAction(OpenTerminalAction(context))
        registry.registerAction(PreferencesAction(context))
        registry.registerAction(DonateAction(context))
        registry.registerAction(DocsAction(context))
    }

    @JvmStatic
    fun clear() {
        ActionsRegistry.getInstance().clearActions(ActionItem.Location.MAIN_SCREEN)
    }
}
