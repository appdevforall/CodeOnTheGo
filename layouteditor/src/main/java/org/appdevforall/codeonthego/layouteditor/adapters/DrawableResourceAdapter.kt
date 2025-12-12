package org.appdevforall.codeonthego.layouteditor.adapters

import android.annotation.SuppressLint
import android.content.Intent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AnimationUtils
import androidx.appcompat.widget.PopupMenu
import androidx.appcompat.widget.TooltipCompat
import androidx.recyclerview.widget.RecyclerView
import com.blankj.utilcode.util.ClipboardUtils
import org.appdevforall.codeonthego.layouteditor.R
import org.appdevforall.codeonthego.layouteditor.activities.PreviewDrawableActivity
import org.appdevforall.codeonthego.layouteditor.adapters.models.DrawableFile
import org.appdevforall.codeonthego.layouteditor.databinding.LayoutDrawableItemBinding
import org.appdevforall.codeonthego.layouteditor.utils.SBUtils.Companion.make
import org.appdevforall.codeonthego.layouteditor.views.AlphaPatternDrawable

class DrawableResourceAdapter(
    private var drawableList: MutableList<DrawableFile>,
    private val listener: OnDrawableActionListener
) :
    RecyclerView.Adapter<DrawableResourceAdapter.VH>() {

    interface OnDrawableActionListener {
        fun onRenameRequested(position: Int, holder: VH, view: View)
        fun onDeleteRequested(position: Int)
    }

    inner class VH(var binding: LayoutDrawableItemBinding) : RecyclerView.ViewHolder(
    binding.root
  ) {
    var drawableName = binding.drawableName
    var imageType = binding.imageType
    var versions = binding.versions
    var drawable = binding.drawable
    var drawableBackground = binding.background
  }

  override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
    return VH(
      LayoutDrawableItemBinding.inflate(LayoutInflater.from(parent.context), parent, false)
    )
  }

  @SuppressLint("SetTextI18n")
  override fun onBindViewHolder(holder: VH, position: Int) {
    val name = drawableList[position].name
    holder.itemView.animation = AnimationUtils.loadAnimation(
      holder.itemView.context, R.anim.project_list_animation
    )

    holder.drawableName.text = name.substring(0, name.lastIndexOf("."))
    holder.imageType.text = "Drawable"

    val version = drawableList[position].versions
    holder.versions.text = "$version version${if (version > 1) "s" else ""}"
    holder.drawableBackground.setImageDrawable(AlphaPatternDrawable(16))

    TooltipCompat.setTooltipText(
      holder.binding.root, name.substring(0, name.lastIndexOf("."))
    )
    TooltipCompat.setTooltipText(holder.binding.menu, "Options")

    holder.drawable.setImageDrawable(drawableList[position].drawable)
    holder.binding.menu.setOnClickListener {
      showOptions(
        it,
        holder.absoluteAdapterPosition,
        holder
      )
    }

    holder.itemView.setOnClickListener {
      PreviewDrawableActivity.onLoad = { imageView, actionBar ->
        imageView.setImageDrawable(drawableList[holder.absoluteAdapterPosition].drawable)
        actionBar?.subtitle = name
      }
      it.context.startActivity(Intent(it.context, PreviewDrawableActivity::class.java))
    }
  }

  override fun getItemCount(): Int {
    return drawableList.size
  }

    private fun showOptions(v: View, position: Int, holder: VH) {
    val popupMenu = PopupMenu(v.context, v)
    popupMenu.inflate(R.menu.menu_drawable)
    popupMenu.setOnMenuItemClickListener {
      val id = it.itemId
      when (id) {
        R.id.menu_copy_name -> {
          ClipboardUtils.copyText(
            drawableList[position].name
              .substring(0, drawableList[position].name.lastIndexOf("."))
          )
          make(holder.binding.root, v.context.getString(R.string.copied))
            .setSlideAnimation()
            .showAsSuccess()
          true
        }

          R.id.menu_delete -> {
              listener.onDeleteRequested(position)
              true
          }

          R.id.menu_rename -> {
              listener.onRenameRequested(position, holder, v)
              true
          }

          else -> false
      }
    }

    popupMenu.show()
  }

}
