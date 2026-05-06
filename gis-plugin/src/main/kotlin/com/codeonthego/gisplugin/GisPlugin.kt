package com.codeonthego.gisplugin

import androidx.fragment.app.Fragment
import com.codeonthego.gisplugin.region.RegionManagerFragment
import com.codeonthego.gisplugin.templates.MapTemplateBuilder
import com.codeonthego.gisplugin.wizard.WizardLauncher
import com.itsaky.androidide.plugins.IPlugin
import com.itsaky.androidide.plugins.PluginContext
import com.itsaky.androidide.plugins.extensions.DocumentationExtension
import com.itsaky.androidide.plugins.extensions.PluginTooltipButton
import com.itsaky.androidide.plugins.extensions.PluginTooltipEntry
import com.itsaky.androidide.plugins.extensions.TabItem
import com.itsaky.androidide.plugins.extensions.UIExtension
import com.itsaky.androidide.plugins.services.IdeTemplateService
import java.io.File

/**
 * GIS plugin entry point — ADFA-2436.
 *
 * Three plugin surfaces:
 *  1. **Project templates** registered via `IdeTemplateService.registerTemplate`.
 *     Currently C1 stubs (empty Activity scaffolding); the wizard hand-off
 *     pattern is described in `QUESTIONS.md` Q1.
 *  2. **"Map Regions" bottom-sheet tab** for cache management — visible whenever
 *     a project is open. Implements `UIExtension.getEditorTabs()`. Even when
 *     the cache is empty (the C1 default), the tab is present and shows the
 *     "no regions yet" empty state, which is what users see before they have
 *     scaffolded their first map project.
 *  3. **Documentation tooltips** — Tier 1 / Tier 2 strings tied to the
 *     templates' `tooltipTag` values, so long-press on the template card
 *     surfaces the right help.
 *
 * Lifecycle: `initialize` stores the context; `activate` builds + registers
 * the .cgt files (idempotent, overwrites prior copies on each activation);
 * `deactivate` unregisters them. We intentionally re-build on every
 * `activate` so a `gis-plugin` upgrade flushes stale archives without the
 * IDE having to track template versions.
 */
class GisPlugin : IPlugin, UIExtension, DocumentationExtension {

    private lateinit var context: PluginContext

    /** Names of the .cgt files we registered, for clean unregistration. */
    private val registeredCgtFiles = mutableListOf<String>()

    override fun initialize(context: PluginContext): Boolean {
        return try {
            this.context = context
            context.logger.info("GisPlugin initialized")
            true
        } catch (e: Exception) {
            context.logger.error("GisPlugin initialize() failed", e)
            false
        }
    }

    override fun activate(): Boolean {
        return try {
            val templateService = context.services.get(IdeTemplateService::class.java)
            if (templateService == null) {
                context.logger.error(
                    "IdeTemplateService not available; skipping template registration. " +
                        "Likely cause: plugin was loaded before the template service registered " +
                        "with the plugin manager. Check plugin.permissions in AndroidManifest.xml " +
                        "(filesystem.write must be present)."
                )
                return false
            }

            val cgtOutputDir = File(context.resources.getPluginDirectory(), "cgt-out").apply {
                mkdirs()
            }

            val registered = MapTemplateBuilder.buildAndRegister(
                ctx = context,
                templateService = templateService,
                outputDir = cgtOutputDir
            )
            // CgtTemplateBuilder produces files named after the template name with
            // non-alphanumeric characters stripped — record both so deactivate can
            // call `unregisterTemplate` with the right basename.
            registeredCgtFiles.clear()
            registeredCgtFiles += "OSMMapreadonlyPOIs.cgt"
            registeredCgtFiles += "OSMMapannotate.cgt"

            context.logger.info("GisPlugin activated; registered $registered template(s)")
            true
        } catch (e: Exception) {
            context.logger.error("GisPlugin activate() failed", e)
            false
        }
    }

    override fun deactivate(): Boolean {
        return try {
            val templateService = context.services.get(IdeTemplateService::class.java)
            templateService?.let { service ->
                registeredCgtFiles.forEach { name ->
                    runCatching { service.unregisterTemplate(name) }
                        .onFailure { context.logger.warn("Failed to unregister $name: ${it.message}") }
                }
            }
            registeredCgtFiles.clear()
            context.logger.info("GisPlugin deactivated")
            true
        } catch (e: Exception) {
            context.logger.error("GisPlugin deactivate() failed", e)
            false
        }
    }

    override fun dispose() {
        context.logger.info("GisPlugin disposed")
    }

