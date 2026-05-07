package com.codeonthego.gisplugin.region

import android.app.AlertDialog
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.codeonthego.gisplugin.GisPlugin
import com.codeonthego.gisplugin.R
import com.codeonthego.gisplugin.wizard.BboxPickerFragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.snackbar.Snackbar
import com.itsaky.androidide.plugins.PluginContext
import com.itsaky.androidide.plugins.base.PluginFragmentHelper
import com.itsaky.androidide.plugins.services.IdeProjectService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Host-resolved Fragment for the "Map Regions" panel. Registered as a
 * [com.itsaky.androidide.plugins.extensions.EditorTabItem] in
 * [GisPlugin.getMainEditorTabs] so it lives inside the IDE's main editor
 * tab bar — same surface markdown-preview-plugin and apk-viewer-plugin use.
 *
 * Was a full-screen Activity (`RegionManagerActivity`) in the previous
 * iteration; that approach silently failed at runtime because plugin-declared
 * Activities don't resolve via the host's `PackageManager` when the plugin
 * APK is loaded with `DexClassLoader`. See REVIEW2.md C1.
 *
 *  Per-row affordances:
 *  - **Use in this project** copies `tiles.mbtiles` + `pois.json` into
 *    `<projectDir>/app/src/main/assets/maps/` and writes a `region-id.txt`
 *    sentinel so subsequent re-renders show the "✓ In this project" badge.
 *    Atomic-rename pattern (write `.tmp`, rename) so a process kill
 *    mid-copy can't leave a half-written `tiles.mbtiles` behind.
 *  - **Refresh** swaps in [BboxPickerFragment] pre-filled with the region's
 *    name + bbox, so the user can confirm and re-download. The picker
 *    reuses the same regionId — refresh rewrites the existing entry.
 *  - **Delete** removes the region directory recursively (path-traversal-safe).
 *
 *  Bottom CTA:
 *  - **+ Download new region** swaps in [BboxPickerFragment] for a fresh
 *    download. On return the cache is re-loaded and the new region appears.
 */
