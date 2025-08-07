package com.itsvks.layouteditor.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.View.DragShadowBuilder
import android.view.ViewGroup
import android.view.animation.AnimationUtils
import androidx.core.view.ViewCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.recyclerview.widget.RecyclerView
import com.itsvks.layouteditor.R
import com.itsvks.layouteditor.databinding.LayoutPaletteItemBinding
import com.itsvks.layouteditor.utils.InvokeUtil.getMipmapId
import com.itsvks.layouteditor.utils.InvokeUtil.getSuperClassName

class PaletteListAdapter(private val drawerLayout: DrawerLayout) :
  RecyclerView.Adapter<PaletteListAdapter.ViewHolder>() {
  private lateinit var tab: List<HashMap<String, Any>>

  override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
    return ViewHolder(
      LayoutPaletteItemBinding.inflate(LayoutInflater.from(parent.context), parent, false)
    )
  }

  override fun onBindViewHolder(holder: ViewHolder, position: Int) {
    val widgetItem = tab[position]

    val binding = holder.binding

    binding.icon.setImageResource(getMipmapId(widgetItem["iconName"].toString()))
    binding.name.text = widgetItem["name"].toString()
    binding.className.text = getSuperClassName(widgetItem["className"].toString())

    binding
      .root
      .setOnLongClickListener {
        if (ViewCompat.startDragAndDrop(
            it, null, DragShadowBuilder(it), widgetItem, 0
          )
        ) {
          drawerLayout.closeDrawers()
        }
        true
      }

    binding
      .root.animation = AnimationUtils.loadAnimation(
      holder.itemView.context, R.anim.project_list_animation
    )
  }

  override fun getItemCount(): Int {
    return tab.size
  }

  fun submitPaletteList(tab: List<HashMap<String, Any>>) {
    this.tab = tab
    notifyDataSetChanged()
  }

  class ViewHolder(var binding: LayoutPaletteItemBinding) : RecyclerView.ViewHolder(
    binding.root
  )
}
