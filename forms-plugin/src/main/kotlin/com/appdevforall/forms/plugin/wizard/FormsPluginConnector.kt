package com.appdevforall.forms.plugin.wizard

import com.appdevforall.forms.plugin.FormSchema
import com.appdevforall.forms.plugin.FormsPlugin

/**
 * Static seam between [WizardHostFragment] and [FormsPlugin].
 *
 * The wizard host fragment lives inside the schema panel's editor tab and
 * can outlive the plugin instance across configuration changes. This object
 * holds a reference to the active [FormsPlugin] (set on `activate()`,
 * cleared on `dispose()`) so the wizard can hand its result back without a
 * Binder/Service round-trip.
 *
 * **Threading.** [deliverCompleted] performs disk I/O via
 * [FormsPlugin.onWizardCompleted]; callers must invoke it from
 * `Dispatchers.IO` (the wizard fragment uses `lifecycleScope.launch` +
 * `withContext(Dispatchers.IO)`). [deliverCanceled] is main-thread.
 *
 * Why not Activity result + bundles: the captured schema is too rich for
 * Parcelable comfort and we want to keep the wizard's UI logic decoupled
 * from the plugin's file-write logic. A direct method call is the simplest
 * seam that survives configuration changes (the plugin instance doesn't get
 * torn down on rotation).
 */
internal object FormsPluginConnector {

    @Volatile
    private var plugin: FormsPlugin? = null

    fun bind(plugin: FormsPlugin) {
        this.plugin = plugin
    }

    fun unbind(plugin: FormsPlugin) {
        if (this.plugin === plugin) this.plugin = null
    }

    /**
     * Write the captured schema to the open project's
     * `assets/form_schema.json`. Performs synchronous disk I/O; **call from
     * `Dispatchers.IO`**, not the UI thread.
     *
     * @return the absolute path of the written file on success, or null if
     *   the plugin instance was unbound, no project was open, or the file
     *   write failed. Callers should surface a generic "could not save"
     *   toast on null.
     */
    fun deliverCompleted(schema: FormSchema): String? {
        return plugin?.onWizardCompleted(schema)
    }

    fun deliverCanceled() {
        // No-op for now. A future iteration may surface a status update
        // when the user explicitly cancels mid-flow vs. when they back out
        // of the wizard via the system back button.
    }
}
