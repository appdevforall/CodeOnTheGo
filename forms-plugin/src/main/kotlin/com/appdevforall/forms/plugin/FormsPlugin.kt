package com.appdevforall.forms.plugin

import com.appdevforall.forms.plugin.template.FormTemplateBuilder
import com.appdevforall.forms.plugin.wizard.FormsPluginConnector
import com.appdevforall.forms.plugin.wizard.WizardActivity
import com.itsaky.androidide.plugins.IPlugin
import com.itsaky.androidide.plugins.PluginContext
import com.itsaky.androidide.plugins.extensions.NavigationItem
import com.itsaky.androidide.plugins.extensions.PluginTooltipButton
import com.itsaky.androidide.plugins.extensions.PluginTooltipEntry
import com.itsaky.androidide.plugins.extensions.DocumentationExtension
import com.itsaky.androidide.plugins.extensions.UIExtension
import com.itsaky.androidide.plugins.services.IdeTemplateService
import com.itsaky.androidide.plugins.services.IdeUIService

/**
 * Code on the Go plugin that scaffolds a runnable, offline-capable form-data
 * Android app from a photo of a paper form.
 *
 * On `activate()` the plugin registers a static "Form-filling app from photo"
 * template via [IdeTemplateService.registerTemplate] so the card is visible in
 * the New Project grid even before the wizard runs. The sidebar entry opens
 * [WizardActivity], which (in C2) lets the user capture the form, edit fields,
 * pick semantic rules, and configure where data goes; the wizard then registers
 * a per-instance template carrying the captured schema.
 *
 * **Design note.** The original plan §5e had the template recipe block on the
 * wizard via a `CompletableDeferred`. That pattern doesn't fit the actual
 * `IdeTemplateService` API — the recipe of a `.cgt` archive is fixed
 * (`ZipRecipeExecutor`) and there's no plugin-supplied Kotlin-recipe seam.
 * Instead the wizard runs **before** the user picks the template; see
 * QUESTIONS.md Q1 in the worktree root.
 */
class FormsPlugin : IPlugin, UIExtension, DocumentationExtension {

    private lateinit var pluginContext: PluginContext
    private var templateBuilder: FormTemplateBuilder? = null

    override fun initialize(context: PluginContext): Boolean {
        return try {
            this.pluginContext = context
            context.logger.info("FormsPlugin initialized")
            true
        } catch (t: Throwable) {
            context.logger.error("FormsPlugin initialize failed", t)
            false
        }
    }

    override fun activate(): Boolean {
        pluginContext.logger.info("FormsPlugin: activating")
        FormsPluginConnector.bind(this)

        val templateService = pluginContext.services.get(IdeTemplateService::class.java)
        if (templateService == null) {
            pluginContext.logger.warn(
                "IdeTemplateService not available — sidebar entry still works, but " +
                    "the static template card won't appear in the New Project grid."
            )
            return true
        }

        templateBuilder = FormTemplateBuilder(pluginContext, templateService).also { tb ->
            // Register the static stub on activate so users see the card even
            // before they run the wizard. Idempotent: registerTemplate copies
            // the file each time, so subsequent activations just overwrite.
            val schema = FormTemplateBuilder.blankStubSchema()
            val registered = tb.buildAndRegister(schema, isStaticStub = true)
            if (registered == null) {
                pluginContext.logger.warn(
                    "Static stub template registration returned null. The wizard " +
                        "is still usable but the static card won't appear."
                )
            }
        }
        return true
    }

    override fun deactivate(): Boolean {
        pluginContext.logger.info("FormsPlugin: deactivating")
        FormsPluginConnector.unbind(this)
        return true
    }

    override fun dispose() {
        pluginContext.logger.info("FormsPlugin: disposing")
        FormsPluginConnector.unbind(this)
        templateBuilder = null
    }

    override fun getSideMenuItems(): List<NavigationItem> {
        return listOf(
            NavigationItem(
                id = "forms_wizard",
                title = pluginContext.androidContext.getString(R.string.forms_sidebar_title),
                icon = android.R.drawable.ic_menu_camera,
                isEnabled = true,
                isVisible = true,
                group = "templates",
                order = 0,
                tooltipTag = "forms_plugin.wizard",
                action = ::launchWizard,
            )
        )
    }

