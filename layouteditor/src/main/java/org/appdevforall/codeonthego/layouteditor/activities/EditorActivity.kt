package org.appdevforall.codeonthego.layouteditor.activities

import android.annotation.SuppressLint
import android.content.Context
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
import androidx.core.view.isEmpty
import androidx.drawerlayout.widget.DrawerLayout
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.blankj.utilcode.util.ToastUtils
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.itsaky.androidide.idetooltips.TooltipManager
import com.itsaky.androidide.FeedbackButtonManager
import org.appdevforall.codeonthego.layouteditor.BaseActivity
import org.appdevforall.codeonthego.layouteditor.LayoutFile
import org.appdevforall.codeonthego.layouteditor.ProjectFile
import org.appdevforall.codeonthego.layouteditor.R
import org.appdevforall.codeonthego.layouteditor.R.string
import org.appdevforall.codeonthego.layouteditor.adapters.LayoutListAdapter
import org.appdevforall.codeonthego.layouteditor.adapters.PaletteListAdapter
import org.appdevforall.codeonthego.layouteditor.databinding.ActivityLayoutEditorBinding
import org.appdevforall.codeonthego.layouteditor.databinding.TextinputlayoutBinding
import org.appdevforall.codeonthego.layouteditor.editor.DesignEditor
import org.appdevforall.codeonthego.layouteditor.editor.DeviceConfiguration
import org.appdevforall.codeonthego.layouteditor.editor.DeviceSize
import org.appdevforall.codeonthego.layouteditor.editor.convert.ConvertImportedXml
import org.appdevforall.codeonthego.layouteditor.managers.DrawableManager
import org.appdevforall.codeonthego.layouteditor.managers.IdManager.clear
import org.appdevforall.codeonthego.layouteditor.managers.ProjectManager
import org.appdevforall.codeonthego.layouteditor.managers.UndoRedoManager
import org.appdevforall.codeonthego.layouteditor.tools.XmlLayoutGenerator
import org.appdevforall.codeonthego.layouteditor.utils.BitmapUtil.createBitmapFromView
import org.appdevforall.codeonthego.layouteditor.utils.Constants
import org.appdevforall.codeonthego.layouteditor.utils.FileCreator
import org.appdevforall.codeonthego.layouteditor.utils.FilePicker
import org.appdevforall.codeonthego.layouteditor.utils.FileUtil
import org.appdevforall.codeonthego.layouteditor.utils.NameErrorChecker
import org.appdevforall.codeonthego.layouteditor.utils.SBUtils
import org.appdevforall.codeonthego.layouteditor.utils.SBUtils.Companion.make
import org.appdevforall.codeonthego.layouteditor.utils.Utils
import org.appdevforall.codeonthego.layouteditor.utils.doubleArgSafeLet
import org.appdevforall.codeonthego.layouteditor.views.CustomDrawerLayout
import java.io.File

@SuppressLint("UnsafeOptInUsageError")
class EditorActivity : BaseActivity() {

    private lateinit var binding: ActivityLayoutEditorBinding

    private lateinit var drawerLayout: DrawerLayout
    private var actionBarDrawerToggle: ActionBarDrawerToggle? = null

    private lateinit var projectManager: ProjectManager
    private lateinit var project: ProjectFile
    private var feedbackButtonManager: FeedbackButtonManager? = null

    private var undoRedo: UndoRedoManager? = null
    private var fileCreator: FileCreator? = null
    private var xmlPicker: FilePicker? = null

    private lateinit var layoutAdapter: LayoutListAdapter

    private val updateMenuIconsState: Runnable = Runnable { undoRedo!!.updateButtons() }

