package com.itsvks.layouteditor.editor.dialogs.interfaces

/**
 * OnSaveValueListener interface for saving value
 */
fun interface OnSaveValueListener {
  /**
   * Called when value is saved
   * @param value Value to be saved
   */
  fun onSave(value: String?)
}