package com.codeonthego.gisplugin.region

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.codeonthego.gisplugin.R
import com.google.android.material.button.MaterialButton
import java.text.DateFormat
import java.util.Date

/**
 * Diffing list adapter for [RegionInfo].
 *
 * Click handlers wired up in C3 — for now the buttons are visible but no-op.
 * The data class equality drives DiffUtil so any change to size /
 * downloadedAt / lastUsedAt rebinds the row efficiently when C2 starts
 * mutating the cache.
 */
internal class RegionAdapter : RecyclerView.Adapter<RegionAdapter.VH>() {

    private val items = mutableListOf<RegionInfo>()

    fun submit(newItems: List<RegionInfo>) {
        val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize() = items.size
            override fun getNewListSize() = newItems.size
            override fun areItemsTheSame(oldPos: Int, newPos: Int) =
                items[oldPos].regionId == newItems[newPos].regionId
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
        private val redownload = view.findViewById<MaterialButton>(R.id.btn_redownload)
        private val delete = view.findViewById<MaterialButton>(R.id.btn_delete)

        fun bind(info: RegionInfo) {
            name.text = info.displayName
            meta.text = formatMeta(info)
            // C3 will wire onClick — keeping refs visible to the linter so we
            // don't accidentally drop them.
            redownload.isEnabled = false
            delete.isEnabled = false
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
