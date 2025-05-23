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

import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned
import android.text.TextPaint
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.annotation.IdRes
import androidx.core.content.ContextCompat
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
import com.google.android.material.navigation.NavigationBarMenuView
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
import com.itsaky.androidide.actions.sidebar.BuildVariantsSidebarAction
import com.itsaky.androidide.actions.sidebar.CloseProjectSidebarAction
import com.itsaky.androidide.actions.sidebar.EmailSidebarAction
import com.itsaky.androidide.actions.sidebar.FileTreeSidebarAction
import com.itsaky.androidide.actions.sidebar.HelpSideBarAction
import com.itsaky.androidide.actions.sidebar.PreferencesSidebarAction
import com.itsaky.androidide.actions.sidebar.TerminalSidebarAction
import com.itsaky.androidide.databinding.ContactDialogBinding
import com.itsaky.androidide.fragments.sidebar.EditorSidebarFragment
import com.itsaky.androidide.utils.ContactDetails.EMAIL_SUPPORT
import java.lang.ref.WeakReference
import androidx.core.net.toUri

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
        registry.registerAction(BuildVariantsSidebarAction(context, ++order))
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

        val data = ActionData()
        data.put(Context::class.java, context)

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
        val dialog = Dialog(context)
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)

        // Inflate the custom layout using view binding
        val binding = ContactDialogBinding.inflate(LayoutInflater.from(context))
        dialog.setContentView(binding.root)

        // Calculate the desired external padding in pixels (32dp each side)
        val metrics = context.resources.displayMetrics
        val externalPadding = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, 16f, metrics
        ).toInt()
        val dialogWidth = metrics.widthPixels - 2 * externalPadding

        // Set the dialog's dimensions: custom width to create padding on each side
        dialog.window?.setLayout(dialogWidth, ViewGroup.LayoutParams.WRAP_CONTENT)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        // Prepare the email intent for reuse
        val emailIntent: () -> Unit = {
            val intent = Intent(Intent.ACTION_SENDTO).apply {
                data = "mailto:$EMAIL_SUPPORT?subject=Feedback about Code on the Go".toUri()
            }
            context.startActivity(intent)
        }

        // Apply a clickable span on tvDescription to remove underline and set blue color.
        val email = EMAIL_SUPPORT
        val text = binding.tvDescription.text.toString()
        val start = text.indexOf(email)
        if (start >= 0) {
            val spannable = SpannableString(text)
            val clickableSpan = object : ClickableSpan() {
                override fun onClick(widget: View) {
                    emailIntent()
                }

                override fun updateDrawState(ds: TextPaint) {
                    ds.color = ContextCompat.getColor(context, R.color.primary_blue)
                    ds.isUnderlineText = false // Remove underline
                }
            }
            spannable.setSpan(
                clickableSpan,
                start,
                start + email.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            binding.tvDescription.text = spannable
            binding.tvDescription.movementMethod = LinkMovementMethod.getInstance()
        }

        // Set the whole dialog clickable (except the Close button) to open the email.
        binding.root.setOnClickListener {
            emailIntent()
        }

        // When the send email button is clicked:
        binding.btnSendEmail.setOnClickListener {
            emailIntent()
        }
        // Close button action: dismiss the dialog.
        binding.btnClose.setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
    }

    fun SidebarActionItem.tooltipTag(): String {
        return "ide.sidebar.${label.lowercase().replace("[^a-z0-9]+".toRegex(), "_")}.longpress"
    }


}