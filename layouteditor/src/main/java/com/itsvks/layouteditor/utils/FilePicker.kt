package com.itsvks.layouteditor.utils

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.itsvks.layouteditor.LayoutEditor.Companion.instance
import com.itsvks.layouteditor.R
import com.itsvks.layouteditor.utils.SBUtils.Companion.make

/**
 * Class for FilePicker
 */
abstract class FilePicker(private val actvty: AppCompatActivity) {
  private val getFile: ActivityResultLauncher<String>
  private val reqPermission: ActivityResultLauncher<String>

  /**
   * Constructor for FilePicker, takes in an AppCompatActivity as a parameter
   *
   * @param actvty AppCompatActivity instance
   */
  init {
    // Create an instance of ActivityResultContracts.GetContent and register it with actvty
    // when the result is returned, call the onPickFile method with the returned uri
    this.getFile =
      actvty.registerForActivityResult<String, Uri>(
        ActivityResultContracts.GetContent()
      ) { onPickFile(it) }

    // Create an instance of ActivityResultContracts.RequestPermission and register it with actvty
    // when the result is returned, call the onRequestPermission method with the granted boolean
    this.reqPermission =
      actvty.registerForActivityResult(
        ActivityResultContracts.RequestPermission()
      ) { onRequestPermission(it) }
  }

  private fun onRequestPermission(isGranted: Boolean) {
    if (isGranted) make(actvty.findViewById(android.R.id.content), R.string.permission_granted)
      .setSlideAnimation()
      .showAsSuccess()
    else make(actvty.findViewById(android.R.id.content), R.string.permission_denied)
      .setSlideAnimation()
      .showAsError()
  }

  /**
   * Abstract method called onPickFile, takes in a Nullable Uri as a parameter
   *
   * @param uri Nullable Uri
   */
  abstract fun onPickFile(uri: Uri?)

  /**
   * Method launch, takes in a String MIME type as a parameter
   */
  fun launch(mimeType: String) {
    checkPermissions(mimeType)
    // If the app has the permission, launch the getFile instance
    getFile.launch(mimeType)
  }

  private fun checkPermissions(mimeType: String) {
    val isImageType =
      mimeType == "image/*" || mimeType == "image/png" || mimeType == "image/jpg" || mimeType == "image/jpeg"

    if (isImageType) {
      if (instance!!.isAtLeastTiramisu) {
        if (actvty.checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES)
          == PackageManager.PERMISSION_DENIED
        ) {
          reqPermission.launch(Manifest.permission.READ_MEDIA_IMAGES)
        }
      } else if (actvty.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE)
        == PackageManager.PERMISSION_DENIED
      ) {
        reqPermission.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
      }
    }
  }
}
