package com.codeonthego.gisplugin.wizard

import android.graphics.RectF
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.codeonthego.gisplugin.GisPlugin
import com.codeonthego.gisplugin.R
import com.codeonthego.gisplugin.region.RegionManagerFragment
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.itsaky.androidide.plugins.base.PluginFragmentHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.core.widget.doAfterTextChanged

/**
 * Bbox picker — the "+ Download new region" destination from
 * [RegionManagerFragment]. Fragment-hosted (not Activity-hosted) because
 * plugin-declared Activities don't resolve via the host's `PackageManager`
 * when the plugin APK is loaded with `DexClassLoader` (see REVIEW2.md C1).
 *
 * Hosted under the same editor tab as [RegionManagerFragment]; the regions
 * fragment swaps this in via `parentFragmentManager.beginTransaction()
 * .replace(...)` and listens for [Listener.onBboxPickerSaved] /
 * [Listener.onBboxPickerCancelled] to swap itself back in.
 *
 * UX:
 *  - top app bar with a back affordance (treated as cancel)
 *  - region-name text field (becomes the user-facing displayName, slugified
 *    into the on-disk regionId)
 *  - the existing [BboxOverlayView] over a placeholder map background
 *  - live tile-count + size estimate above the map
 *  - bottom Cancel / Save buttons
 *
 * On Save, the picker hands the bbox to [RegionDownloader] and writes the
 * canonical `/sdcard/CodeOnTheGo/maps/<regionId>/{tiles.mbtiles,pois.json,meta.json}`
 * layout. The download itself runs inside the fragment's `lifecycleScope`;
 * the fragment notifies its host (regions fragment) on success / cancel.
 *
 * **Refresh mode.** When [ARG_PREFILL_REGION_ID] / [ARG_PREFILL_BBOX] are
 * supplied via the fragment's arguments, the picker prefills the region-name
 * field and reuses the same regionId on save — so a Refresh from the regions
 * panel rewrites the existing cache entry rather than creating a sibling.
 */
class BboxPickerFragment : Fragment() {

    /**
     * Host fragment is expected to implement this to swap itself back in
     * once the picker finishes. Plain interface (no Bundle round-trip needed
     * because both fragments live under the same parent FragmentManager).
     */
    interface Listener {
        fun onBboxPickerSaved()
        fun onBboxPickerCancelled()
    }

    companion object {
        const val ARG_PREFILL_REGION_ID = "prefillRegionId"
        const val ARG_PREFILL_DISPLAY_NAME = "prefillDisplayName"
        const val ARG_PREFILL_BBOX = "prefillBbox"

        fun newInstance(
            prefillRegionId: String? = null,
            prefillDisplayName: String? = null,
            prefillBbox: DoubleArray? = null,
        ): BboxPickerFragment = BboxPickerFragment().apply {
            arguments = Bundle().apply {
                if (prefillRegionId != null) putString(ARG_PREFILL_REGION_ID, prefillRegionId)
                if (prefillDisplayName != null) putString(ARG_PREFILL_DISPLAY_NAME, prefillDisplayName)
                if (prefillBbox != null) putDoubleArray(ARG_PREFILL_BBOX, prefillBbox)
            }
        }
    }

    private lateinit var bboxOverlay: BboxOverlayView
    private lateinit var estimateTilesText: TextView
    private lateinit var estimateSizeText: TextView
    private lateinit var edtName: TextInputEditText
    private lateinit var btnSave: MaterialButton
    private lateinit var btnCancel: MaterialButton
    private lateinit var pickerContainer: View
    private lateinit var toolbar: MaterialToolbar

    private var currentBbox: Bbox? = null
    private var currentEstimate: TileEstimate? = null
    private var regionName: String = ""
    private var downloadJob: Job? = null

    /** Set when arguments specify a refresh of an existing region. */
    private var refreshRegionId: String? = null

