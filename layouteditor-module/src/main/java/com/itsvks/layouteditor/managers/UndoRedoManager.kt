package com.itsvks.layouteditor.managers

import android.view.MenuItem

/** UndoRedoManager class is used to add XML to history and Undo/Redo the previous changes.  */
class UndoRedoManager(
  /** MenuItem for Undo Button  */
  private val btnUndo: MenuItem?,
  /** MenuItem for Redo Button  */
  private val btnRedo: MenuItem?
) {
  /** Max size of the history  */
  private val maxSize = 20

  /** History list to store the XMLs  */
  private val history = arrayListOf("")

  /** Index to track the current history  */
  private var index = 0

  /**
   * Add XML to the history
   *
   * @param xml XML string to add to history
   */
  fun addToHistory(xml: String) {
    if (matchLastHistory(xml)) return
    history.add(xml)
    if (history.size == maxSize) {
      history.removeAt(0)
    }
    index = history.size - 1
    updateButtons()
  }

  /**
   * To get the previous XML from the history
   *
   * @return Previous XML from the history
   */
  fun undo(): String {
    if (index > 0) {
      index--
      updateButtons()
      return history[index]
    }
    return ""
  }

  /**
   * To get the next XML from the history
   *
   * @return Next XML from the history
   */
  fun redo(): String {
    if (index < history.size - 1) {
      index++
      updateButtons()
      return history[index]
    }
    return ""
  }

  /** To update the button's enabled state  */
  fun updateButtons() {
    if (btnRedo == null || btnUndo == null) return
    btnUndo.icon!!.alpha = if (isUndoEnabled) 255 else 130
    btnUndo.setEnabled(isUndoEnabled)
    btnRedo.icon!!.alpha = if (isRedoEnabled) 255 else 130
    btnRedo.setEnabled(isRedoEnabled)
  }

  val isUndoEnabled: Boolean
    /**
     * To check if Undo is enabled or not
     *
     * @return true if undo is enabled
     */
    get() = index > 0
  val isRedoEnabled: Boolean
    /**
     * To check if Redo is enabled or not
     *
     * @return true if redo is enabled
     */
    get() = index < history.size - 1

  /**
   * To check if the last history is same as the current one
   *
   * @param xml Current XML
   * @return true if last history is same as the current one
   */
  private fun matchLastHistory(xml: String): Boolean {
    val lastIndex = history.size - 1
    if (lastIndex < 0) return false
    return xml === history[lastIndex]
  }
}
