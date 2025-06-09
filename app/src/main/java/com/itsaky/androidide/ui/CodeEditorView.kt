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

package com.itsaky.androidide.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import androidx.appcompat.widget.LinearLayoutCompat
import androidx.core.view.isVisible
import com.blankj.utilcode.util.SizeUtils
import com.itsaky.androidide.activities.editor.BaseEditorActivity
import com.itsaky.androidide.app.BaseApplication
import com.itsaky.androidide.editor.api.IEditor
import com.itsaky.androidide.editor.databinding.LayoutCodeEditorBinding
import com.itsaky.androidide.editor.events.FileUpdateEvent
import com.itsaky.androidide.editor.events.LanguageUpdateEvent
import com.itsaky.androidide.editor.language.IDELanguage
import com.itsaky.androidide.editor.ui.EditorSearchLayout
import com.itsaky.androidide.editor.ui.IDEEditor
import com.itsaky.androidide.editor.ui.IDEEditor.Companion.createInputTypeFlags
import com.itsaky.androidide.editor.utils.ContentReadWrite.readContent
import com.itsaky.androidide.editor.utils.ContentReadWrite.writeTo
import com.itsaky.androidide.eventbus.events.preferences.PreferenceChangeEvent
import com.itsaky.androidide.lsp.BreakpointHandler
import com.itsaky.androidide.lsp.IDEDebugClientImpl
import com.itsaky.androidide.lsp.IDELanguageClientImpl
import com.itsaky.androidide.lsp.api.ILanguageServer
import com.itsaky.androidide.lsp.api.ILanguageServerRegistry
import com.itsaky.androidide.lsp.java.JavaLanguageServer
import com.itsaky.androidide.lsp.xml.XMLLanguageServer
import com.itsaky.androidide.models.Range
import com.itsaky.androidide.preferences.internal.EditorPreferences
import com.itsaky.androidide.syntax.colorschemes.SchemeAndroidIDE
import com.itsaky.androidide.tasks.cancelIfActive
import com.itsaky.androidide.tasks.runOnUiThread
import com.itsaky.androidide.utils.customOrJBMono
import io.github.rosemoe.sora.event.ClickEvent
import io.github.rosemoe.sora.event.InterceptTarget
import io.github.rosemoe.sora.text.Content
import io.github.rosemoe.sora.text.LineSeparator
import io.github.rosemoe.sora.util.IntPair
import io.github.rosemoe.sora.widget.CodeEditor
import io.github.rosemoe.sora.widget.REGION_LINE_NUMBER
import io.github.rosemoe.sora.widget.component.Magnifier
import io.github.rosemoe.sora.widget.resolveTouchRegion
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.newSingleThreadContext
import kotlinx.coroutines.withContext
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode
import org.slf4j.LoggerFactory
import java.io.Closeable
import java.io.File

/**
 * A view that handles opened code editor.
 *
 * @author Akash Yadav
 */
