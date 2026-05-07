package com.appdevforall.forms.plugin

import com.appdevforall.forms.plugin.panel.SchemaPanelFragment
import com.appdevforall.forms.plugin.panel.SchemaPanelHost
import com.appdevforall.forms.plugin.template.FormTemplateBuilder
import com.appdevforall.forms.plugin.wizard.FormsPluginConnector
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
import java.io.File
import java.lang.ref.WeakReference

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
 *        as a main editor tab — the panel shows the current schema and
 *        hosts the wizard inline when the user taps Capture.
 *      - a toolbar action ("📷 Capture form from photo") that opens the
 *        same editor tab. (Subject to host wiring; see QUESTIONS.md Q1.)
 *      - a main editor tab ([SchemaPanelFragment]).
 *
 *    The wizard's last step writes `app/src/main/assets/form_schema.json`
 *    into the open project via [IdeFileService] / [IdeProjectService]. The
 *    generated app's runtime renderer reads that JSON on every launch, so
 *    iteration is just file replacement plus an APK rebuild.
 *
 * **No plugin Activities.** Plugin APKs load via DexClassLoader, so any
 * `<activity>` declared in this plugin's AndroidManifest never enters the
 * host's merged manifest — `Intent(ctx, FooActivity::class.java)` would
 * throw `ActivityNotFoundException`. The wizard is therefore a Fragment
 * hosted inside [SchemaPanelFragment]'s editor tab, not its own Activity.
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
        //
        // `SchemaPanelHost.locator` is a process-singleton, so we capture
        // `pluginContext` via a WeakReference rather than letting the lambda
        // strong-hold this plugin instance for the lifetime of the process.
        // If we get re-activated without `dispose()` first running cleanly
        // (hot-reload during dev, plugin recycle), the stale lambda's weak
        // ref returns null and the panel falls back to the empty state until
        // the new locator overwrites the slot.
        val ctxRef = WeakReference(pluginContext)
        SchemaPanelHost.locator = locator@{
            val ctx = ctxRef.get() ?: return@locator null
            val projectService = ctx.services.get(IdeProjectService::class.java)
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
                // Toolbar action opens the schema-panel editor tab; the user
                // taps Capture inside the panel to launch the wizard. We
                // don't try to push the user straight into the wizard from
                // here because the wizard now lives inside the panel's
                // childFragmentManager — it has no entry point of its own.
                action = ::openSchemaPanel,
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
     * `MarkdownPreviewerPlugin` opens its preview tab. If the editor tab
     * system isn't available, log and bail — the panel is the only entry
     * point now (the wizard lives inside it).
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
                "the Forms schema panel could not be opened. The host IDE may " +
                "not yet expose the editor tab system."
        )
    }

    /**
     * Called by [com.appdevforall.forms.plugin.wizard.WizardHostFragment]
     * when the user finishes the wizard. Writes the captured schema into
     * the open project's `assets/form_schema.json` via [IdeFileService] /
     * [IdeProjectService].
     *
     * **Threading.** This function performs disk I/O; callers must invoke
     * it from a coroutine on `Dispatchers.IO` (or equivalent). The wizard
     * fragment does this via `viewLifecycleOwner.lifecycleScope.launch`.
     *
     * **Atomicity.** Writes to a sibling `.tmp` file first and renames into
     * place on success. A mid-write crash leaves the previous schema
     * intact rather than truncating it to garbage that
     * [FormSchema.fromJson] can't parse — important on the reference 4 GB
     * device where flash pressure is a real failure mode.
     *
     * @return the absolute path written on success, or null when no project
     *   is open, services are unavailable, or the write failed.
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
        // Atomic write: stage to a sibling `.tmp` first, rename on success.
        // JSON is plain UTF-8 so writeFile (which UTF-8-transcodes) is safe
        // here. For binary payloads use writeBinary / writeStream — see
        // IdeFileService docs.
        val staging = File(target.parentFile, target.name + ".tmp")
        if (!fileService.writeFile(staging, schema.toJson())) {
            pluginContext.logger.error(
                "IdeFileService.writeFile failed for ${staging.absolutePath}"
            )
            return null
        }
        if (target.exists() && !fileService.delete(target)) {
            pluginContext.logger.error(
                "Could not remove existing schema at ${target.absolutePath} before rename. " +
                    "Staged file left at ${staging.absolutePath}."
            )
            return null
        }
        if (!staging.renameTo(target)) {
            pluginContext.logger.error(
                "Could not rename ${staging.absolutePath} to ${target.absolutePath}. " +
                    "Schema may be in inconsistent state."
            )
            return null
        }
        pluginContext.logger.info("Forms wizard wrote schema to ${target.absolutePath}")
        return target.absolutePath
    }

    companion object {
        /**
         * Plugin id matches the `plugin.id` meta-data declared in the
         * AndroidManifest. Fragments use this to look up the plugin's
         * resource context via [com.itsaky.androidide.plugins.base.PluginFragmentHelper.getPluginInflater].
         */
        const val PLUGIN_ID: String = "com.appdevforall.forms.plugin"
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
