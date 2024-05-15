package com.itsvks.layouteditor.activities

import android.os.Bundle
import android.os.Process
import android.view.Menu
import android.view.MenuItem
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.content.res.AppCompatResources
import com.blankj.utilcode.util.ClipboardUtils
import com.blankj.utilcode.util.DeviceUtils
import com.google.android.material.snackbar.Snackbar
import com.itsvks.layouteditor.BaseActivity
import com.itsvks.layouteditor.BuildConfig
import com.itsvks.layouteditor.R
import com.itsvks.layouteditor.databinding.ActivityCrashBinding
import kotlin.system.exitProcess

class CrashActivity : BaseActivity() {
  private var binding: ActivityCrashBinding? = null

  private val onBackPressedCallback = object : OnBackPressedCallback(true) {
    override fun handleOnBackPressed() {
      killActivity()
    }
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    binding = ActivityCrashBinding.inflate(layoutInflater)
    onBackPressedDispatcher.addCallback(this, onBackPressedCallback)

    setContentView(binding!!.getRoot())
    setSupportActionBar(binding!!.topAppBar)
    supportActionBar!!.setTitle(R.string.app_crashed)

    val error =
      buildString {
        append("Manufacturer: ")
        append(DeviceUtils.getManufacturer())
        append("\nDevice: ")
        append(DeviceUtils.getModel())
        append("\n")
        append(intent.getStringExtra("Software"))
        append("\nApp version: ")
        append("0.1-beta")
        append("\n\n")
        append(intent.getStringExtra("Error"))
        append("\n\n")
        append(
          intent.getStringExtra(
            "Date"
          )
        )
      }

    binding!!.result.text = error

    binding!!.fab.setOnClickListener {
      ClipboardUtils.copyText(binding!!.result.text)
      Snackbar.make(binding!!.getRoot(), getString(R.string.copied), Snackbar.LENGTH_SHORT)
        .setAnimationMode(Snackbar.ANIMATION_MODE_SLIDE)
        .setAnchorView(binding!!.fab)
        .show()
    }
  }

  override fun onCreateOptionsMenu(menu: Menu): Boolean {
    val close = menu.add(getString(R.string.close))
    close.setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
    close.setIcon(AppCompatResources.getDrawable(this, R.drawable.close))
    close.setContentDescription(getString(R.string.close_app))

    return super.onCreateOptionsMenu(menu)
  }

  override fun onOptionsItemSelected(item: MenuItem): Boolean {
    if (item.title == getString(R.string.close)) {
      killActivity()
    }
    return true
  }

  override fun onDestroy() {
    super.onDestroy()
    binding = null
  }

  private fun killActivity() {
    finishAffinity()
    Process.killProcess(Process.myPid())
    exitProcess(0)
  }
}