    // ---------------------------------------------------------------------
    //  UIExtension — bottom-sheet "Map Regions" tab
    // ---------------------------------------------------------------------

    override fun getEditorTabs(): List<TabItem> = listOf(
        TabItem(
            id = "gis.editor_tab.regions",
            title = context.androidContext.getString(R.string.gis_regions_tab_title),
            fragmentFactory = { RegionManagerFragment() },
            isEnabled = true,
            isVisible = true,
            order = 100
        )
    )

    // ---------------------------------------------------------------------
    //  DocumentationExtension — three-tier tooltips for the template cards
    //  + the bottom-sheet tab. Wired so long-press surfaces these strings.
    // ---------------------------------------------------------------------

    override fun getTooltipCategory(): String = "plugin_gis"

    override fun getTooltipEntries(): List<PluginTooltipEntry> = listOf(
        PluginTooltipEntry(
            tag = "gis.template.readonly",
            summary = context.androidContext.getString(R.string.tpl_readonly_desc),
            detail = """
                <h3>OSM Map &mdash; read-only POIs</h3>
                <p>Generates an Android app that renders an offline OpenStreetMap
                tile pack with MapLibre and shows nearby points of interest from
                a bundled JSON dataset. Built for field workers in low-connectivity
                environments.</p>
                <p>The generated project includes a real <code>MapView</code>,
                GPS centering via <code>FusedLocationProviderClient</code>, and a
                style file that points at MapLibre's demo tile server out of the
                box. Replace <code>assets/style.json</code> + drop an
                <code>.mbtiles</code> into <code>assets/maps/</code> to ship
                offline.</p>
            """.trimIndent(),
            buttons = listOf(
                PluginTooltipButton(
                    description = "OSM + MapLibre tutorial",
                    uri = "osm-tutorial.md",
                    order = 0
                )
            )
        ),
        PluginTooltipEntry(
            tag = "gis.template.annotate",
            summary = context.androidContext.getString(R.string.tpl_annotate_desc),
            detail = """
                <h3>OSM Map &mdash; annotate</h3>
                <p>Like the read-only template, but the user can drop pins at
                their current GPS location, attach photos, and submit or share
                the resulting dataset.</p>
                <p>Same MapLibre base; an annotation FAB, CameraX capture, Room
                persistence, and a configurable HTTP submitter (or Sharesheet
                CSV / JSON export) land in later commits.</p>
            """.trimIndent(),
            buttons = listOf(
                PluginTooltipButton(
                    description = "OSM + MapLibre tutorial",
                    uri = "osm-tutorial.md",
                    order = 0
                )
            )
        ),
        PluginTooltipEntry(
            tag = "gis.editor_tab.regions",
            summary = "<b>Map Regions</b><br>Manage cached OSM tile + POI bundles.",
            detail = """
                <h3>Map Regions</h3>
                <p>Lists cached map regions stored under
                <code>/sdcard/CodeOnTheGo/maps/</code>. A region is reusable
                across multiple Map projects so you don't re-download the same
                tile pack twice.</p>
                <p><b>Status (C1):</b> the tab is visible but empty &mdash; the
                cache layout and download flow arrive in C2.</p>
            """.trimIndent(),
            buttons = emptyList()
        )
    )

    override fun onDocumentationInstall(): Boolean {
        context.logger.info("GisPlugin documentation installed")
        return true
    }

    override fun onDocumentationUninstall() {
        context.logger.info("GisPlugin documentation uninstalled")
    }

    /**
     * Tier 3 docs subdirectory under `src/main/assets/`. The IDE walks this
     * tree at install time and inserts every file into the documentation DB
     * under `plugin/<pluginId>/<relative-path>` (per
     * `Tier3AssetWalker`); a `PluginTooltipButton` with `uri = "osm-tutorial.md"`
     * resolves to `http://localhost:6174/plugin/com.codeonthego.gisplugin/osm-tutorial.md`.
     */
    override fun getTier3DocsAssetPath(): String? = "docs"

    // ---------------------------------------------------------------------
    //  Shared helper for tests / future commits — entry point if a host wants
    //  to invoke the wizard directly. Currently unused by the IDE because
    //  the recipe-blocking pattern needs an API extension (see QUESTIONS.md).
    // ---------------------------------------------------------------------

    @Suppress("unused")
    internal fun launchWizardForTesting(): com.codeonthego.gisplugin.wizard.WizardResult? {
        return WizardLauncher.launchAndAwait(context.androidContext)
    }
}
