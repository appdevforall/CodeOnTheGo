package com.itsvks.layouteditor.utils

import java.util.regex.Pattern

/** This class holds all utility methods related to arguments  */
object ArgumentUtil {
  /** Default color value  */
  const val COLOR = "color"

  /** Default drawable value  */
  const val DRAWABLE = "drawable"
  const val STRING = "string"

  /** Map used to store the patterns of color and drawable  */
  val patterns = HashMap<String, String>()

  init {
    patterns[COLOR] = "#[a-fA-F0-9]{6,8}"
    patterns[DRAWABLE] = "@drawable/.*"
    patterns[STRING] = "@string/.*"
  }

  /**
   * Method to parse the type of the value from given list of variants
   *
   * @param value     The value to be parsed
   * @param variants  The list of variants from which type should be parsed
   * @return          The type of the value
   */
  @JvmStatic
  fun parseType(value: String?, variants: Array<String>): String {
    for (variant in variants) {
      if (patterns.containsKey(variant)) if (Pattern.matches(
          patterns[variant], value
        )
      ) return variant
    }
    return "text"
  }
}
