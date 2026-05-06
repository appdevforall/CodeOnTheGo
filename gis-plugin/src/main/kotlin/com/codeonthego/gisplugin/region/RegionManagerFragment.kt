package com.codeonthego.gisplugin.region

import android.app.AlertDialog
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
import com.codeonthego.gisplugin.wizard.WizardLauncher
import com.itsaky.androidide.plugins.base.PluginFragmentHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Bottom-sheet tab that lists cached map regions and lets the user delete or
 * re-download them.
 *
 *  - **Delete** removes the cache directory recursively and refreshes the
 *    list. Confirmed via an AlertDialog so the user doesn't fat-finger away
 *    100 MB of tiles.
 *  - **Re-download** today launches the wizard pre-filled with the existing
 *    region's name. Once C4 + the recipe extension land, this should
 *    instead trigger a foreground re-download against the same bbox without
 *    re-prompting for the area; for now the wizard launch is good enough
 *    because the wizard itself runs the download stub.
 *
 * `onResume` re-loads from disk so external changes (wizard finished writing
 * a new region while we were on another tab) flow back without a content
 * observer.
 */
class RegionManagerFragment : Fragment(), RegionAdapter.Listener {

    private companion object {
        const val PLUGIN_ID = "com.codeonthego.gisplugin"
    }

    private lateinit var list: RecyclerView
    private lateinit var emptyState: View
    private val adapter = RegionAdapter(this)

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
        list.layoutManager = LinearLayoutManager(requireContext())
        list.adapter = adapter
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    /** Reload from disk and toggle empty / list visibility accordingly. */
    private fun refresh() {
        viewLifecycleOwner.lifecycleScope.launch {
            val items = withContext(Dispatchers.IO) { RegionCache.list() }
            adapter.submit(items)
            val isEmpty = items.isEmpty()
            list.visibility = if (isEmpty) View.GONE else View.VISIBLE
            emptyState.visibility = if (isEmpty) View.VISIBLE else View.GONE
        }
    }

    // ----- RegionAdapter.Listener -----

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
        // Re-launch the wizard. WizardLauncher's pending-deferred check makes
        // this a no-op while another wizard is in flight.
        viewLifecycleOwner.lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                runCatching { WizardLauncher.launchAndAwait(requireContext().applicationContext) }
                    .onFailure { /* IllegalStateException for "another wizard is in flight" — ignore */ }
            }
            refresh()
        }
    }
}
