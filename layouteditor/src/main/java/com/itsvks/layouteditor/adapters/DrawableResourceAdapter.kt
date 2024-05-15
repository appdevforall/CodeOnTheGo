package com.itsvks.layouteditor.adapters

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.AnimationUtils
import android.view.inputmethod.InputMethodManager
import androidx.appcompat.widget.PopupMenu
import androidx.appcompat.widget.TooltipCompat
import androidx.recyclerview.widget.RecyclerView
import androidx.vectordrawable.graphics.drawable.VectorDrawableCompat
import com.blankj.utilcode.util.ClipboardUtils
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.itsvks.layouteditor.ProjectFile
import com.itsvks.layouteditor.R
import com.itsvks.layouteditor.activities.PreviewDrawableActivity
import com.itsvks.layouteditor.adapters.models.DrawableFile
import com.itsvks.layouteditor.databinding.LayoutDrawableItemBinding
import com.itsvks.layouteditor.databinding.TextinputlayoutBinding
import com.itsvks.layouteditor.managers.ProjectManager.Companion.instance
import com.itsvks.layouteditor.utils.FileUtil.deleteFile
import com.itsvks.layouteditor.utils.FileUtil.getLastSegmentFromPath
import com.itsvks.layouteditor.utils.NameErrorChecker
import com.itsvks.layouteditor.utils.SBUtils
import com.itsvks.layouteditor.utils.SBUtils.Companion.make
import com.itsvks.layouteditor.utils.Utils
import com.itsvks.layouteditor.views.AlphaPatternDrawable
import java.io.File

class DrawableResourceAdapter(private val drawableList: MutableList<DrawableFile>) :
  RecyclerView.Adapter<DrawableResourceAdapter.VH>() {

  private val project: ProjectFile? = instance.openedProject

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
          MaterialAlertDialogBuilder(v.context)
            .setTitle(R.string.remove_drawable)
            .setMessage(R.string.msg_remove_drawable)
            .setNegativeButton(R.string.no) { d, _ -> d.dismiss() }
            .setPositiveButton(
              R.string.yes
            ) { _, _ ->
              val name = drawableList[position].name
              if (name.substring(0, name.lastIndexOf(".")) == "default_image") {
                make(
                  v,
                  v.context
                    .getString(
                      R.string.msg_cannot_delete_default, "image"
                    )
                )
                  .setFadeAnimation()
                  .setType(SBUtils.Type.INFO)
                  .show()
              } else {
                deleteFile(drawableList[position].path)
                drawableList.removeAt(position)
                notifyItemRemoved(position)
              }
            }
            .show()
          true
        }

        R.id.menu_rename -> {
          rename(v, position, holder)
          true
        }

        else -> false
      }
    }

    popupMenu.show()
  }

  @Suppress("deprecation")
  @SuppressLint("RestrictedApi")
  private fun rename(v: View, position: Int, holder: VH) {
    // File name with extension
    val lastSegment =
      getLastSegmentFromPath(drawableList[position].path)

    // File name without extension
    val fileName = lastSegment.substring(0, lastSegment.lastIndexOf("."))

    // Extension
    val extension =
      lastSegment.substring(lastSegment.lastIndexOf("."))

    val builder = MaterialAlertDialogBuilder(v.context)
    val bind =
      TextinputlayoutBinding.inflate(builder.create().layoutInflater)
    val editText = bind.textinputEdittext
    val inputLayout = bind.textinputLayout
    editText.setText(fileName)
    val padding = Utils.pxToDp(builder.context, 10)
    builder.setView(bind.root, padding, padding, padding, padding)
    builder.setTitle(R.string.rename_drawable)
    builder.setNegativeButton(R.string.cancel) { _, _ -> }
    builder.setPositiveButton(
      R.string.rename
    ) { _, _ ->
      if (drawableList[position].name
          .substring(0, drawableList[position].name.lastIndexOf("."))
        == "default_image"
      ) {
        make(v, v.context.getString(R.string.msg_cannot_rename_default, "image"))
          .setFadeAnimation()
          .setType(SBUtils.Type.INFO)
          .show()
      } else {
        val drawablePath = project!!.drawablePath

        val toPath = drawablePath + editText.text.toString() + extension
        val newFile = File(toPath)
        val oldFile = File(drawableList[position].path)
        oldFile.renameTo(newFile)

        var drawable = Drawable.createFromPath(toPath)
        val name = editText.text.toString()
        drawableList[position].path = toPath
        drawableList[position].name = getLastSegmentFromPath(toPath)
        if (drawableList[position].name.endsWith(".xml")
          || drawableList[position].name.endsWith(".svg")
        ) {
          drawable = VectorDrawableCompat.createFromPath(toPath)
          holder.drawable.setImageDrawable(drawable)
        }
        holder.drawableName.text = name
        holder.drawable.setImageDrawable(drawable)
        notifyItemChanged(position)
      }
    }

    val dialog = builder.create()
    dialog.window!!.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
    dialog.show()

    editText.addTextChangedListener(
      object : TextWatcher {
        override fun beforeTextChanged(p1: CharSequence, p2: Int, p3: Int, p4: Int) {}

        override fun onTextChanged(p1: CharSequence, p2: Int, p3: Int, p4: Int) {}

        override fun afterTextChanged(p1: Editable) {
          NameErrorChecker.checkForDrawable(
            editText.text.toString(), inputLayout, dialog, drawableList, position
          )
        }
      })

    NameErrorChecker.checkForDrawable(fileName, inputLayout, dialog, drawableList, position)

    editText.requestFocus()
    val inputMethodManager =
      v.context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
    inputMethodManager.showSoftInput(editText, InputMethodManager.SHOW_IMPLICIT)

    if (editText.text.toString() != "") {
      editText.setSelection(0, editText.text.toString().length)
    }
  }
}
