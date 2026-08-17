
package com.itsaky.androidide.adapters

import android.view.LayoutInflater
import android.view.Menu
import android.view.View
import android.view.ViewGroup
import android.widget.PopupMenu
import androidx.annotation.StringRes
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.signature.ObjectKey
import com.itsaky.androidide.R
import com.itsaky.androidide.databinding.ItemPluginBinding
import com.itsaky.androidide.idetooltips.TooltipTag
import com.itsaky.androidide.plugins.PluginInfo
import com.itsaky.androidide.utils.displayTooltipOnLongPress
import com.itsaky.androidide.utils.isSystemInDarkMode
import java.io.File

class PluginListAdapter(
	private val onActionClick: (PluginInfo, Action) -> Unit,
) : ListAdapter<PluginInfo, PluginListAdapter.PluginViewHolder>(PluginDiffCallback()) {
	enum class Action(
		@StringRes val labelRes: Int,
	) {
		ENABLE(R.string.enable_plugin),
		DISABLE(R.string.disable_plugin),
		UNINSTALL(R.string.uninstall_plugin),
		DETAILS(R.string.plugin_action_details),
	}

	override fun onCreateViewHolder(
		parent: ViewGroup,
		viewType: Int,
	): PluginViewHolder {
		val binding =
			ItemPluginBinding.inflate(
				LayoutInflater.from(parent.context),
				parent,
				false,
			)
		return PluginViewHolder(binding)
	}

	override fun onBindViewHolder(
		holder: PluginViewHolder,
		position: Int,
	) {
		holder.bind(getItem(position))
	}

	inner class PluginViewHolder(
		private val binding: ItemPluginBinding,
	) : RecyclerView.ViewHolder(binding.root) {
		init {
			// Both the anchor views and their tags are fixed per view holder, not per bound plugin -
			// register once here instead of re-registering an identical listener on every bind().
			binding.btnMenu.displayTooltipOnLongPress(itemView.context, TooltipTag.PLUGIN_MANAGER_ITEM_MENU)
			binding.root.displayTooltipOnLongPress(itemView.context, TooltipTag.PLUGIN_MANAGER_ITEM)
		}

		fun bind(plugin: PluginInfo) {
			binding.apply {
				pluginName.text = plugin.metadata.name
				pluginDescription.text = plugin.metadata.description
				val version = plugin.metadata.version
				val segments = version.split('.')
				pluginVersion.text =
					if (segments.size > 3) {
						"v${segments.take(3).joinToString(".")}..."
					} else {
						"v$version"
					}
				pluginAuthor.text =
					itemView.context.getString(R.string.plugin_author_by, plugin.metadata.author)

				val iconPath =
					if (itemView.context.isSystemInDarkMode()) {
						plugin.metadata.iconNightPath
					} else {
						plugin.metadata.iconDayPath
					}

				pluginIcon.background = null
				pluginIcon.imageTintList = null
				val iconFile = iconPath?.let(::File)?.takeIf { it.exists() }
				if (iconFile != null) {
					Glide
						.with(pluginIcon)
						.load(iconFile)
						.signature(ObjectKey(iconFile.lastModified()))
						.placeholder(R.drawable.ic_extension)
						.error(R.drawable.ic_extension)
						.into(pluginIcon)
				} else {
					Glide.with(pluginIcon).clear(pluginIcon)
					pluginIcon.setImageResource(R.drawable.ic_extension)
				}

				val statusText =
					when {
						!plugin.isLoaded -> R.string.plugin_status_not_loaded
						!plugin.isEnabled -> R.string.plugin_status_disabled
						else -> R.string.plugin_status_enabled
					}
				pluginStatus.setText(statusText)

				val statusColor =
					when {
						!plugin.isLoaded -> R.color.error
						!plugin.isEnabled -> R.color.warning
						else -> R.color.success
					}
				pluginStatus.setTextColor(
					itemView.context.getColor(statusColor),
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

		private fun showPopupMenu(
			view: View,
			plugin: PluginInfo,
		) {
			val popup = PopupMenu(view.context, view)
			val actions = menuActionsFor(plugin)

			actions.forEachIndexed { index, action ->
				popup.menu.add(Menu.NONE, index, index, action.labelRes)
			}

			popup.setOnMenuItemClickListener { menuItem ->
				onActionClick(plugin, actions[menuItem.itemId])
				true
			}

			popup.show()
		}

		private fun menuActionsFor(plugin: PluginInfo): List<Action> =
			buildList {
				if (plugin.isLoaded) {
					add(if (plugin.isEnabled) Action.DISABLE else Action.ENABLE)
					add(Action.UNINSTALL)
				}
				add(Action.DETAILS)
			}
	}
}

class PluginDiffCallback : DiffUtil.ItemCallback<PluginInfo>() {
	override fun areItemsTheSame(
		oldItem: PluginInfo,
		newItem: PluginInfo,
	): Boolean = oldItem.metadata.id == newItem.metadata.id

	override fun areContentsTheSame(
		oldItem: PluginInfo,
		newItem: PluginInfo,
	): Boolean = oldItem == newItem
}
