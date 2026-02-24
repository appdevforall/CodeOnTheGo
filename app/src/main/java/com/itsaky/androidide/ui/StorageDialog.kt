package com.itsaky.androidide.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.provider.Settings
import com.itsaky.androidide.resources.R
import com.itsaky.androidide.utils.getMinimumStorageNeeded


fun Activity.showLowStorageDialog() {
    val appName = getString(R.string.app_name)
    val minSpace = getMinimumStorageNeeded()

    AlertDialog.Builder(this)
        .setTitle(R.string.err_insufficient_storage_title)
        .setMessage(getString(R.string.err_insufficient_storage_msg, appName, minSpace))
        .setCancelable(false)
        .setPositiveButton(getString(R.string.action_close_app, appName)) { _, _ ->
            finishAndRemoveTask()
        }
        .setNegativeButton(R.string.action_free_up_space) { _, _ ->
            startActivity(Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS))
            finishAffinity()
        }
        .show()
}
