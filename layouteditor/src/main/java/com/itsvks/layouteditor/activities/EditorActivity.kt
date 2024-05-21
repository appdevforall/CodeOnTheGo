package com.itsvks.layouteditor.activities

import android.annotation.SuppressLint
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.view.menu.MenuBuilder
import androidx.appcompat.widget.PopupMenu
import androidx.appcompat.widget.TooltipCompat
import androidx.core.content.ContextCompat
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.blankj.utilcode.util.ToastUtils
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.itsvks.layouteditor.BaseActivity
import com.itsvks.layouteditor.LayoutFile
import com.itsvks.layouteditor.ProjectFile
import com.itsvks.layouteditor.R
import com.itsvks.layouteditor.R.string
import com.itsvks.layouteditor.adapters.LayoutListAdapter
import com.itsvks.layouteditor.adapters.PaletteListAdapter
import com.itsvks.layouteditor.databinding.ActivityLayouteditorBinding
import com.itsvks.layouteditor.databinding.TextinputlayoutBinding
import com.itsvks.layouteditor.editor.DesignEditor
import com.itsvks.layouteditor.editor.DeviceConfiguration
import com.itsvks.layouteditor.editor.DeviceSize
import com.itsvks.layouteditor.editor.convert.ConvertImportedXml
import com.itsvks.layouteditor.managers.DrawableManager
import com.itsvks.layouteditor.managers.IdManager.clear
import com.itsvks.layouteditor.managers.ProjectManager
import com.itsvks.layouteditor.managers.UndoRedoManager
import com.itsvks.layouteditor.tools.XmlLayoutGenerator
import com.itsvks.layouteditor.utils.BitmapUtil.createBitmapFromView
import com.itsvks.layouteditor.utils.Constants
import com.itsvks.layouteditor.utils.FileCreator
import com.itsvks.layouteditor.utils.FilePicker
import com.itsvks.layouteditor.utils.FileUtil
import com.itsvks.layouteditor.utils.NameErrorChecker
import com.itsvks.layouteditor.utils.SBUtils
import com.itsvks.layouteditor.utils.SBUtils.Companion.make
import com.itsvks.layouteditor.utils.Utils
import com.itsvks.layouteditor.views.CustomDrawerLayout
import java.io.File

@SuppressLint("UnsafeOptInUsageError")
class EditorActivity : BaseActivity() {
  private lateinit var binding: ActivityLayouteditorBinding

  private lateinit var drawerLayout: DrawerLayout
  private var actionBarDrawerToggle: ActionBarDrawerToggle? = null

  private lateinit var projectManager: ProjectManager
  private lateinit var project: ProjectFile

  private var undoRedo: UndoRedoManager? = null
  private var fileCreator: FileCreator? = null
  private var xmlPicker: FilePicker? = null

  private lateinit var layoutAdapter: LayoutListAdapter

  private val updateMenuIconsState: Runnable = Runnable { undoRedo!!.updateButtons() }

  private val onBackPressedCallback = object : OnBackPressedCallback(true) {
    override fun handleOnBackPressed() {
      if (drawerLayout.isDrawerOpen(GravityCompat.START) || drawerLayout.isDrawerOpen(GravityCompat.END)) {
        drawerLayout.closeDrawers()
      } else {
        val result = XmlLayoutGenerator().generate(binding.editorLayout, true)
        if (result.isNotEmpty()) {
          finishAfterTransition()
        } else {
          finishAfterTransition()
        }
      }
    }
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    init()
  }

  private fun init() {
    binding = ActivityLayouteditorBinding.inflate(layoutInflater)

    setContentView(binding.root)
    setSupportActionBar(binding.topAppBar)
    onBackPressedDispatcher.addCallback(this, onBackPressedCallback)

    projectManager = ProjectManager.instance

    project = projectManager.openedProject!!

    supportActionBar?.title = project.name
    layoutAdapter = LayoutListAdapter(project)

    binding.editorLayout.setBackgroundColor(
      Utils.getSurfaceColor(
        this
      )
    )

    defineFileCreator()
    defineXmlPicker()
    setupDrawerLayout()
    setupStructureView()

    setupDrawerNavigationRail()
    setToolbarButtonOnClickListener(binding)

    openLayout(project.mainLayout)

    layoutAdapter.onClickListener = { openLayout(it) }

    layoutAdapter.onLongClickListener = { view, position ->
      if (project.allLayouts[position].path == project.mainLayout.path) {
        ToastUtils.showShort("You can't modify main layout.")
      } else showLayoutListOptions(view, position)
      true
    }
  }