    private val onBackPressedCallback = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
            when {
                drawerLayout.isDrawerOpen(GravityCompat.START) || drawerLayout.isDrawerOpen(
                    GravityCompat.END
                ) -> {
                    drawerLayout.closeDrawers()
                }

                binding.editorLayout.isLayoutModified() -> {
                    showSaveChangesDialog()
                }

                else -> {
                    saveXml()
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
        binding = ActivityLayoutEditorBinding.inflate(layoutInflater)

        setContentView(binding.root)
        setSupportActionBar(binding.topAppBar)
        onBackPressedDispatcher.addCallback(this, onBackPressedCallback)

        projectManager = ProjectManager.instance

        projectManager.initManger(context = this)

        defineFileCreator()
        defineXmlPicker()
        setupDrawerLayout()
        setupStructureView()
        setupFeedbackButton()
        setupDrawerNavigationRail()
        setToolbarButtonOnClickListener(binding)

        //todo extract file_key to layouteditor constants and use it in both modules.
        //todo extract replace 0 date with actual value.
        //todo extract replace activity_main date with actual value.
        doubleArgSafeLet(
            intent.getStringExtra(Constants.EXTRA_KEY_FILE_PATH),
            intent.getStringExtra(Constants.EXTRA_KEY_LAYOUT_FILE_NAME)
        ) { filePath, fileName ->
            projectManager.openProject(ProjectFile(filePath, "0", this, mainLayoutName = fileName))
            project = projectManager.openedProject!!
            androidToDesignConversion(
                Uri.fromFile(File(projectManager.openedProject?.mainLayout?.path ?: ""))
            )

            supportActionBar?.title = project.name
            layoutAdapter = LayoutListAdapter(project)
        } ?: showNothingDialog()

        binding.editorLayout.setBackgroundColor(
            Utils.getSurfaceColor(
                this
            )
        )
    }

    private fun defineXmlPicker() {
        xmlPicker =
            object : FilePicker(this) {
                override fun onPickFile(uri: Uri?) {
                    val path = uri?.path
                    if (path != null && path.endsWith(".xml")) {
                        androidToDesignConversion(uri)
                    } else {
                        Toast.makeText(
                            this@EditorActivity,
                            getString(string.error_invalid_xml_file),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
    }

    private fun androidToDesignConversion(uri: Uri?) {
        val path = uri?.path
        if (path != null && path.endsWith(".xml")) {
            val xml = FileUtil.readFromUri(uri, this@EditorActivity)
            val xmlConverted = ConvertImportedXml(xml).getXmlConverted(this@EditorActivity)

            if (xmlConverted != null) {
                val fileName = FileUtil.getLastSegmentFromPath(path)

                val productionPath = project.layoutPath + fileName
                val designPath = project.layoutDesignPath + fileName

                FileUtil.writeFile(productionPath, xml)
                FileUtil.writeFile(designPath, xmlConverted)

                openLayout(LayoutFile(productionPath, designPath))

                make(binding.root, getString(string.success_imported)).setFadeAnimation()
                    .showAsSuccess()
            } else {
                make(binding.root, getString(string.error_failed_to_import))
                    .setSlideAnimation()
                    .showAsError()
            }
        } else {
            Toast.makeText(
                this@EditorActivity,
                getString(string.error_invalid_xml_file),
                Toast.LENGTH_SHORT
            ).show()
        }
    }


    private fun defineFileCreator() {
        fileCreator =
            object : FileCreator(this) {
                override fun onCreateFile(uri: Uri) {
                    val result = XmlLayoutGenerator().generate(binding.editorLayout, true)

                    if (FileUtil.saveFile(this@EditorActivity, uri, result)) make(
                        binding.root,
                        getString(string.success_saved)
                    ).setSlideAnimation()
                        .showAsSuccess()
                    else {
                        make(binding.root, getString(string.error_failed_to_save))
                            .setSlideAnimation()
                            .showAsError()
                        FileUtil.deleteFile(FileUtil.convertUriToFilePath(this@EditorActivity, uri))
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

        binding.structureView.apply {
            onItemClickListener = {
                binding.editorLayout.showDefinedAttributes(it)
                drawerLayout.closeDrawer(GravityCompat.END)
            }
            onItemLongClickListener = { view ->
                showTooltip(
                    context = this@EditorActivity,
                    anchorView = view,
                    tag = view.javaClass.superclass.name
                )
            }
        }
    }

    private fun showTooltip(context: Context, anchorView: View, tag: String) {
        TooltipManager.showTooltip(
            context = context,
            anchorView = anchorView,
            tag = tag,
        )
    }

    @SuppressLint("SetTextI18n")
    private fun setupDrawerNavigationRail() {
        val paletteFab =
            binding.paletteNavigation.headerView?.findViewById<FloatingActionButton>(R.id.paletteFab)
        val helpFab =
            binding.paletteNavigation.headerView?.findViewById<FloatingActionButton>(R.id.help_fab)

        // Set tooltip text for help FAB
        TooltipCompat.setTooltipText(helpFab as View, getString(string.help))

        val paletteMenu = binding.paletteNavigation.menu
        paletteMenu.add(Menu.NONE, 0, Menu.NONE, Constants.TAB_TITLE_COMMON)
            .setIcon(R.drawable.android)
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

        binding.listView.layoutManager = LinearLayoutManager(this, RecyclerView.VERTICAL, false)

        val adapter = PaletteListAdapter(binding.drawer)
        try {
            adapter.submitPaletteList(projectManager.getPalette(0))

            binding.paletteNavigation.setOnItemSelectedListener { item: MenuItem ->
                try {
                    adapter.submitPaletteList(projectManager.getPalette(item.itemId))
                    binding.paletteText.text = getString(string.label_palette)
                    binding.title.text = item.title
                    replaceListViewAdapter(adapter)
                    if (paletteFab != null) {
                        paletteFab.setImageDrawable(
                            ContextCompat.getDrawable(
                                this,
                                R.drawable.folder_outline
                            )
                        )
                        TooltipCompat.setTooltipText(paletteFab, getString(string.tooltip_layouts))
                    }
                } catch (e: Exception) {
                    Toast.makeText(
                        this,
                        "${getString(string.error_failed_to_load_palette)}: ${e.message}",
                        Toast.LENGTH_SHORT
                    )
                        .show()
                }
                true
            }
            replaceListViewAdapter(adapter)
        } catch (e: Exception) {
            Toast.makeText(
                this,
                "${getString(string.error_failed_to_initialize_palette)}: ${e.message}",
                Toast.LENGTH_SHORT
            )
                .show()
        }

        paletteFab?.setOnClickListener {
            if (binding.listView.adapter is LayoutListAdapter) {
                createLayout()
            } else {
                try {
                    replaceListViewAdapter(layoutAdapter)
                    binding.title.text = getString(string.layouts)
                    binding.paletteText.text = project.name
                    paletteFab.setImageResource(R.drawable.plus)
                    TooltipCompat.setTooltipText(
                        paletteFab,
                        getString(string.tooltip_create_new_layout)
                    )
                } catch (e: Exception) {
                    Toast.makeText(
                        this,
                        "${getString(string.error_failed_to_load_layouts)}: ${e.message}",
                        Toast.LENGTH_SHORT
                    )
                        .show()
                }
            }
        }

        helpFab.setOnClickListener {
            Toast.makeText(this, "Go to Help", Toast.LENGTH_SHORT).show()
            // TODO - Load help page in [HelpActivity]
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
                val uri = Uri.fromFile(File(project.currentLayout.path))
                val result = XmlLayoutGenerator().generate(binding.editorLayout, true)

                if (FileUtil.saveFile(this@EditorActivity, uri, result)) {
                    binding.editorLayout.markAsSaved()
                    make(binding.root, getString(string.success_saved)).setSlideAnimation()
                        .showAsSuccess()
                } else {
                    make(binding.root, getString(string.error_failed_to_save))
                        .setSlideAnimation()
                        .showAsError()
                    FileUtil.deleteFile(FileUtil.convertUriToFilePath(this@EditorActivity, uri))
                }

                return true
            }

            R.id.export_as_image -> {
                if (binding.editorLayout.getChildAt(0) != null) showSaveMessage(
                    Utils.saveBitmapAsImageToGallery(
                        this, createBitmapFromView(binding.editorLayout), project.name
                    )
                )
                else make(binding.root, getString(string.info_add_some_views))
                    .setFadeAnimation()
                    .setType(SBUtils.Type.INFO)
                    .show()
                return true
            }

            R.id.save_xml -> {

                MaterialAlertDialogBuilder(this@EditorActivity)
                    .setTitle(string.save)
                    .setMessage(string.save_layout_message)
                    .setCancelable(false)
                    .setNeutralButton(string.cancel) { d, _ ->
                        d.cancel()
                    }
                    .setNegativeButton(string.export_as_image) { d, _ ->
                        if (binding.editorLayout.getChildAt(0) != null) showSaveMessage(
                            Utils.saveBitmapAsImageToGallery(
                                this, createBitmapFromView(binding.editorLayout), project.name
                            )
                        )
                        else make(binding.root, getString(string.info_add_some_views))
                            .setFadeAnimation()
                            .setType(SBUtils.Type.INFO)
                            .show()
                    }
                    .setPositiveButton(string.export_layout) { _, _ ->
                        val uri = Uri.fromFile(File(project.currentLayout.path))
                        val result = XmlLayoutGenerator().generate(binding.editorLayout, true)

                        if (FileUtil.saveFile(this@EditorActivity, uri, result)) {
                            binding.editorLayout.markAsSaved()
                            make(binding.root, getString(string.success_saved)).setSlideAnimation()
                                .showAsSuccess()
                        } else {
                            make(binding.root, getString(string.error_failed_to_save))
                                .setSlideAnimation()
                                .showAsError()
                            FileUtil.deleteFile(
                                FileUtil.convertUriToFilePath(
                                    this@EditorActivity,
                                    uri
                                )
                            )
                        }
                    }
                    .show()
                return true
            }

            R.id.exit_editor -> {
                if (binding.editorLayout.isLayoutModified()) {
                    showSaveChangesDialog()
                } else {
                    saveXml()
                    finishAfterTransition()
                }
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
        feedbackButtonManager?.loadFabPosition()
    }

    override fun onDestroy() {
        super.onDestroy()
        projectManager.closeProject()
    }

    private fun showXml() {
        val result = XmlLayoutGenerator().generate(binding.editorLayout, true)
        if (result.isEmpty()) {
            showNothingDialog()
        } else {
            finish()
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
        if (success) make(binding.root, getString(string.success_saved_to_gallery))
            .setFadeAnimation()
            .setType(SBUtils.Type.INFO)
            .show()
        else make(binding.root, getString(string.error_failed_to_save_gallery))
            .setFadeAnimation()
            .setType(SBUtils.Type.ERROR)
            .show()
    }

    private fun setToolbarButtonOnClickListener(binding: ActivityLayoutEditorBinding) {
        TooltipCompat.setTooltipText(binding.viewType, getString(string.tooltip_view_type))
        TooltipCompat.setTooltipText(binding.deviceSize, getString(string.tooltip_size))
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

    private fun createAndOpenNewLayout(name: String, layoutContent: String?) {
        val layoutFile = LayoutFile(project.layoutPath + name, project.layoutPath + name)
        layoutFile.saveLayout(layoutContent)
        openLayout(layoutFile)
        // Mark as saved since we just created and saved it
        binding.editorLayout.markAsSaved()
    }

    private fun openLayout(layoutFile: LayoutFile) {
        var contentToParse = layoutFile.readDesignFile()

        if (contentToParse.isNullOrBlank()) {
            val productionContent = layoutFile.readLayoutFile()
            if (!productionContent.isNullOrBlank()) {
                contentToParse = ConvertImportedXml(productionContent).getXmlConverted(this)
                contentToParse?.let { layoutFile.saveDesignFile(it) }
            }
        }

        binding.editorLayout.loadLayoutFromParser(contentToParse)
        project.currentLayout = layoutFile
        supportActionBar!!.subtitle = layoutFile.name
        if (drawerLayout.isDrawerOpen(GravityCompat.START)) {
            drawerLayout.closeDrawer(GravityCompat.START)
        }

        binding.editorLayout.post {
            binding.editorLayout.requestLayout()
            binding.editorLayout.invalidate()
            binding.editorLayout.markAsSaved()
        }

        make(binding.root, getString(string.success_loaded))
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
            createAndOpenNewLayout(
                "${editText.getText().toString().replace(" ", "_").lowercase()}.xml", ""
            )
        }

        val dialog: AlertDialog = builder.create()
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        dialog.show()

        inputLayout.setHint(string.msg_new_layout_name)
        editText.setText(getString(string.default_layout_name))
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
            renameLayout(path, editText, layouts, pos)
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

    private fun renameLayout(
        path: String,
        editText: TextInputEditText,
        layouts: MutableList<LayoutFile>, pos: Int,
    ) {
        val newPath = "${path.substring(0, path.lastIndexOf("/"))}/${
            editText.text.toString().replace(" ", "_").lowercase()
        }.xml"
        layouts[pos].rename(newPath, "test")
        if (layouts[pos] === project.currentLayout) openLayout(layouts[pos])
        layoutAdapter.notifyItemChanged(pos)
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
                ToastUtils.showShort(getString(string.error_cannot_delete_main_layout))
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
        val currentLayoutFile = project.currentLayout as? LayoutFile ?: return

        if (binding.editorLayout.isEmpty()) {
            currentLayoutFile.saveLayout("")
            currentLayoutFile.saveDesignFile("")
            binding.editorLayout.markAsSaved()
            ToastUtils.showShort(getString(string.layout_saved))
            return
        }

        val productionXml = XmlLayoutGenerator().generate(binding.editorLayout, true)
        currentLayoutFile.saveLayout(productionXml)

        // Generate and save the DESIGN-TIME XML for the editor's internal use
        val designXml = XmlLayoutGenerator().generate(binding.editorLayout, false)
        currentLayoutFile.saveDesignFile(designXml)

        binding.editorLayout.markAsSaved()
        ToastUtils.showShort(getString(string.layout_saved))
    }

    private fun showSaveChangesDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.save_changes)
            .setMessage(R.string.msg_save_changes_to_layout)
            .setPositiveButton(R.string.save_changes_and_exit) { _, _ ->
                saveXml()
                finishAfterTransition()
            }
            .setNegativeButton(R.string.discard_changes_and_exit) { _, _ ->
                binding.editorLayout.markAsSaved() // Reset modified flag
                finishAfterTransition()
            }
            .setNeutralButton(R.string.cancel_and_stay_in_editor) { dialog, _ ->
                dialog.dismiss()
            }
            .setCancelable(false)
            .show()
    }

    private fun setupFeedbackButton(){
        feedbackButtonManager = FeedbackButtonManager(this, binding.fabFeedback)
        feedbackButtonManager?.setupDraggableFab()
    }

    companion object {

        const val ACTION_OPEN: String = "org.appdevforall.codeonthego.layouteditor.open"
    }
}