    /**
     * Launch the wizard. Prefers the foreground Activity from
     * [IdeUIService.getCurrentActivity]; falls back to the application context
     * with FLAG_ACTIVITY_NEW_TASK if no Activity is available (defensive — the
     * sidebar action should always have a foreground Activity).
     */
    private fun launchWizard() {
        val ui = pluginContext.services.get(IdeUIService::class.java)
        val activity = ui?.getCurrentActivity()
        if (activity != null) {
            val intent = android.content.Intent(activity, WizardActivity::class.java)
            activity.startActivity(intent)
            return
        }
        pluginContext.logger.warn(
            "No foreground Activity from IdeUIService; falling back to application " +
                "context launch with FLAG_ACTIVITY_NEW_TASK."
        )
        val ctx = pluginContext.androidContext
        val intent = android.content.Intent(ctx, WizardActivity::class.java)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(intent)
    }

    /**
     * Called by [WizardActivity] when the wizard finishes with a captured
     * schema. Builds and registers a per-instance `.cgt` so the user can pick
     * it from + Create Project.
     *
     * Made `internal` so the wizard can hand results back without exposing the
     * builder publicly. C1 doesn't use this; C2's wizard wires it up.
     */
    internal fun onWizardCompleted(schema: FormSchema): String? {
        val tb = templateBuilder ?: run {
            pluginContext.logger.error(
                "templateBuilder is null — onWizardCompleted called before activate?"
            )
            return null
        }
        return tb.buildAndRegister(schema, isStaticStub = false)
    }

    // DocumentationExtension — three-tier tooltip plumbing per ADFA-2432.
    override fun getTooltipCategory(): String = "plugin_forms_plugin"

    override fun getTooltipEntries(): List<PluginTooltipEntry> {
        return listOf(
            PluginTooltipEntry(
                tag = "forms_plugin.wizard",
                summary = "<b>Form-filling app from photo</b><br>Generate an offline-capable data-collection app from a paper form.",
                detail = """
                    <h3>Form-filling app from photo</h3>
                    <p>This wizard generates a runnable Android app that collects data from a paper form's
                    fields. The generated app works offline (queued submission) and can ship data via HTTP POST,
                    CSV share, or JSON share.</p>

                    <h4>How to use:</h4>
                    <ol>
                      <li>Tap <b>Form-filling app from photo</b> in the IDE sidebar.</li>
                      <li>Take or pick a photo of the paper form, or skip and lay out fields manually.</li>
                      <li>Review and edit the detected fields (label, type, required-ness).</li>
                      <li>Set semantic rules per field (required, reusable, postal-code lookup).</li>
                      <li>Pick where collected data should go (POST URL, CSV share, JSON share).</li>
                      <li>Finish — a new template card will appear in the New Project grid.</li>
                    </ol>

                    <p><b>Tip:</b> CV recognition works best on printed labels with good lighting.</p>
                """.trimIndent(),
                buttons = listOf(
                    PluginTooltipButton(
                        description = "Plugin Development Guide",
                        uri = "plugin/development/guide",
                        order = 0,
                    )
                ),
            ),
            PluginTooltipEntry(
                tag = "forms_plugin.template",
                summary = "<b>Form-filling app from photo</b><br>Generated by the Forms wizard.",
                detail = """
                    <h3>Form-filling app from photo template</h3>
                    <p>The static "Form-filling app from photo" card scaffolds a blank form-data app —
                    you can fill in fields by hand inside the generated project. To get a richer scaffold
                    (CV-detected fields, semantic rules, configured submitters), run the wizard from the
                    sidebar instead and pick the per-instance template it registers.</p>
                """.trimIndent(),
            ),
        )
    }

    override fun onDocumentationInstall(): Boolean {
        pluginContext.logger.info("Forms plugin: documentation installed")
        return true
    }

    override fun onDocumentationUninstall() {
        pluginContext.logger.info("Forms plugin: documentation uninstalled")
    }
}
