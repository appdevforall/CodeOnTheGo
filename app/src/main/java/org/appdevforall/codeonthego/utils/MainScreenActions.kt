package org.appdevforall.codeonthego.utils

import android.content.Context
import org.appdevforall.codeonthego.actions.ActionItem
import org.appdevforall.codeonthego.actions.ActionsRegistry
import org.appdevforall.codeonthego.actions.main.CloneRepositoryAction
import org.appdevforall.codeonthego.actions.main.CreateProjectAction
import org.appdevforall.codeonthego.actions.main.DeleteProjectAction
import org.appdevforall.codeonthego.actions.main.DonateAction
import org.appdevforall.codeonthego.actions.main.DocsAction
import org.appdevforall.codeonthego.actions.main.OpenProjectAction
import org.appdevforall.codeonthego.actions.main.OpenTerminalAction
import org.appdevforall.codeonthego.actions.main.PreferencesAction

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
