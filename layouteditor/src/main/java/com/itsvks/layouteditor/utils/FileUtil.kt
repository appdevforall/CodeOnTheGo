package com.itsvks.layouteditor.utils

import android.annotation.SuppressLint
import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.text.TextUtils
import com.blankj.utilcode.util.ToastUtils
import com.itsvks.layouteditor.LayoutEditor.Companion.instance
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.FileReader
import java.io.FileWriter
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.URLDecoder

object FileUtil {
  fun readFromUri(uri: Uri, context: Context): String? {
    try {
      val inputStream = context.contentResolver.openInputStream(uri)

      // Creates a BufferedReader to read the contents of the InputStream
      val reader = BufferedReader(InputStreamReader(inputStream))

      // Creates a StringBuilder to store the file's contents
      val sb = StringBuilder()
      var line: String?

      // Reads each line from the file and adds it to StringBuilder
      while ((reader.readLine().also { line = it }) != null) {
        sb.append(line)
      }

      // Closes the InputStream and the BufferedReader
      inputStream!!.close()
      reader.close()

      // Returns the string containing the content of the XML file
      return sb.toString()
    } catch (e: Exception) {
      e.printStackTrace()
    }

    return null
  }

  @JvmStatic
  fun copyFile(uri: Uri, destinationPath: String): Boolean {
    var inputStream: InputStream? = null
    var outputStream: OutputStream? = null

    try {
      inputStream = instance!!.context.contentResolver.openInputStream(uri)
      outputStream = FileOutputStream(File(destinationPath))

      val buffer = ByteArray(1024)
      var length: Int

      while ((inputStream!!.read(buffer).also { length = it }) > 0) {
        outputStream.write(buffer, 0, length)
      }

      return true
    } catch (e: IOException) {
      e.printStackTrace()
      ToastUtils.showLong(e.toString())
      return false
    } finally {
      try {
        inputStream?.close()
        outputStream?.close()
      } catch (e: IOException) {
        e.printStackTrace()
        ToastUtils.showLong(e.toString())
      }
    }
  }

  /**
   * Reads from an asset file and returns its content as a String.
   *
   * @param path The path to the asset file
   * @param ctx The context from which the asset should be read
   * @return The content of the asset file as a String
   */
  fun readFromAsset(path: String?, ctx: Context): String {
    try {
      // Get the input stream from the asset
      val inputStream = ctx.assets.open(path!!)

      // Create a byte array output stream to store the read bytes
      val outputStream = ByteArrayOutputStream()

      // Create a buffer of 1024 bytes
      val _buf = ByteArray(1024)
      var i: Int

      // Read the bytes from the input stream, write them to the output stream and close the streams
      while ((inputStream.read(_buf).also { i = it }) != -1) {
        outputStream.write(_buf, 0, i)
      }
      outputStream.close()
      inputStream.close()

      // Return the content of the output stream as a String
      return outputStream.toString()
    } catch (e: Exception) {
      e.printStackTrace()
    }

    // If an exception occurred, return an empty String
    return ""
  }

  /**
   * This method is used to copy file from assets folder to target path.
   *
   * @param filename - File name with extension to copy from assets folder.
   * @param outPath - Target path where you want to copy the file.
   */
  fun copyFileFromAsset(filename: String, outPath: String) {
    // Get asset manager instance from application context
    val assetManager = instance!!.context.assets

    // Create streams for read and write
    val `in`: InputStream
    val out: OutputStream

    try {
      // Create InputStream from assets folder
      `in` = assetManager.open(filename)
      // Create OutputStream to target path
      val newFileName = "$outPath/$filename"
      out = FileOutputStream(newFileName)

      // Buffer for read and write
      val buffer = ByteArray(1024)
      var read: Int

      // Read from InputStream and write to OutputStream
      while ((`in`.read(buffer).also { read = it }) != -1) {
        out.write(buffer, 0, read)
      }

      // Close input and output streams
      `in`.close()
      out.flush()
      out.close()
    } catch (e: IOException) {
      // Print exception stack trace
      e.printStackTrace()
    }
  }

