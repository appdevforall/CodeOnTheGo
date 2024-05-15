package com.itsvks.layouteditor

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.elevation.SurfaceColors
import java.lang.ref.WeakReference

open class BaseActivity : AppCompatActivity() {
  var app: LayoutEditor? = null
  private lateinit var ctx: WeakReference<Context?>

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    instance = this
    ctx = WeakReference(this)
    Thread.setDefaultUncaughtExceptionHandler(CrashHandler(ctx))
    app = LayoutEditor.instance
    window.statusBarColor = SurfaceColors.SURFACE_0.getColor(this)
  }

  fun openUrl(url: String) {
    try {
      Intent(Intent.ACTION_VIEW).apply {
        data = Uri.parse(url)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(this)
      }
    } catch (th: Throwable) {
      Toast.makeText(this, th.message, Toast.LENGTH_SHORT).show()
      th.printStackTrace()
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
