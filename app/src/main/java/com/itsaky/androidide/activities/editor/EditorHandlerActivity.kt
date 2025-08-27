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
import android.view.ViewGroup.LayoutParams
import androidx.collection.MutableIntObjectMap
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.GravityCompat
import com.blankj.utilcode.util.ImageUtils
import com.google.gson.Gson
import com.itsaky.androidide.R.string
import com.itsaky.androidide.actions.ActionData
import com.itsaky.androidide.actions.ActionItem.Location.EDITOR_TOOLBAR
import com.itsaky.androidide.actions.ActionsRegistry.Companion.getInstance
import com.itsaky.androidide.actions.internal.DefaultActionsRegistry
import com.itsaky.androidide.api.ActionContextProvider
import com.itsaky.androidide.app.BaseApplication
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
import com.itsaky.androidide.idetooltips.IDETooltipItem
import com.itsaky.androidide.idetooltips.TooltipManager
import com.itsaky.androidide.interfaces.IEditorHandler
import com.itsaky.androidide.models.FileExtension
import com.itsaky.androidide.models.OpenedFile
import com.itsaky.androidide.models.OpenedFilesCache
import com.itsaky.androidide.models.Range
import com.itsaky.androidide.models.SaveResult
import com.itsaky.androidide.projects.ProjectManagerImpl
import com.itsaky.androidide.projects.builder.BuildResult
import com.itsaky.androidide.tasks.executeAsync
import com.itsaky.androidide.ui.CodeEditorView
import com.itsaky.androidide.utils.DialogUtils.newYesNoDialog
import com.itsaky.androidide.utils.IntentUtils.openImage
import com.itsaky.androidide.utils.UniqueNameBuilder
import com.itsaky.androidide.utils.flashSuccess
import kotlinx.coroutines.Dispatchers
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
open class EditorHandlerActivity : ProjectHandlerActivity(), IEditorHandler {

    private val singleBuildListeners = CopyOnWriteArrayList<Consumer<BuildResult>>()

    companion object {
        const val PREF_KEY_OPEN_FILES_CACHE = "open_files_cache_v1"
    }

    protected val isOpenedFilesSaved = AtomicBoolean(false)

    private val fileTimestamps = mutableMapOf<String, Long>()

    override fun doOpenFile(file: File, selection: Range?) {
        openFileAndSelect(file, selection)
    }

    override fun doCloseAll(runAfter: () -> Unit) {
        closeAll(runAfter)
    }

    override fun provideCurrentEditor(): CodeEditorView? {
        return getCurrentEditor()
    }

    override fun provideEditorAt(index: Int): CodeEditorView? {
        return getEditorAtIndex(index)
    }

    override fun preDestroy() {
        super.preDestroy()
        TSLanguageRegistry.instance.destroy()
        editorViewModel.removeAllFiles()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        mBuildEventListener.setActivity(this)
        super.onCreate(savedInstanceState)

        editorViewModel._displayedFile.observe(
            this
        ) { this.content.editorContainer.displayedChild = it }
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
            TSLanguageRegistry.instance.register(JavaLanguage.TS_TYPE, JavaLanguage.FACTORY)
            TSLanguageRegistry.instance.register(KotlinLanguage.TS_TYPE_KT, KotlinLanguage.FACTORY)
            TSLanguageRegistry.instance.register(KotlinLanguage.TS_TYPE_KTS, KotlinLanguage.FACTORY)
            TSLanguageRegistry.instance.register(LogLanguage.TS_TYPE, LogLanguage.FACTORY)
            TSLanguageRegistry.instance.register(JsonLanguage.TS_TYPE, JsonLanguage.FACTORY)
            TSLanguageRegistry.instance.register(XMLLanguage.TS_TYPE, XMLLanguage.FACTORY)
            IDEColorSchemeProvider.initIfNeeded()
        }