  /**
   * Creates a new file in the specified directory path if it does not already exist
   *
   * @param path the directory path in which to create the new file
   */
  private fun createNewFile(path: String) {
    // Get the last index of the file separator
    val lastSep = path.lastIndexOf(File.separator)
    // If there is a path, call makeDir to create the directory
    if (lastSep > 0) {
      val dirPath = path.substring(0, lastSep)
      makeDir(dirPath)
    }

    // Create a new file in the specified path
    val file = File(path)

    try {
      // Only create the file if it does not already exist
      if (!file.exists()) file.createNewFile()
    } catch (e: IOException) {
      e.printStackTrace()
    }
  }

  /**
   * Reads the contents of a file in the specified path
   *
   * @param path the directory path of the file to read
   * @return the contents of the file as a string
   */
  @JvmStatic
  fun readFile(path: String): String {
    // Create the file if it does not exist
    createNewFile(path)

    val sb = StringBuilder()
    var fr: FileReader? = null
    try {
      fr = FileReader(File(path))

      val buff = CharArray(1024)
      var length = 0

      // Read the contents of the file and append them to the StringBuilder
      while ((fr.read(buff).also { length = it }) > 0) {
        sb.append(String(buff, 0, length))
      }
    } catch (e: IOException) {
      e.printStackTrace()
    } finally {
      if (fr != null) {
        try {
          fr.close()
        } catch (e: Exception) {
          e.printStackTrace()
        }
      }
    }

    // Return the contents of the file
    return sb.toString()
  }

  /**
   * Method to write a file with the given path and string.
   *
   * @param path Path of the file to write.
   * @param str String to write in the file.
   */
  @JvmStatic
  fun writeFile(path: String, str: String?) {
    // Create a new file.
    createNewFile(path)
    var fileWriter: FileWriter? = null

    try {
      // Create a filewriter object with given path
      // and false to overwrite the existing file.
      fileWriter = FileWriter(File(path), false)
      // Write the given string in file.
      fileWriter.write(str)
      // Flush the filewriter object.
      fileWriter.flush()
    } catch (e: IOException) {
      e.printStackTrace()
    } finally {
      try {
        // Close the filewriter object.
        fileWriter?.close()
      } catch (e: IOException) {
        e.printStackTrace()
      }
    }
  }

  /**
   * Method to copy a file from source to destination.
   *
   * @param sourcePath Path of the file to copy from.
   * @param destPath Path of the file to copy to.
   */
  fun copyFile(sourcePath: String, destPath: String) {
    // Check if file exist in source path.
    if (isNotExistFile(sourcePath)) return
    // Create a new file in destination path.
    createNewFile(destPath)

    var fis: FileInputStream? = null
    var fos: FileOutputStream? = null

    try {
      // Create input and output stream objects.
      fis = FileInputStream(sourcePath)
      fos = FileOutputStream(destPath, false)

      val buff = ByteArray(1024)
      var length = 0

      // Read and write the bytes from source path to destination path.
      while ((fis.read(buff).also { length = it }) > 0) {
        fos.write(buff, 0, length)
      }
    } catch (e: IOException) {
      e.printStackTrace()
    } finally {
      // Close the input and output stream objects.
      if (fis != null) {
        try {
          fis.close()
        } catch (e: IOException) {
          e.printStackTrace()
        }
      }
      if (fos != null) {
        try {
          fos.close()
        } catch (e: IOException) {
          e.printStackTrace()
        }
      }
    }
  }

  @Throws(IOException::class)
  fun copyFile(`in`: InputStream, out: OutputStream) {
    val buffer = ByteArray(1024)
    var read: Int
    while ((`in`.read(buffer).also { read = it }) != -1) {
      out.write(buffer, 0, read)
    }
  }

  /**
   * copyDir() Copies a directory from one path to another
   *
   * @param oldPath the path of the directory to be copied
   * @param newPath the path of the directory to be created
   */
  fun copyDir(oldPath: String, newPath: String) {
    val oldFile = File(oldPath)
    val files = oldFile.listFiles()
    val newFile = File(newPath)
    if (!newFile.exists()) {
      newFile.mkdirs()
    }
    for (file in files!!) {
      if (file.isFile) {
        copyFile(file.path, newPath + "/" + file.name)
      } else if (file.isDirectory) {
        copyDir(file.path, newPath + "/" + file.name)
      }
    }
  }

  /**
   * moveFile() Moves a file from one path to another
   *
   * @param sourcePath the path of the file to be moved
   * @param destPath the path of the destination
   */
  fun moveFile(sourcePath: String, destPath: String) {
    copyFile(sourcePath, destPath)
    deleteFile(sourcePath)
  }