class RegionManagerFragment : Fragment(),
    RegionAdapter.Listener,
    BboxPickerFragment.Listener {

    private companion object {
        const val REGION_MARKER_FILE = "region-id.txt"
        /** Cap on the marker file's read size — defensive bound (CodeRabbit theme #1). */
        const val MARKER_MAX_BYTES = 1024L
        const val TAG = "GisPlugin.RegionManager"
        const val BBOX_PICKER_TAG = "gis_bbox_picker"
    }

    private var listView: RecyclerView? = null
    private var emptyState: View? = null
    private var btnDownloadNew: MaterialButton? = null
    private var listContainer: View? = null
    private var pickerContainer: View? = null

    private val adapter = RegionAdapter(this)

    /**
     * Resolve the live [PluginContext] from [GisPlugin]'s static. Read on
     * every access (volatile) so a plugin reload doesn't leave us holding a
     * stale reference. Null when the plugin has been disposed.
     */
    private val pluginContext: PluginContext?
        get() = GisPlugin.pluginContext

    override fun onGetLayoutInflater(savedInstanceState: Bundle?): LayoutInflater {
        val inflater = super.onGetLayoutInflater(savedInstanceState)
        return PluginFragmentHelper.getPluginInflater(GisPlugin.PLUGIN_ID, inflater)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.fragment_region_manager, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        listContainer = view.findViewById(R.id.list_container)
        pickerContainer = view.findViewById(R.id.picker_container)
        listView = view.findViewById<RecyclerView>(R.id.regions_list).also { rv ->
            rv.layoutManager = LinearLayoutManager(requireContext())
            rv.adapter = adapter
        }
        emptyState = view.findViewById(R.id.empty_state)
        btnDownloadNew = view.findViewById<MaterialButton>(R.id.btn_download_new).also {
            it.setOnClickListener { showBboxPicker(prefillFrom = null) }
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    /** Reload from disk and toggle empty / list visibility accordingly. */
    private fun refresh() {
        val container = listContainer ?: return
        val empty = emptyState ?: return
        val rv = listView ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            val (rows, isEmpty) = withContext(Dispatchers.IO) {
                val items = RegionCache.list()
                val activeId = readActiveRegionId()
                val rows = items.map { info ->
                    RegionRow(info = info, isInProject = info.regionId == activeId)
                }
                rows to items.isEmpty()
            }
            adapter.submit(rows)
            // Only render list/empty visibility when the picker isn't on top.
            if (container.visibility == View.VISIBLE) {
                rv.visibility = if (isEmpty) View.GONE else View.VISIBLE
                empty.visibility = if (isEmpty) View.VISIBLE else View.GONE
            }
        }
    }

    // ----- Sub-fragment swap (list ⇄ bbox picker) -----

    private fun showBboxPicker(prefillFrom: RegionInfo?) {
        val list = listContainer ?: return
        val picker = pickerContainer ?: return
        list.visibility = View.GONE
        picker.visibility = View.VISIBLE
        val frag = if (prefillFrom != null) {
            BboxPickerFragment.newInstance(
                prefillRegionId = prefillFrom.regionId,
                prefillDisplayName = prefillFrom.displayName,
                prefillBbox = prefillFrom.bbox,
            )
        } else {
            BboxPickerFragment.newInstance()
        }
        // Use childFragmentManager so the picker fragment lives under this
        // fragment's lifecycle — saves us from racing the host tab's lifecycle.
        childFragmentManager.beginTransaction()
            .replace(R.id.picker_container, frag, BBOX_PICKER_TAG)
            .commit()
    }

    private fun showList() {
        val picker = pickerContainer ?: return
        val list = listContainer ?: return
        // Tear down the picker fragment so its lifecycle ends and any in-flight
        // download coroutine bound to viewLifecycleOwner is cancelled cleanly.
        val frag = childFragmentManager.findFragmentByTag(BBOX_PICKER_TAG)
        if (frag != null) {
            childFragmentManager.beginTransaction().remove(frag).commit()
        }
        picker.visibility = View.GONE
        list.visibility = View.VISIBLE
        refresh()
    }

    // ----- BboxPickerFragment.Listener -----

    override fun onBboxPickerSaved() {
        showList()
    }

    override fun onBboxPickerCancelled() {
        showList()
    }

    // ----- RegionAdapter.Listener -----

    override fun onRegionUseInProject(info: RegionInfo) {
        val projectDir = currentProjectRoot()
        if (projectDir == null) {
            Toast.makeText(
                requireContext(),
                R.string.gis_regions_no_project,
                Toast.LENGTH_LONG
            ).show()
            return
        }
        viewLifecycleOwner.lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) { applyRegionToProject(info, projectDir) }
            val message = if (ok) {
                R.string.gis_regions_apply_success
            } else {
                R.string.gis_regions_apply_failed
            }
            Snackbar.make(
                requireView(),
                getString(message),
                Snackbar.LENGTH_LONG
            ).show()
            if (ok) refresh()
        }
    }

    override fun onRegionDelete(info: RegionInfo) {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.gis_regions_confirm_delete_title)
            .setMessage(getString(R.string.gis_regions_confirm_delete_message))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.gis_regions_delete) { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch {
                    val ok = withContext(Dispatchers.IO) { RegionCache.delete(info.regionId) }
                    val msg = if (ok) "Deleted ${info.displayName}" else "Couldn't delete ${info.displayName}"
                    Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
                    refresh()
                }
            }
            .show()
    }

    override fun onRegionRedownload(info: RegionInfo) {
        // Refresh re-opens the bbox picker pre-filled with this region's
        // existing name + bbox so the user can confirm and re-download. Same
        // regionId is reused on save (RegionDownloader rewrites the payload
        // atomically — see REVIEW2.md I3/I4).
        showBboxPicker(prefillFrom = info)
    }

    // ----- Project / asset wiring -----

    /**
     * Resolve the current project root via [IdeProjectService]. Returns null
     * when no project is open or the service isn't available (running in
     * standalone test mode).
     */
    private fun currentProjectRoot(): File? {
        val ctx = pluginContext ?: return null
        val project = ctx.services.get(IdeProjectService::class.java)?.getCurrentProject()
        return project?.rootDir
    }

    /**
     * Read the currently active regionId from the project's marker file
     * (`<projectDir>/app/src/main/assets/maps/region-id.txt`). Null when no
     * region has been applied or the project root isn't resolvable.
     *
     * Bounded read — the marker is supposed to be a region-id slug
     * (≤256 chars). A corrupt or malicious marker over [MARKER_MAX_BYTES]
     * is dropped to avoid loading an arbitrarily large file. Runs on the IO
     * dispatcher (called from [refresh] under withContext).
     */
    private fun readActiveRegionId(): String? {
        val projectDir = currentProjectRoot() ?: return null
        val marker = File(projectDir, "app/src/main/assets/maps/$REGION_MARKER_FILE")
        if (!marker.exists() || !marker.isFile) return null
        if (marker.length() > MARKER_MAX_BYTES) return null
        return runCatching { marker.readText().trim() }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
    }

    /**
     * Copy the cached region's files into the project's `assets/maps/`. Emits
     * three files: `tiles.mbtiles`, `pois.json`, and a `region-id.txt` marker
     * so [readActiveRegionId] can identify which region is bundled. Returns
     * true on success.
     *
     * Path safety:
     *  - `targetDir` is canonicalised against `projectDir` before writing.
     *  - `srcDir` is canonicalised against the cache root — defense in depth
     *    against future callers fabricating a `RegionInfo` whose `directory`
     *    sits outside the cache.
     *
     * Atomicity:
     *  - free-space precheck (REVIEW2.md MB1 / theme #1).
     *  - tiles + pois are written via `<dest>.tmp` + `Files.move(... ATOMIC_MOVE)`.
     *  - the `region-id.txt` marker is written last — its presence implies
     *    the prior two files completed (REVIEW2.md I2 / theme #4).
     */
    private fun applyRegionToProject(info: RegionInfo, projectDir: File): Boolean {
        return try {
            val targetDir = File(projectDir, "app/src/main/assets/maps").apply { mkdirs() }
            val canonicalProject = projectDir.canonicalFile
            val canonicalTarget = targetDir.canonicalFile
            if (!canonicalTarget.toPath().startsWith(canonicalProject.toPath())) {
                Log.w(TAG, "Refusing to write outside project: $canonicalTarget")
                return false
            }
            // Defense-in-depth: the source directory must live under the
            // cache root. RegionInfo today always comes from RegionCache.list(),
            // but a future caller could fabricate one.
            val cacheRoot = RegionCache.rootDir().canonicalFile
            val canonicalSrc = info.directory.canonicalFile
            if (!canonicalSrc.toPath().startsWith(cacheRoot.toPath())) {
                Log.w(TAG, "Refusing to copy from outside cache: $canonicalSrc")
                return false
            }

            val tilesSrc = File(canonicalSrc, "tiles.mbtiles")
            val poisSrc = File(canonicalSrc, "pois.json")

            val needed = (if (tilesSrc.exists()) tilesSrc.length() else 0L) +
                (if (poisSrc.exists()) poisSrc.length() else 0L)
            // Need ~1 MB headroom for marker + filesystem overhead.
            val safetyMargin = 1L * 1024L * 1024L
            val usable = canonicalTarget.usableSpace
            if (usable in 1 until needed + safetyMargin) {
                Log.w(
                    TAG,
                    "Insufficient space to apply region ${info.regionId}: " +
                        "need ${needed + safetyMargin}, have $usable"
                )
                return false
            }

            // Stage tiles + pois to .tmp, atomic-rename, then write marker last.
            if (tilesSrc.exists()) atomicCopy(tilesSrc, File(canonicalTarget, "tiles.mbtiles"))
            if (poisSrc.exists()) atomicCopy(poisSrc, File(canonicalTarget, "pois.json"))
            atomicWriteText(File(canonicalTarget, REGION_MARKER_FILE), info.regionId)
            true
        } catch (e: Exception) {
            // Surface failure in logcat; the caller's snackbar shows the
            // user-facing apply_failed string. Without this the cause was
            // swallowed (REVIEW2.md M5).
            pluginContext?.logger?.error("applyRegionToProject failed for ${info.regionId}", e)
                ?: Log.e(TAG, "applyRegionToProject failed for ${info.regionId}", e)
            false
        }
    }

    /**
     * Atomically copy [src] to [dest] via a temp file. If the destination's
     * filesystem doesn't support atomic moves (rare on Android internal /
     * external storage but possible on FUSE-mounted partitions), falls back
     * to a non-atomic replace.
     */
    private fun atomicCopy(src: File, dest: File) {
        val tmp = File(dest.parentFile, dest.name + ".tmp")
        if (tmp.exists()) tmp.delete()
        src.copyTo(tmp, overwrite = true)
        try {
            Files.move(
                tmp.toPath(),
                dest.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
            )
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(tmp.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun atomicWriteText(dest: File, text: String) {
        val tmp = File(dest.parentFile, dest.name + ".tmp")
        if (tmp.exists()) tmp.delete()
        tmp.writeText(text)
        try {
            Files.move(
                tmp.toPath(),
                dest.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
            )
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(tmp.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }
}
