package com.codeonthego.gisplugin.region

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.codeonthego.gisplugin.R
import com.google.android.material.button.MaterialButton
import com.google.android.material.progressindicator.CircularProgressIndicator
import java.text.DateFormat
import java.util.Date

/**
 * Diffing list adapter for [RegionRow].
 *
 * Each row surfaces three actions: "Use in this project", "Refresh"
 * (re-download), and "Delete". The "in this project" badge replaces the
 * "Use" button when this region's files are already bundled into the
 * currently open project. A circular progress indicator surfaces inline
 * while a download is in flight against this region.
 *
 * The fragment owns all action handling — including the dialog confirmation
 * flow for delete and the IO copy for "use in this project" — so the adapter
 * stays view-only.
 */
class RegionAdapter(
    private val listener: Listener? = null
) : RecyclerView.Adapter<RegionAdapter.VH>() {

    interface Listener {
        fun onRegionUseInProject(info: RegionInfo)
        fun onRegionDelete(info: RegionInfo)
        fun onRegionRedownload(info: RegionInfo)
    }

    private val items = mutableListOf<RegionRow>()

    fun submit(newItems: List<RegionRow>) {
        val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize() = items.size
            override fun getNewListSize() = newItems.size
            override fun areItemsTheSame(oldPos: Int, newPos: Int) =
                items[oldPos].info.regionId == newItems[newPos].info.regionId
            override fun areContentsTheSame(oldPos: Int, newPos: Int) =
                items[oldPos] == newItems[newPos]
        })
        items.clear()
        items.addAll(newItems)
        diff.dispatchUpdatesTo(this)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_region, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(items[position])
    override fun getItemCount(): Int = items.size

    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
        private val name = view.findViewById<TextView>(R.id.region_name)
        private val meta = view.findViewById<TextView>(R.id.region_meta)
        private val badge = view.findViewById<TextView>(R.id.in_project_badge)
        private val progress = view.findViewById<CircularProgressIndicator>(R.id.region_progress)
        private val useInProject = view.findViewById<MaterialButton>(R.id.btn_use_in_project)
        private val redownload = view.findViewById<MaterialButton>(R.id.btn_redownload)
        private val delete = view.findViewById<MaterialButton>(R.id.btn_delete)

        fun bind(row: RegionRow) {
            val info = row.info
            name.text = info.displayName
            meta.text = formatMeta(info)

            badge.visibility = if (row.isInProject) View.VISIBLE else View.GONE
            // When already bundled, the affirmative CTA collapses to the badge — keep
            // the button visible but disabled-looking so layout doesn't jump.
            useInProject.visibility = if (row.isInProject) View.GONE else View.VISIBLE

            progress.visibility = if (row.isDownloading) View.VISIBLE else View.GONE
            // While downloading, suppress the action buttons — touching them mid-flight
            // would race the IO writer.
            val controlsEnabled = listener != null && !row.isDownloading
            useInProject.isEnabled = controlsEnabled
            redownload.isEnabled = controlsEnabled
            delete.isEnabled = controlsEnabled

            useInProject.setOnClickListener { listener?.onRegionUseInProject(info) }
            redownload.setOnClickListener { listener?.onRegionRedownload(info) }
            delete.setOnClickListener { listener?.onRegionDelete(info) }
        }
    }

    private fun formatMeta(info: RegionInfo): String {
        val sizeMb = info.sizeBytes / (1024.0 * 1024.0)
        val sizeStr = "%.1f MB".format(sizeMb)
        val downloaded = info.downloadedAt?.let { DateFormat.getDateInstance().format(Date(it)) }
        return buildString {
            append(sizeStr)
            if (downloaded != null) {
                append(" · downloaded ")
                append(downloaded)
            }
            if (info.source.isNotEmpty() && info.source != "unknown") {
                append(" · ")
                append(info.source)
            }
        }
    }
}

/**
 * View-model row wrapping a [RegionInfo] with two transient flags the fragment
 * needs to track per render: whether this region is currently bundled into
 * the open project, and whether a fresh download is running for it.
 *
 * Keeping these flags out of [RegionInfo] means [RegionInfo] stays a pure
 * on-disk projection — the fragment owns the per-render UI state.
 */
data class RegionRow(
    val info: RegionInfo,
    val isInProject: Boolean = false,
    val isDownloading: Boolean = false
)
