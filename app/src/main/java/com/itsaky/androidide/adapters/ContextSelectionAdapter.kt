package com.itsaky.androidide.adapters

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.itsaky.androidide.databinding.ListItemContextHeaderBinding
import com.itsaky.androidide.databinding.ListItemContextSelectableBinding
import com.itsaky.androidide.models.ContextListItem
import com.itsaky.androidide.models.HeaderItem
import com.itsaky.androidide.models.SelectableItem

class ContextSelectionAdapter(
    private val onItemClick: (SelectableItem) -> Unit
) : ListAdapter<ContextListItem, RecyclerView.ViewHolder>(DiffCallback) {

    private val TYPE_HEADER = 0
    private val TYPE_ITEM = 1

    inner class HeaderViewHolder(val binding: ListItemContextHeaderBinding) :
        RecyclerView.ViewHolder(binding.root)

    inner class ItemViewHolder(val binding: ListItemContextSelectableBinding) :
        RecyclerView.ViewHolder(binding.root)

    override fun getItemViewType(position: Int): Int {
        return when (getItem(position)) { // Use getItem() from ListAdapter
            is HeaderItem -> TYPE_HEADER
            is SelectableItem -> TYPE_ITEM
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_HEADER) {
            HeaderViewHolder(ListItemContextHeaderBinding.inflate(inflater, parent, false))
        } else {
            ItemViewHolder(ListItemContextSelectableBinding.inflate(inflater, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = getItem(position)) { // Use getItem() from ListAdapter
            is HeaderItem -> {
                (holder as HeaderViewHolder).binding.headerTitle.text = item.title
            }
            is SelectableItem -> {
                val itemHolder = holder as ItemViewHolder
                itemHolder.binding.itemText.text = item.text
                itemHolder.binding.itemIcon.setImageResource(item.icon)
                itemHolder.binding.itemCheck.isVisible = item.isSelected
                itemHolder.itemView.setOnClickListener { onItemClick(item) }
            }
        }
    }

    companion object DiffCallback : DiffUtil.ItemCallback<ContextListItem>() {
        override fun areItemsTheSame(oldItem: ContextListItem, newItem: ContextListItem): Boolean {
            return when {
                oldItem is SelectableItem && newItem is SelectableItem -> oldItem.id == newItem.id
                oldItem is HeaderItem && newItem is HeaderItem -> oldItem.title == newItem.title
                else -> false
            }
        }

        override fun areContentsTheSame(
            oldItem: ContextListItem,
            newItem: ContextListItem
        ): Boolean {
            // Data classes provide a structural equality check, which is perfect here.
            return oldItem == newItem
        }
    }
}