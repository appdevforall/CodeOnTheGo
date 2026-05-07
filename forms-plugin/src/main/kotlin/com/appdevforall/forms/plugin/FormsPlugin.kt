package com.appdevforall.forms.plugin

import com.appdevforall.forms.plugin.panel.SchemaPanelFragment
import com.appdevforall.forms.plugin.panel.SchemaPanelHost
import com.appdevforall.forms.plugin.template.FormTemplateBuilder
import com.appdevforall.forms.plugin.wizard.FormsPluginConnector
import com.appdevforall.forms.plugin.wizard.WizardActivity
import com.itsaky.androidide.plugins.IPlugin
import com.itsaky.androidide.plugins.PluginContext
import com.itsaky.androidide.plugins.extensions.DocumentationExtension
import com.itsaky.androidide.plugins.extensions.EditorTabExtension
import com.itsaky.androidide.plugins.extensions.EditorTabItem
import com.itsaky.androidide.plugins.extensions.NavigationItem
import com.itsaky.androidide.plugins.extensions.PluginTooltipButton
import com.itsaky.androidide.plugins.extensions.PluginTooltipEntry
import com.itsaky.androidide.plugins.extensions.ShowAsAction
import com.itsaky.androidide.plugins.extensions.ToolbarAction
import com.itsaky.androidide.plugins.extensions.UIExtension
import com.itsaky.androidide.plugins.services.IdeEditorTabService
import com.itsaky.androidide.plugins.services.IdeFileService
import com.itsaky.androidide.plugins.services.IdeProjectService
import com.itsaky.androidide.plugins.services.IdeTemplateService
import com.itsaky.androidide.plugins.services.IdeUIService
import java.io.File

/**
 * Code on the Go plugin that scaffolds a runnable, offline-capable form-data
 * Android app from a photo of a paper form.
 *
 * Two-phase architecture:
 *
 * 1. **Static template registration.** On `activate()` the plugin registers a
 *    single "Form-filling app from photo" template via
 *    [IdeTemplateService.registerTemplate]. The template scaffolds a generic
 *    form-app shell with a one-field stub `assets/form_schema.json` — the
 *    project compiles and runs out of the box without ever launching the
 *    wizard.
 *
 * 2. **Schema capture into the open project.** After the user opens a
 *    generated project, the plugin contributes:
 *      - a side-menu entry ("Form schema") that opens [SchemaPanelFragment]
 *        as a main editor tab — the panel shows the current schema and a
 *        Capture button.
 *      - a toolbar action ("📷 Capture form from photo") that launches the
 *        wizard directly. (Subject to host wiring; see QUESTIONS.md Q1.)
 *      - a main editor tab ([SchemaPanelFragment]) that the side-menu /
 *        toolbar hand off to.
 *
 *    The wizard's last step writes `app/src/main/assets/form_schema.json`
 *    into the open project via [IdeFileService] / [IdeProjectService]. The
 *    generated app's runtime renderer reads that JSON on every launch, so
 *    iteration is just file replacement plus an APK rebuild.
 */
class FormsPlugin : IPlugin, UIExtension, EditorTabExtension, DocumentationExtension {

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

        // Hook the schema panel up to the open project's schema file. The
        // panel re-resolves the locator on every render, so this lambda runs
        // on the UI thread and must stay cheap. Returning null is the panel's
        // empty-state signal.
        SchemaPanelHost.locator = {
            val projectService = pluginContext.services.get(IdeProjectService::class.java)
            val project = projectService?.getCurrentProject()
            if (project != null) {
                SchemaPanelHost(
                    schemaFile = File(project.rootDir, ASSETS_SCHEMA_PATH),
                )
            } else {
                null
            }
        }

        val templateService = pluginContext.services.get(IdeTemplateService::class.java)
        if (templateService == null) {
            pluginContext.logger.warn(
                "IdeTemplateService not available — schema panel still works, but " +
                    "the static template card won't appear in the New Project grid."
            )
            return true
        }

