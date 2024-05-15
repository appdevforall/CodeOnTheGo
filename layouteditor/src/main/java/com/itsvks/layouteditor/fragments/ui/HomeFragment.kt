package com.itsvks.layouteditor.fragments.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputLayout
import com.itsvks.layouteditor.LayoutEditor
import com.itsvks.layouteditor.ProjectFile
import com.itsvks.layouteditor.R.string
import com.itsvks.layouteditor.activities.EditorActivity
import com.itsvks.layouteditor.adapters.ProjectListAdapter
import com.itsvks.layouteditor.databinding.FragmentHomeBinding
import com.itsvks.layouteditor.databinding.TextinputlayoutBinding
import com.itsvks.layouteditor.managers.ProjectManager
import com.itsvks.layouteditor.utils.FileUtil
import java.io.File
import java.util.Calendar

@Suppress("unused")
class HomeFragment : Fragment() {
  private var binding: FragmentHomeBinding? = null
  private var sharedPreferences: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(LayoutEditor.instance!!.context)

  private val projects = mutableListOf<ProjectFile>()
  private lateinit var adapter: ProjectListAdapter

  override fun onCreateView(
    inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
  ): View {
    binding = FragmentHomeBinding.inflate(inflater, container, false)
    return binding!!.root
  }

  override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
    super.onViewCreated(view, savedInstanceState)

    binding!!.fab.setOnClickListener { showCreateProjectDialog() }

    loadProjects()

    adapter = ProjectListAdapter(projects)

    adapter.onDeleteCallback = {
      updateNoProjectsViewVisibility()
    }

    binding!!.listProjects.adapter = adapter
    binding!!.listProjects.layoutManager =
      LinearLayoutManager(requireContext(), RecyclerView.VERTICAL, false)
    updateNoProjectsViewVisibility()
  }

  override fun onDestroyView() {
    super.onDestroyView()
    binding = null
  }

  @SuppressLint("SimpleDateFormat", "RestrictedApi", "SetTextI18n")
  @Suppress("deprecation")
  private fun showCreateProjectDialog() {
    val builder = MaterialAlertDialogBuilder(requireContext())
    builder.setTitle(getString(string.create_project))

    val bind = TextinputlayoutBinding.inflate(
      layoutInflater
    )
    val editText = bind.textinputEdittext
    val inputLayout = bind.textinputLayout

    builder.setView(bind.root, 10, 10, 10, 10)
    builder.setNegativeButton(string.cancel) { _, _ -> }
    builder.setPositiveButton(
      string.create
    ) { _, _ -> createProject(bind.textinputEdittext.text.toString()) }

    val dialog = builder.create()
    dialog.window!!.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
    dialog.show()

    inputLayout.hint = getString(string.msg_new_project_name)
    editText.setText("NewProject" + System.currentTimeMillis())
    editText.addTextChangedListener(
      object : TextWatcher {
        override fun beforeTextChanged(p1: CharSequence, p2: Int, p3: Int, p4: Int) {}

        override fun onTextChanged(p1: CharSequence, p2: Int, p3: Int, p4: Int) {}

        override fun afterTextChanged(p1: Editable) {
          checkNameErrors(editText.text.toString(), null, inputLayout, dialog)
        }
      })

    editText.requestFocus()

    val inputMethodManager =
      requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
    inputMethodManager.showSoftInput(editText, InputMethodManager.SHOW_IMPLICIT)

    if (editText.text.toString().isNotEmpty()) {
      editText.setSelection(0, editText.text.toString().length)
    }

    checkNameErrors(editText.text.toString(), "", inputLayout, dialog)
  }

  @SuppressLint("NotifyDataSetChanged")
  private fun loadProjects() {
    projects.clear()

    val root = File(FileUtil.getPackageDataDir(requireContext()) + "/projects/")

    if (!root.exists()) {
      FileUtil.makeDir(FileUtil.getPackageDataDir(requireContext()) + "/projects/")
    }

    for (file in root.listFiles()!!) {
      val path = file.path
      projects.add(ProjectFile(path, sharedPreferences.getString(path, currentTime)))
    }
  }

  @SuppressLint("NotifyDataSetChanged")
  private fun createProject(name: String) {
    val projectDir = FileUtil.getPackageDataDir(requireContext()) + "/projects/" + name
    val time = Calendar.getInstance().time.toString()
    FileUtil.makeDir(projectDir)
    FileUtil.makeDir("$projectDir/drawable/")
    FileUtil.makeDir("$projectDir/values/")
    FileUtil.makeDir("$projectDir/font/")
    FileUtil.copyFileFromAsset("default_image.png", "$projectDir/drawable")
    FileUtil.copyFileFromAsset("colors.xml", "$projectDir/values")
    FileUtil.copyFileFromAsset("strings.xml", "$projectDir/values")
    FileUtil.copyFileFromAsset("default_font.ttf", "$projectDir/font")

    val project = ProjectFile(projectDir, time)
    //((LayoutFile) getActivity().getIntent().getExtras().getParcelable(Constants.EXTRA_KEY_LAYOUT)).saveLayout("");
    project.createDefaultLayout()
    projects.add(project)
    adapter.notifyItemInserted(projects.indexOf(project))
    updateNoProjectsViewVisibility()

    sharedPreferences.edit().putString(projectDir, time).apply()

    val intent = Intent(requireContext(), EditorActivity::class.java)
    ProjectManager.instance.openProject(project)
    startActivity(intent)
  }

  private fun checkNameErrors(
    name: String, currentName: String?, inputLayout: TextInputLayout, dialog: AlertDialog
  ) {
    if (name.isEmpty()) {
      inputLayout.isErrorEnabled = true
      inputLayout.error = getString(string.msg_cannnot_empty)
      dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
      return
    }

    for (file in projects) {
      if (name == currentName) break

      if (file.name == name) {
        inputLayout.isErrorEnabled = true
        inputLayout.error = getString(string.msg_current_name_unavailable)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
        return
      }
    }

    inputLayout.isErrorEnabled = false
    inputLayout.error = ""
    dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = true
  }

  private fun updateNoProjectsViewVisibility() {
    binding!!.noProjectsView.visibility = if (projects.isEmpty()) View.VISIBLE else View.GONE
    binding!!.listProjects.visibility =
      if (binding!!.noProjectsView.visibility == View.GONE) View.VISIBLE else View.GONE
  }

  private val currentTime: String
    get() = Calendar.getInstance().time.toString()
}
