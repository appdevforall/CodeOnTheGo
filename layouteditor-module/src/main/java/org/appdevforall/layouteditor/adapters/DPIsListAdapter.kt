package com.itsvks.layouteditor.adapters

import android.annotation.SuppressLint
import android.graphics.drawable.Drawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RelativeLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.itsvks.layouteditor.databinding.LayoutSelectDpiItemBinding
import com.itsvks.layouteditor.utils.Utils
import java.util.Collections

class DPIsListAdapter(private val image: Drawable) : RecyclerView.Adapter<DPIsListAdapter.VH>() {
  private val dpiList: MutableList<String> = ArrayList()
  private val mSelectedItems: MutableList<Boolean>

  init {
    dpiList.add("ldpi")
    dpiList.add("mdpi")
    dpiList.add("hdpi")
    dpiList.add("xhdpi")
    dpiList.add("xxhdpi")
    dpiList.add("xxxhdpi")

    mSelectedItems = ArrayList(Collections.nCopies(dpiList.size, false))
  }

  inner class VH(binding: LayoutSelectDpiItemBinding) : RecyclerView.ViewHolder(binding.root) {
    var shadowView = binding.shadowView
    var image = binding.image
    var checkbox = binding.checkbox
    var dpiName = binding.dpiName
  }

  override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
    return VH(
      LayoutSelectDpiItemBinding.inflate(
        LayoutInflater.from(parent.context), parent, false
      )
    )
  }

  @SuppressLint("SetTextI18n")
  override fun onBindViewHolder(holder: VH, position: Int) {
    holder.image.layoutParams =
      RelativeLayout.LayoutParams(Utils.getScreenWidth() / 2, Utils.getScreenWidth() / 2)
    holder.shadowView.layoutParams =
      RelativeLayout.LayoutParams(Utils.getScreenWidth() / 2, Utils.getScreenWidth() / 2)
    val dpi = dpiList[position]
    holder.image.setImageDrawable(image)
    holder.dpiName.text = "drawable-$dpi"
    holder.checkbox.isChecked = mSelectedItems[position]
    holder.shadowView.visibility = if (mSelectedItems[position]) View.VISIBLE else View.INVISIBLE

    holder.itemView.setOnClickListener {
      val isChecked = !mSelectedItems[position]
      mSelectedItems[position] = isChecked
      holder.checkbox.isChecked = isChecked
    }
  }

  override fun getItemCount(): Int {
    return dpiList.size
  }

  val selectedItems: List<String>
    get() {
      val selectedItems: MutableList<String> = ArrayList()
      for (i in mSelectedItems.indices) {
        if (mSelectedItems[i]) {
          selectedItems.add(dpiList[i])
        }
      }
      return selectedItems
    }
}