  private fun defineXmlPicker() {
    xmlPicker =
      object : FilePicker(this) {
        override fun onPickFile(uri: Uri?) {
         //if (FileUtil.isDownloadsDocument(uri)) {
         //  make(binding.root, string.select_from_storage).showAsError()
         //  return
         //}
          val path = uri?.path
          if (path != null && path.endsWith(".xml")) {
            val xml = FileUtil.readFromUri(uri, this@EditorActivity)
            val xmlConverted = ConvertImportedXml(xml).getXmlConverted(this@EditorActivity)

            if (xmlConverted != null) {
              if (!File(project.layoutPath + FileUtil.getLastSegmentFromPath(path)).exists()) {
                createNewLayout(FileUtil.getLastSegmentFromPath(path), xmlConverted)
                make(binding.root, "Imported!").setFadeAnimation().showAsSuccess()
              } else {
                make(binding.root, "Layout Already Exists!").setFadeAnimation().showAsError()
              }
            } else {
              make(binding.root, "Failed to import!")
                .setSlideAnimation()
                .showAsError()
            }
          } else {
            Toast.makeText(
              this@EditorActivity,
              "Selected file is not an Android XML layout file",
              Toast.LENGTH_SHORT
            ).show()
          }
        }
      }
  }

  private fun defineFileCreator() {
    fileCreator =
      object : FileCreator(this) {
        override fun onCreateFile(uri: Uri) {
          val result = XmlLayoutGenerator().generate(binding.editorLayout, true)

          if (FileUtil.saveFile(uri, result)) make(binding.root, "Success!").setSlideAnimation()
            .showAsSuccess()
          else {
            make(binding.root, "Failed to save!")
              .setSlideAnimation()
              .showAsError()
            FileUtil.deleteFile(FileUtil.convertUriToFilePath(uri))
          }
        }
      }
  }

  private fun setupDrawerLayout() {
    drawerLayout = binding.drawer
    actionBarDrawerToggle =
      ActionBarDrawerToggle(
        this, drawerLayout, binding.topAppBar, string.palette, string.palette
      )

    (drawerLayout as CustomDrawerLayout).addDrawerListener(actionBarDrawerToggle!!)
    actionBarDrawerToggle!!.syncState()
    (drawerLayout as CustomDrawerLayout).addDrawerListener(
      object : DrawerLayout.SimpleDrawerListener() {
        override fun onDrawerStateChanged(state: Int) {
          super.onDrawerStateChanged(state)
          undoRedo!!.updateButtons()
        }

        override fun onDrawerSlide(v: View, slideOffset: Float) {
          super.onDrawerSlide(v, slideOffset)
          undoRedo!!.updateButtons()
        }

        override fun onDrawerClosed(v: View) {
          super.onDrawerClosed(v)
          undoRedo!!.updateButtons()
        }

        override fun onDrawerOpened(v: View) {
          super.onDrawerOpened(v)
          undoRedo!!.updateButtons()
        }
      })
  }

  private fun setupStructureView() {
    binding.editorLayout.setStructureView(binding.structureView)

    binding.structureView.onItemClickListener = {
      binding.editorLayout.showDefinedAttributes(it)
      drawerLayout.closeDrawer(GravityCompat.END)
    }
  }

