package com.itsvks.layouteditor.adapters

import android.annotation.SuppressLint
import android.graphics.Color
import android.graphics.HardwareRenderer
import android.graphics.PixelFormat
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
import android.hardware.HardwareBuffer
import android.media.ImageReader
import android.os.Build
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AnimationUtils
import android.widget.TextView
import androidx.annotation.RequiresApi
import androidx.core.graphics.drawable.toBitmap
import androidx.recyclerview.widget.RecyclerView
import com.itsvks.layouteditor.LayoutFile
import com.itsvks.layouteditor.ProjectFile
import com.itsvks.layouteditor.R
import com.itsvks.layouteditor.databinding.LayoutProjectLayoutItemBinding
import com.itsvks.layouteditor.utils.BitmapUtil

class LayoutListAdapter(
  val project: ProjectFile
) : RecyclerView.Adapter<LayoutListAdapter.VH>() {

  var onClickListener: ((LayoutFile) -> Unit)? = null
  var onLongClickListener: ((View, Int) -> Boolean)? = null

  class VH(binding: LayoutProjectLayoutItemBinding) : RecyclerView.ViewHolder(binding.getRoot()) {
    val iconText: TextView = binding.icon
    val layoutName: TextView = binding.layoutName
  }

  override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
    return VH(
      LayoutProjectLayoutItemBinding.inflate(LayoutInflater.from(parent.context), parent, false)
    )
  }

  @RequiresApi(Build.VERSION_CODES.S)
  @SuppressLint("RecyclerView", "WrongConstant")
  override fun onBindViewHolder(holder: VH, position: Int) {
    val layoutList = project.allLayouts

    holder.itemView.apply {
      animation = AnimationUtils.loadAnimation(
        holder.itemView.context, R.anim.project_list_animation
      )
      setOnClickListener { onClickListener?.invoke(layoutList[position]) }
      setOnLongClickListener { onLongClickListener?.invoke(it, position) == true }
    }
    holder.layoutName.text = layoutList[position].name
    val gradientDrawable = GradientDrawable(
      GradientDrawable.Orientation.BL_TR,
      intArrayOf(
        Color.MAGENTA,
        Color.YELLOW
      )
    ).apply {
      cornerRadius = 8f
    }
    holder.iconText.apply {
      text = layoutList[position].name.substring(0, 1).uppercase()
      background = gradientDrawable
      setTextColor(Color.WHITE)
    }
  }

  override fun getItemCount(): Int {
    return project.allLayouts.size
  }
}