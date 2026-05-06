package com.codeonthego.gisplugin.wizard

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.codeonthego.gisplugin.R
import com.codeonthego.gisplugin.region.RegionInfo
import com.google.android.material.button.MaterialButton

/**
 * Reuses the [com.codeonthego.gisplugin.region.RegionAdapter] item layout for
 * a *single-select* picker inside the wizard's step 1.
 *
 * We could share a single adapter implementation between the bottom-sheet tab
 * and this picker, but the affordances differ enough (the bottom-sheet tab
 * has Delete + Re-download buttons; the wizard picker only needs a tap-to-
 * select with a visible selection state) that two adapters with one shared
 * row layout is clearer than one adapter with mode flags.
 */
internal class CachedRegionPickerAdapter(
    private val onRegionSelected: (RegionInfo) -> Unit
) : RecyclerView.Adapter<CachedRegionPickerAdapter.VH>() {

    private val items = mutableListOf<RegionInfo>()
    private var selectedId: String? = null

    fun submit(newItems: List<RegionInfo>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    fun selectedRegion(): RegionInfo? = items.firstOrNull { it.regionId == selectedId }

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
            val sizeMb = info.sizeBytes / (1024.0 * 1024.0)
            meta.text = "%.1f MB · %s".format(sizeMb, info.source)
            // The picker hides per-row actions — that role belongs to the
            // bottom-sheet tab's adapter. Tap the row to select.
            redownload.visibility = View.GONE
            delete.visibility = View.GONE
            itemView.isSelected = info.regionId == selectedId
            itemView.setOnClickListener {
                selectedId = info.regionId
                onRegionSelected(info)
                notifyDataSetChanged()
            }
        }
    }
}
