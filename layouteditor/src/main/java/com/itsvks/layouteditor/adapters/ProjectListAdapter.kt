package com.itsvks.layouteditor.adapters

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.text.Editable
import android.text.TextWatcher
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.AnimationUtils
import android.view.inputmethod.InputMethodManager
import android.widget.ArrayAdapter
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.PopupMenu
import androidx.appcompat.widget.TooltipCompat
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputLayout
import com.itsvks.layouteditor.LayoutEditor.Companion.instance
import com.itsvks.layouteditor.ProjectFile
import com.itsvks.layouteditor.R
import com.itsvks.layouteditor.R.string
import com.itsvks.layouteditor.activities.EditorActivity
import com.itsvks.layouteditor.activities.PreviewLayoutActivity
import com.itsvks.layouteditor.databinding.ListProjectFileBinding
import com.itsvks.layouteditor.databinding.TextinputlayoutBinding
import com.itsvks.layouteditor.managers.PreferencesManager
import com.itsvks.layouteditor.managers.ProjectManager
import com.itsvks.layouteditor.utils.Constants
import com.itsvks.layouteditor.utils.FileUtil
import com.itsvks.layouteditor.utils.SBUtils.Companion.make
import java.io.File

class ProjectListAdapter(private val projects: MutableList<ProjectFile>) :
  RecyclerView.Adapter<ProjectListAdapter.ViewHolder>() {
  private val prefs = PreferencesManager.prefs

  var onDeleteCallback: ((Int) -> Unit)? = null

  class ViewHolder(var binding: ListProjectFileBinding) : RecyclerView.ViewHolder(
    binding.root
  ) {
    var projectName = binding.projectName
    var projectDate = binding.projectDate
    var projectIcon = binding.icon
    var menu = binding.menu
  }

  override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
    return ViewHolder(
      ListProjectFileBinding.inflate(LayoutInflater.from(parent.context), parent, false)
    )
  }

  @SuppressLint("RecyclerView")
  override fun onBindViewHolder(holder: ViewHolder, position: Int) {
    val context = holder.binding.root.context
    holder.itemView.animation = AnimationUtils.loadAnimation(
      holder.itemView.context, R.anim.project_list_animation
    )
    holder.projectName.text = projects[position].name
    holder.projectDate.text = projects[position].date

    TooltipCompat.setTooltipText(holder.menu, context.getString(string.options))
    TooltipCompat.setTooltipText(holder.binding.root, projects[position].name)

    holder.binding.root.setOnClickListener { openProject(it, holder.absoluteAdapterPosition) }
    holder.projectIcon.text = projects[position].name.substring(0, 1).uppercase()
    holder.menu.setOnClickListener { showOptions(it, holder.absoluteAdapterPosition) }
  }

  override fun getItemCount(): Int {
    return projects.size
  }

  private fun checkNameErrors(
    projects: List<ProjectFile>,
    name: String,
    currentName: String,
    inputLayout: TextInputLayout,
    dialog: AlertDialog
  ) {
    if (name.isEmpty()) {
      inputLayout.isErrorEnabled = true
      inputLayout.error = dialog.context.getString(string.msg_cannnot_empty)
      dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
      return
    }

    for (file in projects) {
      if (name == currentName) break

      if (file.name == name) {
        inputLayout.isErrorEnabled = true
        inputLayout.error = instance!!.context.getString(string.msg_current_name_unavailable)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
        return
      }
    }

    inputLayout.isErrorEnabled = false
    inputLayout.error = ""
    dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = true
  }

  @Suppress("deprecation")
  @SuppressLint("RestrictedApi")
  private fun renameProject(v: View, position: Int) {
    val builder = MaterialAlertDialogBuilder(v.context)
    builder.setTitle(string.rename_project)
    val bind =
      TextinputlayoutBinding.inflate(builder.create().layoutInflater)
    val editText = bind.textinputEdittext
    val inputLayout = bind.textinputLayout

    editText.setText(projects[position].name)
    inputLayout.setHint(string.msg_new_project_name)

    val padding = TypedValue.applyDimension(
      TypedValue.COMPLEX_UNIT_DIP, 10f, v.context.resources.displayMetrics
    ).toInt()

    builder.setView(bind.root, padding, padding, padding, padding)
    builder.setNegativeButton(string.cancel) { _, _ -> }
    builder.setPositiveButton(
      string.rename
    ) { _, _ ->
      val path = projects[position].path
      val newPath =
        "${path.substring(0, path.lastIndexOf("/"))}/${editText.text.toString()}"
      projects[position].rename(newPath)
      notifyItemChanged(position)
    }

    val dialog = builder.create()
    dialog.window!!.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
    dialog.show()

    editText.addTextChangedListener(
      object : TextWatcher {
        override fun beforeTextChanged(p1: CharSequence, p2: Int, p3: Int, p4: Int) {
        }

        override fun onTextChanged(p1: CharSequence, p2: Int, p3: Int, p4: Int) {
        }

        override fun afterTextChanged(p1: Editable) {
          checkNameErrors(
            projects,
            editText.text.toString(),
            projects[position].name,
            inputLayout,
            dialog
          )
        }
      })

    checkNameErrors(
      projects,
      editText.text.toString(),
      projects[position].name,
      inputLayout,
      dialog
    )

    editText.requestFocus()
    val inputMethodManager =
      v.context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
    inputMethodManager.showSoftInput(editText, InputMethodManager.SHOW_IMPLICIT)

    if (editText.text.toString().isNotEmpty()) {
      editText.setSelection(0, editText.text.toString().length)
    }
  }

  private fun deleteProject(v: View, position: Int) {
    val builder = MaterialAlertDialogBuilder(v.context)
    builder.setTitle(string.delete_project)
    builder.setMessage(string.msg_delete_project)
    builder.setNegativeButton(string.no) { d, _ -> d.dismiss() }
    builder.setPositiveButton(
      string.yes
    ) { _, _ ->
      FileUtil.deleteFile(projects[position].path)
      projects.remove(projects[position])
      notifyItemRemoved(position)
      onDeleteCallback?.invoke(position)
    }

    builder.create().show()
  }

  private fun showOptions(v: View, position: Int) {
    val popupMenu = PopupMenu(v.context, v)
    popupMenu.inflate(R.menu.menu_project_file_options)
    popupMenu.setOnMenuItemClickListener { item: MenuItem ->
      val id = item.itemId
      when (id) {
        R.id.menu_delete -> {
          deleteProject(v, position)
          true
        }

        R.id.menu_preview -> {
          previewLayout(v, position)
          true
        }

        R.id.menu_rename -> {
          renameProject(v, position)
          true
        }

        else -> false
      }
    }

    popupMenu.show()
  }

  private fun openProject(v: View, position: Int) {
    val intent = Intent(v.context, EditorActivity::class.java)

    ProjectManager.instance.openProject(projects[position])

    val projectDir =
      "${FileUtil.getPackageDataDir(instance!!.context)}/projects/${projects[position].name}"

    if (!prefs.getBoolean("copyAssets", false)
      && !(File("$projectDir/values/colors.xml").exists())
    ) {
      FileUtil.makeDir("$projectDir/values/")
      // FileUtil.makeDir(projectDir + "/drawable/");
      // FileUtil.copyFileFromAsset("default_image.png", projectDir + "/drawable");
      FileUtil.copyFileFromAsset("colors.xml", "$projectDir/values")
      prefs.edit().putBoolean("copyAssets", true).apply()
    }
    v.context.startActivity(intent)
  }

  private fun previewLayout(v: View, position: Int) {
    val layouts = ArrayList<String>()
    val allLayouts = projects[position].allLayouts
    allLayouts.forEach { layouts.add(it.name) }

    MaterialAlertDialogBuilder(v.context)
      .setTitle("Choose layout")
      .setAdapter(
        ArrayAdapter(
          v.context,
          android.R.layout.simple_list_item_1,
          layouts
        )
      ) { d, w ->
        val intent = Intent(v.context, PreviewLayoutActivity::class.java)
        intent.putExtra(Constants.EXTRA_KEY_LAYOUT, allLayouts[w])
        if (allLayouts[w].read().isEmpty()) {
          make(v, layouts[w] + " is empty...").setFadeAnimation().showAsError()
        } else v.context.startActivity(intent)
        d.dismiss()
      }
      .setPositiveButton(string.cancel, null)
      .show()
  }
}
