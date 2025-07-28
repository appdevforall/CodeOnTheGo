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

package com.itsaky.androidide.utils

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.annotation.IdRes
import androidx.core.net.toUri
import androidx.core.view.forEach
import androidx.navigation.NavController
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.createGraph
import androidx.navigation.fragment.FragmentNavigator
import androidx.navigation.fragment.FragmentNavigatorDestinationBuilder
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.get
import androidx.navigation.navOptions
import com.google.android.material.shape.CornerFamily
import com.google.android.material.shape.MaterialShapeDrawable
import com.google.android.material.shape.ShapeAppearanceModel
import com.itsaky.androidide.R
import com.itsaky.androidide.actions.ActionData
import com.itsaky.androidide.actions.ActionItem
import com.itsaky.androidide.actions.ActionsRegistry
import com.itsaky.androidide.actions.FillMenuParams
import com.itsaky.androidide.actions.SidebarActionItem
import com.itsaky.androidide.actions.internal.DefaultActionsRegistry
import com.itsaky.androidide.actions.sidebar.CloseProjectSidebarAction
import com.itsaky.androidide.actions.sidebar.EmailSidebarAction
import com.itsaky.androidide.actions.sidebar.FileTreeSidebarAction
import com.itsaky.androidide.actions.sidebar.GitSidebarAction
import com.itsaky.androidide.actions.sidebar.HelpSideBarAction
import com.itsaky.androidide.actions.sidebar.PreferencesSidebarAction
import com.itsaky.androidide.actions.sidebar.TerminalSidebarAction
import com.itsaky.androidide.fragments.sidebar.EditorSidebarFragment
import com.itsaky.androidide.utils.ContactDetails.EMAIL_SUPPORT
import java.lang.ref.WeakReference

/**
 * Sets up the actions that are shown in the
 * [EditorActivityKt][com.itsaky.androidide.activities.editor.EditorActivityKt]'s drawer's sidebar.
 *
 * @author Akash Yadav
 */

object ContactDetails {
    const val EMAIL_SUPPORT = "feedback@appdevforall.org"
}

internal object EditorSidebarActions {
    val tooltipTags = mutableListOf<String>()

    @JvmStatic
    fun registerActions(context: Context) {
        val registry = ActionsRegistry.getInstance()
        var order = -1

        @Suppress("KotlinConstantConditions")
        registry.registerAction(FileTreeSidebarAction(context, ++order))
//        registry.registerAction(BuildVariantsSidebarAction(context, ++order))
        registry.registerAction(GitSidebarAction(context, ++order))
        registry.registerAction(TerminalSidebarAction(context, ++order))
        registry.registerAction(PreferencesSidebarAction(context, ++order))
        registry.registerAction(CloseProjectSidebarAction(context, ++order))
        registry.registerAction(HelpSideBarAction(context, ++order))
        registry.registerAction(EmailSidebarAction(context, ++order))
    }