  private fun setupDrawerNavigationRail() {
    val fab = binding.paletteNavigation.headerView?.findViewById<FloatingActionButton>(R.id.fab)

    val paletteMenu = binding.paletteNavigation.menu
    paletteMenu.add(Menu.NONE, 0, Menu.NONE, Constants.TAB_TITLE_COMMON).setIcon(R.drawable.android)
    paletteMenu.add(Menu.NONE, 1, Menu.NONE, Constants.TAB_TITLE_TEXT)
      .setIcon(R.mipmap.ic_palette_text_view)
    paletteMenu.add(Menu.NONE, 2, Menu.NONE, Constants.TAB_TITLE_BUTTONS)
      .setIcon(R.mipmap.ic_palette_button)
    paletteMenu.add(Menu.NONE, 3, Menu.NONE, Constants.TAB_TITLE_WIDGETS)
      .setIcon(R.mipmap.ic_palette_view)
    paletteMenu.add(Menu.NONE, 4, Menu.NONE, Constants.TAB_TITLE_LAYOUTS)
      .setIcon(R.mipmap.ic_palette_relative_layout)
    paletteMenu.add(Menu.NONE, 5, Menu.NONE, Constants.TAB_TITLE_CONTAINERS)
      .setIcon(R.mipmap.ic_palette_view_pager)
    paletteMenu.add(Menu.NONE, 6, Menu.NONE, Constants.TAB_TITLE_LEGACY)
      .setIcon(R.mipmap.ic_palette_grid_layout)

    binding.listView.layoutManager = LinearLayoutManager(this, RecyclerView.VERTICAL, false)

    val adapter = PaletteListAdapter(binding.drawer)
    adapter.submitPaletteList(projectManager.getPalette(0))

    binding.paletteNavigation.setOnItemSelectedListener { item: MenuItem ->
      adapter.submitPaletteList(projectManager.getPalette(item.itemId))
      binding.paletteText.text = "Palette"
      binding.title.text = item.title
      replaceListViewAdapter(adapter)
      if (fab != null) {
        fab.setImageDrawable(ContextCompat.getDrawable(this, R.drawable.folder_outline))
        TooltipCompat.setTooltipText(fab, "Layouts")
      }
      true
    }
    replaceListViewAdapter(adapter)

    fab?.setOnClickListener {
      if (binding.listView.adapter is LayoutListAdapter) {
        createLayout()
      } else {
        replaceListViewAdapter(layoutAdapter)
        binding.title.text = getString(string.layouts)
        binding.paletteText.text = project.name
        // binding.paletteNavigation.getMenu().getItem(binding.paletteNavigation.getSelectedItemId()).setChecked(false);
        fab.setImageResource(R.drawable.plus)
        TooltipCompat.setTooltipText(fab, "Create new layout")
      }
    }
    clear()
  }

  private fun replaceListViewAdapter(adapter: RecyclerView.Adapter<*>) {
    binding.listView.adapter = adapter
  }

  override fun onOptionsItemSelected(item: MenuItem): Boolean {
    val id = item.itemId
    undoRedo!!.updateButtons()
    if (actionBarDrawerToggle!!.onOptionsItemSelected(item)) return true
    when (id) {
      android.R.id.home -> {
        drawerLayout.openDrawer(GravityCompat.START)
        return true
      }

      R.id.undo -> {
        binding.editorLayout.undo()
        return true
      }

      R.id.redo -> {
        binding.editorLayout.redo()
        return true
      }

      R.id.show_structure -> {
        drawerLayout.openDrawer(GravityCompat.END)
        return true
      }

      R.id.save_xml -> {
        saveXml()
        return true
      }

      R.id.edit_xml -> {
        showXml()
        return true
      }

      R.id.resources_manager -> {
        startActivity(
          Intent(this, ResourceManagerActivity::class.java)
            .putExtra(Constants.EXTRA_KEY_PROJECT, project)
        )
        return true
      }

      R.id.preview -> {
        val result = XmlLayoutGenerator().generate(binding.editorLayout, true)
        if (result.isEmpty()) showNothingDialog()
        else {
          saveXml()
          startActivity(
            Intent(this, PreviewLayoutActivity::class.java)
              .putExtra(Constants.EXTRA_KEY_LAYOUT, project.currentLayout)
          )
        }
        return true
      }

      R.id.export_xml -> {
        fileCreator!!.create(projectManager.formattedProjectName, "text/xml")
        return true
      }

      R.id.export_as_image -> {
        if (binding.editorLayout.getChildAt(0) != null) showSaveMessage(
          Utils.saveBitmapAsImageToGallery(
            this, createBitmapFromView(binding.editorLayout), project.name
          )
        )
        else make(binding.root, "Add some views...")
          .setFadeAnimation()
          .setType(SBUtils.Type.INFO)
          .show()
        return true
      }

      R.id.import_xml -> {
        MaterialAlertDialogBuilder(this@EditorActivity)
          .setTitle(string.note)
          .setMessage("*Be aware it will fail to import when you try to import the layout file with view, different from LayoutEditor view set!")
          .setCancelable(false)
          .setNegativeButton(string.cancel) { d, _ -> d.cancel() }
          .setPositiveButton(string.okay) { _, _ -> xmlPicker!!.launch("text/xml") }
          .show()
        return true
      }

      else -> return false
    }
  }

