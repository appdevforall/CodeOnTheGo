

package com.itsaky.androidide.actions

import android.content.Context
import android.graphics.drawable.Drawable
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.itsaky.androidide.plugins.extensions.NavigationItem
import com.itsaky.androidide.plugins.manager.pluginCategory
import com.itsaky.androidide.plugins.manager.pluginTooltipTag
import com.itsaky.androidide.plugins.manager.ui.PluginDrawableResolver
import com.itsaky.androidide.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.reflect.KClass

class PluginSidebarActionItem(
    private val context: Context,
    private val navigationItem: NavigationItem,
    baseOrder: Int,
    val pluginId: String
) : SidebarActionItem {

    override val id: String = "plugin_sidebar_${navigationItem.id}"
    override var enabled: Boolean = navigationItem.isEnabled
    override var visible: Boolean = navigationItem.isVisible
    override var label: String = navigationItem.title
    override var order: Int = navigationItem.order + 1000 + baseOrder
    override var requiresUIThread: Boolean = true
    override var location: ActionItem.Location = ActionItem.Location.EDITOR_SIDEBAR

    override var icon: Drawable? = null

    override val fragmentClass: KClass<out Fragment>? = null

    init {
        val iconResId = navigationItem.icon
        icon = if (iconResId != null) {
            PluginDrawableResolver.resolve(iconResId, pluginId, context)
                ?: ContextCompat.getDrawable(context, R.drawable.ic_extension)
        } else {
            ContextCompat.getDrawable(context, R.drawable.ic_extension)
        }
    }

    override fun retrieveTooltipTag(isReadOnlyContext: Boolean): String =
        navigationItem.tooltipTag ?: pluginTooltipTag(pluginId, navigationItem.id)

    override fun retrieveTooltipCategory(): String = pluginCategory(pluginId)

    override suspend fun execAction(data: ActionData): Boolean {
        return try {
            // Plugin actions might need UI thread access for dialogs/UI operations
            if (requiresUIThread) {
                withContext(Dispatchers.Main) {
                    navigationItem.action.invoke()
                }
            } else {
                navigationItem.action.invoke()
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    override fun prepare(data: ActionData) {
        super.prepare(data)
        visible = navigationItem.isVisible
        enabled = navigationItem.isEnabled
    }
}