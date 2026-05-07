package com.codeonthego.gisplugin.region

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.codeonthego.gisplugin.R
import com.codeonthego.gisplugin.wizard.BboxPickerActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.snackbar.Snackbar
import com.itsaky.androidide.plugins.PluginContext
import com.itsaky.androidide.plugins.base.PluginFragmentHelper
import com.itsaky.androidide.plugins.services.IdeProjectService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Full-screen sidebar destination for managing cached map regions.
 *
 * Hosted by [RegionManagerActivity]; same layout / UX as the bottom-sheet
 * tab the previous prototype shipped, but promoted to a primary navigation
 * destination because:
 *  - region management is project-scoped resource management, not process
 *    output (build / lint / search / log) which is what the bottom sheet is
 *    optimised for;
 *  - "Use in this project" needs space to surface confirmation + per-region
 *    progress without jamming a half-screen sheet over the editor.
 *
 *  Per-row affordances:
 *  - **Use in this project** copies `tiles.mbtiles` + `pois.json` into
 *    `<projectDir>/app/src/main/assets/maps/` and writes a `region-id.txt`
 *    sentinel so subsequent re-renders show the "✓ In this project" badge.
 *  - **Refresh** re-launches the bbox picker; the picker writes back into
 *    the same regionId, overwriting tiles + POIs in place.
 *  - **Delete** removes the region directory recursively (path-traversal-safe).
 *
 *  Bottom CTA:
 *  - **+ Download new region** launches [BboxPickerActivity] for a fresh
 *    download. On return the cache is re-loaded and the new region appears.
 */
class RegionManagerFragment : Fragment(), RegionAdapter.Listener {

    private companion object {
        const val PLUGIN_ID = "com.codeonthego.gisplugin"
        const val REGION_MARKER_FILE = "region-id.txt"
        const val REQ_BBOX_PICKER = 1042
    }

    private lateinit var list: RecyclerView
    private lateinit var emptyState: View
    private lateinit var btnDownloadNew: MaterialButton
    private val adapter = RegionAdapter(this)

    /** Static handle the host Activity sets so we can resolve services + project. */
    var pluginContext: PluginContext? = null

    override fun onGetLayoutInflater(savedInstanceState: Bundle?): LayoutInflater {
        val inflater = super.onGetLayoutInflater(savedInstanceState)
        return PluginFragmentHelper.getPluginInflater(PLUGIN_ID, inflater)
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
        list = view.findViewById(R.id.regions_list)
        emptyState = view.findViewById(R.id.empty_state)
        btnDownloadNew = view.findViewById(R.id.btn_download_new)
        list.layoutManager = LinearLayoutManager(requireContext())
        list.adapter = adapter

        btnDownloadNew.setOnClickListener { launchBboxPicker() }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    /** Reload from disk and toggle empty / list visibility accordingly. */
    private fun refresh() {
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
            list.visibility = if (isEmpty) View.GONE else View.VISIBLE
            emptyState.visibility = if (isEmpty) View.VISIBLE else View.GONE
        }
    }

    @Suppress("DEPRECATION")
    private fun launchBboxPicker() {
        // Using the deprecated startActivityForResult/onActivityResult pair
        // because the modern ActivityResultLauncher wiring needs to register
        // before STARTED — fine here, but we don't actually need the intent
        // result, only "did the picker run." The deprecated path is
        // mechanically simpler and equivalent for this use case.
        val intent = Intent(requireContext(), BboxPickerActivity::class.java)
        startActivityForResult(intent, REQ_BBOX_PICKER)
    }

    @Deprecated("Pairs with the deprecated startActivityForResult; modern launcher is overkill for our needs.")
    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_BBOX_PICKER) {
            // Whatever the picker did, the cache may have a new entry. Refresh.
            refresh()
        }
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
        // Refresh today re-opens the bbox picker so the user can re-pick / re-confirm
        // and trigger a re-download. A future "silently re-download against this
        // exact bbox" pass would skip the picker, but that's a follow-up.
        launchBboxPicker()
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
     * Runs on the IO dispatcher (called from [refresh] under withContext).
     */
    private fun readActiveRegionId(): String? {
        val projectDir = currentProjectRoot() ?: return null
        val marker = File(projectDir, "app/src/main/assets/maps/$REGION_MARKER_FILE")
        if (!marker.exists() || !marker.isFile) return null
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
     * Path safety: all writes are confined to `<projectDir>/app/src/main/assets/maps/`.
     * No user-supplied path component reaches the filesystem.
     */
    private fun applyRegionToProject(info: RegionInfo, projectDir: File): Boolean {
        return try {
            val targetDir = File(projectDir, "app/src/main/assets/maps").apply { mkdirs() }
            // Defensive: ensure target stays inside projectDir.
            val canonicalProject = projectDir.canonicalFile
            val canonicalTarget = targetDir.canonicalFile
            if (!canonicalTarget.toPath().startsWith(canonicalProject.toPath())) {
                return false
            }
            val srcDir = info.directory
            val tilesSrc = File(srcDir, "tiles.mbtiles")
            val poisSrc = File(srcDir, "pois.json")

            if (tilesSrc.exists()) tilesSrc.copyTo(File(targetDir, "tiles.mbtiles"), overwrite = true)
            if (poisSrc.exists()) poisSrc.copyTo(File(targetDir, "pois.json"), overwrite = true)

            File(targetDir, REGION_MARKER_FILE).writeText(info.regionId)
            true
        } catch (e: Exception) {
            // Surface failure to the caller; snackbar shows the apply_failed string.
            false
        }
    }
}