  /**
   * deleteFile() Deletes a file with the given path
   *
   * @param path the path of the file to be deleted
   */
  @JvmStatic
  fun deleteFile(path: String) {
    val file = File(path)

    if (!file.exists()) return

    if (file.isFile) {
      file.delete()
      return
    }

    val fileArr = file.listFiles()

    if (fileArr != null) {
      for (subFile in fileArr) {
        if (subFile.isDirectory) {
          deleteFile(subFile.absolutePath)
        }

        if (subFile.isFile) {
          subFile.delete()
        }
      }
    }

    file.delete()
  }

  /**
   * Checks if a file exists at the given path.
   *
   * @param path the path to check
   * @return true if the file exists, false otherwise
   */
  fun isNotExistFile(path: String): Boolean {
    val file = File(path)
    return !file.exists()
  }

  /**
   * Creates a directory at the given path, if it doesn't exist.
   *
   * @param path the path at which the directory should be created
   */
  fun makeDir(path: String) {
    if (isNotExistFile(path)) {
      val file = File(path)
      file.mkdirs()
    }
  }

  /**
   * Lists all the files in the given directory, and adds them to the given list.
   *
   * @param path the directory to list the files from
   * @param list the list to which the files should be added
   */
  fun listDir(path: String, list: ArrayList<String?>?) {
    val dir = File(path)
    if (!dir.exists() || dir.isFile) return

    val listFiles = dir.listFiles()
    if (listFiles == null || listFiles.isEmpty()) return

    if (list == null) return
    list.clear()
    for (file in listFiles) {
      list.add(file.absolutePath)
    }
  }

