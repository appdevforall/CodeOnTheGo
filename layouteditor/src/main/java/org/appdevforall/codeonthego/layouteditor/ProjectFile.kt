package org.appdevforall.codeonthego.layouteditor

import android.content.Context
import android.os.Parcel
import android.os.Parcelable
import android.os.Parcelable.Creator
import org.appdevforall.codeonthego.layouteditor.managers.PreferencesManager
import org.appdevforall.codeonthego.layouteditor.utils.Constants
import org.appdevforall.codeonthego.layouteditor.utils.FileUtil
import com.itsaky.androidide.utils.formatDate
import org.jetbrains.annotations.Contract
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths

class ProjectFile : Parcelable {

  var path: String
    private set

  @JvmField
  var name: String

  @JvmField
  var createdAt: String? = null

  @JvmField
  var lastModified: String? = null

  private val mainLayoutName: String
  private lateinit var preferencesManager: PreferencesManager

  constructor(path: String, createdAt: String?, lastModified: String?, context: Context,
    mainLayoutName: String = "layout_main") {

    this.path = path
    this.createdAt = createdAt
    this.lastModified = lastModified
    this.name = FileUtil.getLastSegmentFromPath(path)
    this.mainLayoutName = mainLayoutName
    this.preferencesManager = PreferencesManager(context = context)
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
  val layoutDesignPath: String
    get() = "$path/layout/design/"

    val drawables: Array<File>
        get() {
            val file = File("$path/drawable")

            if (!file.exists()) {
                file.mkdirs()
                return emptyArray()
            }

            return file.listFiles() ?: emptyArray()
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

  val layoutDesigns: Array<out File>?
    get() {
      val file = File(layoutDesignPath)
      if (!file.exists()) {
        FileUtil.makeDir(layoutDesignPath)
      }
      return file.listFiles()
    }

    val allLayouts: MutableList<LayoutFile>
        get() {
            val list: MutableList<LayoutFile> = mutableListOf()
            layouts?.forEach { file ->
                val designFile = File(layoutDesignPath, file.name)
                list.add(LayoutFile(file.absolutePath, designFile.absolutePath))
            }
            return list
        }


    val mainLayout: LayoutFile
        get() = LayoutFile("$layoutPath$mainLayoutName.xml", "$layoutDesignPath$mainLayoutName.xml")

  val mainLayoutDesign: LayoutFile
    get() {
      Files.createDirectories(Paths.get(layoutDesignPath))
      val file = File("$layoutPath$mainLayoutName.xml", "$layoutDesignPath$mainLayoutName.xml")
      file.createNewFile()
      return LayoutFile("$layoutPath$mainLayoutName.xml", "$layoutDesignPath$mainLayoutName.xml")
    }

    var currentLayout: LayoutFile
        get() {
            val currentLayoutPath =
                preferencesManager.prefs.getString(Constants.CURRENT_LAYOUT, "") ?: ""
            val currentLayoutDesignPath =
                preferencesManager.prefs.getString(Constants.CURRENT_LAYOUT_DESIGN, "") ?: ""
            return LayoutFile(currentLayoutPath, currentLayoutDesignPath)
        }
        set(value) {
            preferencesManager.prefs.edit().apply {
                putString(Constants.CURRENT_LAYOUT, value.path)
                putString(Constants.CURRENT_LAYOUT_DESIGN, value.designPath)
                apply()
            }
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

  fun renderDateText(context: Context): String {
    val showModified = createdAt != lastModified
    val renderDate = if (showModified) lastModified else createdAt

    val label = if (showModified)
      context.getString(R.string.date_modified_label)
    else
      context.getString(R.string.date_created_label)

    return context.getString(
      R.string.date,
      label,
      formatDate(renderDate ?: "")
    )
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
