package com.appdevforall.forms.plugin.panel

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.appdevforall.forms.plugin.FormSchema
import com.appdevforall.forms.plugin.R
import com.appdevforall.forms.plugin.wizard.FieldsAdapter
import com.appdevforall.forms.plugin.wizard.WizardActivity
import com.google.android.material.button.MaterialButton
import java.io.File

/**
 * Main editor tab fragment that shows the open project's current
 * `form_schema.json`. Surfaces a Capture button that launches
 * [WizardActivity]; the wizard's last step writes a new schema back to the
 * same file and the panel refreshes when the fragment resumes.
 *
 * The panel is intentionally read-only-ish — it renders the schema and
 * launches the wizard for edits, rather than trying to inline-edit the
 * fields itself. Field-level editing happens in the wizard's step 2.
 *
 * Discovery via [SchemaPanelHost.locator] supplies the open project's path
 * and the launch action. That indirection keeps this Fragment from holding
 * a hard reference to the [com.appdevforall.forms.plugin.FormsPlugin]
 * instance (which can be unbound across config changes / plugin reload).
 */
class SchemaPanelFragment : Fragment() {

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

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = inflater.inflate(R.layout.fragment_schema_panel, container, false)

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
            val ctx = requireContext()
            val intent = Intent(ctx, WizardActivity::class.java)
            // Fragment.startActivity forwards to the host Activity; that's
            // important because the wizard's static FormsPluginConnector
            // delivers results through the plugin instance, not a result
            // contract on this fragment.
            startActivity(intent)
        }

        renderState()
    }

    override fun onResume() {
        super.onResume()
        // The wizard runs in its own Activity. When the user finishes it and
        // returns, refresh from disk so the panel reflects the new schema.
        renderState()
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
        // "Placeholder field". If we still see that we're showing the unedited
        // stub state and want a friendlier hint than "1 field configured".
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

/**
 * Snapshot of what the panel needs to know at render time: where the open
 * project's schema lives. Resolved fresh on every render via
 * [SchemaPanelHost.locator] because both `IdeProjectService` registration
 * and the open-project state can flicker across plugin lifecycle events.
 */
data class SchemaPanelHost(
    val schemaFile: File,
) {
    companion object {
        /**
         * Set by [com.appdevforall.forms.plugin.FormsPlugin] on `activate()`
         * and cleared on `dispose()`. Fragment reads this each render to find
         * the open project. Returning null means "no project open" or "plugin
         * not active" — the panel renders the empty state in that case.
         */
        @Volatile
        var locator: (() -> SchemaPanelHost?)? = null
    }
}
