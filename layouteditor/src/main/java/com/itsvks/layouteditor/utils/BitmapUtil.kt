package com.itsvks.layouteditor.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Bitmap.Config.ARGB_8888
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.cardview.widget.CardView
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.palette.graphics.Palette
import java.io.IOException

/** Utility class for manipulating bitmaps  */
object BitmapUtil {
  /**
   * Sets a tint on an [ImageView] according to the background color of a [View]
   *
   * @param imageView the ImageView whose tint will be set
   * @param background the view whose background color will be used to determine the tint
   */
  @JvmStatic
  fun setImageTintAccordingToBackground(imageView: ImageView, background: View) {
    // Check if the background View is a CardView
    val backgroundColor: Int = if (background is CardView) {
      // Get the color from the CardView
      background.cardBackgroundColor.defaultColor
      // Check if the background View is a ColorDrawable
    } else if (background.background is ColorDrawable) {
      (background.background as ColorDrawable).color
    } else {
      // Throw an exception if the background View is not a ColorDrawable
      throw IllegalArgumentException("Background must be a ColorDrawable")
    }

    // Calculate the luminance from the background color
    val luminance = ColorUtils.calculateLuminance(backgroundColor)
    // Set the image color to black or white depending on the luminance
    if (luminance >= 0.5) {
      imageView.setColorFilter(Color.BLACK)
    } else {
      imageView.setColorFilter(Color.WHITE)
    }
  }

  /**
   * Sets a background color on a [View] according to an [ImageView]
   *
   * @param context the context
   * @param view the view whose background color will be set
   * @param image the ImageView whose color will be used to determine the background color
   */
  fun setBackgroundAccordingToImage(context: Context?, view: View, image: ImageView) {
    val drawable = image.drawable
    setBackgroundAccordingToImage(context, view, drawable)
  }

  /**
   * Inverts a color by subtracting each color channel from 255
   *
   * @param color the color to invert
   * @return the inverted color
   */
  fun invertColor(color: Int): Int {
    val r = Color.red(color)
    val g = Color.green(color)
    val b = Color.blue(color)
    return Color.rgb(255 - r, 255 - g, 255 - b)
  }

  /**
   * Sets the text color of the TextView according to the background color provided.
   *
   * @param background The background color to use to determine the text color.
   * @param textView The TextView whose text color needs to be set.
   */
  @JvmStatic
  fun setTextColorAccordingToBackground(background: View, textView: TextView) {
    val backgroundColor: Int = if (background is CardView) {
      // Get the color from the CardView
      background.cardBackgroundColor.defaultColor
    } else if (background.background is ColorDrawable) {
      (background.background as ColorDrawable).color
    } else {
      // Throw an exception if the background View is not a ColorDrawable
      throw IllegalArgumentException("Background must be a ColorDrawable")
    }

    // Calculate the luminance from the background color
    val luminance = ColorUtils.calculateLuminance(backgroundColor)
    // Set the text color to black or white depending on the luminance
    if (luminance >= 0.5) {
      textView.setTextColor(Color.BLACK)
    } else {
      textView.setTextColor(Color.WHITE)
    }
  }

  @JvmStatic
  fun getLuminance(view: View): Double {
    val backgroundColor: Int = if (view is CardView) {
      // Get the color from the CardView
      view.cardBackgroundColor.defaultColor
    } else if (view.background is ColorDrawable) {
      (view.background as ColorDrawable).color
    } else {
      // Throw an exception if the background View is not a ColorDrawable
      throw IllegalArgumentException("Background must be a ColorDrawable")
    }

    // Calculate the luminance from the background color
    return ColorUtils.calculateLuminance(backgroundColor)
  }

  /**
   * Merges two bitmaps into a single bitmap
   *
   * @param background the background bitmap
   * @param foreground the foreground bitmap
   * @return the merged bitmap
   */
  fun mergeBitmaps(background: Bitmap, foreground: Bitmap): Bitmap {
    // Create a new Bitmap with the width and height of the background
    val width = background.width
    val height = background.height
    val mergedBitmap = Bitmap.createBitmap(width, height, background.config)

    // Create a Canvas object with the new Bitmap
    val canvas = Canvas(mergedBitmap).apply {
      // Draw the background on the Canvas
      drawBitmap(background, 0f, 0f, null)

      // Draw the foreground on the Canvas in the center of the background
      drawBitmap(
        foreground,
        ((width - foreground.width) / 2).toFloat(),
        ((height - foreground.height) / 2).toFloat(),
        null
      )
    }

    // Return the merged Bitmap
    return mergedBitmap
  }

  /**
   * Sets a background color on a [View] according to a [Drawable]
   *
   * @param context the context
   * @param view the view whose background color will be set
   * @param drawable the Drawable whose color will be used to determine the background color
   */
  fun setBackgroundAccordingToImage(context: Context?, view: View, drawable: Drawable?) {
    // Check the drawable type
    val bitmap: Bitmap = when (drawable) {
      is BitmapDrawable -> {
        // Get the bitmap from the BitmapDrawable
        drawable.bitmap
      }

      is AdaptiveIconDrawable -> {
        // Get the background and foreground drawables from the AdaptiveIconDrawable
        val backgroundDr = drawable.background
        val foregroundDr = drawable.foreground
        // Get the bitmaps from the drawables
        val background = (backgroundDr as BitmapDrawable).bitmap
        val foreground = (foregroundDr as BitmapDrawable).bitmap
        // Merge the bitmaps
        mergeBitmaps(background, foreground)
      }

      else -> {
        // Handle other drawable types as needed
        return
      }
    }

    // Generate a palette from the bitmap
    val palette = Palette.from(bitmap).generate()

    // Get the background color from the palette
    val backgroundColor: Int = when {
      palette.getDarkVibrantColor(0) != 0 -> {
        palette.getDarkVibrantColor(0)
      }

      palette.getDarkMutedColor(0) != 0 -> {
        palette.getDarkMutedColor(0)
      }

      palette.getLightVibrantColor(0) != 0 -> {
        palette.getLightVibrantColor(0)
      }

      palette.getLightMutedColor(0) != 0 -> {
        palette.getLightMutedColor(0)
      }

      palette.getVibrantColor(0) != 0 -> {
        palette.getVibrantColor(0)
      }

      palette.getMutedColor(0) != 0 -> {
        palette.getMutedColor(0)
      }

      else -> {
        ContextCompat.getColor(context!!, android.R.color.white)
      }
    }

    // Set the background color of the view
    if (view is CardView) {
      view.setCardBackgroundColor(backgroundColor)
    } else {
      view.setBackgroundColor(backgroundColor)
    }
  }

  @JvmStatic
  fun createBitmapFromView(view: View): Bitmap {
    val bitmap = Bitmap.createBitmap(view.width, view.height, ARGB_8888)
    val canvas = Canvas(bitmap)
    view.draw(canvas)
    return bitmap
  }

  @Throws(IOException::class)
  fun loadBitmapFromUri(context: Context, uri: Uri): Bitmap? {
    val inputStream = context.contentResolver.openInputStream(uri)
    val options = BitmapFactory.Options()
    options.inPreferredConfig = ARGB_8888
    val bitmap = BitmapFactory.decodeStream(inputStream, null, options)
    inputStream!!.close()
    return bitmap
  }
}
