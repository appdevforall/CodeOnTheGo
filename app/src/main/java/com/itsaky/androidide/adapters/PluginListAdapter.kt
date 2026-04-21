
package com.itsaky.androidide.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.PopupMenu
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.itsaky.androidide.R
import com.itsaky.androidide.databinding.ItemPluginBinding
import com.itsaky.androidide.idetooltips.TooltipManager
import com.itsaky.androidide.idetooltips.TooltipTag
import com.itsaky.androidide.plugins.PluginInfo
import com.itsaky.androidide.utils.isSystemInDarkMode
import java.io.File

class PluginListAdapter(
    private val onActionClick: (PluginInfo, Action) -> Unit
) : ListAdapter<PluginInfo, PluginListAdapter.PluginViewHolder>(PluginDiffCallback()) {

    enum class Action {
        ENABLE,
        DISABLE,
        UNINSTALL,
        DETAILS
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PluginViewHolder {
        val binding = ItemPluginBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return PluginViewHolder(binding)
    }

    override fun onBindViewHolder(holder: PluginViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class PluginViewHolder(
        private val binding: ItemPluginBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(plugin: PluginInfo) {
            binding.apply {
                pluginName.text = plugin.metadata.name
                pluginDescription.text = plugin.metadata.description
                val version = plugin.metadata.version
                val segments = version.split('.')
                pluginVersion.text = if (segments.size > 3) {
                    "v${segments.take(3).joinToString(".")}..."
                } else {
                    "v$version"
                }
                pluginAuthor.text = "by ${plugin.metadata.author}"

                val iconPath = if (itemView.context.isSystemInDarkMode()) {
                    plugin.metadata.iconNightPath
                } else {
                    plugin.metadata.iconDayPath
                }

                pluginIcon.background = null
                pluginIcon.imageTintList = null
                val iconFile = iconPath?.let(::File)?.takeIf { it.exists() }
                if (iconFile != null) {
                    Glide.with(pluginIcon)
                        .load(iconFile)
                        .placeholder(R.drawable.ic_extension)
                        .error(R.drawable.ic_extension)
                        .into(pluginIcon)
                } else {
                    Glide.with(pluginIcon).clear(pluginIcon)
                    pluginIcon.setImageResource(R.drawable.ic_extension)
                }

                // Set status
                val statusText = when {
                    !plugin.isLoaded -> "Not Loaded"
                    !plugin.isEnabled -> "Disabled"
                    else -> "Enabled"
                }
                pluginStatus.text = statusText
                
                // Set status color
                val statusColor = when {
                    !plugin.isLoaded -> R.color.error
                    !plugin.isEnabled -> R.color.warning
                    else -> R.color.success
                }
                pluginStatus.setTextColor(
                    itemView.context.getColor(statusColor)
                )

                // Setup menu button
                btnMenu.setOnClickListener { view ->
                    showPopupMenu(view, plugin)
                }
                
                // Setup item click for details
                root.setOnClickListener {
                    onActionClick(plugin, Action.DETAILS)
                }

                // Long-press for Plugin Manager tooltip
                root.setOnLongClickListener {
                    TooltipManager.showIdeCategoryTooltip(it.context, it, TooltipTag.PLUGIN_MANAGER)
                    true
                }
            }
        }

        private fun showPopupMenu(view: View, plugin: PluginInfo) {
            val popup = PopupMenu(view.context, view)
            
            // Add menu items based on plugin state
            if (plugin.isLoaded) {
                if (plugin.isEnabled) {
                    popup.menu.add(0, 1, 0, "Disable")
                } else {
                    popup.menu.add(0, 2, 0, "Enable")
                }
                popup.menu.add(0, 3, 0, "Uninstall")
            }
            popup.menu.add(0, 4, 0, "Details")

            popup.setOnMenuItemClickListener { menuItem ->
                when (menuItem.itemId) {
                    1 -> onActionClick(plugin, Action.DISABLE)
                    2 -> onActionClick(plugin, Action.ENABLE)
                    3 -> onActionClick(plugin, Action.UNINSTALL)
                    4 -> onActionClick(plugin, Action.DETAILS)
                }
                true
            }
            
            popup.show()
        }
    }
}

class PluginDiffCallback : DiffUtil.ItemCallback<PluginInfo>() {
    override fun areItemsTheSame(oldItem: PluginInfo, newItem: PluginInfo): Boolean {
        return oldItem.metadata.id == newItem.metadata.id
    }

    override fun areContentsTheSame(oldItem: PluginInfo, newItem: PluginInfo): Boolean {
        return oldItem == newItem
    }
}