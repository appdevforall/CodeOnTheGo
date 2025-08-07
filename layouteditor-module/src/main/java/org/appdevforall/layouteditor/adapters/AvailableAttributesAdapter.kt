package org.appdevforall.layouteditor.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import org.appdevforall.layouteditor.R

class AvailableAttributesAdapter(
    private var attributes: List<String>,
    private val onAttributeClick: (String) -> Unit
) : RecyclerView.Adapter<AvailableAttributesAdapter.ViewHolder>() {

    private var filteredAttributes: List<String> = attributes

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val textView: TextView = view.findViewById(android.R.id.text1)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(android.R.layout.simple_list_item_1, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val attribute = filteredAttributes[position]
        holder.textView.text = attribute
        holder.itemView.setOnClickListener { onAttributeClick(attribute) }
    }

    override fun getItemCount(): Int = filteredAttributes.size

    fun filter(query: String) {
        filteredAttributes = if (query.isEmpty()) {
            attributes
        } else {
            attributes.filter { it.contains(query, ignoreCase = true) }
        }
        notifyDataSetChanged()
    }

    fun updateAttributes(newAttributes: List<String>) {
        attributes = newAttributes
        filteredAttributes = newAttributes
        notifyDataSetChanged()
    }
}