  override fun onConfigurationChanged(config: Configuration) {
    super.onConfigurationChanged(config)
    actionBarDrawerToggle!!.onConfigurationChanged(config)
    undoRedo!!.updateButtons()
  }

  override fun onPostCreate(savedInstanceState: Bundle?) {
    super.onPostCreate(savedInstanceState)
    actionBarDrawerToggle!!.syncState()
    if (undoRedo != null) undoRedo!!.updateButtons()
  }

  override fun onResume() {
    super.onResume()
    project.drawables?.let {
      DrawableManager.loadFromFiles(it)
    }
    if (undoRedo != null) undoRedo!!.updateButtons()
  }

  override fun onDestroy() {
    super.onDestroy()
//    binding = null
    projectManager.closeProject()
  }

  private fun showXml() {
    val result = XmlLayoutGenerator().generate(binding.editorLayout, true)
    if (result.isEmpty()) {
      showNothingDialog()
    } else {
      startActivity(
        Intent(this, ShowXMLActivity::class.java).putExtra(ShowXMLActivity.EXTRA_KEY_XML, result)
      )
    }
  }

  private fun showNothingDialog() {
    MaterialAlertDialogBuilder(this)
      .setTitle(string.nothing)
      .setMessage(string.msg_add_some_widgets)
      .setPositiveButton(string.okay) { d, _ -> d.cancel() }
      .show()
  }

  @SuppressLint("RestrictedApi")
  override fun onCreateOptionsMenu(menu: Menu): Boolean {
    if (menu is MenuBuilder) menu.setOptionalIconsVisible(true)

    menuInflater.inflate(R.menu.menu_editor, menu)
    val undo = menu.findItem(R.id.undo)
    val redo = menu.findItem(R.id.redo)
    undoRedo = UndoRedoManager(undo, redo)
    binding.editorLayout.bindUndoRedoManager(undoRedo)
    binding.editorLayout.updateUndoRedoHistory()
    updateUndoRedoBtnState()
    return super.onCreateOptionsMenu(menu)
  }

  private fun updateUndoRedoBtnState() {
    Handler(Looper.getMainLooper()).postDelayed(updateMenuIconsState, 10)
  }

  private fun showSaveMessage(success: Boolean) {
    if (success) make(binding.root, "Saved to gallery.")
      .setFadeAnimation()
      .setType(SBUtils.Type.INFO)
      .show()
    else make(binding.root, "Failed to save...")
      .setFadeAnimation()
      .setType(SBUtils.Type.ERROR)
      .show()
  }

