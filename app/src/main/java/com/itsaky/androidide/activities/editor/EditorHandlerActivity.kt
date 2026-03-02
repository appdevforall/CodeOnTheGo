/*
 *  This file is part of AndroidIDE.
 *
 *  AndroidIDE is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  AndroidIDE is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *   along with AndroidIDE.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.itsaky.androidide.activities.editor

import android.content.Intent
import android.os.Bundle
import android.text.TextUtils
import android.util.Log
import android.view.View
import android.view.ViewGroup.LayoutParams
import androidx.collection.MutableIntObjectMap
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.GravityCompat
import androidx.lifecycle.lifecycleScope
import com.blankj.utilcode.util.ImageUtils
import com.google.android.material.tabs.TabLayout
import com.google.gson.Gson
import com.itsaky.androidide.R.string
import com.itsaky.androidide.actions.ActionData
import com.itsaky.androidide.actions.ActionItem
import com.itsaky.androidide.actions.ActionItem.Location.EDITOR_TOOLBAR
import com.itsaky.androidide.actions.ActionsRegistry.Companion.getInstance
import com.itsaky.androidide.actions.build.QuickRunAction
import com.itsaky.androidide.actions.internal.DefaultActionsRegistry
import com.itsaky.androidide.api.ActionContextProvider
import com.itsaky.androidide.app.BaseApplication
import com.itsaky.androidide.app.IDEApplication
import com.itsaky.androidide.databinding.FileActionPopupWindowBinding
import com.itsaky.androidide.databinding.FileActionPopupWindowItemBinding
import com.itsaky.androidide.editor.language.treesitter.JavaLanguage
import com.itsaky.androidide.editor.language.treesitter.JsonLanguage
import com.itsaky.androidide.editor.language.treesitter.KotlinLanguage
import com.itsaky.androidide.editor.language.treesitter.LogLanguage
import com.itsaky.androidide.editor.language.treesitter.TSLanguageRegistry
import com.itsaky.androidide.editor.language.treesitter.XMLLanguage
import com.itsaky.androidide.editor.schemes.IDEColorSchemeProvider
import com.itsaky.androidide.editor.ui.IDEEditor
import com.itsaky.androidide.eventbus.events.editor.DocumentChangeEvent
import com.itsaky.androidide.eventbus.events.file.FileRenameEvent
import com.itsaky.androidide.idetooltips.TooltipManager
import com.itsaky.androidide.idetooltips.TooltipTag
import com.itsaky.androidide.interfaces.IEditorHandler
import com.itsaky.androidide.models.FileExtension
import com.itsaky.androidide.models.OpenedFile
import com.itsaky.androidide.models.OpenedFilesCache
import com.itsaky.androidide.models.Range
import com.itsaky.androidide.models.SaveResult
import com.itsaky.androidide.plugins.manager.fragment.PluginFragmentFactory
import com.itsaky.androidide.plugins.manager.ui.PluginEditorTabManager
import com.itsaky.androidide.projects.ProjectManagerImpl
import com.itsaky.androidide.projects.builder.BuildResult
import com.itsaky.androidide.tasks.executeAsync
import com.itsaky.androidide.ui.CodeEditorView
import com.itsaky.androidide.utils.DialogUtils.newMaterialDialogBuilder
import com.itsaky.androidide.utils.DialogUtils.showConfirmationDialog
import com.itsaky.androidide.utils.IntentUtils.openImage
import com.itsaky.androidide.utils.UniqueNameBuilder
import com.itsaky.androidide.utils.flashSuccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.adfa.constants.CONTENT_KEY
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.function.Consumer

/**
 * Base class for EditorActivity. Handles logic for working with file editors.
 *
 * @author Akash Yadav
 */
