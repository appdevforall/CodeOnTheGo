package com.appdevforall.forms.plugin.panel

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.appdevforall.forms.plugin.FormSchema
import com.appdevforall.forms.plugin.FormsPlugin
import com.appdevforall.forms.plugin.R
import com.appdevforall.forms.plugin.wizard.FieldsAdapter
import com.google.android.material.button.MaterialButton
import com.itsaky.androidide.plugins.base.PluginFragmentHelper

/**
 * Inner content fragment for [SchemaPanelFragment]. Renders the open
 * project's `form_schema.json` and exposes a Capture button that pushes the
 * wizard onto the parent fragment's back stack.
 *
 * Field-level edits happen in the wizard's step 2; this surface is
 * read-only on purpose. Re-renders on every resume so the user sees the
 * latest schema after the wizard finishes.
 */
internal class SchemaPanelContentFragment : Fragment() {

    private var titleView: TextView? = null
    private var stateHintView: TextView? = null
    private var pathLabelView: TextView? = null
    private var pathView: TextView? = null
    private var captureButton: MaterialButton? = null
    private var dividerView: View? = null
    private var fieldCountView: TextView? = null
    private var fieldsRecycler: RecyclerView? = null

    private val fieldsAdapter = FieldsAdapter(
        onEdit = { /* no-op in panel — edits happen via Capture / wizard */ },
        onDelete = { /* no-op in panel */ },
    )

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
    ): View = inflater.inflate(R.layout.fragment_schema_panel_content, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        titleView = view.findViewById(R.id.forms_panel_title)
        stateHintView = view.findViewById(R.id.forms_panel_state_hint)
        pathLabelView = view.findViewById(R.id.forms_panel_path_label)
        pathView = view.findViewById(R.id.forms_panel_path)
        captureButton = view.findViewById(R.id.forms_panel_capture_button)
        dividerView = view.findViewById(R.id.forms_panel_divider)
        fieldCountView = view.findViewById(R.id.forms_panel_field_count)
        fieldsRecycler = view.findViewById<RecyclerView>(R.id.forms_panel_fields).apply {
            layoutManager = LinearLayoutManager(view.context)
            adapter = fieldsAdapter
        }

        captureButton?.setOnClickListener {
            // Hand off to the parent host fragment, which manages the
            // panel-vs-wizard back stack via its childFragmentManager.
            (parentFragment as? SchemaPanelFragment)?.launchWizard()
        }

        renderState()
    }

    override fun onResume() {
        super.onResume()
        // The wizard runs inside our parent fragment's childFragmentManager.
        // When it finishes/cancels and pops itself, this content fragment
        // resumes — re-read from disk so the panel reflects any new schema.
        renderState()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        titleView = null
        stateHintView = null
        pathLabelView = null
        pathView = null
        captureButton = null
        dividerView = null
        fieldCountView = null
        fieldsRecycler = null
    }

    private fun renderState() {
        val host = SchemaPanelHost.locator?.invoke()
        if (host == null) {
            stateHintView?.setText(R.string.forms_panel_no_project)
            pathLabelView?.visibility = View.GONE
            pathView?.visibility = View.GONE
            captureButton?.isEnabled = false
            dividerView?.visibility = View.GONE
            fieldCountView?.visibility = View.GONE
            fieldsRecycler?.visibility = View.GONE
            fieldsAdapter.submitList(emptyList())
            return
        }
        captureButton?.isEnabled = true
        pathLabelView?.visibility = View.VISIBLE
        pathView?.visibility = View.VISIBLE
        pathView?.text = host.schemaFile.absolutePath

        if (!host.schemaFile.exists()) {
            stateHintView?.setText(R.string.forms_panel_schema_missing)
            dividerView?.visibility = View.GONE
            fieldCountView?.visibility = View.GONE
            fieldsRecycler?.visibility = View.GONE
            fieldsAdapter.submitList(emptyList())
            return
        }

        val text = try {
            host.schemaFile.readText()
        } catch (t: Throwable) {
            ""
        }
        val schema = FormSchema.fromJson(text)
        if (schema == null) {
            stateHintView?.setText(R.string.forms_panel_schema_unreadable)
            dividerView?.visibility = View.GONE
            fieldCountView?.visibility = View.GONE
            fieldsRecycler?.visibility = View.GONE
            fieldsAdapter.submitList(emptyList())
            return
        }

        val fields = schema.fields
        // Heuristic: the static stub ships exactly one field titled
        // "Placeholder field". If we still see that we're showing the
        // unedited stub state and want a friendlier hint than "1 field".
        val isStub = fields.size == 1 && fields[0].id == "f_placeholder"
        if (isStub) {
            stateHintView?.setText(R.string.forms_panel_stub_hint)
        } else {
            stateHintView?.text = ""
            stateHintView?.visibility = if (fields.isEmpty()) View.VISIBLE else View.GONE
        }
        dividerView?.visibility = View.VISIBLE
        fieldCountView?.visibility = View.VISIBLE
        fieldCountView?.text =
            getString(R.string.forms_panel_field_count, fields.size)
        fieldsRecycler?.visibility = if (fields.isEmpty()) View.GONE else View.VISIBLE
        fieldsAdapter.submitList(fields)
    }
}
