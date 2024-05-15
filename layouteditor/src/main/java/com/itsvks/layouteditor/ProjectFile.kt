package com.itsvks.layouteditor

import android.os.Parcel
import android.os.Parcelable
import android.os.Parcelable.Creator
import com.itsvks.layouteditor.managers.PreferencesManager
import com.itsvks.layouteditor.utils.Constants
import com.itsvks.layouteditor.utils.FileUtil
import org.jetbrains.annotations.Contract
import java.io.File

class ProjectFile : Parcelable {
  var path: String
    private set

  @JvmField
  var name: String

  @JvmField
  var date: String? = null

  private val mainLayoutName:String

  constructor(path: String, date: String?, mainLayoutName: String = "layout_main") {
    this.path = path
    this.date = date
    this.name = FileUtil.getLastSegmentFromPath(path)
    this.mainLayoutName = mainLayoutName
  }

  fun rename(newPath: String) {
    val newFile = File(newPath)
    val oldFile = File(path)
    oldFile.renameTo(newFile)

    path = newPath
    name = FileUtil.getLastSegmentFromPath(path)
  }

  val drawablePath: String
    get() = "$path/drawable/"

  val fontPath: String
    get() = "$path/font/"

  val colorsPath: String
    get() = "$path/values/colors.xml"

  val stringsPath: String
    get() = "$path/values/strings.xml"

  val layoutPath: String
    get() = "$path/layout/"

  val drawables: Array<out File>?
    get() {
      val file = File("$path/drawable/")

      if (!file.exists()) {
        FileUtil.makeDir("$path/drawable/")
      }

      return file.listFiles()
    }

  val fonts: Array<out File>?
    get() {
      val file = File("$path/font/")

      if (!file.exists()) {
        FileUtil.makeDir("$path/font/")
      }

      return file.listFiles()
    }

  val layouts: Array<out File>?
    get() {
      val file = File(layoutPath)
      if (!file.exists()) {
        FileUtil.makeDir(layoutPath)
      }
      return file.listFiles()
    }

  val allLayouts: MutableList<LayoutFile>
    get() {
      val list: MutableList<LayoutFile> = mutableListOf()
      layouts?.forEach { list.add(LayoutFile(it.absolutePath)) }
      return list
    }

  val mainLayout: LayoutFile
    get() = LayoutFile("$path/layout/$mainLayoutName.xml")

  var currentLayout: LayoutFile
    get() {
      val currentLayoutPath = PreferencesManager.prefs.getString(Constants.CURRENT_LAYOUT, "")
      return LayoutFile(currentLayoutPath)
    }
    set(value) {
      PreferencesManager.prefs.edit().putString(Constants.CURRENT_LAYOUT, value.path).apply()
    }

  fun createDefaultLayout() {
    FileUtil.writeFile(layoutPath + "layout_main.xml", "")
  }

  override fun describeContents(): Int {
    return 0
  }

  override fun writeToParcel(parcel: Parcel, flags: Int) {
    parcel.writeString(path)
    parcel.writeString(name)
  }

  private constructor(parcel: Parcel, mainLayoutName: String) {
    path = parcel.readString().toString()
    name = parcel.readString().toString()
    this.mainLayoutName = mainLayoutName
  }

  companion object {
    @JvmField
    val CREATOR: Creator<ProjectFile> = object : Creator<ProjectFile> {
      @Contract("_ -> new")
      override fun createFromParcel(`in`: Parcel): ProjectFile {
        return ProjectFile(`in`, "")
      }

      @Contract(value = "_ -> new", pure = true)
      override fun newArray(size: Int): Array<ProjectFile?> {
        return arrayOfNulls(size)
      }
    }
  }
}
