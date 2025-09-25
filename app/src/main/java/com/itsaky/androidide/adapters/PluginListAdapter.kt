
package com.itsaky.androidide.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.PopupMenu
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.itsaky.androidide.R
import com.itsaky.androidide.databinding.ItemPluginBinding
import com.itsaky.androidide.plugins.PluginInfo

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
                pluginVersion.text = "v${plugin.metadata.version}"
                pluginAuthor.text = "by ${plugin.metadata.author}"
                
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