@SuppressLint("ViewConstructor")
class CodeEditorView(
  context: Context,
  file: File,
  selection: Range
) : LinearLayoutCompat(context), BreakpointHandler.EventListener, Closeable {

  private var _binding: LayoutCodeEditorBinding? = null
  private var _searchLayout: EditorSearchLayout? = null

  private val codeEditorScope = CoroutineScope(
    Dispatchers.Default + CoroutineName("CodeEditorView"))

  /**
   * The [CoroutineContext][kotlin.coroutines.CoroutineContext] used to reading and writing the file
   * in this editor. We use a separate, single-threaded context assuming that the file will be either
   * read from or written to at a time, but not both. If in future we add support for anything like
   * that, the number of thread should probably be increased.
   */
  @OptIn(DelicateCoroutinesApi::class, ExperimentalCoroutinesApi::class)
  private val readWriteContext = newSingleThreadContext("CodeEditorView")

  private val binding: LayoutCodeEditorBinding
    get() = checkNotNull(_binding) { "Binding has been destroyed" }

  private val searchLayout: EditorSearchLayout
    get() = checkNotNull(_searchLayout) { "Search layout has been destroyed" }

  /**
   * Get the file of this editor.
   */
  val file: File?
    get() = editor?.file

  /**
   * Get the [IDEEditor] instance of this editor view.
   */
  val editor: IDEEditor?
    get() = _binding?.editor

  /**
   * Returns whether the content of the editor has been modified.
   *
   * @see IDEEditor.isModified
   */
  val isModified: Boolean
    get() = editor?.isModified ?: false

  companion object {

    private val log = LoggerFactory.getLogger(CodeEditorView::class.java)
  }

  init {
    IDEDebugClientImpl.breakpoints.addListener(this)
    _binding = LayoutCodeEditorBinding.inflate(LayoutInflater.from(context))

    binding.editor.apply {
      isHighlightCurrentBlock = true
      props.autoCompletionOnComposing = true
      dividerWidth = SizeUtils.dp2px(2f).toFloat()
      colorScheme = SchemeAndroidIDE.newInstance(context)
      lineSeparator = LineSeparator.LF

      subscribeEvent(ClickEvent::class.java) { event, unsubscribe ->
        // if the editor is not backed by a file, then there's no point in adding a breakpoint
        val editorFile = this.file ?: return@subscribeEvent
        val region = IntPair.getFirst(resolveTouchRegion(event.causingEvent))
        if (region == REGION_LINE_NUMBER) {
          val language = editorLanguage as? IDELanguage? ?: return@subscribeEvent
          val server = languageServer ?: return@subscribeEvent
          if (server.debugAdapter != null) {
            event.intercept(InterceptTarget.TARGET_EDITOR)

            // If we already have a breakpoint added, we won't have received this event
            // this is because the click is consumed by the SideIconClickEvent for the breakpoint would have consumed this event
            // as a result, it's safe to assume that there aren't any breakpoints on this line
            IDEDebugClientImpl.toggleBreakpoint(editorFile, event.line)
            language.toggleBreakpoint(event.line)
            postInvalidate()
          }
        }
      }

      subscribeEvent(LanguageUpdateEvent::class.java) { event, unsubscribe ->
        this.file?.also { file ->
          resetBreakpointsInFile(file)
        }
      }

      subscribeEvent(FileUpdateEvent::class.java) { event, unsubscribe ->
        this.file?.also { file ->
          resetBreakpointsInFile(file)
        }
      }
    }

    _searchLayout = EditorSearchLayout(context, binding.editor)
    orientation = VERTICAL

    removeAllViews()
    addView(binding.root, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
    addView(searchLayout, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

    readFileAndApplySelection(file, selection)
  }

  override fun onHighlightLine(file: String, line: Int) {
    if (file != this.file?.canonicalPath) {
      return
    }

    (editor?.editorLanguage as? IDELanguage?)?.apply {
      unhighlightLines()
      highlightLine(line)
    }
  }

  override fun onUnhighlight() {
    (editor?.editorLanguage as? IDELanguage?)?.apply {
      unhighlightLines()
    }
  }

  private fun resetBreakpointsInFile(file: File) {
    val handler = IDEDebugClientImpl.breakpoints
    val breakpoints = handler.breakpointsInFile(file.canonicalPath)
    val highlightedLine = handler.highlightedLocation?.takeIf { it.first == file.canonicalPath }?.second
    editor?.apply {
      (editorLanguage as? IDELanguage?)?.apply {
        removeAllBreakpoints()
        addBreakpoints(breakpoints.map { it.line })
        unhighlightLines()
        if (highlightedLine != null) {
          highlightLine(highlightedLine)
        }
      }
    }
  }

  /**
   * Get the file of this editor. Throws [IllegalStateException] if no file is available.
   */
  fun requireFile(): File {
    return checkNotNull(file)
  }

  /**
   * Update the file of this editor. This only updates the file reference of the editor and does
   * not resets the content.
   */
  fun updateFile(file: File) {
    val editor = _binding?.editor ?: return
    editor.file = file
    postRead(file)
  }

  /**
   * Called when the editor has been selected and is visible to the user.
   */
  fun onEditorSelected() {
    _binding?.editor?.onEditorSelected() ?: run {
      log.warn("onEditorSelected() called but no editor instance is available")
    }
  }

  /**
   * Begins search mode and shows the [search layout][EditorSearchLayout].
   */
  fun beginSearch() {
    if (_binding == null || _searchLayout == null) {
      log.warn(
        "Editor layout is null content=$binding, searchLayout=$searchLayout")
      return
    }

    searchLayout.beginSearchMode()
  }

  /**
   * Mark this files as saved. Even if it not saved.
   */
  fun markAsSaved() {
    editor?.markUnmodified()
  }

  /**
   * Saves the content of the editor to the editor's file.
   *
   * @return Whether the save operation was successfully completed or not. If this method returns `false`,
   * it means that there was an error saving the file or the content of the file was not modified and
   * hence the save operation was skipped.
   */
  suspend fun save(): Boolean {
    val file = this.file ?: return false

    if (!isModified && file.exists()) {
      log.info("File was not modified. Skipping save operation for file {}", file.name)
      return false
    }

    val text = _binding?.editor?.text ?: run {
      log.error("Failed to save file. Unable to retrieve the content of editor as it is null.")
      return false
    }

    withContext(Dispatchers.Main.immediate) {

      withEditingDisabled {
        withContext(readWriteContext) {
          // Do not call suspend functions in this scope
          // the writeTo function acquires lock to the Content object before writing and releases
          // the lock after writing
          // if there are any suspend function calls in between, the lock and unlock calls might not
          // be called on the same thread
          text.writeTo(file, this@CodeEditorView::updateReadWriteProgress)
        }
      }

      _binding?.rwProgress?.isVisible = false
    }

    markUnmodified()
    notifySaved()

    return true
  }

  private fun updateReadWriteProgress(progress: Int) {
    val binding = this.binding
    runOnUiThread {
      if (binding.rwProgress.isVisible && (progress < 0 || progress >= 100)) {
        binding.rwProgress.isVisible = false
        return@runOnUiThread
      }

      if (!binding.rwProgress.isVisible) {
        binding.rwProgress.isVisible = true
      }

      binding.rwProgress.progress = progress
    }
  }

  private inline fun <R : Any?> withEditingDisabled(action: () -> R): R {
    return try {
      _binding?.editor?.isEditable = false
      action()
    } finally {
      _binding?.editor?.isEditable = true
    }
  }

  private fun readFileAndApplySelection(file: File, selection: Range) {
    codeEditorScope.launch(Dispatchers.Main.immediate) {
      updateReadWriteProgress(0)

      withEditingDisabled {

        val content = withContext(readWriteContext) {
          selection.validate()
          file.readContent(this@CodeEditorView::updateReadWriteProgress)
        }

        initializeContent(content, file, selection)
        _binding?.rwProgress?.isVisible = false
      }
    }
  }

  private fun initializeContent(content: Content, file: File, selection: Range) {
    val ideEditor = binding.editor
    ideEditor.postInLifecycle {
      val args = Bundle().apply {
        putString(IEditor.KEY_FILE, file.absolutePath)
      }

      ideEditor.setText(content, args)

      // editor.setText(...) sets the modified flag to true
      // but in this case, file is read from disk and hence the contents are not modified at all
      // so the flag must be changed to unmodified
      // TODO: Find a better way to check content modification status
      markUnmodified()
      postRead(file)

      ideEditor.validateRange(selection)
      ideEditor.setSelection(selection)

      configureEditorIfNeeded()
    }
  }

  private fun postRead(file: File) {
    binding.editor.setupLanguage(file)
    binding.editor.setLanguageServer(createLanguageServer(file))

    if (IDELanguageClientImpl.isInitialized()) {
      binding.editor.setLanguageClient(IDELanguageClientImpl.getInstance())
    }

    // File must be set only after setting the language server
    // This will make sure that textDocument/didOpen is sent
    binding.editor.file = file

    // do not pass this editor instance
    // symbol input must be updated for the current editor
    (context as? BaseEditorActivity?)?.refreshSymbolInput()
    (context as? Activity?)?.invalidateOptionsMenu()
  }

  private fun createLanguageServer(file: File): ILanguageServer? {
    if (!file.isFile) {
      return null
    }

    val serverID: String = when (file.extension) {
      "java" -> JavaLanguageServer.SERVER_ID
      "xml" -> XMLLanguageServer.SERVER_ID
      else -> return null
    }

    return ILanguageServerRegistry.getDefault().getServer(serverID)
  }

  private fun configureEditorIfNeeded() {
    onCustomFontPrefChanged()
    onFontSizePrefChanged()
    onFontLigaturesPrefChanged()
    onPrintingFlagsPrefChanged()
    onInputTypePrefChanged()
    onWordwrapPrefChanged()
    onMagnifierPrefChanged()
    onUseIcuPrefChanged()
    onDeleteEmptyLinesPrefChanged()
    onDeleteTabsPrefChanged()
    onStickyScrollEnabeldPrefChanged()
    onPinLineNumbersPrefChanged()
  }

  private fun onMagnifierPrefChanged() {
    binding.editor.getComponent(Magnifier::class.java).isEnabled = EditorPreferences.useMagnifier
  }

  private fun onWordwrapPrefChanged() {
    val enabled = EditorPreferences.wordwrap
    binding.editor.isWordwrap = enabled
  }

  private fun onInputTypePrefChanged() {
    binding.editor.inputType = createInputTypeFlags()
  }

  private fun onPrintingFlagsPrefChanged() {
    var flags = 0
    if (EditorPreferences.drawLeadingWs) {
      flags = flags or CodeEditor.FLAG_DRAW_WHITESPACE_LEADING
    }
    if (EditorPreferences.drawTrailingWs) {
      flags = flags or CodeEditor.FLAG_DRAW_WHITESPACE_TRAILING
    }
    if (EditorPreferences.drawInnerWs) {
      flags = flags or CodeEditor.FLAG_DRAW_WHITESPACE_INNER
    }
    if (EditorPreferences.drawEmptyLineWs) {
      flags = flags or CodeEditor.FLAG_DRAW_WHITESPACE_FOR_EMPTY_LINE
    }
    if (EditorPreferences.drawLineBreak) {
      flags = flags or CodeEditor.FLAG_DRAW_LINE_SEPARATOR
    }
    binding.editor.nonPrintablePaintingFlags = flags
  }

  private fun onFontLigaturesPrefChanged() {
    val enabled = EditorPreferences.fontLigatures
    binding.editor.isLigatureEnabled = enabled
  }

  private fun onFontSizePrefChanged() {
    var textSize = EditorPreferences.fontSize
    if (textSize < 6 || textSize > 32) {
      textSize = 14f
    }
    binding.editor.setTextSize(textSize)
  }

  private fun onUseIcuPrefChanged() {
    binding.editor.props.useICULibToSelectWords = EditorPreferences.useIcu
  }

  private fun onCustomFontPrefChanged() {
    val state = EditorPreferences.useCustomFont
    binding.editor.typefaceText = customOrJBMono(state)
    binding.editor.typefaceLineNumber = customOrJBMono(state)
  }

  private fun onDeleteEmptyLinesPrefChanged() {
    binding.editor.props.deleteEmptyLineFast = EditorPreferences.deleteEmptyLines
  }

  private fun onDeleteTabsPrefChanged() {
    binding.editor.props.deleteMultiSpaces = if (EditorPreferences.deleteTabsOnBackspace) -1 else 1
  }

  private fun onStickyScrollEnabeldPrefChanged() {
    binding.editor.props.stickyScroll = EditorPreferences.stickyScrollEnabled
  }

  private fun onPinLineNumbersPrefChanged() {
    binding.editor.setPinLineNumber(EditorPreferences.pinLineNumbers)
  }

  /**
   * For internal use only!
   *
   *
   * Marks this editor as unmodified. Used only when the activity is being destroyed.
   */
  internal fun markUnmodified() {
    binding.editor.markUnmodified()
  }

  /**
   * For internal use only!
   *
   *
   * Marks this editor as modified.
   */
  internal fun markModified() {
    binding.editor.markModified()
  }

  @Subscribe(threadMode = ThreadMode.MAIN)
  @Suppress("unused")
  fun onPreferenceChanged(event: PreferenceChangeEvent) {
    if (_binding == null) {
      return
    }

    BaseApplication.getBaseInstance().prefManager
    when (event.key) {
      EditorPreferences.FONT_SIZE -> onFontSizePrefChanged()
      EditorPreferences.FONT_LIGATURES -> onFontLigaturesPrefChanged()

      EditorPreferences.FLAG_LINE_BREAK,
      EditorPreferences.FLAG_WS_INNER,
      EditorPreferences.FLAG_WS_EMPTY_LINE,
      EditorPreferences.FLAG_WS_LEADING,
      EditorPreferences.FLAG_WS_TRAILING -> onPrintingFlagsPrefChanged()

      EditorPreferences.FLAG_PASSWORD -> onInputTypePrefChanged()
      EditorPreferences.WORD_WRAP -> onWordwrapPrefChanged()
      EditorPreferences.USE_MAGNIFER -> onMagnifierPrefChanged()
      EditorPreferences.USE_ICU -> onUseIcuPrefChanged()
      EditorPreferences.USE_CUSTOM_FONT -> onCustomFontPrefChanged()
      EditorPreferences.DELETE_EMPTY_LINES -> onDeleteEmptyLinesPrefChanged()
      EditorPreferences.DELETE_TABS_ON_BACKSPACE -> onDeleteTabsPrefChanged()
      EditorPreferences.STICKY_SCROLL_ENABLED -> onStickyScrollEnabeldPrefChanged()
      EditorPreferences.PIN_LINE_NUMBERS -> onPinLineNumbersPrefChanged()
    }
  }

  /**
   * Notifies the editor that its content has been saved.
   */
  private fun notifySaved() {
    binding.editor.dispatchDocumentSaveEvent()
  }

  override fun onAttachedToWindow() {
    super.onAttachedToWindow()
    if (!EventBus.getDefault().isRegistered(this)) {
      EventBus.getDefault().register(this)
    }
  }

  override fun onDetachedFromWindow() {
    super.onDetachedFromWindow()
    EventBus.getDefault().unregister(this)
  }

  override fun close() {
    codeEditorScope.cancelIfActive("Cancellation was requested")
    IDEDebugClientImpl.breakpoints.removeListener(this)
    _binding?.editor?.apply {
      notifyClose()
      release()
    }

    readWriteContext.use { }
  }
}