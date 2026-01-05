package org.appdevforall.codeonthego.layouteditor.activities

import android.annotation.SuppressLint
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.appcompat.view.menu.MenuBuilder
import androidx.appcompat.widget.PopupMenu
import androidx.appcompat.widget.TooltipCompat
import androidx.core.view.GravityCompat
import androidx.core.view.isEmpty
import androidx.drawerlayout.widget.DrawerLayout
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.blankj.utilcode.util.ToastUtils
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.itsaky.androidide.FeedbackButtonManager
import com.itsaky.androidide.activities.editor.HelpActivity
import com.itsaky.androidide.idetooltips.TooltipCategory
import com.itsaky.androidide.idetooltips.TooltipManager
import com.itsaky.androidide.utils.getCreatedTime
import com.itsaky.androidide.utils.getLastModifiedTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.adfa.constants.CONTENT_KEY
import org.adfa.constants.CONTENT_TITLE_KEY
import org.appdevforall.codeonthego.layouteditor.BaseActivity
import org.appdevforall.codeonthego.layouteditor.LayoutFile
import org.appdevforall.codeonthego.layouteditor.ProjectFile
import org.appdevforall.codeonthego.layouteditor.R
import org.appdevforall.codeonthego.layouteditor.R.string
import org.appdevforall.codeonthego.layouteditor.adapters.LayoutListAdapter
import org.appdevforall.codeonthego.layouteditor.adapters.PaletteListAdapter
import org.appdevforall.codeonthego.layouteditor.databinding.ActivityLayoutEditorBinding
import org.appdevforall.codeonthego.layouteditor.editor.DesignEditor
import org.appdevforall.codeonthego.layouteditor.editor.DeviceConfiguration
import org.appdevforall.codeonthego.layouteditor.editor.DeviceSize
import org.appdevforall.codeonthego.layouteditor.editor.convert.ConvertImportedXml
import org.appdevforall.codeonthego.layouteditor.managers.DrawableManager
import org.appdevforall.codeonthego.layouteditor.managers.IdManager.clear
import org.appdevforall.codeonthego.layouteditor.managers.PreferencesManager
import org.appdevforall.codeonthego.layouteditor.managers.ProjectManager
import org.appdevforall.codeonthego.layouteditor.managers.UndoRedoManager
import org.appdevforall.codeonthego.layouteditor.tools.XmlLayoutGenerator
import org.appdevforall.codeonthego.layouteditor.utils.BitmapUtil.createBitmapFromView
import org.appdevforall.codeonthego.layouteditor.utils.Constants
import org.appdevforall.codeonthego.layouteditor.utils.FileCreator
import org.appdevforall.codeonthego.layouteditor.utils.FilePicker
import org.appdevforall.codeonthego.layouteditor.utils.FileUtil
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
	private var originalProductionXml: String? = null
	private var originalDesignXml: String? = null

	private val onBackPressedCallback =
		object : OnBackPressedCallback(true) {
			override fun handleOnBackPressed() {
				when {
					drawerLayout.isDrawerOpen(GravityCompat.START) ||
						drawerLayout.isDrawerOpen(
							GravityCompat.END,
						) -> {
						drawerLayout.closeDrawers()
					}

					binding.editorLayout.isLayoutModified() -> {
						showSaveChangesDialog()
					}

					else -> {
						lifecycleScope.launch {
							saveXml()
							finishAfterTransition()
						}
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

		// todo extract file_key to layouteditor constants and use it in both modules.
		// todo extract replace 0 date with actual value.
		// todo extract replace activity_main date with actual value.
		doubleArgSafeLet(
			intent.getStringExtra(Constants.EXTRA_KEY_FILE_PATH),
			intent.getStringExtra(Constants.EXTRA_KEY_LAYOUT_FILE_NAME),
		) { filePath, fileName ->
			supportActionBar?.title = getString(string.loading_project)
			lifecycleScope.launch {
				val createdAt = getCreatedTime(filePath).toString()
				val modifiedAt = getLastModifiedTime(filePath).toString()

				try {
					projectManager.openProject(
						ProjectFile(
							filePath,
							createdAt,
							modifiedAt,
							this@EditorActivity,
							mainLayoutName = fileName
						)
					)
					project = projectManager.openedProject!!
					invalidateOptionsMenu()
					androidToDesignConversion(
						Uri.fromFile(File(projectManager.openedProject?.mainLayout?.path ?: "")),
					)

					supportActionBar?.title = project.name
					layoutAdapter = LayoutListAdapter(project)
					binding.editorLayout.setBackgroundColor(
						Utils.getSurfaceColor(this@EditorActivity)
					)
				} catch (e: Exception) {
					Log.e("EditorActivity", "Error loading project", e)
					Toast.makeText(
						this@EditorActivity,
						getString(string.msg_error_opening_project),
						Toast.LENGTH_SHORT
					).show()
					finish()
				}
			}
		} ?: showNothingDialog()
	}

	private fun defineXmlPicker() {
		xmlPicker =
			object : FilePicker(this) {
				override fun onPickFile(uri: Uri?) {
					val path = uri?.path
					if (path != null && path.endsWith(".xml")) {
						androidToDesignConversion(uri)
					} else {
						Toast
							.makeText(
								this@EditorActivity,
								getString(string.error_invalid_xml_file),
								Toast.LENGTH_SHORT,
							).show()
					}
				}
			}
	}

	private fun androidToDesignConversion(uri: Uri?) {
		if (uri == null) {
			Toast.makeText(
				this@EditorActivity,
				getString(string.error_invalid_xml_file),
				Toast.LENGTH_SHORT
			).show()
			return
		}

		lifecycleScope.launch {
			try {
				val fileName = FileUtil.getLastSegmentFromPath(uri.path ?: "")

				if (!fileName.endsWith(".xml", ignoreCase = true)) {
					Toast.makeText(
						this@EditorActivity,
						getString(string.error_invalid_xml_file),
						Toast.LENGTH_SHORT
					).show()
					return@launch
				}

				val xml = withContext(Dispatchers.IO) {
					FileUtil.readFromUri(uri, this@EditorActivity)
				} ?: run {
					make(binding.root, getString(string.error_failed_to_import))
						.setSlideAnimation()
						.showAsError()
					return@launch
				}

				val xmlConverted = withContext(Dispatchers.Default) {
					ConvertImportedXml(xml).getXmlConverted(this@EditorActivity)
				}

				if (xmlConverted == null) {
					make(binding.root, getString(string.error_failed_to_import))
						.setSlideAnimation()
						.showAsError()
					return@launch
				}

				val productionPath = project.layoutPath + fileName
				val designPath = project.layoutDesignPath + fileName
				withContext(Dispatchers.IO) {
					FileUtil.writeFile(productionPath, xml)
					FileUtil.writeFile(designPath, xmlConverted)
				}

				openLayout(LayoutFile(productionPath, designPath))
				make(binding.root, getString(string.success_imported))
					.setFadeAnimation()
					.showAsSuccess()
			} catch (t: Throwable) {
				make(binding.root, getString(string.error_failed_to_import))
					.setSlideAnimation()
					.showAsError()
			}
		}
	}

	private fun defineFileCreator() {
		fileCreator =
			object : FileCreator(this) {
				override fun onCreateFile(uri: Uri) {
					val result = XmlLayoutGenerator().generate(binding.editorLayout, true)

					if (FileUtil.saveFile(this@EditorActivity, uri, result)) {
						make(
							binding.root,
							getString(string.success_saved),
						).setSlideAnimation()
							.showAsSuccess()
					} else {
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
				this,
				drawerLayout,
				binding.topAppBar,
				string.palette,
				string.palette,
			)

		(drawerLayout as CustomDrawerLayout).addDrawerListener(actionBarDrawerToggle!!)
		actionBarDrawerToggle!!.syncState()
		(drawerLayout as CustomDrawerLayout).addDrawerListener(
			object : DrawerLayout.SimpleDrawerListener() {
				override fun onDrawerStateChanged(state: Int) {
					super.onDrawerStateChanged(state)
					undoRedo!!.updateButtons()
				}

				override fun onDrawerSlide(
					v: View,
					slideOffset: Float,
				) {
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
			},
		)
	}

	private fun setupStructureView() {
		binding.editorLayout.setStructureView(binding.structureView)

		binding.structureView.apply {
			onItemClickListener = {
				binding.editorLayout.showDefinedAttributes(it)
				drawerLayout.closeDrawer(GravityCompat.END)
			}
			onItemLongClickListener = { view ->
				TooltipManager.showTooltip(
					context = this@EditorActivity,
					anchorView = view,
					category = TooltipCategory.CATEGORY_XML,
					tag = view.javaClass.superclass.name,
				)
			}
		}
	}

	@SuppressLint("SetTextI18n")
	private fun setupDrawerNavigationRail() {
		val helpFab =
			binding.paletteNavigation.headerView?.findViewById<FloatingActionButton>(R.id.help_fab)

		// Set tooltip text for help FAB
		TooltipCompat.setTooltipText(helpFab as View, getString(string.help))

		val paletteMenu = binding.paletteNavigation.menu
		paletteMenu
			.add(Menu.NONE, 0, Menu.NONE, Constants.TAB_TITLE_COMMON)
			.setIcon(R.drawable.android)
		paletteMenu
			.add(Menu.NONE, 1, Menu.NONE, Constants.TAB_TITLE_TEXT)
			.setIcon(R.mipmap.ic_palette_text_view)
		paletteMenu
			.add(Menu.NONE, 2, Menu.NONE, Constants.TAB_TITLE_BUTTONS)
			.setIcon(R.mipmap.ic_palette_button)
		paletteMenu
			.add(Menu.NONE, 3, Menu.NONE, Constants.TAB_TITLE_WIDGETS)
			.setIcon(R.mipmap.ic_palette_view)
		paletteMenu
			.add(Menu.NONE, 4, Menu.NONE, Constants.TAB_TITLE_LAYOUTS)
			.setIcon(R.mipmap.ic_palette_relative_layout)
		paletteMenu
			.add(Menu.NONE, 5, Menu.NONE, Constants.TAB_TITLE_CONTAINERS)
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
				} catch (e: Exception) {
					Toast
						.makeText(
							this,
							"${getString(string.error_failed_to_load_palette)}: ${e.message}",
							Toast.LENGTH_SHORT,
						).show()
				}
				true
			}
			replaceListViewAdapter(adapter)
		} catch (e: Exception) {
			Toast
				.makeText(
					this,
					"${getString(string.error_failed_to_initialize_palette)}: ${e.message}",
					Toast.LENGTH_SHORT,
				).show()
		}

		helpFab.setOnClickListener {
			val intent =
				Intent(this, HelpActivity::class.java).apply {
					putExtra(CONTENT_KEY, getString(R.string.layout_editor_url))
					putExtra(
						CONTENT_TITLE_KEY,
						getString(R.string.back_to_cogo),
					)
				}
			this.startActivity(intent)
		}
		clear()
	}

	private fun replaceListViewAdapter(adapter: RecyclerView.Adapter<*>) {
		binding.listView.adapter = adapter
	}

	private fun isProjectReady(): Boolean {
		if (!::project.isInitialized) {
			ToastUtils.showShort(getString(R.string.loading_project))
			return false
		}
		return true
	}

	override fun onOptionsItemSelected(item: MenuItem): Boolean {
		val id = item.itemId
		undoRedo!!.updateButtons()
		if (actionBarDrawerToggle!!.onOptionsItemSelected(item)) return true

		when (id) {
			R.id.resources_manager,
			R.id.preview,
			R.id.export_xml,
			R.id.export_as_image,
			R.id.save_xml,
			R.id.exit_editor,
			R.id.edit_xml -> {
				if (!isProjectReady()) return false
			}
		}

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
						.putExtra(Constants.EXTRA_KEY_PROJECT, project),
				)
				return true
			}

			R.id.preview -> {
				val result = XmlLayoutGenerator().generate(binding.editorLayout, true)
				if (result.isEmpty()) {
					showNothingDialog()
				} else {
					lifecycleScope.launch {
						saveXml()
						startActivity(
							Intent(this@EditorActivity, PreviewLayoutActivity::class.java)
								.putExtra(Constants.EXTRA_KEY_LAYOUT, project.currentLayout),
						)
					}
				}
				return true
			}

			R.id.export_xml -> {
				val uri = Uri.fromFile(File(project.currentLayout.path))
				val result = XmlLayoutGenerator().generate(binding.editorLayout, true)

				if (FileUtil.saveFile(this@EditorActivity, uri, result)) {
					binding.editorLayout.markAsSaved()
					make(binding.root, getString(string.success_saved))
						.setSlideAnimation()
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
				if (binding.editorLayout.getChildAt(0) != null) {
					showSaveMessage(
						Utils.saveBitmapAsImageToGallery(
							this,
							createBitmapFromView(binding.editorLayout),
							project.name,
						),
					)
				} else {
					make(binding.root, getString(string.info_add_some_views))
						.setFadeAnimation()
						.setType(SBUtils.Type.INFO)
						.show()
				}
				return true
			}

			R.id.save_xml -> {
				MaterialAlertDialogBuilder(this@EditorActivity)
					.setTitle(string.save)
					.setMessage(string.save_layout_message)
					.setCancelable(false)
					.setNeutralButton(string.cancel) { d, _ ->
						d.cancel()
					}.setNegativeButton(string.export_as_image) { d, _ ->
						if (binding.editorLayout.getChildAt(0) != null) {
							showSaveMessage(
								Utils.saveBitmapAsImageToGallery(
									this,
									createBitmapFromView(binding.editorLayout),
									project.name,
								),
							)
						} else {
							make(binding.root, getString(string.info_add_some_views))
								.setFadeAnimation()
								.setType(SBUtils.Type.INFO)
								.show()
						}
					}.setPositiveButton(string.export_layout) { _, _ ->
						val uri = Uri.fromFile(File(project.currentLayout.path))
						val result = XmlLayoutGenerator().generate(binding.editorLayout, true)

						if (FileUtil.saveFile(this@EditorActivity, uri, result)) {
							binding.editorLayout.markAsSaved()
							make(binding.root, getString(string.success_saved))
								.setSlideAnimation()
								.showAsSuccess()
						} else {
							make(binding.root, getString(string.error_failed_to_save))
								.setSlideAnimation()
								.showAsError()
							FileUtil.deleteFile(
								FileUtil.convertUriToFilePath(
									this@EditorActivity,
									uri,
								),
							)
						}
					}.show()
				return true
			}

			R.id.exit_editor -> {
				if (binding.editorLayout.isLayoutModified()) {
					showSaveChangesDialog()
				} else {
					lifecycleScope.launch {
						saveXml()
						finishAfterTransition()
					}
				}
				return true
			}

			else -> return false
		}
	}

	override fun onPrepareOptionsMenu(menu: Menu): Boolean {
		val isReady = ::project.isInitialized
		menu.findItem(R.id.resources_manager)?.isEnabled = isReady
		menu.findItem(R.id.preview)?.isEnabled = isReady
		menu.findItem(R.id.export_xml)?.isEnabled = isReady
		menu.findItem(R.id.export_as_image)?.isEnabled = isReady
		menu.findItem(R.id.save_xml)?.isEnabled = isReady
		menu.findItem(R.id.edit_xml)?.isEnabled = isReady
		menu.findItem(R.id.exit_editor)?.isEnabled = isReady
		return super.onPrepareOptionsMenu(menu)
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
        if (::project.isInitialized) {
            DrawableManager.loadFromFiles(project.drawables)
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
			lifecycleScope.launch {
				saveXml()
				finish()
			}
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
		if (success) {
			make(binding.root, getString(string.success_saved_to_gallery))
				.setFadeAnimation()
				.setType(SBUtils.Type.INFO)
				.show()
		} else {
			make(binding.root, getString(string.error_failed_to_save_gallery))
				.setFadeAnimation()
				.setType(SBUtils.Type.ERROR)
				.show()
		}
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

  private suspend fun openLayout(layoutFile: LayoutFile) {
    val ioResult = withContext(Dispatchers.IO) {
      PreferencesManager.getInstance(this@EditorActivity).warmUp()
      val production = layoutFile.readLayoutFile()
      var design = layoutFile.readDesignFile()

      if (design.isNullOrBlank() && !production.isNullOrBlank()) {
        val converted = withContext(Dispatchers.Default) {
					ConvertImportedXml(production).getXmlConverted(this@EditorActivity)
				}
        if (!converted.isNullOrBlank()) {
          layoutFile.saveDesignFile(converted)
          design = converted
        }
      }
      Triple(production, design, layoutFile.name)
    }
    originalProductionXml = ioResult.first
    originalDesignXml = ioResult.second

		val contentToParse = ioResult.second
		binding.editorLayout.loadLayoutFromParser(contentToParse)

		project.currentLayout = layoutFile
		supportActionBar?.subtitle = ioResult.third

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

	private fun currentLayoutFileOrNull(): LayoutFile? =
		project.currentLayout as? LayoutFile

	private fun restoreOriginalXmlIfNeeded() {
		val xmlToRestore = originalDesignXml ?: originalProductionXml
		if (!xmlToRestore.isNullOrBlank()) {
			binding.editorLayout.loadLayoutFromParser(xmlToRestore)
		}
	}

	/**
	 * Writes the current editor state to disk.
	 * - Generates XML on the current thread (UI-safe)
	 * - Performs file I/O on Dispatchers.IO
	 * - No UI side-effects (no toast / no markAsSaved) to keep it reusable
	 */
	private suspend fun persistEditorLayout(layoutFile: LayoutFile): Boolean {
		return runCatching {
			if (binding.editorLayout.isEmpty()) {
				withContext(Dispatchers.IO) {
					layoutFile.saveLayout("")
					layoutFile.saveDesignFile("")
				}
				return@runCatching
			}

			val generator = XmlLayoutGenerator()
			val productionXml = generator.generate(binding.editorLayout, true)
			val designXml = generator.generate(binding.editorLayout, false)

			withContext(Dispatchers.IO) {
				layoutFile.saveLayout(productionXml)
				layoutFile.saveDesignFile(designXml)
			}
		}.isSuccess
	}

	private suspend fun saveXml() {
		val layoutFile = currentLayoutFileOrNull() ?: return

		val success = persistEditorLayout(layoutFile)
		if (!success) {
			withContext(Dispatchers.Main) {
				ToastUtils.showShort(getString(string.failed_to_save_layout))
			}
			return
		}

		binding.editorLayout.markAsSaved()
		ToastUtils.showShort(getString(string.layout_saved))
	}

	private fun showSaveChangesDialog() {
		MaterialAlertDialogBuilder(this)
			.setTitle(R.string.save_changes)
			.setMessage(R.string.msg_save_changes_to_layout)
			.setPositiveButton(R.string.save_changes_and_exit) { _, _ ->
				lifecycleScope.launch {
					saveXml()
					finishAfterTransition()
				}
			}.setNegativeButton(R.string.discard_changes_and_exit) { _, _ ->
				lifecycleScope.launch {
					val layoutFile = currentLayoutFileOrNull() ?: run {
						finishAfterTransition()
						return@launch
					}
					restoreOriginalXmlIfNeeded()
					persistEditorLayout(layoutFile)
					finishAfterTransition()
				}
			}.setNeutralButton(R.string.cancel_and_stay_in_editor) { dialog, _ ->
				dialog.dismiss()
			}.setCancelable(false)
			.show()
	}

	private fun setupFeedbackButton() {
		feedbackButtonManager = FeedbackButtonManager(this, binding.fabFeedback)
		feedbackButtonManager?.setupDraggableFab()
	}

}