        templateBuilder = FormTemplateBuilder(pluginContext, templateService).also { tb ->
            // Idempotent: registerTemplate overwrites previous file content each
            // time. Cheap enough to do unconditionally on every activate; not
            // worth a sentinel file.
            val registered = tb.buildAndRegisterStaticStub()
            if (registered == null) {
                pluginContext.logger.warn(
                    "Static stub template registration returned null. The Forms " +
                        "side menu still works but the New Project card won't appear."
                )
            }
        }
        return true
    }

    override fun deactivate(): Boolean {
        pluginContext.logger.info("FormsPlugin: deactivating")
        FormsPluginConnector.unbind(this)
        SchemaPanelHost.locator = null
        return true
    }

    override fun dispose() {
        pluginContext.logger.info("FormsPlugin: disposing")
        FormsPluginConnector.unbind(this)
        SchemaPanelHost.locator = null
        templateBuilder = null
    }

    override fun getSideMenuItems(): List<NavigationItem> {
        return listOf(
            NavigationItem(
                id = "forms_schema_panel",
                title = pluginContext.androidContext.getString(R.string.forms_sidebar_title),
                icon = android.R.drawable.ic_menu_edit,
                isEnabled = true,
                isVisible = true,
                group = "templates",
                order = 0,
                tooltipTag = "forms_plugin.wizard",
                action = ::openSchemaPanel,
            )
        )
    }

    override fun getToolbarActions(): List<ToolbarAction> {
        return listOf(
            ToolbarAction(
                id = "forms_capture_from_photo",
                title = pluginContext.androidContext.getString(R.string.forms_toolbar_action_title),
                icon = android.R.drawable.ic_menu_camera,
                showAsAction = ShowAsAction.IF_ROOM,
                isEnabled = true,
                isVisible = true,
                order = 0,
                action = ::launchWizard,
            )
        )
    }

    override fun getMainEditorTabs(): List<EditorTabItem> {
        return listOf(
            EditorTabItem(
                id = SCHEMA_PANEL_TAB_ID,
                title = pluginContext.androidContext.getString(R.string.forms_panel_tab_title),
                icon = android.R.drawable.ic_menu_edit,
                fragmentFactory = { SchemaPanelFragment() },
                isCloseable = true,
                isPersistent = false,
                order = 50,
                isEnabled = true,
                isVisible = true,
                tooltip = pluginContext.androidContext.getString(R.string.forms_panel_tab_title),
            )
        )
    }

    /**
     * Surface the schema panel as a main editor tab. Mirrors how
     * `MarkdownPreviewerPlugin` opens its preview tab — falls through to a
     * direct wizard launch if the editor tab system isn't available, so the
     * action is never a complete dead-end.
     */
    private fun openSchemaPanel() {
        val tabService = pluginContext.services.get(IdeEditorTabService::class.java)
        if (tabService != null && tabService.isTabSystemAvailable() &&
            tabService.selectPluginTab(SCHEMA_PANEL_TAB_ID)
        ) {
            return
        }
        pluginContext.logger.warn(
            "IdeEditorTabService not available or selectPluginTab failed — " +
                "falling back to launching the wizard directly."
        )
        launchWizard()
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
     * Called by [WizardActivity] when the user finishes the wizard. Writes
     * the captured schema into the open project's `assets/form_schema.json`
     * via [com.itsaky.androidide.plugins.services.IdeFileService] and
     * [com.itsaky.androidide.plugins.services.IdeProjectService]. Returns
     * the absolute path written on success, or null when no project is
     * open / writing fails.
     *
     * Side effect: the next time the user rebuilds the project, the
     * generated app's runtime renderer reads the new schema from assets.
     * The plugin doesn't trigger the rebuild itself — that would require
     * `IdeBuildService` and we want the user to stay in control.
     */
    internal fun onWizardCompleted(schema: FormSchema): String? {
        val projectService = pluginContext.services.get(IdeProjectService::class.java)
        val fileService = pluginContext.services.get(IdeFileService::class.java)
        if (projectService == null || fileService == null) {
            pluginContext.logger.error(
                "IdeProjectService or IdeFileService unavailable. The wizard captured " +
                    "a schema but the plugin can't write it without filesystem access."
            )
            return null
        }
        val project = projectService.getCurrentProject() ?: run {
            pluginContext.logger.warn(
                "No open project — wizard schema not written. Open the form-data " +
                    "project before running Capture form from photo."
            )
            return null
        }
        val target = File(project.rootDir, ASSETS_SCHEMA_PATH)
        target.parentFile?.mkdirs()
        val ok = fileService.writeFile(target, schema.toJson())
        if (!ok) {
            pluginContext.logger.error(
                "IdeFileService.writeFile failed for ${target.absolutePath}"
            )
            return null
        }
        pluginContext.logger.info("Forms wizard wrote schema to ${target.absolutePath}")
        return target.absolutePath
    }

    companion object {
        private const val SCHEMA_PANEL_TAB_ID = "forms_schema_panel_tab"
        /** Project-relative path of the schema the runtime renderer reads. */
        internal const val ASSETS_SCHEMA_PATH = "app/src/main/assets/form_schema.json"
    }

    // DocumentationExtension — three-tier tooltip plumbing per ADFA-2432.
    override fun getTooltipCategory(): String = "plugin_forms_plugin"

    override fun getTooltipEntries(): List<PluginTooltipEntry> {
        return listOf(
            PluginTooltipEntry(
                tag = "forms_plugin.wizard",
                summary = "<b>Form schema</b><br>Capture or edit the form schema for the open project.",
                detail = """
                    <h3>Form schema</h3>
                    <p>The Forms plugin generates a runnable Android app for filling out a paper form.
                    Open the form-data project, then use this side-menu entry to view or recapture the
                    schema. The generated app reads <code>assets/form_schema.json</code> at startup and
                    lays out one input per field; rerunning Capture rewrites the schema and the app
                    picks it up on next rebuild.</p>

                    <h4>Capture flow:</h4>
                    <ol>
                      <li>Tap <b>📷 Capture form from photo</b>.</li>
                      <li>Take or pick a photo of the paper form, or skip to lay out manually.</li>
                      <li>Review and edit the detected fields (label, type, required-ness).</li>
                      <li>Set semantic rules per field (required, reusable).</li>
                      <li>Pick where collected data should go (POST URL, CSV share, JSON share).</li>
                      <li>Finish — the schema is written into the open project's assets dir.</li>
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
                summary = "<b>Form-filling app from photo</b><br>Static template that scaffolds a form-data app.",
                detail = """
                    <h3>Form-filling app from photo template</h3>
                    <p>This card scaffolds an offline-capable Android app whose layout is driven by
                    <code>assets/form_schema.json</code>. The schema starts as a one-field stub so the
                    app builds and launches out of the box — open the project and tap
                    <b>Form schema</b> in the IDE side menu to capture real fields from a photo.</p>
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
