package com.codeonthego.gisplugin.wizard

import android.graphics.RectF
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.codeonthego.gisplugin.R
import com.codeonthego.gisplugin.region.RegionCache
import com.codeonthego.gisplugin.region.RegionInfo
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers

/**
 * Three-step wizard for the GIS plugin.
 *
 *  Step 1: pick from cached regions OR opt for a new download (with a
 *          search-pill / freeform name field).
 *  Step 2: Gaia-GPS-style direct manipulation on a placeholder map. The user
 *          sees a default 60 % square (which would be 10 × 10 km on a real
 *          map) and can drag corners to resize / drag interior to translate.
 *          A live tile/MB estimate updates above the map. When C4 lands the
 *          real MapLibre `MapView`, only the background view changes.
 *  Step 3: download progress. Stub today (synthetic timer); real HTTP hits
 *          drop in here in C2's network commit.
 *
 * State is held in [WizardState]. Step navigation is done by toggling
 * visibility of three sibling views in `step_container` rather than a
 * `ViewPager` — the wizard's state machine is small enough that explicit
 * `View.GONE` / `View.VISIBLE` toggles are easier to reason about than fragment
 * lifecycle events.
 *
 * Cancellation contract: any path that exits the wizard without a successful
 * step-3 finish (back button, Cancel, system kill) calls
 * [WizardLauncher.complete] with `null`. Idempotency is on `CompletableDeferred`
 * — only the first non-null result wins.
 */
class WizardActivity : AppCompatActivity() {

    private val state = WizardState()

    // --- step 1
    private lateinit var cachedAdapter: CachedRegionPickerAdapter
    private lateinit var sourceToggle: MaterialButtonToggleGroup
    private lateinit var btnUseCached: MaterialButton
    private lateinit var btnDownloadNew: MaterialButton
    private lateinit var cachedSection: View
    private lateinit var cachedList: RecyclerView
    private lateinit var cachedEmpty: View
    private lateinit var downloadSection: View
    private lateinit var edtSearch: TextInputEditText

    // --- step 2
    private lateinit var bboxOverlay: BboxOverlayView
    private lateinit var estimateTilesText: TextView
    private lateinit var estimateSizeText: TextView

    // --- step 3
    private lateinit var downloadProgress: LinearProgressIndicator
    private lateinit var downloadStatus: TextView

    // --- chrome
    private lateinit var step1Root: View
    private lateinit var step2Root: View
    private lateinit var step3Root: View
    private lateinit var btnBack: MaterialButton
    private lateinit var btnCancel: MaterialButton
    private lateinit var btnNext: MaterialButton
    private lateinit var progressFill: View

