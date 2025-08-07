package com.itsvks.layouteditor.utils

import android.content.Context
import android.util.TypedValue
import android.view.ViewGroup
import java.util.regex.Pattern

/**
 * This class is used to perform Dimension Utility operations.
 */
object DimensionUtil {
  /**
   * Constant variable for Dimension Unit type DP
   */
  const val DP: String = "dp"

  /**
   * Constant variable for Dimension Unit type SP
   */
  const val SP: String = "sp"

  /**
   * A map containing dimension unit type and its related integer value
   */
  private val dimensMap = HashMap<String, Int>()

  // Initializing dimensMap with Dimension Unit type and its related integer value
  init {
    dimensMap[DP] = TypedValue.COMPLEX_UNIT_DIP
    dimensMap[SP] = TypedValue.COMPLEX_UNIT_SP
  }

  /**
   * Pattern for matching Dimension Unit type
   */
  private val pattern: Pattern = Pattern.compile("dp|sp")

  /**
   * Method to parse the input string and return the related dimension value
   *
   * @param input string for parsing
   * @param contxt context
   * @return dimension value
   */
  @JvmStatic
  fun parse(input: String, contxt: Context): Float {
    when (input) {
      "match_parent" -> return ViewGroup.LayoutParams.MATCH_PARENT.toFloat()
      "wrap_content" -> return ViewGroup.LayoutParams.WRAP_CONTENT.toFloat()
      else -> {
        val matcher = pattern.matcher(input)
        var dimen = DP

        // Finding dimension unit type from input string
        while (matcher.find()) {
          dimen = input.substring(matcher.start(), matcher.end())
        }

        // Getting dimension number from input string
        val number = input.substring(0, input.lastIndexOf(dimen)).toFloat()


        // Returning calculated dimension value
        return TypedValue.applyDimension(
          dimensMap[dimen]!!, number, contxt.resources.displayMetrics
        )
      }
    }
  }

  /**
   * Method to get the dimension value without the suffix, i.e Dimension Unit type
   *
   * @param input string for parsing
   * @return dimension value without suffix
   */
  @JvmStatic
  fun getDimenWithoutSuffix(input: String): String {
    val matcher = pattern.matcher(input)
    var dimen = DP

    // Finding dimension unit type from input string
    while (matcher.find()) {
      dimen = input.substring(matcher.start(), matcher.end())
    }

    // Getting dimension number from input string
    return input.substring(0, input.lastIndexOf(dimen))
  }

  /**
   * Method to get the dimension value in DIP
   *
   * @param value dimension number
   * @param ctx context
   * @return dimension value in DIP
   */
  fun getDip(value: Float, ctx: Context): Float {
    return TypedValue.applyDimension(
      TypedValue.COMPLEX_UNIT_DIP, value, ctx.resources.displayMetrics
    )
  }
}