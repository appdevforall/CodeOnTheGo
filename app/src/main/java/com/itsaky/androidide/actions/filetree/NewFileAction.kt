/*
 *  This file is part of AndroidIDE.
 *
 *  AndroidIDE is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  AndroidIDE is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *   along with AndroidIDE.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.itsaky.androidide.actions.filetree

import android.content.Context
import android.view.LayoutInflater
import androidx.core.view.isVisible
import com.blankj.utilcode.util.FileIOUtils
import com.itsaky.androidide.actions.ActionData
import com.itsaky.androidide.actions.FileActionManager
import com.itsaky.androidide.actions.observers.FileActionObserver
import com.itsaky.androidide.actions.requireFile
import com.itsaky.androidide.adapters.viewholders.FileTreeViewHolder
import com.itsaky.androidide.api.commands.CreateFileCommand
import com.itsaky.androidide.databinding.LayoutCreateFileJavaBinding
import com.itsaky.androidide.eventbus.events.file.FileCreationEvent
import com.itsaky.androidide.idetooltips.TooltipTag
import com.itsaky.androidide.preferences.databinding.LayoutDialogTextInputBinding
import com.itsaky.androidide.projects.IProjectManager
import com.itsaky.androidide.projects.ProjectManagerImpl
import com.itsaky.androidide.resources.R
import com.itsaky.androidide.templates.base.models.Dependency
import com.itsaky.androidide.utils.DialogUtils
import com.itsaky.androidide.utils.Environment
import com.itsaky.androidide.utils.ProjectWriter
import com.itsaky.androidide.utils.SingleTextWatcher
import com.itsaky.androidide.utils.flashError
import com.itsaky.androidide.utils.flashSuccess
import com.itsaky.androidide.utils.showWithLongPressTooltip
import com.unnamed.b.atv.model.TreeNode
import jdkx.lang.model.SourceVersion
import org.greenrobot.eventbus.EventBus
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import org.slf4j.LoggerFactory
import java.io.File
import java.util.Objects
import java.util.regex.Pattern

/**
 * File tree action to create a new file.
 *
 * @author Akash Yadav
 */
