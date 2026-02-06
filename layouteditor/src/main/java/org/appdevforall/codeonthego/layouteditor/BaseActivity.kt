package org.appdevforall.codeonthego.layouteditor

import android.annotation.SuppressLint
import android.content.Context
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import com.google.android.material.elevation.SurfaceColors
import com.itsaky.androidide.utils.OrientationUtilities
import com.itsaky.androidide.utils.isSystemInDarkMode
import java.lang.ref.WeakReference

open class BaseActivity : AppCompatActivity() {
  var app: LayoutEditor? = null
  private lateinit var ctx: WeakReference<Context?>

  @SuppressLint("SourceLockedOrientationActivity")
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    instance = this
    ctx = WeakReference(this)
    app = LayoutEditor.instance
    window.statusBarColor = SurfaceColors.SURFACE_0.getColor(this)
    OrientationUtilities.setOrientation {
      OrientationUtilities.setAdaptiveOrientation(this) { requestedOrientation = it }
    }

      // Set status bar icons to be dark in light mode and light in dark mode
      WindowCompat.getInsetsController(this.window, this.window.decorView).apply {
          isAppearanceLightStatusBars = !isSystemInDarkMode()
          isAppearanceLightNavigationBars = !isSystemInDarkMode()
      }
  }


  override fun onDestroy() {
    ctx.clear()
    super.onDestroy()
  }

  companion object {
    var instance: BaseActivity? = null
      private set
  }
}