  private fun setToolbarButtonOnClickListener(binding: ActivityLayouteditorBinding) {
    TooltipCompat.setTooltipText(binding.viewType, "View Type")
    TooltipCompat.setTooltipText(binding.deviceSize, "Size")
    binding.viewType.setOnClickListener { view ->
      val popupMenu = PopupMenu(view.context, view)
      popupMenu.inflate(R.menu.menu_view_type)
      popupMenu.setOnMenuItemClickListener {
        val id = it.itemId
        when (id) {
          R.id.view_type_design -> {
            binding.editorLayout.viewType = DesignEditor.ViewType.DESIGN
          }

          R.id.view_type_blueprint -> {
            binding.editorLayout.viewType = DesignEditor.ViewType.BLUEPRINT
          }
        }
        true
      }
      popupMenu.show()
    }
    binding.deviceSize.setOnClickListener {
      val popupMenu = PopupMenu(it.context, it)
      popupMenu.inflate(R.menu.menu_device_size)
      popupMenu.setOnMenuItemClickListener { item ->
        val id = item.itemId
        when (id) {
          R.id.device_size_small -> {
            binding.editorLayout.resizeLayout(DeviceConfiguration(DeviceSize.SMALL))
          }

          R.id.device_size_medium -> {
            binding.editorLayout.resizeLayout(DeviceConfiguration(DeviceSize.MEDIUM))
          }

          R.id.device_size_large -> {
            binding.editorLayout.resizeLayout(DeviceConfiguration(DeviceSize.LARGE))
          }
        }
        true
      }
      popupMenu.show()
    }
  }

  fun createNewLayout(name: String, layoutContent: String?) {
    val layoutFile = LayoutFile(project.layoutPath + name)
    layoutFile.saveLayout(layoutContent)
    openLayout(layoutFile)
  }

  private fun openLayout(layoutFile: LayoutFile) {
    binding.editorLayout.loadLayoutFromParser(layoutFile.read())
    project.currentLayout = layoutFile
    supportActionBar!!.subtitle = layoutFile.name
    if (drawerLayout.isDrawerOpen(GravityCompat.START)) {
      drawerLayout.closeDrawer(GravityCompat.START)
    }
    make(binding.root, "Loaded!")
      .setFadeAnimation()
      .setType(SBUtils.Type.INFO)
      .show()
  }

  @SuppressLint("RestrictedApi", "SetTextI18n")
  fun createLayout() {
    val builder = MaterialAlertDialogBuilder(this)
    builder.setTitle(string.create_layout)

    val bind: TextinputlayoutBinding =
      TextinputlayoutBinding.inflate(builder.create().layoutInflater)
    val editText: TextInputEditText = bind.textinputEdittext
    val inputLayout: TextInputLayout = bind.textinputLayout

    inputLayout.suffixText = ".xml"

    @Suppress("DEPRECATION")
    builder.setView(bind.getRoot(), 10, 10, 10, 10)
    builder.setNegativeButton(
      string.cancel
    ) { _, _ -> }
    builder.setPositiveButton(string.create) { _, _ ->
      createNewLayout(
        "${editText.getText().toString().replace(" ", "_").lowercase()}.xml", ""
      )
    }

    val dialog: AlertDialog = builder.create()
    dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
    dialog.show()

    inputLayout.setHint(string.msg_new_layout_name)
    editText.setText("layout_new")
    editText.addTextChangedListener(
      object : TextWatcher {
        override fun beforeTextChanged(p1: CharSequence, p2: Int, p3: Int, p4: Int) {}

        override fun onTextChanged(p1: CharSequence, p2: Int, p3: Int, p4: Int) {
          NameErrorChecker.checkForLayouts(
            editText.text.toString(),
            inputLayout,
            dialog,
            project.allLayouts,
            -1
          )
        }

        override fun afterTextChanged(p1: Editable) {}
      })

    editText.requestFocus()

    val inputMethodManager =
      getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
    inputMethodManager.showSoftInput(editText, InputMethodManager.SHOW_IMPLICIT)

    if (editText.text.toString().isEmpty()) {
      editText.setSelection(0, editText.text.toString().length)
    }

    NameErrorChecker.checkForLayouts(
      editText.text.toString(),
      inputLayout,
      dialog,
      project.allLayouts,
      -1
    )
  }