class NewFileAction(val context: Context, override val order: Int) :
  BaseDirNodeAction(
    context = context,
    labelRes = R.string.new_file,
    iconRes = R.drawable.ic_new_file
  ), KoinComponent, FileActionObserver {

  private val fileActionManager: FileActionManager = get()

  private var currentNode: TreeNode? = null

  override val id: String = "ide.editor.fileTree.newFile"

    override fun retrieveTooltipTag(isReadOnlyContext: Boolean): String =
        TooltipTag.PROJECT_FOLDER_NEWFILE

  companion object {

    const val RES_PATH_REGEX = "/.*/src/.*/res"
    const val LAYOUT_RES_PATH_REGEX = "/.*/src/.*/res/layout"
    const val MENU_RES_PATH_REGEX = "/.*/src/.*/res/menu"
    const val DRAWABLE_RES_PATH_REGEX = "/.*/src/.*/res/drawable"
    const val JAVA_PATH_REGEX = "/.*/src/.*/java"

    private val log = LoggerFactory.getLogger(NewFileAction::class.java)
  }

  override suspend fun execAction(data: ActionData) {
    val context = data.requireActivity()
    val file = data.requireFile()
    val node = data.getTreeNode()
    try {
      createNewFile(context, node, file, false)
    } catch (e: Exception) {
      log.error("Failed to create new file", e)
      flashError(e.cause?.message ?: e.message)
    }
  }

  private fun createNewFile(
    context: Context,
    node: TreeNode?,
    file: File,
    forceUnknownType: Boolean
  ) {
    if (forceUnknownType) {
      createNewEmptyFile(context, node, file)
      return
    }

    val projectDir = IProjectManager.getInstance().projectDirPath
    Objects.requireNonNull(projectDir)
    val isJava =
      Pattern.compile(Pattern.quote(projectDir) + JAVA_PATH_REGEX).matcher(file.absolutePath).find()
    val isRes =
      Pattern.compile(Pattern.quote(projectDir) + RES_PATH_REGEX).matcher(file.absolutePath).find()
    val isLayoutRes =
      Pattern.compile(Pattern.quote(projectDir) + LAYOUT_RES_PATH_REGEX)
        .matcher(file.absolutePath)
        .find()
    val isMenuRes =
      Pattern.compile(Pattern.quote(projectDir) + MENU_RES_PATH_REGEX)
        .matcher(file.absolutePath)
        .find()
    val isDrawableRes =
      Pattern.compile(Pattern.quote(projectDir) + DRAWABLE_RES_PATH_REGEX)
        .matcher(file.absolutePath)
        .find()

    if (isJava) {
      createJavaClass(context, node, file)
      return
    }

    if (isLayoutRes && file.name == "layout") {
      createLayoutRes(context, node, file)
      return
    }

    if (isMenuRes && file.name == "menu") {
      createMenuRes(context, node, file)
      return
    }

    if (isDrawableRes && file.name == "drawable") {
      createDrawableRes(context, node, file)
      return
    }

    if (isRes && file.name == "res") {
      createNewResource(context, node, file)
      return
    }

    createNewEmptyFile(context, node, file)
  }

  private fun createJavaClass(context: Context, node: TreeNode?, file: File) {
    val builder = DialogUtils.newMaterialDialogBuilder(context)
    val binding: LayoutCreateFileJavaBinding =
      LayoutCreateFileJavaBinding.inflate(LayoutInflater.from(context))
    binding.typeGroup.addOnButtonCheckedListener { _, _, _ ->
      binding.createLayout.isVisible = binding.typeGroup.checkedButtonId == binding.typeActivity.id
    }
    binding.name.editText?.addTextChangedListener(
      object : SingleTextWatcher() {
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
          if (isValidJavaName(s)) {
            binding.name.isErrorEnabled = true
            binding.name.error = context.getString(R.string.msg_invalid_name)
          } else {
            binding.name.isErrorEnabled = false
          }
        }
      }
    )
    builder.setView(binding.root)
    builder.setTitle(R.string.new_java_class)
    builder.setPositiveButton(R.string.text_create) { dialogInterface, _ ->
      dialogInterface.dismiss()
      try {
        doCreateJavaFile(binding, file, context, node)
      } catch (e: Exception) {
        log.error("Failed to create Java file", e)
        flashError(e.cause?.message ?: e.message)
      }
    }
    builder.setNegativeButton(android.R.string.cancel, null)
    builder.setCancelable(false)
        .showWithLongPressTooltip(
            context = context,
            tooltipTag = TooltipTag.PROJECT_FOLDER_NEWTYPE,
            // Pass the specific buttons to the helper function
            binding.typeClass,
            binding.typeActivity,
            binding.typeInterface,
            binding.typeEnum
        )
  }

  private fun doCreateJavaFile(
    binding: LayoutCreateFileJavaBinding,
    file: File,
    context: Context,
    node: TreeNode?
  ) {
    if (binding.name.isErrorEnabled) {
      flashError(R.string.msg_invalid_name)
      return
    }

    val name: String = binding.name.editText!!.text.toString().trim()
    if (name.isBlank()) {
      flashError(R.string.msg_invalid_name)
      return
    }

    val autoLayout =
      binding.typeGroup.checkedButtonId == binding.typeActivity.id &&
          binding.createLayout.isChecked
    val pkgName = ProjectWriter.getPackageName(file)

    val id: Int = binding.typeGroup.checkedButtonId
    val javaName = if (name.endsWith(".java")) name else "$name.java"
    val className = if (!name.contains(".")) name else name.substring(0, name.lastIndexOf("."))

    // Package Structure Check: When the package is "com", it checks if a com subdirectory exists within the current directory.
    // If com/ exists, it means the package structure is organized correctly.
    // The Java file should be created within com/ to maintain package consistency.
    // If the com subdirectory does not exist, use the original directory (file), avoiding FileNotFoundException errors.
    val javaFileDirectory = if (pkgName == "com") {
      val subDir = File(file, "com")
      if (subDir.exists() && subDir.isDirectory) subDir else file
    } else {
      file
    }

      when (id) {
        binding.typeClass.id ->
          createFile(
              node,
              javaFileDirectory,
              javaName,
              ProjectWriter.createJavaClass(pkgName, className),
          )

        binding.typeInterface.id ->
          createFile(
            node,
            javaFileDirectory,
            javaName,
            ProjectWriter.createJavaInterface(pkgName, className)
          )

        binding.typeEnum.id ->
          createFile(
            node,
            javaFileDirectory,
            javaName,
            ProjectWriter.createJavaEnum(pkgName, className)
          )

		  binding.typeActivity.id -> {
			  val appCompat = Dependency.AndroidX.AppCompat
			  val projectManager = ProjectManagerImpl.getInstance()
			  val hasAppCompatDependency = projectManager.findModuleForFile(file)
				  ?.hasExternalDependency(appCompat.group, appCompat.artifact)
			  createFile(
				  node,
				  javaFileDirectory,
				  javaName,
				  ProjectWriter.createActivity(
					  pkgName,
					  className,
					  hasAppCompatDependency ?: false
				  )
			  )
		  }

        else -> createFile(node, javaFileDirectory, name, "")
      }

    node?.let {
      requestCollapseNode(it, true)
    }

    if (autoLayout) {
      val packagePath = pkgName.toString().replace(".", "/")
      createAutoLayout(context, javaFileDirectory, name, packagePath)
    }
  }

  private fun isValidJavaName(s: CharSequence?) =
    s == null || !SourceVersion.isName(s) || SourceVersion.isKeyword(s)

  private fun createLayoutRes(context: Context, node: TreeNode?, file: File) {
    createNewFileWithContent(
      context,
      node,
      Environment.mkdirIfNotExits(file),
      ProjectWriter.createLayout(),
      ".xml"
    )
  }

  private fun createAutoLayout(
    context: Context,
    directory: File,
    fileName: String,
    packagePath: String
  ) {
    val dir = directory.toString().replace("java/$packagePath", "res/layout/")
    val layoutName = ProjectWriter.createLayoutName(fileName.replace(".java", ".xml"))
    val newFileLayout = File(dir, layoutName)
    if (newFileLayout.exists()) {
      flashError(R.string.msg_layout_file_exists)
      return
    }

    if (!FileIOUtils.writeFileFromString(newFileLayout, ProjectWriter.createLayout())) {
      flashError(R.string.msg_layout_file_creation_failed)
      return
    }

    notifyFileCreated(newFileLayout, context)
  }

  private fun createMenuRes(context: Context, node: TreeNode?, file: File) {
    createNewFileWithContent(
      context,
      node,
      Environment.mkdirIfNotExits(file),
      ProjectWriter.createMenu(),
      ".xml"
    )
  }

  private fun createDrawableRes(context: Context, node: TreeNode?, file: File) {
    createNewFileWithContent(
      context,
      node,
      Environment.mkdirIfNotExits(file),
      ProjectWriter.createDrawable(),
      ".xml"
    )
  }

  private fun createNewResource(context: Context, node: TreeNode?, file: File) {
    val labels =
      arrayOf(
        context.getString(R.string.restype_drawable),
        context.getString(R.string.restype_layout),
        context.getString(R.string.restype_menu),
        context.getString(R.string.restype_other)
      )
    val builder = DialogUtils.newMaterialDialogBuilder(context)
    builder.setTitle(R.string.new_xml_resource)
    builder.setItems(labels) { _, position ->
      when (position) {
        0 -> createDrawableRes(context, node, File(file, "drawable"))
        1 -> createLayoutRes(context, node, File(file, "layout"))
        2 -> createMenuRes(context, node, File(file, "menu"))
        3 -> createNewFile(context, node, file, true)
      }
    }
        .showWithLongPressTooltip(
            context = context,
            tooltipTag = TooltipTag.PROJECT_FOLDER_NEWXML
        )
  }

  private fun createNewEmptyFile(context: Context, node: TreeNode?, file: File) {
    createNewFileWithContent(context, node, file, "")
  }

  private fun createNewFileWithContent(
    context: Context,
    node: TreeNode?,
    file: File,
    content: String
  ) {
    createNewFileWithContent(context, node, file, content, null)
  }

  private fun createNewFileWithContent(
    context: Context,
    node: TreeNode?,
    folder: File,
    content: String,
    extension: String?,
  ) {
    val binding = LayoutDialogTextInputBinding.inflate(LayoutInflater.from(context))
    val builder = DialogUtils.newMaterialDialogBuilder(context)
    binding.name.editText!!.setHint(R.string.file_name)
    builder.setTitle(R.string.new_file)
    builder.setMessage(
      context.getString(R.string.msg_can_contain_slashes) +
          "\n\n" +
          context.getString(R.string.msg_newfile_dest, folder.absolutePath)
    )
    builder.setView(binding.root)
    builder.setCancelable(false)
    builder.setPositiveButton(R.string.text_create) { dialogInterface, _ ->
      dialogInterface.dismiss()
      var name = binding.name.editText!!.text.toString().trim()
      if (name.isBlank()) {
        flashError(R.string.msg_invalid_name)
        return@setPositiveButton
      }

      if (extension != null && extension.trim { it <= ' ' }.isNotEmpty()) {
        name = if (name.endsWith(extension)) name else name + extension
      }

      try {
        createFile(node, folder, name, content)
      } catch (e: Exception) {
        log.error("Failed to create file", e)
        flashError(e.cause?.message ?: e.message)
      }
    }
    builder.setNegativeButton(android.R.string.cancel, null)
        .showWithLongPressTooltip(
            context = context,
            tooltipTag = TooltipTag.PROJECT_NEWFILE_DIALOG
        )
  }

  private fun createFile(
    node: TreeNode?,
    directory: File,
    name: String,
    content: String
  ) {
    if (name.length !in 1..40 || name.startsWith("/")) {
      flashError(R.string.msg_invalid_name)
      return
    }
    this.currentNode = node
    val command = CreateFileCommand(directory, name, content)
    fileActionManager.execute(command, this)
  }

  private fun notifyFileCreated(file: File, context: Context) {
    EventBus.getDefault().post(FileCreationEvent(file).putData(context))
  }

  override fun onActionSuccess(message: String, createdFile: File?) {
    flashSuccess(R.string.msg_file_created)
    if (currentNode != null && createdFile != null) {
      val newNode = TreeNode(createdFile)
      newNode.viewHolder = FileTreeViewHolder(this.context)
      currentNode!!.addChild(newNode)
      requestExpandNode(currentNode!!)
    } else {
      requestFileListing()
    }
  }

  override fun onActionFailure(errorMessage: String) {
    flashError(errorMessage)
  }
}
