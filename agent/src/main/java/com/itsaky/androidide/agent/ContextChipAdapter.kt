package com.itsaky.androidide.agent

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.chip.Chip

class ContextChipAdapter(
    private val onRemoveClicked: (String) -> Unit
) : ListAdapter<String, ContextChipAdapter.ViewHolder>(DiffCallback) {

    class ViewHolder(val chip: Chip) : RecyclerView.ViewHolder(chip)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val chip = LayoutInflater.from(parent.context)
            .inflate(R.layout.list_item_context_chip, parent, false) as Chip
        return ViewHolder(chip)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val itemText = getItem(position)
        holder.chip.text = itemText
        holder.chip.setOnCloseIconClickListener {
            onRemoveClicked(itemText)
        }
    }

    companion object DiffCallback : DiffUtil.ItemCallback<String>() {
        override fun areItemsTheSame(oldItem: String, newItem: String): Boolean {
            return oldItem == newItem
        }

        override fun areContentsTheSame(oldItem: String, newItem: String): Boolean {
            // For a simple string, the content is the same if the item is the same.
            return oldItem == newItem
        }
    }
}