  @SuppressLint("RestrictedApi")
  private fun renameLayout(pos: Int) {
    val layouts = project.allLayouts
    val builder = MaterialAlertDialogBuilder(this)
    builder.setTitle(string.rename_layout)
    val bind: TextinputlayoutBinding =
      TextinputlayoutBinding.inflate(builder.create().layoutInflater)
    val editText: TextInputEditText = bind.textinputEdittext
    val inputLayout: TextInputLayout = bind.textinputLayout

    inputLayout.suffixText = ".xml"

    editText.setText(layouts[pos].name.substring(0, layouts[pos].name.lastIndexOf(".")))
    inputLayout.setHint(string.msg_new_layout_name)

    val padding = TypedValue.applyDimension(
      TypedValue.COMPLEX_UNIT_DIP,
      10f,
      resources.displayMetrics
    ).toInt()

    @Suppress("DEPRECATION")
    builder.setView(bind.getRoot(), padding, padding, padding, padding)
    builder.setNegativeButton(
      string.cancel
    ) { _, _ -> }
    builder.setPositiveButton(
      string.rename
    ) { _, _ ->
      val path: String = layouts[pos].path
      val newPath = "${path.substring(0, path.lastIndexOf("/"))}/${
        editText.text.toString().replace(" ", "_").lowercase()
      }.xml"
      layouts[pos].rename(newPath)
      if (layouts[pos] === project.currentLayout) openLayout(layouts[pos])
      layoutAdapter.notifyItemChanged(pos)
    }

    val dialog: AlertDialog = builder.create()
    dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
    dialog.show()

    editText.addTextChangedListener(
      object : TextWatcher {
        override fun beforeTextChanged(p1: CharSequence, p2: Int, p3: Int, p4: Int) {}

        override fun onTextChanged(p1: CharSequence, p2: Int, p3: Int, p4: Int) {
          NameErrorChecker.checkForLayouts(
            editText.text.toString(),
            inputLayout,
            dialog,
            project.allLayouts,
            pos
          )
        }

        override fun afterTextChanged(p1: Editable) {}
      })

    NameErrorChecker.checkForLayouts(
      editText.text.toString(),
      inputLayout,
      dialog,
      project.allLayouts,
      pos
    )

    editText.requestFocus()
    val inputMethodManager = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
    inputMethodManager.showSoftInput(editText, InputMethodManager.SHOW_IMPLICIT)

    if (editText.text.toString().isNotEmpty()) {
      editText.setSelection(0, editText.text.toString().length)
    }
  }

  @SuppressLint("NotifyDataSetChanged")
  fun deleteLayout(pos: Int) {
    val layouts = project.allLayouts
    val builder = MaterialAlertDialogBuilder(this)
    builder.setTitle(string.delete_layout)
    builder.setMessage(string.msg_delete_layout)
    builder.setNegativeButton(
      string.no
    ) { d, _ -> d.dismiss() }
    builder.setPositiveButton(
      string.yes
    ) { _, _ ->
      if (layouts[pos].path == project.mainLayout.path) {
        ToastUtils.showShort("You can't delete main layout.")
        return@setPositiveButton
      }
      FileUtil.deleteFile(layouts[pos].path)
      if (layouts[pos] === project.currentLayout) openLayout(project.mainLayout)
      layouts.remove(layouts[pos])
      layoutAdapter.notifyItemRemoved(pos)
    }

    builder.create().show()
  }

  private fun showLayoutListOptions(v: View, pos: Int) {
    val popupMenu = PopupMenu(v.context, v)
    popupMenu.inflate(R.menu.menu_layout_file_options)
    popupMenu.setOnMenuItemClickListener { item: MenuItem ->
      val id = item.itemId
      when (id) {
        R.id.menu_delete_layout -> {
          deleteLayout(pos)
          true
        }

        R.id.menu_rename_layout -> {
          renameLayout(pos)
          true
        }

        else -> false
      }
    }

    popupMenu.show()
  }

  private fun saveXml() {
    if (binding.editorLayout.childCount == 0) {
      project.currentLayout.saveLayout("")
      ToastUtils.showShort(getString(string.layout_saved))
      return
    }

    val result = XmlLayoutGenerator().generate(binding.editorLayout, false)
    project.currentLayout.saveLayout(result)
    ToastUtils.showShort(getString(string.layout_saved))
  }

  companion object {
    const val ACTION_OPEN: String = "com.itsvks.layouteditor.open"
  }
}
