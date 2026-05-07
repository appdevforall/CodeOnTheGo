package com.appdevforall.forms.plugin.wizard

import com.appdevforall.forms.plugin.FormSchema
import com.appdevforall.forms.plugin.FormsPlugin

/**
 * Static seam between [WizardActivity] and [FormsPlugin].
 *
 * The wizard runs in its own Activity and can outlive the plugin instance
 * across configuration changes. This object holds a weak-ish reference to the
 * active [FormsPlugin] (set on `activate()`, cleared on `dispose()`) so the
 * wizard can hand its result back without a Binder/Service round-trip.
 *
 * Threading: all calls happen on the main thread (Activity lifecycle methods
 * + plugin lifecycle methods both fire on main). No locking needed.
 *
 * Why not Activity result + bundles: the captured schema is too rich for
 * Parcelable comfort and we want to keep the wizard's UI logic decoupled
 * from the plugin's template-emitting logic. A direct method call is the
 * simplest seam that survives configuration changes (the plugin instance
 * doesn't get torn down on rotation).
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
     * @return the absolute path of the written `form_schema.json` on success,
     *   or null if the plugin instance was unbound, no project was open, or
     *   the file write failed. Callers that need to flag the failure to the
     *   user should fall through to a generic "could not save" toast.
     */
    fun deliverCompleted(schema: FormSchema): String? {
        return plugin?.onWizardCompleted(schema)
    }

    fun deliverCanceled() {
        // No-op for now. C2 will surface a toast / status update when the
        // user explicitly cancels mid-flow vs when they close the wizard
        // accidentally.
    }
}
