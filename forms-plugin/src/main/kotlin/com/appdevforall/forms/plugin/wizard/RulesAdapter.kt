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
import com.google.android.material.checkbox.MaterialCheckBox

/**
 * Renders the rules row in step 3 — one row per [FormField], with required +
 * reusable toggles. Tapping a checkbox immediately mutates the underlying
 * field via the [onRulesChanged] callback (no Save button — autosave on
 * change matches the keystore plugin's UX).
 *
 * Postal-code lookup is intentionally absent in C2; it lands in C4 with the
 * US-zip CSV asset.
 */
internal class RulesAdapter(
    private val onRulesChanged: (fieldId: String, required: Boolean, reusable: Boolean) -> Unit,
) : ListAdapter<FormField, RulesAdapter.VH>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_rule_row, parent, false)
        return VH(view)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(getItem(position))
    }

    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
        private val labelView: TextView = view.findViewById(R.id.forms_rule_row_label)
        private val typeView: TextView = view.findViewById(R.id.forms_rule_row_type)
        private val requiredCb: MaterialCheckBox = view.findViewById(R.id.forms_rule_row_required)
        private val reusableCb: MaterialCheckBox = view.findViewById(R.id.forms_rule_row_reusable)

        fun bind(field: FormField) {
            labelView.text = field.label
            typeView.text = "[" + field.type.label + "]"

            // Detach listeners before setting state to avoid feedback when the
            // adapter rebinds during a recycle.
            requiredCb.setOnCheckedChangeListener(null)
            reusableCb.setOnCheckedChangeListener(null)
            requiredCb.isChecked = field.required
            reusableCb.isChecked = field.reusable

            val push: () -> Unit = {
                onRulesChanged(field.id, requiredCb.isChecked, reusableCb.isChecked)
            }
            requiredCb.setOnCheckedChangeListener { _, _ -> push() }
            reusableCb.setOnCheckedChangeListener { _, _ -> push() }
        }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<FormField>() {
            override fun areItemsTheSame(o: FormField, n: FormField) = o.id == n.id
            override fun areContentsTheSame(o: FormField, n: FormField) = o == n
        }
    }
}
