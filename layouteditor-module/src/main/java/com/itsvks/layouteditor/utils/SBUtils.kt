package com.itsvks.layouteditor.utils

import android.view.View
import androidx.annotation.StringRes
import com.google.android.material.R
import com.google.android.material.color.MaterialColors
import com.google.android.material.snackbar.Snackbar
import org.jetbrains.annotations.Contract

/**
 * SBUtils is a utility class for creating and displaying snackbars.
 */
class SBUtils private constructor(
  private val snackbar: Snackbar
) {
  /**
   * Enum for the types of snackbars that can be created.
   */
  enum class Type {
    ERROR,
    SUCCESS,
    INFO
  }

  private var type: Type? = null

  /**
   * Sets the type of the snackbar.
   *
   * @param type The type of snackbar to set
   * @return The SBUtils instance
   */
  fun setType(type: Type?): SBUtils {
    this.type = type
    return this
  }

  /**
   * Sets the background color and text color of the snackbar.
   *
   * @param colorBg The background color of the snackbar
   * @param colorTxt The text color of the snackbar
   * @return The SBUtils instance
   */
  fun setColors(colorBg: Int, colorTxt: Int): SBUtils {
    snackbar.setBackgroundTint(colorBg).setTextColor(colorTxt).setActionTextColor(colorTxt)
    return this
  }

  /**
   * Sets the animation mode of the snackbar to Fade.
   *
   * @return The SBUtils instance
   */
  fun setFadeAnimation(): SBUtils {
    snackbar.setAnimationMode(Snackbar.ANIMATION_MODE_FADE)
    return this
  }

  /**
   * Sets the animation mode of the snackbar to Slide.
   *
   * @return The SBUtils instance
   */
  fun setSlideAnimation(): SBUtils {
    snackbar.setAnimationMode(Snackbar.ANIMATION_MODE_SLIDE)
    return this
  }

  /**
   * Sets the anchor view for the snackbar.
   *
   * @param anchorView The anchor view for the snackbar
   * @return The SBUtils instance
   */
  fun setAnchorView(anchorView: View?): SBUtils {
    snackbar.setAnchorView(anchorView)
    return this
  }

  /**
   * Shows the snackbar. The colors will be set based on the type if a type has been set.
   */
  fun show() {
    when (type) {
      Type.ERROR -> setColors(
        MaterialColors.getColor(snackbar.context, R.attr.colorErrorContainer, ""),
        MaterialColors.getColor(snackbar.context, R.attr.colorOnErrorContainer, "")
      )

      Type.SUCCESS -> setColors(
        MaterialColors.getColor(snackbar.context, R.attr.colorPrimaryContainer, ""),
        MaterialColors.getColor(snackbar.context, R.attr.colorOnPrimaryContainer, "")
      )

      Type.INFO -> setColors(
        MaterialColors.getColor(snackbar.context, R.attr.colorSecondaryContainer, ""),
        MaterialColors.getColor(snackbar.context, R.attr.colorOnSecondaryContainer, "")
      )

      null -> {
        showAsSuccess()
        return
      }
    }
    snackbar.show()
  }

  /**
   * Sets the type of snackbar to ERROR and shows it.
   */
  fun showAsError() {
    setType(Type.ERROR).show()
  }

  /**
   * Sets the type of snackbar to SUCCESS and shows it.
   */
  fun showAsSuccess() {
    setType(Type.SUCCESS).show()
  }

  /**
   * Sets the duration of snackbar to LONG and sets the type of snackbar to ERROR and shows it.
   */
  fun showLongAsError() {
    snackbar.setDuration(Snackbar.LENGTH_LONG)
    setType(Type.ERROR).show()
  }

  /**
   * Sets the duration of snackbar to LONG and sets the type of snackbar to SUCCESS and shows it.
   */
  fun showLongAsSuccess() {
    snackbar.setDuration(Snackbar.LENGTH_LONG)
    setType(Type.SUCCESS).show()
  }

  companion object {
    /**
     * Creates and returns a new instance of SBUtils with a Snackbar with the given message for the
     * given view.
     *
     * @param v The view where the snackbar should be displayed
     * @param msg The message to display in the snackbar
     * @return a new instance of SBUtils
     */
    @JvmStatic
    @Contract("_, _ -> new")
    fun make(v: View, msg: CharSequence): SBUtils {
      return SBUtils(Snackbar.make(v, msg, Snackbar.LENGTH_SHORT))
    }

    /**
     * Creates and returns a new instance of SBUtils with a Snackbar with the given string resource
     * ID for the given view.
     *
     * @param v The view where the snackbar should be displayed
     * @param msgResId The string resource ID to display in the snackbar
     * @return a new instance of SBUtils
     */
    @JvmStatic
    @Contract("_, _ -> new")
    fun make(v: View, @StringRes msgResId: Int): SBUtils {
      return SBUtils(Snackbar.make(v, msgResId, Snackbar.LENGTH_SHORT))
    }
  }
}