  /**
   * This method is used to convert the Uri to File Path.
   *
   * @param uri Uri of the file
   * @return Returns the File Path of Uri
   */
  @JvmStatic
  fun convertUriToFilePath(uri: Uri): String {
    var path = ""
    // Check if the Uri is provided by documents contract
    if (DocumentsContract.isDocumentUri(instance!!.context, uri)) {
      // Check if Uri is External Storage Document
      if (isExternalStorageDocument(uri)) {
        val docId = DocumentsContract.getDocumentId(uri)
        // Split the document Id into two parts
        val split = docId.split(":".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
        val type = split[0]

        // Check if it is primary storage
        if ("primary".equals(type, ignoreCase = true)) {
          // Append the split[1] to Environment.getExternalStorageDirectory() to get File Path
          path = Environment.getExternalStorageDirectory().toString() + "/" + split[1]
        }
      } else if (isDownloadsDocument(uri)) {
        val id = DocumentsContract.getDocumentId(uri)

        // Check if the Id is empty
        if (!TextUtils.isEmpty(id)) {
          // Replace 'raw:' from Id to get File Path
          if (id.startsWith("raw:")) {
            return id.replaceFirst("raw:".toRegex(), "")
          }
        }
      } else if (isMediaDocument(uri)) {
        val docId = DocumentsContract.getDocumentId(uri)
        val split = docId.split(":".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
        val type = split[0]

        var contentUri: Uri? = null
        // Check the type of Media
        when (type) {
          "image" -> {
            contentUri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
          }
          "video" -> {
            contentUri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
          }
          "audio" -> {
            contentUri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
          }
        }

        val selection = "_id=?"
        val selectionArgs = arrayOf(split[1])

        // Get Data Column from content Uri
        path = getDataColumn(contentUri, selection, selectionArgs).toString()
      }
    } else if (ContentResolver.SCHEME_CONTENT.equals(uri.scheme, ignoreCase = true)) {
      // Get Data Column from content Uri
      path = getDataColumn(uri, null, null).toString()
    } else if (ContentResolver.SCHEME_FILE.equals(uri.scheme, ignoreCase = true)) {
      // Get File Path from Uri
      path = uri.path.toString()
    }

    // Check if path is not empty
    return try {
      // Decode the File Path using 'UTF-8'
      URLDecoder.decode(path, "UTF-8")
    } catch (e: Exception) {
      null
    }.toString()
  }

  /**
   * This method is used to get the data column of a particular URI.
   *
   * @param uri The URI of the data column to be retrieved.
   * @param selection The selection argument for the query.
   * @param selectionArgs The selection arguments for the query.
   * @return The data column retrieved from the specified URI.
   */
  @SuppressLint("Recycle")
  private fun getDataColumn(uri: Uri?, selection: String?, selectionArgs: Array<String>?): String? {
    val column =
      MediaStore.Images.Media.DATA // Column name of the data column to be retrieved
    val projection = arrayOf(column) // Projection of the data column to be retrieved

    try {
      val cursor =
        instance!!.context // Get the application context
          .contentResolver // Get the content resolver
          .query(uri!!, projection, selection, selectionArgs, null) // Query the content resolver
      if (cursor != null && cursor.moveToFirst()) {
        val column_index =
          cursor.getColumnIndexOrThrow(column) // Get the index of the data column
        return cursor.getString(column_index) // Return the data column
      }
    } catch (ignored: Exception) {
    }
    return null
  }

  /**
   * Checks whether the Uri authority is ExternalStorageProvider.
   *
   * @param uri The Uri to check.
   * @return Whether the Uri authority is ExternalStorageProvider.
   */
  fun isExternalStorageDocument(uri: Uri): Boolean {
    return "com.android.externalstorage.documents" == uri.authority
  }

  /**
   * Checks whether the Uri authority is DownloadsProvider.
   *
   * @param uri The Uri to check.
   * @return Whether the Uri authority is DownloadsProvider.
   */
  fun isDownloadsDocument(uri: Uri?): Boolean {
    if (uri != null) {
      return "com.android.providers.downloads.documents" == uri.authority
    }
    return false
  }

  /**
   * Checks whether the Uri authority is MediaProvider.
   *
   * @param uri The Uri to check.
   * @return Whether the Uri authority is MediaProvider.
   */
  fun isMediaDocument(uri: Uri): Boolean {
    return "com.android.providers.media.documents" == uri.authority
  }

  /**
   * Checks whether the file is a directory.
   *
   * @param path The path of the file.
   * @return Whether the file is a directory.
   */
  fun isDirectory(path: String): Boolean {
    if (isNotExistFile(path)) return false
    return File(path).isDirectory
  }

  /**
   * Checks whether the file is a file.
   *
   * @param path The path of the file.
   * @return Whether the file is a file.
   */
  fun isFile(path: String): Boolean {
    if (isNotExistFile(path)) return false
    return File(path).isFile
  }

  /**
   * Gets the file length.
   *
   * @param path The path of the file.
   * @return The file length.
   */
  fun getFileLength(path: String): Long {
    if (isNotExistFile(path)) return 0
    return File(path).length()
  }

  val externalStorageDir: String
    /**
     * Gets the absolute path of the external storage directory.
     *
     * @return The absolute path of the external storage directory.
     */
    get() = Environment.getExternalStorageDirectory().absolutePath

  /**
   * Gets the absolute path of the application-specific directory.
   *
   * @param ctx The application context.
   * @return The absolute path of the application-specific directory.
   */
  fun getPackageDataDir(ctx: Context): String {
    return ctx.getExternalFilesDir("")!!.absolutePath
  }

  /**
   * Gets the absolute path of the public directory.
   *
   * @param type The type of the public directory.
   * @return The absolute path of the public directory.
   */
  fun getPublicDir(type: String): String {
    return Environment.getExternalStoragePublicDirectory(type).absolutePath
  }

  /**
   * Gets the last segment from the path.
   *
   * @param path The path to get the last segment.
   * @return The last segment from the path.
   */
  @JvmStatic
  fun getLastSegmentFromPath(path: String): String {
    return path.substring(path.lastIndexOf("/") + 1)
  }

  /**
   * Saves the file with given Uri and data.
   *
   * @param uri URI of the file to be saved
   * @param data Data to be written in the file
   * @return boolean True if file is saved successfully, else false
   */
  fun saveFile(uri: Uri, data: String): Boolean {
    try {
      // Open the file descriptor in read-write-truncate mode
      val pfd =
        instance!!.context.contentResolver.openFileDescriptor(uri, "rwt")
      // Initialize file output stream with the file descriptor
      val fos = FileOutputStream(pfd!!.fileDescriptor)
      // Write the data in the file
      fos.write(data.toByteArray())
      // Close the output stream
      fos.close()
      // Close the file descriptor
      pfd.close()
      return true
    } catch (e: IOException) {
      // Print the stacktrace for the exception
      e.printStackTrace()
      return false
    }
  }
}
