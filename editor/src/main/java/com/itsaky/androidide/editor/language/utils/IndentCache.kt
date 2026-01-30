package com.itsaky.androidide.editor.language.utils

import io.github.rosemoe.sora.text.TextUtils

internal class IndentCache {

  companion object {
    const val MAX_INDENT_COLUMNS = 500
  }
  private var cache: Array<String?> = arrayOfNulls(MAX_INDENT_COLUMNS + 1)
  private var lastTabSize: Int = -1
  private var lastUseTab: Boolean = false

  @Synchronized
  fun indent(columns: Int, tabSize: Int, useTab: Boolean): String {
    if (tabSize != lastTabSize || useTab != lastUseTab) {
      cache.fill(null)
      lastTabSize = tabSize
      lastUseTab = useTab
    }

    val column = columns.coerceIn(0, MAX_INDENT_COLUMNS)
    val existing = cache[column]
    if (existing != null) return existing

    val created = TextUtils.createIndent(column, tabSize, useTab)
    cache[column] = created
    return created
  }
}