    override fun onGetLayoutInflater(savedInstanceState: Bundle?): LayoutInflater {
        val inflater = super.onGetLayoutInflater(savedInstanceState)
        return PluginFragmentHelper.getPluginInflater(GisPlugin.PLUGIN_ID, inflater)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.fragment_bbox_picker, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        toolbar = view.findViewById(R.id.toolbar)
        toolbar.setNavigationOnClickListener { cancel() }

        edtName = view.findViewById(R.id.edt_name)
        bboxOverlay = view.findViewById(R.id.bbox_overlay)
        estimateTilesText = view.findViewById(R.id.estimate_tiles)
        estimateSizeText = view.findViewById(R.id.estimate_size)
        btnSave = view.findViewById(R.id.btn_save)
        btnCancel = view.findViewById(R.id.btn_cancel)
        pickerContainer = view.findViewById(R.id.picker_container)

        val args = arguments
        refreshRegionId = args?.getString(ARG_PREFILL_REGION_ID)
        val prefillName = args?.getString(ARG_PREFILL_DISPLAY_NAME)
        if (!prefillName.isNullOrBlank()) {
            edtName.setText(prefillName)
            regionName = prefillName.trim()
        }

        edtName.doAfterTextChanged {
            regionName = it?.toString().orEmpty().trim()
        }

        bboxOverlay.setListener { rect -> recomputeEstimate(rect) }
        bboxOverlay.post {
            // Force first estimate render once layout settles (BboxOverlayView
            // sets up its default 60 % square in onSizeChanged).
            recomputeEstimate(bboxOverlay.currentBboxPx())
        }

        btnSave.setOnClickListener { save() }
        btnCancel.setOnClickListener { cancel() }
    }

    /**
     * Project a pixel-space rectangle to a synthetic lat/lon bbox. Same
     * approach the old BboxPickerActivity used for the C2 stub — pretend the
     * picker is looking at a square centred on (37.0, -122.0) at 0.0005°/px.
     * Replaced by the real MapLibre projection once the placeholder
     * background is swapped for an actual MapView (R1 backlog).
     */
    private fun recomputeEstimate(rect: RectF) {
        if (rect.isEmpty) return
        val centreLat = 37.0
        val centreLon = -122.0
        val degPerPx = 0.0005
        val midX = pickerContainer.width / 2f
        val midY = pickerContainer.height / 2f
        val south = centreLat - (rect.bottom - midY) * degPerPx
        val north = centreLat - (rect.top - midY) * degPerPx
        val west = centreLon + (rect.left - midX) * degPerPx
        val east = centreLon + (rect.right - midX) * degPerPx
        val bbox = runCatching { Bbox(south, west, north, east) }.getOrNull() ?: return
        currentBbox = bbox
        val estimate = TileEstimator.estimate(bbox)
        currentEstimate = estimate
        estimateTilesText.text = "${estimate.tileCount} tiles"
        estimateSizeText.text = "%.1f MB · zoom %d–%d".format(
            estimate.sizeMb(), estimate.zoomMin, estimate.zoomMax
        )
    }

    private fun save() {
        val bbox = currentBbox ?: return
        // Slugify the user-supplied name. Empty input → timestamp-based id.
        val displayName = regionName.ifBlank { "Custom region" }
        val regionId = refreshRegionId
            ?: regionName
                .ifBlank { "region-${System.currentTimeMillis()}" }
                .lowercase()
                .replace(Regex("[^a-z0-9-]+"), "-")
                .trim('-')
                .ifBlank { "region-${System.currentTimeMillis()}" }

        // Disable buttons during the download to avoid double-fire.
        btnSave.isEnabled = false
        btnCancel.isEnabled = false

        downloadJob = viewLifecycleOwner.lifecycleScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    RegionDownloader.download(
                        context = requireContext().applicationContext,
                        regionId = regionId,
                        displayName = displayName,
                        bbox = bbox
                    )
                }
            }.onSuccess {
                notifySaved()
            }.onFailure {
                // Surface error inline; restore buttons so user can retry / cancel.
                btnSave.isEnabled = true
                btnCancel.isEnabled = true
                estimateSizeText.text = "Save failed: ${it.message ?: it.javaClass.simpleName}"
            }
        }
    }

    private fun cancel() {
        downloadJob?.cancel()
        notifyCancelled()
    }

    private fun notifySaved() {
        // Prefer parent fragment listener; fall back to host activity.
        (parentFragment as? Listener)?.onBboxPickerSaved()
            ?: (activity as? Listener)?.onBboxPickerSaved()
            ?: defaultPopBack()
    }

    private fun notifyCancelled() {
        (parentFragment as? Listener)?.onBboxPickerCancelled()
            ?: (activity as? Listener)?.onBboxPickerCancelled()
            ?: defaultPopBack()
    }

    /** Fallback: pop the fragment back-stack if no listener is wired. */
    private fun defaultPopBack() {
        if (parentFragmentManager.backStackEntryCount > 0) {
            parentFragmentManager.popBackStack()
        }
    }
}
