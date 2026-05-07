package com.appdevforall.forms.plugin.panel

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.appdevforall.forms.plugin.FormsPlugin
import com.appdevforall.forms.plugin.R
import com.appdevforall.forms.plugin.wizard.WizardHostFragment
import com.itsaky.androidide.plugins.base.PluginFragmentHelper
import java.io.File

/**
 * Editor-tab fragment that hosts either [SchemaPanelContentFragment] (the
 * default schema view + Capture button) or [WizardHostFragment] (the 4-step
 * wizard). Switches between them via [childFragmentManager], so the wizard
 * lives inside the same editor tab as the panel — no Activity launch.
 *
 * Why a host/content split: the wizard needs its own back-stack entry so a
 * system back press (or the wizard's Cancel button) returns the user to the
 * panel, not out of the editor tab. Putting the panel content into a child
 * fragment lets the wizard be `replace(...)`-pushed onto the stack
 * cleanly; popping restores the panel and triggers its `onResume`, which
 * re-reads the schema from disk.
 *
 * Discovery via [SchemaPanelHost.locator] supplies the open project's path.
 * That indirection keeps this Fragment from holding a hard reference to the
 * [FormsPlugin] instance (which can be unbound across config changes /
 * plugin reload).
 */
class SchemaPanelFragment : Fragment() {

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
    ): View = inflater.inflate(R.layout.fragment_schema_panel_host, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // Mount the default content fragment if this is the first creation.
        // On config-change re-creation Android restores the existing child
        // (content or wizard) automatically.
        if (savedInstanceState == null && childFragmentManager.findFragmentById(
                R.id.forms_panel_host_container,
            ) == null
        ) {
            childFragmentManager.beginTransaction()
                .replace(R.id.forms_panel_host_container, SchemaPanelContentFragment())
                .commit()
        }
    }

    /**
     * Push the wizard onto the panel's back stack. Called by the content
     * fragment when the user taps Capture. The wizard pops itself when the
     * user finishes / cancels; the content fragment then refreshes from disk
     * in its onResume.
     */
    internal fun launchWizard() {
        childFragmentManager.beginTransaction()
            .replace(R.id.forms_panel_host_container, WizardHostFragment())
            .addToBackStack("forms_wizard")
            .commit()
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
         * and cleared on `dispose()`. The content fragment reads this each
         * render to find the open project. Returning null means "no project
         * open" or "plugin not active" — the panel renders the empty state.
         */
        @Volatile
        var locator: (() -> SchemaPanelHost?)? = null
    }
}