    private var downloadJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_wizard)

        bindViews()
        wireStep1()
        wireStep2()
        showStep(1)

        findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener { cancel() }
        btnCancel.setOnClickListener { cancel() }
        btnBack.setOnClickListener { onBack() }
        btnNext.setOnClickListener { onNext() }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = onBack()
        })
    }

    /** Idempotent dispatcher for the wizard's cancellation flow. */
    private fun cancel() {
        downloadJob?.cancel()
        WizardLauncher.complete(null)
        finish()
    }

    override fun onDestroy() {
        // Belt-and-braces — wakes the recipe coroutine if the Activity dies before
        // the buttons fire (e.g. system process kill). complete() is idempotent.
        WizardLauncher.complete(null)
        super.onDestroy()
    }

    // ------------------------------------------------------------------
    //  Wiring
    // ------------------------------------------------------------------

    private fun bindViews() {
        step1Root = findViewById(R.id.step1)
        step2Root = findViewById(R.id.step2)
        step3Root = findViewById(R.id.step3)
        btnBack = findViewById(R.id.btn_back)
        btnCancel = findViewById(R.id.btn_cancel)
        btnNext = findViewById(R.id.btn_next)
        progressFill = findViewById(R.id.progress_fill)

        sourceToggle = step1Root.findViewById(R.id.source_toggle)
        btnUseCached = step1Root.findViewById(R.id.btn_use_cached)
        btnDownloadNew = step1Root.findViewById(R.id.btn_download_new)
        cachedSection = step1Root.findViewById(R.id.cached_section)
        cachedList = step1Root.findViewById(R.id.cached_list)
        cachedEmpty = step1Root.findViewById(R.id.cached_empty)
        downloadSection = step1Root.findViewById(R.id.download_section)
        edtSearch = step1Root.findViewById(R.id.edt_search)

        bboxOverlay = step2Root.findViewById(R.id.bbox_overlay)
        estimateTilesText = step2Root.findViewById(R.id.estimate_tiles)
        estimateSizeText = step2Root.findViewById(R.id.estimate_size)

        downloadProgress = step3Root.findViewById(R.id.download_progress)
        downloadStatus = step3Root.findViewById(R.id.download_status)
    }

    private fun wireStep1() {
        cachedAdapter = CachedRegionPickerAdapter { selected ->
            state.cachedSelection = selected
        }
        cachedList.layoutManager = LinearLayoutManager(this)
        cachedList.adapter = cachedAdapter

        sourceToggle.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            when (checkedId) {
                R.id.btn_use_cached -> {
                    state.mode = SourceMode.CACHED
                    cachedSection.visibility = View.VISIBLE
                    downloadSection.visibility = View.GONE
                }
                R.id.btn_download_new -> {
                    state.mode = SourceMode.DOWNLOAD
                    cachedSection.visibility = View.GONE
                    downloadSection.visibility = View.VISIBLE
                }
            }
        }

        edtSearch.doAfterTextChanged {
            state.searchQuery = it?.toString().orEmpty().trim()
        }

        // Default selection: cached if there's anything cached, otherwise download.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                refreshCached()
            }
        }
    }

    private suspend fun refreshCached() {
        val items = withContext(Dispatchers.IO) { RegionCache.list() }
        cachedAdapter.submit(items)
        val isEmpty = items.isEmpty()
        cachedList.visibility = if (isEmpty) View.GONE else View.VISIBLE
        cachedEmpty.visibility = if (isEmpty) View.VISIBLE else View.GONE
        if (isEmpty) {
            sourceToggle.check(R.id.btn_download_new)
        } else if (sourceToggle.checkedButtonId == View.NO_ID) {
            sourceToggle.check(R.id.btn_use_cached)
        }
    }

    private fun wireStep2() {
        bboxOverlay.setListener { rect ->
            state.bboxRectPx = RectF(rect)
            recomputeEstimate()
        }
    }

    /**
     * Take the current pixel-space bbox, project it back to a synthetic
     * lat/lon bbox using a hardcoded scale (1 px = ~50 m) for the C2 stub,
     * then ask [TileEstimator] for a tile count + size estimate. Once the
     * MapLibre `MapView` lands, this projection switches to the real map's
     * `projection.fromScreenLocation`.
     */
    private fun recomputeEstimate() {
        val rect = state.bboxRectPx ?: return
        if (rect.isEmpty) return
        // Synthetic projection: pretend the map is centred at (37.0, -122.0) and
        // 1 px == 0.0005°. Good enough for the C2 picker to produce sensible
        // tile/MB numbers; replaced by the real projection once C4 lands.
        val centreLat = 37.0
        val centreLon = -122.0
        val degPerPx = 0.0005
        val midX = step2Root.width / 2f
        val midY = step2Root.height / 2f
        val south = centreLat - (rect.bottom - midY) * degPerPx
        val north = centreLat - (rect.top - midY) * degPerPx
        val west = centreLon + (rect.left - midX) * degPerPx
        val east = centreLon + (rect.right - midX) * degPerPx
        val bbox = runCatching { Bbox(south, west, north, east) }.getOrNull() ?: return
        state.bbox = bbox
        val estimate = TileEstimator.estimate(bbox)
        state.estimate = estimate
        estimateTilesText.text = "${estimate.tileCount} tiles"
        estimateSizeText.text = "%.1f MB · zoom %d–%d".format(
            estimate.sizeMb(), estimate.zoomMin, estimate.zoomMax
        )
    }

    // ------------------------------------------------------------------
    //  Step navigation
    // ------------------------------------------------------------------

    private fun showStep(step: Int) {
        state.step = step
        step1Root.visibility = if (step == 1) View.VISIBLE else View.GONE
        step2Root.visibility = if (step == 2) View.VISIBLE else View.GONE
        step3Root.visibility = if (step == 3) View.VISIBLE else View.GONE

        // Progress strip width = step / 3.
        progressFill.layoutParams = (progressFill.layoutParams as LinearLayout.LayoutParams).apply {
            width = LinearLayout.LayoutParams.WRAP_CONTENT
        }
        progressFill.post {
            val parentWidth = (progressFill.parent as View).width
            progressFill.layoutParams = progressFill.layoutParams.apply {
                width = (parentWidth * (step / 3f)).toInt().coerceAtLeast(1)
            }
            progressFill.requestLayout()
        }

        btnBack.visibility = if (step == 1) View.INVISIBLE else View.VISIBLE
        btnCancel.visibility = if (step == 3) View.GONE else View.VISIBLE
        btnNext.visibility = if (step == 3) View.GONE else View.VISIBLE
        when (step) {
            1 -> btnNext.setText(R.string.gis_wizard_continue)
            2 -> btnNext.setText(R.string.gis_wizard_continue)
            3 -> btnNext.setText(R.string.gis_wizard_finish)
        }
    }

    private fun onNext() {
        when (state.step) {
            1 -> {
                if (state.mode == SourceMode.CACHED && state.cachedSelection != null) {
                    // Skip step 2 — the cached region's bbox is already on disk.
                    finishWithCached(state.cachedSelection!!)
                    return
                }
                if (state.mode == SourceMode.DOWNLOAD) {
                    showStep(2)
                    // Re-emit the current bbox so the estimate text fills in.
                    bboxOverlay.post { recomputeEstimate() }
                    return
                }
                // CACHED but no selection — block the user.
                // (The Material toggle group won't let us "stay disabled"; we
                // just no-op the button.)
            }
            2 -> {
                showStep(3)
                startDownload()
            }
            3 -> {
                finishWithDownloaded()
            }
        }
    }

    private fun onBack() {
        when (state.step) {
            1 -> cancel()
            2 -> showStep(1)
            3 -> {
                // Allow back from step 3 only before the download finishes.
                if (downloadJob?.isCompleted == true) cancel() else cancel()
            }
        }
    }

    private fun startDownload() {
        downloadProgress.progress = 0
        downloadStatus.text = "Starting…"
        val regionId = state.searchQuery
            .ifBlank { "region-${System.currentTimeMillis()}" }
            .replace(Regex("[^A-Za-z0-9-]"), "-")
            .lowercase()
        val displayName = state.searchQuery.ifBlank { "Custom region" }
        val bbox = state.bbox ?: Bbox.aroundPoint(37.0, -122.0, 10.0)
        downloadJob = lifecycleScope.launch {
            try {
                val dir = RegionDownloader.download(
                    context = applicationContext,
                    regionId = regionId,
                    displayName = displayName,
                    bbox = bbox,
                    onProgress = { fraction ->
                        downloadProgress.setProgressCompat((fraction * 100).toInt(), true)
                        downloadStatus.text = "%d%%".format((fraction * 100).toInt())
                    }
                )
                state.downloadedRegionId = dir.name
                state.downloadedDir = dir
                showStep(3)
                downloadStatus.text = "Done. Tap Finish to continue."
                btnNext.visibility = View.VISIBLE
                btnNext.setText(R.string.gis_wizard_finish)
                btnCancel.visibility = View.GONE
                btnNext.setOnClickListener { finishWithDownloaded() }
            } catch (e: Exception) {
                downloadStatus.text = "Download failed: ${e.message ?: e.javaClass.simpleName}"
            }
        }
    }

    // ------------------------------------------------------------------
    //  Exit paths
    // ------------------------------------------------------------------

    private fun finishWithCached(info: RegionInfo) {
        WizardLauncher.complete(
            WizardResult(
                regionId = info.regionId,
                bbox = DoubleArray(0), // bbox already serialized in meta.json
                source = "cache"
            )
        )
        finish()
    }

    private fun finishWithDownloaded() {
        WizardLauncher.complete(
            WizardResult(
                regionId = state.downloadedRegionId ?: "unknown",
                bbox = state.bbox?.toBoundsArray() ?: DoubleArray(0),
                source = "download"
            )
        )
        finish()
    }
}

/** Mutable state held by [WizardActivity]. */
private class WizardState {
    var step: Int = 1
    var mode: SourceMode = SourceMode.CACHED
    var cachedSelection: RegionInfo? = null
    var searchQuery: String = ""
    var bboxRectPx: RectF? = null
    var bbox: Bbox? = null
    var estimate: TileEstimate? = null
    var downloadedRegionId: String? = null
    var downloadedDir: java.io.File? = null
}

private enum class SourceMode { CACHED, DOWNLOAD }
