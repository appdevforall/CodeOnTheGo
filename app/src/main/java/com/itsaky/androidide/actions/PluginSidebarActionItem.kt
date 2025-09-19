

package com.itsaky.androidide.actions

import android.content.Context
import android.graphics.drawable.Drawable
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.itsaky.androidide.plugins.extensions.NavigationItem
import com.itsaky.androidide.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.reflect.KClass

/**
 * A sidebar action item that wraps plugin-contributed navigation items.
 *
 */
class PluginSidebarActionItem(
    private val context: Context,
    private val navigationItem: NavigationItem,
    baseOrder: Int
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
            try {
                ContextCompat.getDrawable(context, iconResId)
            } catch (e: Exception) {
                ContextCompat.getDrawable(context, R.drawable.ic_extension)
            }
        } else {
            ContextCompat.getDrawable(context, R.drawable.ic_extension)
        }
    }

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