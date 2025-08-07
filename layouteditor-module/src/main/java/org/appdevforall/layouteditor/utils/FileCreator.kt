package com.itsvks.layouteditor.utils

import android.net.Uri
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

/**
 * FileCreator Class is used to create a file with given name and MIME Type.
 */
abstract class FileCreator(actvty: AppCompatActivity) {
  /** To create a file  */
  private val createFile: ActivityResultLauncher<String>

  /** MIME Type of file  */
  private var mimeType = "*/*"

  /**
   * Constructor of class
   *
   * @param actvty Instance of AppCompatActivity
   */
  init {
    // Set MIME type
    // Register activity result for CreateDocument
    this.createFile =
      actvty.registerForActivityResult<String, Uri>(
        ActivityResultContracts.CreateDocument(mimeType)
      ) { onCreateFile(it) }
  }

  /**
   * Abstract method onCreateFile to call on result
   */
  abstract fun onCreateFile(uri: Uri)

  /**
   * Method to create file
   *
   * @param fileName The name of the file
   * @param mimeType The MIME type of the file
   */
  fun create(fileName: String, mimeType: String) {
    this.mimeType = mimeType // Set MIME type
    createFile.launch(fileName) // Launch file
  }
}