    @JvmStatic
    fun setup(sidebarFragment: EditorSidebarFragment) {
        val binding = sidebarFragment.getBinding() ?: return
        val controller = binding.fragmentContainer.getFragment<NavHostFragment>().navController
        val context = sidebarFragment.requireContext()
        val rail = binding.navigation


        val registry = ActionsRegistry.getInstance()
        val actions = registry.getActions(ActionItem.Location.EDITOR_SIDEBAR)
        if (actions.isEmpty()) {
            return
        }

        rail.background = (rail.background as MaterialShapeDrawable).apply {
            shapeAppearanceModel = shapeAppearanceModel.roundedOnRight()
        }

        rail.menu.clear()

        val data = ActionData.create(context)
        val titleRef = WeakReference(binding.title)
        val params = FillMenuParams(
            data,
            ActionItem.Location.EDITOR_SIDEBAR,
            rail.menu
        ) { actionsRegistry, action, item, actionsData ->
            action as SidebarActionItem

            if (action.fragmentClass == null) {
                (actionsRegistry as DefaultActionsRegistry).executeAction(action, actionsData)
                return@FillMenuParams true
            }

            return@FillMenuParams try {
                controller.navigate(action.id, navOptions {
                    launchSingleTop = true
                    restoreState = true
                })

                val result = controller.currentDestination?.matchDestination(action.id) == true
                if (result) {
                    item.isChecked = true
                    titleRef.get()?.text = item.title
                }

                result
            } catch (e: IllegalArgumentException) {
                false
            }
        }

        registry.fillMenu(params)

        rail.menu.forEach { item ->
            val view = rail.findViewById<View>(item.itemId)
            val action = actions.values.find { it.itemId == item.itemId } as? SidebarActionItem

            if (view != null && action != null) {
                val tag = action.tooltipTag()
                sidebarFragment.setupTooltip(view, "ide", tag)
                tooltipTags += tag
            }
        }

        controller.graph = controller.createGraph(startDestination = FileTreeSidebarAction.ID) {
            actions.forEach { (actionId, action) ->
                if (action !is SidebarActionItem) {
                    throw IllegalStateException(
                        "Actions registered at location ${ActionItem.Location.EDITOR_SIDEBAR}" +
                                " must implement ${SidebarActionItem::class.java.simpleName}"
                    )
                }

                val fragment = action.fragmentClass ?: return@forEach

                val builder = FragmentNavigatorDestinationBuilder(
                    provider[FragmentNavigator::class],
                    actionId,
                    fragment
                )

                builder.apply {
                    action.apply { buildNavigation() }
                }

                destination(builder)
            }
        }

        val railRef = WeakReference(rail)
        controller.addOnDestinationChangedListener(
            object : NavController.OnDestinationChangedListener {
                override fun onDestinationChanged(
                    controller: NavController,
                    destination: NavDestination,
                    arguments: Bundle?
                ) {
                    val railView = railRef.get()
                    if (railView == null) {
                        controller.removeOnDestinationChangedListener(this)
                        return
                    }
                    railView.menu.forEach { item ->
                        if (destination.matchDestination(item.itemId)) {
                            item.isChecked = true
                            titleRef.get()?.text = item.title
                        }
                    }
                }
            })

        rail.menu.findItem(FileTreeSidebarAction.ID.hashCode())?.also {
            it.isChecked = true
            binding.title.text = it.title
        }
    }

    /**
     * Determines whether the given `route` matches the NavDestination. This handles
     * both the default case (the destination's route matches the given route) and the nested case where
     * the given route is a parent/grandparent/etc of the destination.
     */
    @JvmStatic
    internal fun NavDestination.matchDestination(route: String): Boolean =
        hierarchy.any { it.route == route }

    @JvmStatic
    internal fun NavDestination.matchDestination(@IdRes destId: Int): Boolean =
        hierarchy.any { it.id == destId }

    @JvmStatic
    internal fun ShapeAppearanceModel.roundedOnRight(cornerSize: Float = 28f): ShapeAppearanceModel {
        return toBuilder().run {
            setTopRightCorner(CornerFamily.ROUNDED, cornerSize)
            setBottomRightCorner(CornerFamily.ROUNDED, cornerSize)
            build()
        }
    }

    fun showContactDialog(context: Context) {
        val builder = DialogUtils.newMaterialDialogBuilder(context)

        builder.setTitle(R.string.msg_contact_app_dev_title)
            .setMessage(R.string.msg_contact_app_dev_description)
            .setNegativeButton(android.R.string.cancel) { dialog, _ ->
                dialog.dismiss()
            }
            .setPositiveButton(R.string.send_email) { dialog, _ ->
                val intent = Intent(Intent.ACTION_SENDTO).apply {
                    data = "mailto:$EMAIL_SUPPORT?subject=${context.getString(R.string.feedback_email_subject)}".toUri()
                }
                context.startActivity(intent)
                dialog.dismiss()
            }
            .create()
            .show()
    }

    fun SidebarActionItem.tooltipTag(): String {
        return "ide.sidebar.${label.lowercase().replace("[^a-z0-9]+".toRegex(), "_")}.longpress"
    }
}