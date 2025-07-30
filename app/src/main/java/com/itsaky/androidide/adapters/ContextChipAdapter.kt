package com.itsaky.androidide.adapters

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.chip.Chip
import com.itsaky.androidide.R

class ContextChipAdapter(
    private val items: MutableList<String>,
    private val onRemoveClicked: (String) -> Unit
) : RecyclerView.Adapter<ContextChipAdapter.ViewHolder>() {

    class ViewHolder(val chip: Chip) : RecyclerView.ViewHolder(chip)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val chip = LayoutInflater.from(parent.context)
            .inflate(R.layout.list_item_context_chip, parent, false) as Chip
        return ViewHolder(chip)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val itemText = items[position]
        holder.chip.text = itemText
        holder.chip.setOnCloseIconClickListener {
            onRemoveClicked(itemText)
        }
    }

    fun updateData(newItems: Set<String>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged() // It will always be more efficient to use more specific change events if you can. Rely on notifyDataSetChanged as a last resort. Toggle info
    }
}