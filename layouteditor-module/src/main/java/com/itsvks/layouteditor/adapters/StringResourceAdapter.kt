package com.itsvks.layouteditor.adapters

import android.content.DialogInterface
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.animation.AnimationUtils
import android.widget.TextView
import androidx.appcompat.widget.PopupMenu
import androidx.recyclerview.widget.RecyclerView
import com.blankj.utilcode.util.ClipboardUtils
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.itsvks.layouteditor.ProjectFile
import com.itsvks.layouteditor.R
import com.itsvks.layouteditor.adapters.models.ValuesItem
import com.itsvks.layouteditor.databinding.LayoutValuesItemBinding
import com.itsvks.layouteditor.databinding.LayoutValuesItemDialogBinding
import com.itsvks.layouteditor.utils.FileUtil.writeFile
import com.itsvks.layouteditor.utils.NameErrorChecker
import com.itsvks.layouteditor.utils.SBUtils
import com.itsvks.layouteditor.utils.SBUtils.Companion.make
import org.apache.commons.lang3.StringEscapeUtils

class StringResourceAdapter(
  private val project: ProjectFile,
  private val stringList: MutableList<ValuesItem>
) : RecyclerView.Adapter<StringResourceAdapter.VH>() {
  class VH(var binding: LayoutValuesItemBinding) : RecyclerView.ViewHolder(
    binding.root
  ) {
    var stringName: TextView = binding.name
    var stringValue: TextView = binding.value
  }

  override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
    return VH(
      LayoutValuesItemBinding.inflate(LayoutInflater.from(parent.context), parent, false)
    )
  }

  override fun onBindViewHolder(holder: VH, position: Int) {
    holder.stringName.text = stringList[position].name
    holder.stringValue.text = stringList[position].value
    holder.itemView.animation = AnimationUtils.loadAnimation(
      holder.itemView.context, R.anim.project_list_animation
    )

    holder.binding.menu.setOnClickListener { showOptions(it, holder.absoluteAdapterPosition) }
    holder.itemView.setOnClickListener { editString(it, holder.absoluteAdapterPosition) }
  }

  override fun getItemCount(): Int {
    return stringList.size
  }

  fun generateStringsXml() {
    val stringsPath = project.stringsPath

    val sb = StringBuilder()
    sb.append("<resources>\n")
    for ((name, value) in stringList) {
      // Generate string item code
      sb.append("\t<string name=\"")
        .append(name)
        .append("\">")
        .append(StringEscapeUtils.escapeXml11(value))
        .append("</string>\n")
    }
    sb.append("</resources>")

    writeFile(stringsPath, sb.toString().trim { it <= ' ' })
  }

  private fun showOptions(v: View, position: Int) {
    val popupMenu = PopupMenu(v.context, v)
    popupMenu.inflate(R.menu.menu_values)
    popupMenu.setOnMenuItemClickListener { item: MenuItem ->
      val id = item.itemId
      when (id) {
        R.id.menu_copy_name -> {
          ClipboardUtils.copyText(stringList[position].name)
          make(
            v,
            "${v.context.getString(R.string.copied)} ${stringList[position].name}"
          )
            .setSlideAnimation()
            .showAsSuccess()
          true
        }
        R.id.menu_delete -> {
          MaterialAlertDialogBuilder(v.context)
            .setTitle("Remove String")
            .setMessage(
              String.format("Do you want to remove %s?", stringList[position].name)
            )
            .setNegativeButton(R.string.no, null)
            .setPositiveButton(
              R.string.yes
            ) { _, _ ->
              val name = stringList[position].name
              if (name == "default_string") {
                make(
                  v,
                  v.context
                    .getString(R.string.msg_cannot_delete_default, "string")
                )
                  .setFadeAnimation()
                  .setType(SBUtils.Type.INFO)
                  .show()
              } else {
                stringList.removeAt(position)
                notifyItemRemoved(position)
                generateStringsXml()
              }
            }
            .show()
          true
        }
        else -> false
      }
    }

    popupMenu.show()
  }

  private fun editString(v: View, pos: Int) {
    val builder = MaterialAlertDialogBuilder(v.context)
    builder.setTitle("Edit String")

    val bind =
      LayoutValuesItemDialogBinding.inflate(builder.create().layoutInflater)
    val ilName = bind.textInputLayoutName
    val ilValue = bind.textInputLayoutValue
    val etName = bind.textinputName
    val etValue = bind.textinputValue

    etName.setText(stringList[pos].name)
    etValue.setText(stringList[pos].value)
    builder.setView(bind.root)
    builder.setPositiveButton(
      R.string.okay
    ) { dlg: DialogInterface?, i: Int ->
      if (stringList[pos].name == "default_string" && etName.text.toString() != "default_string") {
        make(v, v.context.getString(R.string.msg_cannot_rename_default, "string"))
          .setFadeAnimation()
          .setType(SBUtils.Type.INFO)
          .show()
      } else {
        // Update position
        stringList[pos].name = etName.text.toString()
      }
      // Update position
      stringList[pos].value = etValue.text.toString()
      notifyItemChanged(pos)
      // Generate code from all strings in list
      generateStringsXml()
    }
    builder.setNegativeButton(R.string.cancel, null)

    val dialog = builder.create()
    dialog.show()

    etName.addTextChangedListener(
      object : TextWatcher {
        override fun beforeTextChanged(p1: CharSequence, p2: Int, p3: Int, p4: Int) {}

        override fun onTextChanged(p1: CharSequence, p2: Int, p3: Int, p4: Int) {}

        override fun afterTextChanged(p1: Editable) {
          NameErrorChecker.checkForValues(
            etName.text.toString(), ilName, dialog, stringList, pos
          )
        }
      })
    NameErrorChecker.checkForValues(etName.text.toString(), ilName, dialog, stringList, pos)
  }
}
