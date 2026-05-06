package com.appdevforall.forms.plugin.wizard

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.appdevforall.forms.plugin.FormField
import com.appdevforall.forms.plugin.R

/**
 * Renders the [FormField] list in step 2's review surface. Single-tap edits a
 * field via [FieldEditorDialog]; long-press deletes (with no confirm — fields
 * are cheap to re-add via the + Add button).
 *
 * @param onEdit invoked with the tapped field. The fragment opens the editor
 *   dialog and propagates the resulting field back to the ViewModel.
 * @param onDelete invoked with the field id on long-press.
 */
internal class FieldsAdapter(
    private val onEdit: (FormField) -> Unit,
    private val onDelete: (String) -> Unit,
) : ListAdapter<FormField, FieldsAdapter.VH>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_field_row, parent, false)
        return VH(view)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(getItem(position))
    }

    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
        private val labelView: TextView = view.findViewById(R.id.forms_field_row_label)
        private val typeView: TextView = view.findViewById(R.id.forms_field_row_type)
        private val flagsView: TextView = view.findViewById(R.id.forms_field_row_flags)

        fun bind(field: FormField) {
            labelView.text = field.label.ifBlank { "(unlabeled)" }
            typeView.text = "[" + field.type.label + "]"
            val flags = buildList {
                if (field.required) add("required")
                if (field.reusable) add("reusable")
                field.confidence?.let { add("conf %.2f".format(it)) }
            }
            flagsView.text = flags.joinToString(" · ")
            flagsView.visibility = if (flags.isEmpty()) View.GONE else View.VISIBLE
            itemView.setOnClickListener { onEdit(field) }
            itemView.setOnLongClickListener {
                onDelete(field.id)
                true
            }
        }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<FormField>() {
            override fun areItemsTheSame(oldItem: FormField, newItem: FormField) =
                oldItem.id == newItem.id

            override fun areContentsTheSame(oldItem: FormField, newItem: FormField) =
                oldItem == newItem
        }
    }
}
