package com.codeonthego.gisplugin.wizard

import android.app.Activity
import android.graphics.RectF
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import com.codeonthego.gisplugin.R
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Full-screen bbox picker — the "+ Download new region" destination from
 * [com.codeonthego.gisplugin.region.RegionManagerFragment]. Replaces the old
 * 3-step `WizardActivity` (which assumed a recipe-blocking handshake with
 * `IdeTemplateService` that the API doesn't actually support — see
 * [QUESTIONS.md] Q1).
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
 * layout. The download itself is asynchronous; today the [RegionDownloader]
 * stub completes synchronously fast enough that we can wait for it inside
 * the Activity's lifecycle, but the long-term plan is to fire-and-finish
 * (return immediately, run the download in a process-scoped service) so the
 * user can return to the regions panel and watch the row's progress
 * indicator. For R2 we keep it simple: Save blocks until the writer
 * completes, then the Activity finishes.
 */
class BboxPickerActivity : AppCompatActivity() {

    private lateinit var bboxOverlay: BboxOverlayView
    private lateinit var estimateTilesText: TextView
    private lateinit var estimateSizeText: TextView
    private lateinit var edtName: TextInputEditText
    private lateinit var btnSave: MaterialButton
    private lateinit var btnCancel: MaterialButton
    private lateinit var pickerContainer: View

    private var currentBbox: Bbox? = null
    private var currentEstimate: TileEstimate? = null
    private var regionName: String = ""
    private var downloadJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_bbox_picker)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.setNavigationOnClickListener { cancel() }

        edtName = findViewById(R.id.edt_name)
        bboxOverlay = findViewById(R.id.bbox_overlay)
        estimateTilesText = findViewById(R.id.estimate_tiles)
        estimateSizeText = findViewById(R.id.estimate_size)
        btnSave = findViewById(R.id.btn_save)
        btnCancel = findViewById(R.id.btn_cancel)
        pickerContainer = findViewById(R.id.picker_container)

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
     * approach the old WizardActivity used for C2 — pretend the picker is
     * looking at a square centred on (37.0, -122.0) at 0.0005°/px. Replaced
     * by the real MapLibre projection once the placeholder background is
     * swapped for an actual MapView (R1 backlog).
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
        val regionId = regionName
            .ifBlank { "region-${System.currentTimeMillis()}" }
            .lowercase()
            .replace(Regex("[^a-z0-9-]+"), "-")
            .trim('-')
            .ifBlank { "region-${System.currentTimeMillis()}" }

        // Disable buttons during the download to avoid double-fire.
        btnSave.isEnabled = false
        btnCancel.isEnabled = false

        downloadJob = lifecycleScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    RegionDownloader.download(
                        context = applicationContext,
                        regionId = regionId,
                        displayName = displayName,
                        bbox = bbox
                    )
                }
            }.onSuccess {
                setResult(Activity.RESULT_OK)
                finish()
            }.onFailure {
                btnSave.isEnabled = true
                btnCancel.isEnabled = true
                // Surface error inline; a future revision should hook a
                // toast / snackbar here.
                estimateSizeText.text = "Save failed: ${it.message ?: it.javaClass.simpleName}"
            }
        }
    }

    private fun cancel() {
        downloadJob?.cancel()
        setResult(Activity.RESULT_CANCELED)
        finish()
    }
}