        optionsMenuInvalidator = Runnable {
            prepareOptionsMenu()
        }

    }

    override fun onPause() {
        super.onPause()
        // Record timestamps for all currently open files before saving the cache
        editorViewModel.getOpenedFiles().forEach { file ->
            // Note: Using the file's absolutePath as the key
            fileTimestamps[file.absolutePath] = file.lastModified()
        }
        ActionContextProvider.clearActivity()
        if (!isOpenedFilesSaved.get()) {
            saveOpenedFiles()
        }
    }

    override fun onResume() {
        super.onResume()
        ActionContextProvider.setActivity(this)
        isOpenedFilesSaved.set(false)
        prepareOptionsMenu()
        checkForExternalFileChanges()
    }

    private fun checkForExternalFileChanges() {
        // Get the list of files currently managed by the ViewModel
        val openFiles = editorViewModel.getOpenedFiles()
        if (openFiles.isEmpty() || fileTimestamps.isEmpty()) return

        // Check each open file
        openFiles.forEach { file ->
            val lastKnownTimestamp = fileTimestamps[file.absolutePath] ?: return@forEach
            val currentTimestamp = file.lastModified()
            val editorView = getEditorForFile(file)

            // If the file on disk is newer AND the editor for it exists AND has no unsaved changes...
            if (currentTimestamp > lastKnownTimestamp && editorView != null && !editorView.isModified) {
                val newContent = file.readText()
                editorView.editor?.post {
                    editorView.editor?.setText(newContent)
                    editorView.markAsSaved()
                    updateTabs()
                }
            }
        }
    }

    override fun saveOpenedFiles() {
        writeOpenedFilesCache(getOpenedFiles(), getCurrentEditor()?.editor?.file)
    }

    private fun writeOpenedFilesCache(openedFiles: List<OpenedFile>, selectedFile: File?) {
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

        try {
            val prefs = (application as BaseApplication).prefManager
            val jsonCache = prefs.getString(PREF_KEY_OPEN_FILES_CACHE, null)
            if (jsonCache != null) {
                val cache = Gson().fromJson(jsonCache, OpenedFilesCache::class.java)
                onReadOpenedFilesCache(cache)

                // Clear the preference so it's only loaded once on startup
                prefs.putString(PREF_KEY_OPEN_FILES_CACHE, null)
            }
        } catch (err: Throwable) {
            log.error("Failed to reopen recently opened files", err)
        }
    }

    private fun onReadOpenedFilesCache(cache: OpenedFilesCache?) {
        cache ?: return

        val existingFiles = cache.allFiles.filter { File(it.filePath).exists() }
        val selectedFileExists = File(cache.selectedFile).exists()

        if (existingFiles.isEmpty()) return

        existingFiles.forEach { file ->
            openFile(File(file.filePath), file.selection)
        }

        if (selectedFileExists) {
            openFile(File(cache.selectedFile))
        }
    }

    fun prepareOptionsMenu() {
        val registry = getInstance() as DefaultActionsRegistry
        val data = createToolbarActionData()
        content.customToolbar.clearMenu()

        val actions = getInstance().getActions(EDITOR_TOOLBAR)
        actions.onEachIndexed { index, entry ->
            val action = entry.value
            val isLast = index == actions.size - 1

            action.prepare(data)

            action.icon?.apply {
                colorFilter = action.createColorFilter(data)
                alpha = if (action.enabled) 255 else 76
            }

            content.customToolbar.addMenuItem(
                icon = action.icon,
                hint = action.label,
                onClick = { if (action.enabled) registry.executeAction(action, data) },
                shouldAddMargin = !isLast
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

    override fun getCurrentEditor(): CodeEditorView? {
        return if (editorViewModel.getCurrentFileIndex() != -1) {
            getEditorAtIndex(editorViewModel.getCurrentFileIndex())
        } else null
    }

    override fun getEditorAtIndex(index: Int): CodeEditorView? {
        return _binding?.content?.editorContainer?.getChildAt(index) as CodeEditorView?
    }

    override fun openFileAndSelect(file: File, selection: Range?) {
        openFile(file, selection)

        getEditorForFile(file)?.editor?.also { editor ->
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

    override fun openFile(file: File, selection: Range?): CodeEditorView? {
        val range = selection ?: Range.NONE
        if (ImageUtils.isImage(file)) {
            openImage(this, file)
            return null
        }

        val index = openFileAndGetIndex(file, range)
        val tab = content.tabs.getTabAt(index)
        if (tab != null && index >= 0 && !tab.isSelected) {
            tab.select()
        }

        editorViewModel.startDrawerOpened = false
        editorViewModel.displayedFileIndex = index

        return try {
            getEditorAtIndex(index)
        } catch (th: Throwable) {
            log.error("Unable to get editor fragment at opened file index {}", index, th)
            null
        }
    }

    override fun openFileAndGetIndex(file: File, selection: Range?): Int {
        val openedFileIndex = findIndexOfEditorByFile(file)
        if (openedFileIndex != -1) {
            return openedFileIndex
        }

        if (!file.exists()) {
            return -1
        }

        val position = editorViewModel.getOpenedFileCount()

        log.info("Opening file at index {} file:{}", position, file)

        val editor = CodeEditorView(this, file, selection!!)
        editor.layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)

        content.editorContainer.addView(editor)
        content.tabs.addTab(content.tabs.newTab())

        editorViewModel.addFile(file)
        editorViewModel.setCurrentFile(position, file)

        updateTabs()

        return position
    }

    override fun getEditorForFile(file: File): CodeEditorView? {
        for (i in 0 until editorViewModel.getOpenedFileCount()) {
            val editor = content.editorContainer.getChildAt(i) as? CodeEditorView
            if (file == editor?.file) return editor
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
        runAfter: (() -> Unit)?
    ) {
        editorActivityScope.launch {
            saveAll(notify, requestSync, processResources, progressConsumer)
            runAfter?.invoke()
        }
    }

    override suspend fun saveAll(
        notify: Boolean,
        requestSync: Boolean,
        processResources: Boolean,
        progressConsumer: ((Int, Int) -> Unit)?
    ): Boolean {
        val result = saveAllResult(progressConsumer)

        // don't bother to switch the context if we don't need to
        if (notify || (result.gradleSaved && requestSync)) {
            withContext(Dispatchers.Main) {
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

    override suspend fun saveResult(index: Int, result: SaveResult) {
        performFileSave {
            saveResultInternal(index, result)
        }
    }

    private suspend fun saveResultInternal(
        index: Int,
        result: SaveResult
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

            editorViewModel.areFilesModified = hasUnsaved

            // set tab as unmodified
            val tab = content.tabs.getTabAt(index) ?: return@withContext
            if (tab.text!!.startsWith('*')) {
                tab.text = tab.text!!.substring(startIndex = 1)
            }
        }

        return true
    }

    private fun hasUnsavedFiles() = editorViewModel.getOpenedFiles().any { file ->
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

    override fun areFilesModified(): Boolean {
        return editorViewModel.areFilesModified
    }

    override fun areFilesSaving(): Boolean {
        return editorViewModel.areFilesSaving
    }

    override fun closeFile(index: Int, runAfter: () -> Unit) {
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

        editorViewModel.removeFile(index)
        content.apply {
            tabs.removeTabAt(index)
            editorContainer.removeViewAt(index)
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
            editorViewModel.getOpenedFiles().map(::getEditorForFile)
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

    override suspend fun getTooltipData(category: String, tag: String): IDETooltipItem? {
        return withContext(Dispatchers.IO) {
            TooltipManager.getTooltip(this@EditorHandlerActivity, category, tag)
        }
    }

    override fun closeAll(runAfter: () -> Unit) {
        val count = editorViewModel.getOpenedFileCount()
        val unsavedFiles =
            editorViewModel.getOpenedFiles().map(this::getEditorForFile)
                .filter { it != null && it.isModified }

        if (unsavedFiles.isNotEmpty()) {
            // There are unsaved files
            notifyFilesUnsaved(unsavedFiles) { closeAll(runAfter) }
            return
        }

        // Files were already saved, close all files one by one
        for (i in 0 until count) {
            getEditorAtIndex(i)?.close() ?: run {
                log.error("Unable to close file at index {}", i)
            }
        }

        editorViewModel.removeAllFiles()
        content.apply {
            tabs.removeAllTabs()
            tabs.requestLayout()
            editorContainer.removeAllViews()
        }

        runAfter()
    }

    override fun getOpenedFiles() =
        editorViewModel.getOpenedFiles().mapNotNull {
            val editor = getEditorForFile(it)?.editor ?: return@mapNotNull null
            OpenedFile(it.absolutePath, editor.cursorLSPRange)
        }

    fun closeCurrentFile() {
        content.tabs.selectedTabPosition.let { index ->
            closeFile(index) {
                invalidateOptionsMenu()
            }
        }
    }

    private fun notifyFilesUnsaved(unsavedEditors: List<CodeEditorView?>, invokeAfter: Runnable) {
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
            newYesNoDialog(
                context = this,
                title = getString(string.title_files_unsaved),
                message = getString(string.msg_files_unsaved, TextUtils.join("\n", mapped)),
                positiveClickListener = { dialog, _ ->
                    dialog.dismiss()
                    saveAllAsync(notify = true, runAfter = { runOnUiThread(invokeAfter) })
                }
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
        // update content modification status
        editorViewModel.areFilesModified = true

        val index = findIndexOfEditorByFile(event.file.toFile())
        if (index == -1) {
            return
        }

        val tab = content.tabs.getTabAt(index)!!
        if (tab.text?.startsWith('*') == true) {
            return
        }

        // mark as modified
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
                    name = "*${name}"
                }

                names[index] = name to FileExtension.Factory.forFile(file).icon
            }

            withContext(Dispatchers.Main) {
                names.forEach { index, (name, iconId) ->
                    val tab = content.tabs.getTabAt(index) ?: return@forEach
                    tab.icon = ResourcesCompat.getDrawable(resources, iconId, theme)
                    tab.text = name
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
}