open class EditorHandlerActivity :
	ProjectHandlerActivity(),
	IEditorHandler {
	private val singleBuildListeners = CopyOnWriteArrayList<Consumer<BuildResult>>()

	companion object {
		const val PREF_KEY_OPEN_FILES_CACHE = "open_files_cache_v1"
		const val PREF_KEY_OPEN_PLUGIN_TABS = "open_plugin_tabs_v1"
	}

	protected val isOpenedFilesSaved = AtomicBoolean(false)

	private val fileTimestamps = mutableMapOf<String, Long>()

	private val pluginTabIndices = mutableMapOf<String, Int>()
	private val tabIndexToPluginId = mutableMapOf<Int, String>()

	private fun getTabPositionForFileIndex(fileIndex: Int): Int {
		if (fileIndex < 0) return -1
		var tabPos = 0
		var fileCount = 0
		while (tabPos < content.tabs.tabCount) {
			if (!isPluginTab(tabPos)) {
				if (fileCount == fileIndex) return tabPos
				fileCount++
			}
			tabPos++
		}
		return -1
	}

	override fun doOpenFile(
		file: File,
		selection: Range?,
	) {
		openFileAndSelect(file, selection)
	}

	override fun doCloseAll() {
		closeAll {}
	}

	override fun provideCurrentEditor(): CodeEditorView? = getCurrentEditor()

	override fun provideEditorAt(index: Int): CodeEditorView? = getEditorAtIndex(index)

	override fun preDestroy() {
		super.preDestroy()
		TSLanguageRegistry.instance.destroy()
		editorViewModel.removeAllFiles()
	}

	override fun onCreate(savedInstanceState: Bundle?) {
		setupPluginFragmentFactory()
		mBuildEventListener.setActivity(this)
		super.onCreate(savedInstanceState)

		editorViewModel._displayedFile.observe(
			this,
		) { fileIndex ->
			val tabPosition = getTabPositionForFileIndex(fileIndex)
			if (tabPosition >= 0) {
				this.content.editorContainer.displayedChild = tabPosition
			}
		}
		editorViewModel._startDrawerOpened.observe(this) { opened ->
			this.binding.editorDrawerLayout.apply {
				if (opened) openDrawer(GravityCompat.START) else closeDrawer(GravityCompat.START)
			}
		}

		editorViewModel._filesModified.observe(this) { invalidateOptionsMenu() }
		editorViewModel._filesSaving.observe(this) { invalidateOptionsMenu() }

		editorViewModel.observeFiles(this) {
			// rewrite the cached files index if there are any opened files
			val currentFile =
				getCurrentEditor()?.editor?.file?.absolutePath
					?: run {
						editorViewModel.writeOpenedFiles(null)
						editorViewModel.openedFilesCache = null
						return@observeFiles
					}
			getOpenedFiles().also {
				val cache = OpenedFilesCache(currentFile, it)
				editorViewModel.writeOpenedFiles(cache)
				editorViewModel.openedFilesCache = cache
			}
		}

		executeAsync {
			TSLanguageRegistry.instance.registerIfNeeded(JavaLanguage.TS_TYPE, JavaLanguage.FACTORY)
			TSLanguageRegistry.instance.registerIfNeeded(KotlinLanguage.TS_TYPE_KT, KotlinLanguage.FACTORY)
			TSLanguageRegistry.instance.registerIfNeeded(KotlinLanguage.TS_TYPE_KTS, KotlinLanguage.FACTORY)
			TSLanguageRegistry.instance.registerIfNeeded(LogLanguage.TS_TYPE, LogLanguage.FACTORY)
			TSLanguageRegistry.instance.registerIfNeeded(JsonLanguage.TS_TYPE, JsonLanguage.FACTORY)
			TSLanguageRegistry.instance.registerIfNeeded(XMLLanguage.TS_TYPE, XMLLanguage.FACTORY)
			IDEColorSchemeProvider.initIfNeeded()
		}

		optionsMenuInvalidator =
			Runnable {
				prepareOptionsMenu()
			}

		loadPluginTabs()
	}

	override fun onPause() {
		super.onPause()
		// Record timestamps for all currently open files before saving the cache
		val openFiles = editorViewModel.getOpenedFiles()
		lifecycleScope.launch(Dispatchers.IO) {
			openFiles.forEach { file ->
				// Note: Using the file's absolutePath as the key
				fileTimestamps[file.absolutePath] = file.lastModified()
			}
		}
		ActionContextProvider.clearActivity()
		if (!isOpenedFilesSaved.get()) {
			saveOpenedFiles()
			saveOpenedPluginTabs()
			saveAllAsync(notify = false)
		}
	}

	private fun saveOpenedPluginTabs() {
		val prefs = (application as BaseApplication).prefManager
		val openPluginTabIds = pluginTabIndices.keys.toList()
		if (openPluginTabIds.isEmpty()) {
			prefs.putString(PREF_KEY_OPEN_PLUGIN_TABS, null)
			return
		}
		val json = Gson().toJson(openPluginTabIds)
		prefs.putString(PREF_KEY_OPEN_PLUGIN_TABS, json)
		Log.d("EditorHandlerActivity", "Saved open plugin tabs: $openPluginTabIds")
	}

	override fun onResume() {
		super.onResume()
		ActionContextProvider.setActivity(this)
		isOpenedFilesSaved.set(false)
		checkForExternalFileChanges()
		// Invalidate the options menu to reflect any changes
		invalidateOptionsMenu()
	}

	private fun checkForExternalFileChanges() {
		// Get the list of files currently managed by the ViewModel
		val openFiles = editorViewModel.getOpenedFiles()
		if (openFiles.isEmpty() || fileTimestamps.isEmpty()) return

		lifecycleScope.launch(Dispatchers.IO) {
			// Check each open file
			openFiles.forEach { file ->
				val lastKnownTimestamp = fileTimestamps[file.absolutePath] ?: return@forEach
				val currentTimestamp = file.lastModified()

				// If the file on disk is newer.
				if (currentTimestamp > lastKnownTimestamp) {
					val newContent = runCatching { file.readText() }.getOrNull() ?: return@forEach
					withContext(Dispatchers.Main) {
						// If the editor for the new file exists AND has no unsaved changes...
						val editorView = getEditorForFile(file) ?: return@withContext
						if (editorView.isModified) return@withContext

						editorView.editor?.setText(newContent)
						editorView.markAsSaved()
						updateTabs()
					}
				}
			}
		}
	}

	override fun saveOpenedFiles() {
		writeOpenedFilesCache(getOpenedFiles(), getCurrentEditor()?.editor?.file)
	}

	private fun writeOpenedFilesCache(
		openedFiles: List<OpenedFile>,
		selectedFile: File?,
	) {
		val prefs = (application as BaseApplication).prefManager

		if (selectedFile == null || openedFiles.isEmpty()) {
			// If there are no files, clear the saved preference
			prefs.putString(PREF_KEY_OPEN_FILES_CACHE, null)
			log.debug("[onPause] No opened files. Session cache cleared.")
			isOpenedFilesSaved.set(true)
			return
		}

		val cache =
			OpenedFilesCache(selectedFile = selectedFile.absolutePath, allFiles = openedFiles)

		val jsonCache = Gson().toJson(cache)
		prefs.putString(PREF_KEY_OPEN_FILES_CACHE, jsonCache)

		log.debug("[onPause] Editor session saved to SharedPreferences.")
		isOpenedFilesSaved.set(true)
	}

	override fun onStart() {
		super.onStart()

		lifecycleScope.launch {
			try {
				val prefs = (application as BaseApplication).prefManager
				val jsonCache = withContext(Dispatchers.IO) {
					prefs.getString(PREF_KEY_OPEN_FILES_CACHE, null)
				} ?: return@launch

				val cache = withContext(Dispatchers.Default) {
					Gson().fromJson(jsonCache, OpenedFilesCache::class.java)
				}
				onReadOpenedFilesCache(cache)

				// Clear the preference so it's only loaded once on startup
				withContext(Dispatchers.IO) { prefs.putString(PREF_KEY_OPEN_FILES_CACHE, null) }
			} catch (err: Throwable) {
				log.error("Failed to reopen recently opened files", err)
			}
		}

		restoreOpenedPluginTabs()
	}

	private fun restoreOpenedPluginTabs() {
		try {
			val prefs = (application as BaseApplication).prefManager
			val json = prefs.getString(PREF_KEY_OPEN_PLUGIN_TABS, null) ?: return

			val tabIds = Gson().fromJson(json, Array<String>::class.java)?.toList() ?: return
			Log.d("EditorHandlerActivity", "Restoring plugin tabs: $tabIds")

			tabIds.forEach { tabId ->
				if (!pluginTabIndices.containsKey(tabId)) {
					selectPluginTabById(tabId)
				}
			}

			prefs.putString(PREF_KEY_OPEN_PLUGIN_TABS, null)
		} catch (e: Exception) {
			Log.e("EditorHandlerActivity", "Failed to restore plugin tabs", e)
		}
	}

	private fun onReadOpenedFilesCache(cache: OpenedFilesCache?) {
		cache ?: return

		lifecycleScope.launch(Dispatchers.IO) {
			val existingFiles = cache.allFiles.filter { File(it.filePath).exists() }
			val selectedFileExists = File(cache.selectedFile).exists()

			if (existingFiles.isEmpty()) return@launch

			withContext(Dispatchers.Main) {
				if (contentOrNull == null) return@withContext
				existingFiles.forEach { file ->
					openFile(File(file.filePath), file.selection)
				}

				if (selectedFileExists) {
					openFile(File(cache.selectedFile))
				}
			}
		}
	}

	fun prepareOptionsMenu() {
		val registry = getInstance() as DefaultActionsRegistry
		val data = createToolbarActionData()
		content.projectActionsToolbar.clearMenu()

		val actions = getInstance().getActions(EDITOR_TOOLBAR)
		actions.onEachIndexed { index, entry ->
			val action = entry.value
			val isLast = index == actions.size - 1

			action.prepare(data)

			action.icon?.apply {
				colorFilter = action.createColorFilter(data)
				alpha = if (action.enabled) 255 else 76
			}

			content.projectActionsToolbar.addMenuItem(
				icon = action.icon,
				hint = getToolbarContentDescription(action, data),
				onClick = { if (action.enabled) registry.executeAction(action, data) },
				onLongClick = {
					TooltipManager.showIdeCategoryTooltip(
						context = this,
						anchorView = content.projectActionsToolbar,
						tag = action.retrieveTooltipTag(false),
					)
				},
				shouldAddMargin = !isLast,
			)
		}
	}

	private fun createToolbarActionData(): ActionData {
		val data = ActionData.create(this)
		val currentEditor = getCurrentEditor()

		data.put(CodeEditorView::class.java, currentEditor)

		if (currentEditor != null) {
			data.put(IDEEditor::class.java, currentEditor.editor)
			data.put(File::class.java, currentEditor.file)
		}
		return data
	}

	private fun getToolbarContentDescription(action: ActionItem, data: ActionData): String {
		val buildInProgress =
			with(com.itsaky.androidide.actions.build.AbstractCancellableRunAction) {
				this@EditorHandlerActivity.isBuildInProgress()
			}
		if (action.id == QuickRunAction.ID && buildInProgress) {
			return getString(string.cd_toolbar_cancel_build)
		}
		val resId =
			when (action.id) {
				QuickRunAction.ID -> string.cd_toolbar_quick_run
				"ide.editor.syncProject" -> string.cd_toolbar_sync_project
				"ide.editor.build.debug" -> string.cd_toolbar_start_debugger
				"ide.editor.build.runTasks" -> string.cd_toolbar_run_gradle_tasks
				"ide.editor.code.text.undo" -> string.cd_toolbar_undo
				"ide.editor.code.text.redo" -> string.cd_toolbar_redo
				"ide.editor.files.saveAll" -> string.cd_toolbar_save
				"ide.editor.previewLayout" -> string.cd_toolbar_preview_layout
				"ide.editor.find" -> string.cd_toolbar_find
				"ide.editor.find.inFile" -> string.cd_toolbar_find_in_file
				"ide.editor.find.inProject" -> string.cd_toolbar_find_in_project
				"ide.editor.launchInstalledApp" -> string.cd_toolbar_launch_app
				"ide.editor.service.logreceiver.disconnectSenders" ->
					string.cd_toolbar_disconnect_log_senders
				"ide.editor.generatexml" -> string.cd_toolbar_image_to_layout
				else -> null
			}
		return if (resId != null) getString(resId) else action.label
	}

	override fun getCurrentEditor(): CodeEditorView? =
		if (editorViewModel.getCurrentFileIndex() != -1) {
			getEditorAtIndex(editorViewModel.getCurrentFileIndex())
		} else {
			null
		}

	override fun getEditorAtIndex(index: Int): CodeEditorView? {
		val tabPosition = getTabPositionForFileIndex(index)
		if (tabPosition < 0) return null
		val child = _binding?.content?.editorContainer?.getChildAt(tabPosition) ?: return null
		return if (child is CodeEditorView) child else null
	}

	override fun openFileAndSelect(
		file: File,
		selection: Range?,
	) {
		lifecycleScope.launch {
			val editorView = openFile(file, selection)

			editorView?.editor?.also { editor ->
				editor.postInLifecycle {
					if (selection == null) {
						editor.setSelection(0, 0)
						return@postInLifecycle
					}
					editor.validateRange(selection)
					editor.setSelection(selection)
				}
			}
		}
	}

	override suspend fun openFile(
		file: File,
		selection: Range?,
	): CodeEditorView? = withContext(Dispatchers.Main) {
		val range = selection ?: Range.NONE
		val isImage = withContext(Dispatchers.IO) { ImageUtils.isImage(file) }
		if (isImage) {
			openImage(this@EditorHandlerActivity, file)
			return@withContext null
		}

		val fileIndex = openFileAndGetIndex(file, range)
		if (fileIndex < 0) return@withContext null

		editorViewModel.startDrawerOpened = false
		editorViewModel.displayedFileIndex = fileIndex

		val tabPosition = getTabPositionForFileIndex(fileIndex)
		val tab = content.tabs.getTabAt(tabPosition)
		if (tab != null && !tab.isSelected) {
			tab.select()
		}

		return@withContext try {
			getEditorAtIndex(fileIndex)
		} catch (th: Throwable) {
			log.error("Unable to get editor at file index {}", fileIndex, th)
			null
		}
	}

	fun openFileAsync(
		file: File,
		selection: Range? = null,
		onResult: (CodeEditorView?) -> Unit
	) {
		lifecycleScope.launch {
			onResult(openFile(file, selection))
		}
	}

	override fun openFileAndGetIndex(
		file: File,
		selection: Range?,
	): Int {
		val openedFileIndex = findIndexOfEditorByFile(file)
		if (openedFileIndex != -1) {
			return openedFileIndex
		}

		if (!file.exists()) {
			return -1
		}

		val fileIndex = editorViewModel.getOpenedFileCount()
		val tabPosition = getNextFileTabPosition()

		log.info("Opening file at file index {} tab position {} file:{}", fileIndex, tabPosition, file)

		val editor = CodeEditorView(this, file, selection!!)
		editor.layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)

		if (tabPosition >= content.tabs.tabCount) {
			content.tabs.addTab(content.tabs.newTab())
			content.editorContainer.addView(editor)
		} else {
			content.tabs.addTab(content.tabs.newTab(), tabPosition)
			content.editorContainer.addView(editor, tabPosition)
			shiftPluginIndices(tabPosition, 1)
		}

		editorViewModel.addFile(file)
		editorViewModel.setCurrentFile(fileIndex, file)

		updateTabs()

		return fileIndex
	}

	private fun getNextFileTabPosition(): Int {
		var lastFileTabPos = -1
		for (i in 0 until content.tabs.tabCount) {
			if (!isPluginTab(i)) {
				lastFileTabPos = i
			}
		}
		return lastFileTabPos + 1
	}

	private fun shiftPluginIndices(
		fromPosition: Int,
		delta: Int,
	) {
		val shifted = mutableMapOf<String, Int>()
		pluginTabIndices.forEach { (id, index) ->
			val newIndex = if (index >= fromPosition) index + delta else index
			if (newIndex >= 0) {
				shifted[id] = newIndex
			}
		}

		pluginTabIndices.clear()
		pluginTabIndices.putAll(shifted)

		tabIndexToPluginId.clear()
		shifted.forEach { (id, index) ->
			tabIndexToPluginId[index] = id
		}

		Log.d("EditorHandlerActivity", "Updated plugin indices after shift: $pluginTabIndices")
	}

	override fun getEditorForFile(file: File): CodeEditorView? {
		val content = contentOrNull ?: return null
		for (i in 0 until content.editorContainer.childCount) {
			val child = content.editorContainer.getChildAt(i)
			if (child is CodeEditorView && file == child.file) {
				return child
			}
		}
		return null
	}

	override fun findIndexOfEditorByFile(file: File?): Int {
		if (file == null) {
			log.error("Cannot find index of a null file.")
			return -1
		}

		for (i in 0 until editorViewModel.getOpenedFileCount()) {
			val opened: File = editorViewModel.getOpenedFile(i)
			if (opened == file) {
				return i
			}
		}

		return -1
	}

	override fun saveAllAsync(
		notify: Boolean,
		requestSync: Boolean,
		processResources: Boolean,
		progressConsumer: ((Int, Int) -> Unit)?,
		runAfter: (() -> Unit)?,
	) {
		lifecycleScope.launch(Dispatchers.IO) {
			withContext(NonCancellable) {
				saveAll(notify, requestSync, processResources, progressConsumer)
			}
			withContext(Dispatchers.Main) {
				runAfter?.invoke()
			}
		}
	}

	override suspend fun saveAll(
		notify: Boolean,
		requestSync: Boolean,
		processResources: Boolean,
		progressConsumer: ((Int, Int) -> Unit)?,
	): Boolean {
		val result = saveAllResult(progressConsumer)

		// don't bother to switch the context if we don't need to
		if (notify || (result.gradleSaved && requestSync)) {
			withContext(Dispatchers.Main) {
				if (contentOrNull == null) return@withContext
				if (notify) {
					flashSuccess(string.all_saved)
				}

				if (result.gradleSaved && requestSync) {
					editorViewModel.isSyncNeeded = true
				}
			}
		}

		if (processResources) {
			ProjectManagerImpl.getInstance().generateSources()
		}

		return result.gradleSaved
	}

	override suspend fun saveAllResult(progressConsumer: ((Int, Int) -> Unit)?): SaveResult {
		return performFileSave {
			val result = SaveResult()
			for (i in 0 until editorViewModel.getOpenedFileCount()) {
				saveResultInternal(i, result)
				progressConsumer?.invoke(i + 1, editorViewModel.getOpenedFileCount())
			}

			return@performFileSave result
		}
	}

	override suspend fun saveResult(
		index: Int,
		result: SaveResult,
	) {
		performFileSave {
			saveResultInternal(index, result)
		}
	}

	private suspend fun saveResultInternal(
		index: Int,
		result: SaveResult,
	): Boolean {
		if (index < 0 || index >= editorViewModel.getOpenedFileCount()) {
			return false
		}

		val frag = getEditorAtIndex(index) ?: return false
		val fileName = frag.file?.name ?: return false

		run {
			// Must be called before frag.save()
			// Otherwise, it'll always return false
			val modified = frag.isModified
			if (!frag.save()) {
				return false
			}

			val isGradle = fileName.endsWith(".gradle") || fileName.endsWith(".gradle.kts")
			val isXml: Boolean = fileName.endsWith(".xml")
			if (!result.gradleSaved) {
				result.gradleSaved = modified && isGradle
			}

			if (!result.xmlSaved) {
				result.xmlSaved = modified && isXml
			}
		}

		val hasUnsaved = hasUnsavedFiles()

		withContext(Dispatchers.Main) {
			val content = contentOrNull ?: return@withContext
			editorViewModel.areFilesModified = hasUnsaved

			// set tab as unmodified
			val tabPosition = getTabPositionForFileIndex(index)
			if (tabPosition < 0) return@withContext
			val tab = content.tabs.getTabAt(tabPosition) ?: return@withContext
			val text = tab.text?.toString() ?: return@withContext
			if (text.startsWith('*')) {
				tab.text = text.substring(1)
			}
		}

		return true
	}

	private fun hasUnsavedFiles() =
		editorViewModel.getOpenedFiles().any { file ->
			getEditorForFile(file)?.isModified == true
		}

	private suspend inline fun <T : Any?> performFileSave(crossinline action: suspend () -> T): T {
		setFilesSaving(true)
		try {
			return action()
		} finally {
			setFilesSaving(false)
		}
	}

	private suspend fun setFilesSaving(saving: Boolean) {
		withContext(Dispatchers.Main.immediate) {
			editorViewModel.areFilesSaving = saving
		}
	}

	override fun areFilesModified(): Boolean = editorViewModel.areFilesModified

	override fun areFilesSaving(): Boolean = editorViewModel.areFilesSaving

	override fun closeFile(
		index: Int,
		runAfter: () -> Unit,
	) {
		if (index < 0 || index >= editorViewModel.getOpenedFileCount()) {
			log.error("Invalid file index. Cannot close.")
			return
		}

		val opened = editorViewModel.getOpenedFile(index)
		log.info("Closing file: {}", opened)

		val editor = getEditorAtIndex(index)
		if (editor?.isModified == true) {
			log.info("File has been modified: {}", opened)
			notifyFilesUnsaved(listOf(editor)) {
				closeFile(index, runAfter)
			}
			return
		}

		editor?.close() ?: run {
			log.error("Cannot save file before close. Editor instance is null")
		}

		val tabPosition = getTabPositionForFileIndex(index)
		editorViewModel.removeFile(index)

		if (tabPosition >= 0) {
			content.tabs.removeTabAt(tabPosition)
			content.editorContainer.removeViewAt(tabPosition)
			shiftPluginIndices(tabPosition + 1, -1)
		}

		editorViewModel.areFilesModified = hasUnsavedFiles()
		updateTabs()
		runAfter()
	}

	override fun closeOthers() {
		if (editorViewModel.getOpenedFileCount() == 0) {
			return
		}

		val unsavedFiles =
			editorViewModel
				.getOpenedFiles()
				.map(::getEditorForFile)
				.filter { it != null && it.isModified }

		if (unsavedFiles.isNotEmpty()) {
			notifyFilesUnsaved(unsavedFiles) { closeOthers() }
			return
		}

		val file = editorViewModel.getCurrentFile()
		var index = 0

		// keep closing the file at index 0
		// if openedFiles[0] == file, then keep closing files at index 1
		while (editorViewModel.getOpenedFileCount() != 1) {
			val editor = getEditorAtIndex(index)

			if (editor == null) {
				log.error("Unable to save file at index {}", index)
				continue
			}

			// Index of files changes as we keep close files
			// So we compare the files instead of index
			if (file != editor.file) {
				closeFile(index)
			} else {
				index = 1
			}
		}
	}

	override fun openFAQActivity(htmlData: String) {
		val intent = Intent(this, FAQActivity::class.java)
		intent.putExtra(CONTENT_KEY, htmlData)
		startActivity(intent)
	}

	override fun closeAll(runAfter: () -> Unit) {
		val unsavedFiles =
			editorViewModel
				.getOpenedFiles()
				.map(this::getEditorForFile)
				.filter { it != null && it.isModified }

		if (unsavedFiles.isNotEmpty()) {
			// If there are unsaved files, show the confirmation dialog.
			notifyFilesUnsaved(unsavedFiles) { closeAll(runAfter) }
			return
		}

		// If there are NO unsaved files, just perform the close action directly.
		// The 'manualFinish' is false because this action doesn't exit the activity by itself.
		performCloseAllFiles(manualFinish = false)
		runAfter()
	}

	override fun getOpenedFiles() =
		editorViewModel.getOpenedFiles().mapNotNull {
			val editor = getEditorForFile(it)?.editor ?: return@mapNotNull null
			OpenedFile(it.absolutePath, editor.cursorLSPRange)
		}

	fun closeCurrentFile() {
		val tabPosition = content.tabs.selectedTabPosition

		if (isPluginTab(tabPosition)) {
			closePluginTab(tabPosition)
			return
		}

		val fileIndex = getFileIndexForTabPosition(tabPosition)
		if (fileIndex >= 0) {
			closeFile(fileIndex) {
				invalidateOptionsMenu()
			}
		}
	}

	private fun closePluginTab(tabPosition: Int) {
		val pluginId = tabIndexToPluginId[tabPosition] ?: return

		try {
			val fragment = supportFragmentManager.findFragmentByTag("plugin_tab_$pluginId")
			if (fragment != null) {
				supportFragmentManager
					.beginTransaction()
					.remove(fragment)
					.commitAllowingStateLoss()
			}

			val tabManager = PluginEditorTabManager.getInstance()
			tabManager.closeTab(pluginId)
		} catch (e: Exception) {
			Log.e("EditorHandlerActivity", "Error cleaning up plugin tab $pluginId", e)
		}

		content.tabs.removeTabAt(tabPosition)
		content.editorContainer.removeViewAt(tabPosition)

		pluginTabIndices.remove(pluginId)
		tabIndexToPluginId.remove(tabPosition)

		shiftPluginIndices(tabPosition + 1, -1)
		updateTabVisibility()

		invalidateOptionsMenu()
		Log.d("EditorHandlerActivity", "Successfully closed plugin tab: $pluginId")
	}

	private fun notifyFilesUnsaved(
		unsavedEditors: List<CodeEditorView?>,
		invokeAfter: Runnable,
	) {
		if (isDestroying) {
			// Do not show unsaved files dialog if the activity is being destroyed
			// TODO Use a service to save files and to avoid file content loss
			for (editor in unsavedEditors) {
				editor?.markUnmodified()
			}
			invokeAfter.run()
			return
		}

		val mapped = unsavedEditors.mapNotNull { it?.file?.absolutePath }
		val builder =
			showConfirmationDialog(
				context = this,
				title = getString(string.title_files_unsaved),
				message = getString(string.msg_files_unsaved, TextUtils.join("\n", mapped)),
				positiveClickListener = { dialog, _ ->
					dialog.dismiss()
					saveAllAsync(notify = true, runAfter = { runOnUiThread(invokeAfter) })
				},
			) { dialog, _ ->
				dialog.dismiss()
				// Mark all the files as saved, then try to close them all
				for (editor in unsavedEditors) {
					editor?.markAsSaved()
				}
				invokeAfter.run()
			}
		builder.show()
	}

	@Subscribe(threadMode = ThreadMode.MAIN)
	fun onFileRenamed(event: FileRenameEvent) {
		val content = contentOrNull ?: return
		val index = findIndexOfEditorByFile(event.file)
		if (index < 0 || index >= content.tabs.tabCount) {
			return
		}

		val editor = getEditorAtIndex(index) ?: return
		editorViewModel.updateFile(index, event.newFile)
		editor.updateFile(event.newFile)

		updateTabs()
	}

	@Subscribe(threadMode = ThreadMode.MAIN)
	fun onDocumentChange(event: DocumentChangeEvent) {
		if (contentOrNull == null) return
		editorViewModel.areFilesModified = true

		val fileIndex = findIndexOfEditorByFile(event.file.toFile())
		if (fileIndex == -1) return

		val tabPosition = getTabPositionForFileIndex(fileIndex)
		if (tabPosition < 0) return

		val tab = content.tabs.getTabAt(tabPosition) ?: return
		if (tab.text?.startsWith('*') == true) return

		tab.text = "*${tab.text}"
	}

	private fun updateTabs() {
		editorActivityScope.launch {
			val files = editorViewModel.getOpenedFiles()
			val dupliCount = mutableMapOf<String, Int>()
			val names = MutableIntObjectMap<Pair<String, Int>>()
			val nameBuilder = UniqueNameBuilder<File>("", File.separator)

			files.forEach {
				var count = dupliCount[it.name] ?: 0
				dupliCount[it.name] = ++count
				nameBuilder.addPath(it, it.path)
			}

			for (index in 0 until content.tabs.tabCount) {
				val file = files.getOrNull(index) ?: continue
				val count = dupliCount[file.name] ?: 0

				val isModified = getEditorAtIndex(index)?.isModified ?: false
				var name = if (count > 1) nameBuilder.getShortPath(file) else file.name
				if (isModified) {
					name = "*$name"
				}

				names[index] = name to FileExtension.Factory.forFile(file, file.isDirectory).icon
			}

			withContext(Dispatchers.Main) {
				val content = contentOrNull ?: return@withContext
				names.forEach { index, (name, iconId) ->
					val tab = content.tabs.getTabAt(index) ?: return@forEach
					tab.icon = ResourcesCompat.getDrawable(resources, iconId, theme)
					tab.text = name
					tab.view.setOnLongClickListener {
						TooltipManager.showIdeCategoryTooltip(
							context = this@EditorHandlerActivity,
							anchorView = tab.view,
							tag = TooltipTag.PROJECT_FILENAME,
						)
						true
					}
				}
			}
		}
	}

	/**
	 * Adds a one-time listener that will be invoked when the current build process finishes.
	 * The listener will be automatically removed after being called.
	 */
	fun addOneTimeBuildResultListener(listener: Consumer<BuildResult>) {
		singleBuildListeners.add(listener)
	}

	/**
	 * Called by [EditorBuildEventListener] to notify all registered listeners of the build result.
	 */
	fun notifyBuildResult(result: BuildResult) {
		// Ensure this runs on the main thread if UI updates are needed from listeners
		runOnUiThread {
			singleBuildListeners.forEach { it.accept(result) }
			singleBuildListeners.clear()
		}
	}

	fun selectPluginTabById(tabId: String): Boolean {
		Log.d("EditorHandlerActivity", "selectPluginTabById called with tabId: $tabId")
		Log.d("EditorHandlerActivity", "Available plugin tab indices: $pluginTabIndices")
		Log.d("EditorHandlerActivity", "Available plugin tab keys: ${pluginTabIndices.keys.toList()}")
		Log.d("EditorHandlerActivity", "Total plugin tabs loaded: ${pluginTabIndices.size}")

		// Check if the tab already exists
		val existingTabIndex = pluginTabIndices[tabId]
		if (existingTabIndex != null) {
			Log.d("EditorHandlerActivity", "Plugin tab $tabId already exists at index $existingTabIndex")
			val tab = content.tabs.getTabAt(existingTabIndex)
			if (tab != null && !tab.isSelected) {
				tab.select()
			}
			return true
		}

		// If tab doesn't exist, create it now
		Log.d("EditorHandlerActivity", "Plugin tab $tabId not found, creating it now...")
		return createPluginTab(tabId)
	}

	private fun createPluginTab(tabId: String): Boolean {
		try {
			val pluginManager =
				IDEApplication.getPluginManager() ?: run {
					Log.w("EditorHandlerActivity", "Plugin manager not available")
					return false
				}

			val tabManager = PluginEditorTabManager.getInstance()
			tabManager.loadPluginTabs(pluginManager)

			val pluginTabs = tabManager.getAllPluginTabs()
			val pluginTab =
				pluginTabs.find { it.id == tabId } ?: run {
					Log.w("EditorHandlerActivity", "Plugin tab $tabId not found in available tabs")
					return false
				}

			Log.d("EditorHandlerActivity", "Creating UI tab for plugin: ${pluginTab.id} (${pluginTab.title})")

			runOnUiThread {
				val content = contentOrNull ?: return@runOnUiThread

				val tab = content.tabs.newTab()
				tab.text = pluginTab.title

				val iconRes = pluginTab.icon
				if (iconRes != null) {
					tab.icon = ResourcesCompat.getDrawable(resources, iconRes, theme)
				}

				val tabIndex = content.tabs.tabCount
				content.tabs.addTab(tab)

				val containerView =
					android.widget.FrameLayout(this@EditorHandlerActivity).apply {
						id = android.view.View.generateViewId()
						layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
					}
				content.editorContainer.addView(containerView)

				pluginTabIndices[pluginTab.id] = tabIndex
				tabIndexToPluginId[tabIndex] = pluginTab.id

				Log.d("EditorHandlerActivity", "Plugin tab ${pluginTab.id} created at index $tabIndex")

				// Load the plugin fragment into the container
				val fragment = tabManager.getOrCreateTabFragment(pluginTab.id)
				if (fragment != null) {
					val fragmentManager = supportFragmentManager
					val transaction = fragmentManager.beginTransaction()
					transaction.add(containerView.id, fragment, "plugin_tab_${pluginTab.id}")
					transaction.commitAllowingStateLoss()
					Log.d("EditorHandlerActivity", "Plugin fragment added to container for tab: ${pluginTab.id}")
				} else {
					Log.w("EditorHandlerActivity", "Failed to create fragment for plugin tab: ${pluginTab.id}")
				}

				if (!tab.isSelected) {
					tab.select()
				}
				editorViewModel.displayedFileIndex = -1
				updateTabVisibility()

				Log.d("EditorHandlerActivity", "Successfully created and selected plugin tab: ${pluginTab.id}")
			}

			return true
		} catch (e: Exception) {
			Log.e("EditorHandlerActivity", "Failed to create plugin tab $tabId", e)
			return false
		}
	}

	private fun setupPluginFragmentFactory() {
		try {
			val defaultFactory = supportFragmentManager.fragmentFactory
			supportFragmentManager.fragmentFactory = PluginFragmentFactory(defaultFactory)
			Log.d("EditorHandlerActivity", "PluginFragmentFactory installed")
		} catch (e: Exception) {
			Log.e("EditorHandlerActivity", "Failed to setup PluginFragmentFactory", e)
		}
	}

	fun loadPluginTabs() {
		try {
			val pluginManager =
				IDEApplication.getPluginManager() ?: run {
					Log.w("EditorHandlerActivity", "Plugin manager not available, skipping plugin tab loading")
					return
				}

			val tabManager = PluginEditorTabManager.getInstance()
			tabManager.loadPluginTabs(pluginManager)

			val pluginTabs = tabManager.getAllPluginTabs()

			if (pluginTabs.isEmpty()) {
				Log.d("EditorHandlerActivity", "No plugin tabs to load")
				return
			}
		} catch (e: Exception) {
			Log.e("EditorHandlerActivity", "Failed to load plugin tabs", e)
		}
	}

	fun isPluginTab(position: Int): Boolean {
		if (position < 0 || position >= content.tabs.tabCount) {
			return false
		}
		val result = tabIndexToPluginId.containsKey(position)
		return result
	}

	fun getPluginTabId(position: Int): String? = tabIndexToPluginId[position]

	private fun canClosePluginTab(position: Int): Boolean {
		val pluginId = tabIndexToPluginId[position] ?: return false
		val tabManager = PluginEditorTabManager.getInstance()
		return tabManager.canCloseTab(pluginId)
	}

	fun updateTabVisibility() {
		val hasFiles = editorViewModel.getOpenedFileCount() > 0
		val hasPluginTabs = pluginTabIndices.isNotEmpty()

		content.apply {
			if (!hasFiles && !hasPluginTabs) {
				tabs.visibility = View.GONE
				viewContainer.displayedChild = 1
			} else {
				tabs.visibility = View.VISIBLE
				viewContainer.displayedChild = 0
			}
		}
	}

	/**
	 * Converts tab position to actual file index, accounting for plugin tabs.
	 * Plugin tabs don't have corresponding file indices.
	 */
	fun getFileIndexForTabPosition(tabPosition: Int): Int {
		if (isPluginTab(tabPosition)) {
			return -1 // Plugin tabs don't have file indices
		}

		// Count how many plugin tabs come before this position
		var pluginTabsBefore = 0
		for (i in 0 until tabPosition) {
			if (isPluginTab(i)) {
				pluginTabsBefore++
			}
		}

		// The file index is the tab position minus the plugin tabs before it
		return tabPosition - pluginTabsBefore
	}

	fun showPluginTabPopup(tab: TabLayout.Tab) {
		val anchorView = tab.view ?: return

		// Check if this plugin tab can actually be closed
		val position = tab.position
		if (!canClosePluginTab(position)) {
			return
		}

		val binding =
			FileActionPopupWindowBinding.inflate(
				android.view.LayoutInflater.from(this),
				null,
				false,
			)

		val popupWindow =
			android.widget
				.PopupWindow(
					binding.root,
					LayoutParams.WRAP_CONTENT,
					LayoutParams.WRAP_CONTENT,
				).apply {
					elevation = 2f
					isOutsideTouchable = true
				}

		val closeItem =
			FileActionPopupWindowItemBinding
				.inflate(
					android.view.LayoutInflater.from(this),
					null,
					false,
				).root

		closeItem.apply {
			text = "Close Tab"
			setOnClickListener {
				val position = tab.position
				if (isPluginTab(position)) {
					closePluginTab(position)
				}
				popupWindow.dismiss()
			}
		}

		binding.root.addView(closeItem)
		popupWindow.showAsDropDown(anchorView, 0, 0)
	}

	override fun doConfirmProjectClose() {
		confirmProjectClose()
	}

	private fun performCloseAllFiles(manualFinish: Boolean) {
		// Close all open file editors
		val fileCount = editorViewModel.getOpenedFileCount()
		for (i in 0 until fileCount) {
			getEditorAtIndex(i)?.close()
		}

		// Close all plugin tabs
		val pluginTabIds = this.pluginTabIndices.keys.toList()
		for (pluginId in pluginTabIds) {
			val tabIndex = this.pluginTabIndices[pluginId]
			if (tabIndex != null) {
				this.closePluginTab(tabIndex)
			}
		}

		editorViewModel.removeAllFiles()
		content.apply {
			tabs.removeAllTabs()
			editorContainer.removeAllViews()
		}

		if (manualFinish) {
			finish()
		}
	}

	private fun confirmProjectClose() {
		val content = contentOrNull ?: return
		val builder = newMaterialDialogBuilder(this)
		builder.setTitle(string.title_confirm_project_close)
		builder.setMessage(string.msg_confirm_project_close)

		builder.setNegativeButton(string.cancel_project_text, null)

		// OPTION 1: Close without saving
		builder.setNeutralButton(string.close_without_saving) { dialog, _ ->
			dialog.dismiss()

			for (i in 0 until editorViewModel.getOpenedFileCount()) {
				(content.editorContainer.getChildAt(i) as? CodeEditorView)?.editor?.markUnmodified()
			}

			performCloseAllFiles(manualFinish = true)
		}

		// OPTION 2: Save and close
		builder.setPositiveButton(string.save_and_close) { dialog, _ ->
			dialog.dismiss()

			saveAllAsync(notify = false) {

				runOnUiThread {
					if (contentOrNull == null) return@runOnUiThread
					performCloseAllFiles(manualFinish = true)
				}
				recentProjectsViewModel.updateProjectModifiedDate(
					editorViewModel.getProjectName(),
				)
			}
		}

		builder.show()
	}
}
