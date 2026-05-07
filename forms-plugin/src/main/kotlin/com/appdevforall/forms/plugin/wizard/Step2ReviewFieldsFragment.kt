package com.appdevforall.forms.plugin.wizard

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.appdevforall.forms.plugin.FormField
import com.appdevforall.forms.plugin.FormsPlugin
import com.appdevforall.forms.plugin.R
import com.google.android.material.button.MaterialButton
import com.itsaky.androidide.plugins.base.PluginFragmentHelper

/**
 * Step 2 of 4 — Review fields.
 *
 * C2: pure manual placement — the user adds fields one by one via the +Add
 * button; tap a row to edit; long-press to delete. C3 will pre-seed the list
 * from the CV-detected field set (if Step 1 used a photo) before showing this
 * fragment.
 */
class Step2ReviewFieldsFragment : Fragment() {

    private val viewModel: WizardViewModel by viewModels(
        ownerProducer = { requireParentFragment() },
    )
    private lateinit var adapter: FieldsAdapter

    override fun onGetLayoutInflater(savedInstanceState: Bundle?): LayoutInflater {
        return PluginFragmentHelper.getPluginInflater(
            FormsPlugin.PLUGIN_ID,
            super.onGetLayoutInflater(savedInstanceState),
        )
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = inflater.inflate(R.layout.fragment_wizard_step2, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val rv = view.findViewById<RecyclerView>(R.id.forms_wizard_step2_fields)
        val empty = view.findViewById<View>(R.id.forms_wizard_step2_empty)
        val addBtn = view.findViewById<MaterialButton>(R.id.forms_wizard_step2_add)

        adapter = FieldsAdapter(
            onEdit = { existing -> openEditor(existing) },
            onDelete = { id -> viewModel.removeField(id) },
        )
        rv.adapter = adapter
        rv.layoutManager = LinearLayoutManager(requireContext())

        viewModel.fields.observe(viewLifecycleOwner) { fields ->
            adapter.submitList(fields)
            empty.visibility = if (fields.isEmpty()) View.VISIBLE else View.GONE
            rv.visibility = if (fields.isEmpty()) View.GONE else View.VISIBLE
        }

        addBtn.setOnClickListener { openEditor(null) }
    }

    private fun openEditor(existing: FormField?) {
        val dlg = FieldEditorDialog.newInstance(existing)
        dlg.onFieldSaved = { saved ->
            if (existing == null) viewModel.addField(saved)
            else viewModel.updateField(saved)
        }
        dlg.show(parentFragmentManager, "field_editor")
    }
}
