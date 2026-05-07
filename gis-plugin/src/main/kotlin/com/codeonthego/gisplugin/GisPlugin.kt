package com.codeonthego.gisplugin

import android.content.Intent
import com.codeonthego.gisplugin.region.RegionManagerActivity
import com.codeonthego.gisplugin.templates.MapTemplateBuilder
import com.itsaky.androidide.plugins.IPlugin
import com.itsaky.androidide.plugins.PluginContext
import com.itsaky.androidide.plugins.extensions.DocumentationExtension
import com.itsaky.androidide.plugins.extensions.NavigationItem
import com.itsaky.androidide.plugins.extensions.PluginTooltipButton
import com.itsaky.androidide.plugins.extensions.PluginTooltipEntry
import com.itsaky.androidide.plugins.extensions.UIExtension
import com.itsaky.androidide.plugins.services.IdeTemplateService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.io.File

/**
 * GIS plugin entry point — ADFA-2436.
 *
 * **Two-phase architecture** (replaces the recipe-blocking-wizard model
 * documented in the prior `QUESTIONS.md` Q1 finding):
 *
 *  1. **Static project templates.** Two `.cgt` archives ("OSM Map —
 *     read-only POIs" + "OSM Map — annotate") are emitted on first
 *     activation and registered with [IdeTemplateService]. Each scaffolds a
 *     generic Map app with a stub `tiles.mbtiles` (~1 MB world overview)
 *     and an empty `pois.json`, so the generated app builds and runs out
 *     of the box even before the user picks a region. Recipes stay pure
 *     Pebble — no Kotlin-recipe injection point needed.
 *
 *  2. **"Map Regions" sidebar entry.** After the project is open, the
 *     plugin contributes one [NavigationItem] via
 *     [UIExtension.getSideMenuItems]. Tapping it launches
 *     [RegionManagerActivity], which hosts the regions panel — list of
 *     cached regions with per-row "Use in this project" / "Refresh" /
 *     "Delete" affordances and a bottom "+ Download new region" CTA that
 *     fires the bbox picker.
 *
 *  No bottom-sheet tab. Region management is project-resource navigation,
 *  not process output, so the sidebar slot is the right surface.
 */
class GisPlugin : IPlugin, UIExtension, DocumentationExtension {

    /**
     * Static handle so internal Activities ([RegionManagerActivity]) can
     * resolve the [IdeProjectService] without re-implementing service
     * lookup. Set in [initialize], cleared in [dispose]. The plugin runs
     * one instance at a time; the static is safe.
     */
    companion object {
        @Volatile
        var pluginContext: PluginContext? = null
            internal set
    }

    private lateinit var context: PluginContext

    /** Names of the .cgt files we registered, for clean unregistration. */
    private val registeredCgtFiles = mutableListOf<String>()

    /**
     * Background scope tied to the plugin's lifecycle. Cancelled in [dispose].
     * SupervisorJob so a single failed coroutine doesn't tear the rest down.
     */
    private val pluginScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun initialize(context: PluginContext): Boolean {
        return try {
            this.context = context
            pluginContext = context
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
            // CgtTemplateBuilder names files after the template name with
            // non-alphanumerics stripped. Track both so deactivate can
            // call unregisterTemplate cleanly.
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
        pluginScope.cancel()
        pluginContext = null
        context.logger.info("GisPlugin disposed")
    }

    // ---------------------------------------------------------------------
    //  UIExtension — sidebar entry only. No bottom-sheet tab; that's the
    //  wrong surface for project-resource navigation.
    // ---------------------------------------------------------------------

    override fun getSideMenuItems(): List<NavigationItem> = listOf(
        NavigationItem(
            id = "gis.sidebar.map_regions",
            title = context.androidContext.getString(R.string.gis_regions_title),
            icon = android.R.drawable.ic_menu_mapmode,
            isEnabled = true,
            isVisible = true,
            group = "tools",
            order = 100,
            tooltipTag = "gis.sidebar.map_regions",
            action = { launchRegionManager() }
        )
    )

    private fun launchRegionManager() {
        val androidContext = context.androidContext
        val intent = Intent(androidContext, RegionManagerActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { androidContext.startActivity(intent) }
            .onFailure { context.logger.warn("Failed to launch RegionManagerActivity: ${it.message}") }
    }

    // ---------------------------------------------------------------------
    //  DocumentationExtension — three-tier tooltips for the template cards
    //  + the sidebar entry.
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
                <p>The scaffold ships a stub world-overview <code>tiles.mbtiles</code>
                and an empty <code>pois.json</code>, so the app builds and runs
                out of the box. To target a specific region: open <b>Map Regions</b>
                in the sidebar, download a region with the bbox picker, and tap
                <b>Use in this project</b>.</p>
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
            tag = "gis.sidebar.map_regions",
            summary = "<b>Map Regions</b><br>Manage cached OSM tile + POI bundles for the current project.",
            detail = """
                <h3>Map Regions</h3>
                <p>Lists cached map regions stored under
                <code>/sdcard/CodeOnTheGo/maps/</code>. A region is reusable
                across multiple Map projects so you don't re-download the same
                tile pack twice.</p>
                <p>Per region you can <b>Use in this project</b> (copies the
                region's <code>tiles.mbtiles</code> + <code>pois.json</code>
                into the open project's <code>assets/maps/</code>),
                <b>Refresh</b> (re-download the region from source), or
                <b>Delete</b> (free disk space).</p>
                <p>Tap <b>+ Download new region</b> at the bottom to launch
                the bbox picker and create a new entry in the cache.</p>
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
     * under `plugin/<pluginId>/<relative-path>` (per `Tier3AssetWalker`); a
     * `PluginTooltipButton` with `uri = "osm-tutorial.md"` resolves to
     * `http://localhost:6174/plugin/com.codeonthego.gisplugin/osm-tutorial.md`.
     */
    override fun getTier3DocsAssetPath(): String? = "docs"

}
