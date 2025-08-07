package com.itsvks.layouteditor.adapters

import android.annotation.SuppressLint
import android.content.Context
import android.content.DialogInterface
import android.graphics.Typeface
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.AnimationUtils
import android.view.inputmethod.InputMethodManager
import androidx.appcompat.widget.PopupMenu
import androidx.recyclerview.widget.RecyclerView
import com.blankj.utilcode.util.ClipboardUtils
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.itsvks.layouteditor.R
import com.itsvks.layouteditor.adapters.models.FontItem
import com.itsvks.layouteditor.databinding.LayoutFontItemBinding
import com.itsvks.layouteditor.databinding.TextinputlayoutBinding
import com.itsvks.layouteditor.managers.ProjectManager.Companion.instance
import com.itsvks.layouteditor.utils.FileUtil.deleteFile
import com.itsvks.layouteditor.utils.FileUtil.getLastSegmentFromPath
import com.itsvks.layouteditor.utils.NameErrorChecker
import com.itsvks.layouteditor.utils.SBUtils
import com.itsvks.layouteditor.utils.SBUtils.Companion.make
import com.itsvks.layouteditor.utils.Utils
import java.io.File

class FontResourceAdapter(private val fontList: MutableList<FontItem>) :
  RecyclerView.Adapter<FontResourceAdapter.VH>() {
  private val project = instance.openedProject

  class VH(var binding: LayoutFontItemBinding) : RecyclerView.ViewHolder(
    binding.root
  ) {
    var fontName = binding.name
    var fontLook = binding.fontLook
  }

  override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
    return VH(
      LayoutFontItemBinding.inflate(LayoutInflater.from(parent.context), parent, false)
    )
  }

  override fun onBindViewHolder(holder: VH, position: Int) {
    val fontName = fontList[position].name
    holder
      .binding
      .root.animation = AnimationUtils.loadAnimation(
      holder.itemView.context, R.anim.project_list_animation
    )
    holder.fontName.text = fontName.substring(0, fontName.lastIndexOf("."))
    holder.fontLook.typeface = Typeface.createFromFile(File(fontList[position].path))

    holder
      .itemView
      .setOnClickListener {
        ClipboardUtils.copyText(fontName.substring(0, fontName.lastIndexOf(".")))
        make(
          holder.binding.root,
          "${it.context.getString(R.string.copied)} $fontName"
        )
          .setSlideAnimation()
          .showAsSuccess()
      }
    holder.binding.menu.setOnClickListener {
      showOptions(
        it,
        holder.absoluteAdapterPosition,
        holder
      )
    }
  }

  override fun getItemCount(): Int {
    return fontList.size
  }

  private fun showOptions(v: View, position: Int, holder: VH) {
    val popupMenu = PopupMenu(v.context, v)
    popupMenu.inflate(R.menu.menu_font)
    popupMenu.setOnMenuItemClickListener { item: MenuItem ->
      val id = item.itemId
      when (id) {
        R.id.menu_delete -> {
          MaterialAlertDialogBuilder(v.context)
            .setTitle(R.string.remove_font)
            .setMessage(R.string.msg_remove_font)
            .setNegativeButton(R.string.no) { d, _ -> d.dismiss() }
            .setPositiveButton(
              R.string.yes
            ) { _, _ ->
              val name = fontList[position].name
              if (name.substring(0, name.lastIndexOf(".")) == "default_font") {
                make(
                  v,
                  v.context
                    .getString(
                      R.string.msg_cannot_delete_default, "font"
                    )
                )
                  .setFadeAnimation()
                  .setType(SBUtils.Type.INFO)
                  .show()
              } else {
                deleteFile(fontList[position].path)
                fontList.removeAt(position)
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

  @SuppressLint("RestrictedApi")
  private fun rename(v: View, position: Int, holder: VH) {
    val lastSegment = getLastSegmentFromPath(fontList[position].path)
    val fileName = lastSegment.substring(0, lastSegment.lastIndexOf("."))
    val extension =
      lastSegment.substring(lastSegment.lastIndexOf("."))
    val builder = MaterialAlertDialogBuilder(v.context)
    val bind =
      TextinputlayoutBinding.inflate(builder.create().layoutInflater)
    val editText = bind.textinputEdittext
    val inputLayout = bind.textinputLayout
    editText.setText(fileName)
    val padding = Utils.pxToDp(builder.context, 10)
    @Suppress("DEPRECATION")
    builder.setView(bind.root, padding, padding, padding, padding)
    builder.setTitle(R.string.rename_font)
    builder.setNegativeButton(R.string.cancel) { _, _ -> }
    builder.setPositiveButton(
      R.string.rename
    ) { _, _ ->
      if (fontList[position].name
          .substring(0, fontList[position].name.lastIndexOf("."))
        == "default_font"
      ) {
        make(v, v.context.getString(R.string.msg_cannot_rename_default, "font"))
          .setFadeAnimation()
          .setType(SBUtils.Type.INFO)
          .show()
      } else {
        val fontPath = project!!.fontPath

        val toPath = fontPath + editText.text.toString() + extension
        val newFile = File(toPath)
        val oldFile = File(fontList[position].path)
        oldFile.renameTo(newFile)

        val name = editText.text.toString()
        fontList[position].path = toPath
        fontList[position].name = getLastSegmentFromPath(toPath)
        holder.fontName.text = name
        holder.fontLook.typeface = Typeface.createFromFile(File(fontList[position].path))
        notifyItemChanged(position)
      }
    }

    val dialog = builder.create()
    dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
    dialog.show()

    editText.addTextChangedListener(
      object : TextWatcher {
        override fun beforeTextChanged(p1: CharSequence, p2: Int, p3: Int, p4: Int) {}

        override fun onTextChanged(p1: CharSequence, p2: Int, p3: Int, p4: Int) {}

        override fun afterTextChanged(p1: Editable) {
          NameErrorChecker.checkForFont(
            editText.text.toString(), inputLayout, dialog, fontList, position
          )
        }
      })

    NameErrorChecker.checkForFont(fileName, inputLayout, dialog, fontList, position)

    editText.requestFocus()
    val inputMethodManager =
      v.context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
    inputMethodManager.showSoftInput(editText, InputMethodManager.SHOW_IMPLICIT)

    if (editText.text.toString().isNotEmpty()) {
      editText.setSelection(0, editText.text.toString().length)
    }
  }
}
