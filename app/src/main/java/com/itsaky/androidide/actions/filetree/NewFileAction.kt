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
import android.content.DialogInterface
import android.view.LayoutInflater
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import com.itsaky.androidide.actions.ActionData
import com.itsaky.androidide.actions.FileActionManager
import com.itsaky.androidide.actions.observers.FileActionObserver
import com.itsaky.androidide.actions.requireFile
import com.itsaky.androidide.databinding.LayoutCreateFileCppBinding
import com.itsaky.androidide.databinding.LayoutCreateFileJavaBinding
import com.itsaky.androidide.eventbus.events.file.FileCreationEvent
import com.itsaky.androidide.idetooltips.TooltipTag
import com.itsaky.androidide.idetooltips.attachTooltip
import com.itsaky.androidide.preferences.databinding.LayoutDialogTextInputBinding
import com.itsaky.androidide.projects.IProjectManager
import com.itsaky.androidide.projects.ProjectManagerImpl
import com.itsaky.androidide.resources.R
import com.itsaky.androidide.templates.base.models.Dependency
import com.itsaky.androidide.utils.ClassBuilder.SourceLanguage
import com.itsaky.androidide.utils.DialogUtils
import com.itsaky.androidide.utils.Environment
import com.itsaky.androidide.utils.FileIOUtils
import com.itsaky.androidide.utils.NativeSourceBuilder
import com.itsaky.androidide.utils.ProjectWriter
import com.itsaky.androidide.utils.SingleTextWatcher
import com.itsaky.androidide.utils.flashError
import com.itsaky.androidide.utils.flashSuccess
import com.unnamed.b.atv.model.TreeNode
import jdkx.lang.model.SourceVersion
import kotlinx.coroutines.CancellationException
import org.greenrobot.eventbus.EventBus
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.FileAlreadyExistsException
import java.util.Objects
import java.util.regex.Pattern

/**
 * File tree action to create a new file.
 *
 * @author Akash Yadav
 */
class NewFileAction(
	val context: Context,
	override val order: Int,
) : BaseDirNodeAction(
		context = context,
		labelRes = R.string.new_file,
		iconRes = R.drawable.ic_new_file,
	),
	KoinComponent,
	FileActionObserver {
	private val fileActionManager: FileActionManager = get()

	private var currentNode: TreeNode? = null

	override val id: String = "ide.editor.fileTree.newFile"

	override fun retrieveTooltipTag(isReadOnlyContext: Boolean): String = TooltipTag.PROJECT_FOLDER_NEWFILE

	companion object {
		const val RES_PATH_REGEX = "/.*/src/.*/res"
		const val LAYOUT_RES_PATH_REGEX = "/.*/src/.*/res/layout"
		const val MENU_RES_PATH_REGEX = "/.*/src/.*/res/menu"
		const val DRAWABLE_RES_PATH_REGEX = "/.*/src/.*/res/drawable"
		const val JAVA_PATH_REGEX = "/.*/src/.*/java"
		const val CPP_PATH_REGEX = "/.*/src/[^/]+/cpp(/.*)?$"
		private const val MAX_FILE_NAME_LENGTH = 40

		private val log = LoggerFactory.getLogger(NewFileAction::class.java)
	}

	override suspend fun execAction(data: ActionData) {
		val context = data.requireActivity()
		val file = data.requireFile()
		val node = data.getTreeNode()
		try {
			createNewFile(context, node, file, false)
		} catch (e: CancellationException) {
			throw e
		} catch (e: Exception) {
			log.error("Failed to create new file", e)
			flashError(e.cause?.message ?: e.message)
		}
	}

	private fun createNewFile(
		context: Context,
		node: TreeNode?,
		file: File,
		forceUnknownType: Boolean,
	) {
		if (forceUnknownType) {
			createNewEmptyFile(context, node, file)
			return
		}

		val projectDir = IProjectManager.getInstance().projectDirPath
		Objects.requireNonNull(projectDir)
		val isRes =
			Pattern.compile(Pattern.quote(projectDir) + RES_PATH_REGEX).matcher(file.absolutePath).find()
		val isLayoutRes =
			Pattern
				.compile(Pattern.quote(projectDir) + LAYOUT_RES_PATH_REGEX)
				.matcher(file.absolutePath)
				.find()
		val isMenuRes =
			Pattern
				.compile(Pattern.quote(projectDir) + MENU_RES_PATH_REGEX)
				.matcher(file.absolutePath)
				.find()
		val isDrawableRes =
			Pattern
				.compile(Pattern.quote(projectDir) + DRAWABLE_RES_PATH_REGEX)
				.matcher(file.absolutePath)
				.find()

		when (sourceDialogFor(projectDir, file.absolutePath)) {
			SourceDialog.CPP -> {
				createNativeSource(context, node, file)
				return
			}

			SourceDialog.JAVA -> {
				createJavaClass(context, node, file)
				return
			}

			null -> {}
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

	private fun createJavaClass(
		context: Context,
		node: TreeNode?,
		file: File,
	) {
		val builder = DialogUtils.newMaterialDialogBuilder(context)
		val binding: LayoutCreateFileJavaBinding =
			LayoutCreateFileJavaBinding.inflate(LayoutInflater.from(context))
		binding.typeGroup.addOnButtonCheckedListener { _, _, _ ->
			binding.createLayout.isVisible = binding.typeGroup.checkedButtonId == binding.typeActivity.id
		}
		binding.name.editText?.addTextChangedListener(
			object : SingleTextWatcher() {
				override fun onTextChanged(
					s: CharSequence?,
					start: Int,
					before: Int,
					count: Int,
				) {
					if (isValidJavaName(s)) {
						binding.name.isErrorEnabled = true
						binding.name.error = context.getString(R.string.msg_invalid_name)
					} else {
						binding.name.isErrorEnabled = false
					}
				}
			},
		)
		builder.setView(binding.root)
		builder.setTitle(R.string.new_file)
		builder.setPositiveButton(R.string.text_create) { dialogInterface, _ ->
			dialogInterface.dismiss()
			try {
				doCreateSourceFile(binding, file, context, node)
			} catch (e: CancellationException) {
				throw e
			} catch (e: Exception) {
				log.error("Failed to create source file", e)
				flashError(e.cause?.message ?: e.message)
			}
		}
		builder.setNegativeButton(android.R.string.cancel, null)
		builder
			.setCancelable(false)
			.create()
			.attachTooltip(TooltipTag.PROJECT_FOLDER_NEWTYPE)
			.show()
	}

	private fun doCreateSourceFile(
		binding: LayoutCreateFileJavaBinding,
		file: File,
		context: Context,
		node: TreeNode?,
	) {
		if (binding.name.isErrorEnabled) {
			flashError(R.string.msg_invalid_name)
			return
		}

		val name: String =
			binding.name.editText!!
				.text
				.toString()
				.trim()
		if (name.isBlank()) {
			flashError(R.string.msg_invalid_name)
			return
		}

		val isKotlin = binding.languageGroup.checkedButtonId == binding.langKotlin.id
		val language = if (isKotlin) SourceLanguage.KOTLIN else SourceLanguage.JAVA
		val extension = if (isKotlin) ".kt" else ".java"

		val autoLayout =
			binding.typeGroup.checkedButtonId == binding.typeActivity.id &&
				binding.createLayout.isChecked
		val pkgName = ProjectWriter.getPackageName(file)

		val id: Int = binding.typeGroup.checkedButtonId
		val fileName = if (name.endsWith(extension)) name else "$name$extension"
		val className = if (!name.contains(".")) name else name.substring(0, name.lastIndexOf("."))

		val sourceFileDirectory =
			if (pkgName == "com") {
				val subDir = File(file, "com")
				if (subDir.exists() && subDir.isDirectory) subDir else file
			} else {
				file
			}

		when (id) {
			binding.typeClass.id -> {
				createFile(
					node,
					sourceFileDirectory,
					fileName,
					ProjectWriter.createClass(pkgName, className, language),
				)
			}

			binding.typeInterface.id -> {
				createFile(
					node,
					sourceFileDirectory,
					fileName,
					ProjectWriter.createInterface(pkgName, className, language),
				)
			}

			binding.typeEnum.id -> {
				createFile(
					node,
					sourceFileDirectory,
					fileName,
					ProjectWriter.createEnum(pkgName, className, language),
				)
			}

			binding.typeActivity.id -> {
				val appCompat = Dependency.AndroidX.AppCompat
				val projectManager = ProjectManagerImpl.getInstance()
				val hasAppCompatDependency =
					projectManager
						.findModuleForFile(file)
						?.hasExternalDependency(appCompat.group, appCompat.artifact)
				createFile(
					node,
					sourceFileDirectory,
					fileName,
					ProjectWriter.createActivity(
						pkgName,
						className,
						hasAppCompatDependency ?: false,
						language,
					),
				)
			}

			else -> {
				createFile(node, sourceFileDirectory, name, "")
			}
		}

		if (autoLayout) {
			val packagePath = pkgName.toString().replace(".", "/")
			createAutoLayout(context, sourceFileDirectory, name, packagePath, isKotlin)
		}
	}

	private fun isValidJavaName(s: CharSequence?) = s == null || !SourceVersion.isName(s) || SourceVersion.isKeyword(s)

	private fun createNativeSource(
		context: Context,
		node: TreeNode?,
		directory: File,
	) {
		val binding = LayoutCreateFileCppBinding.inflate(LayoutInflater.from(context))
		val dialog =
			DialogUtils
				.newMaterialDialogBuilder(context)
				.setView(binding.root)
				.setTitle(R.string.new_file)
				.setPositiveButton(R.string.text_create, null)
				.setNegativeButton(android.R.string.cancel, null)
				.setCancelable(false)
				.create()
				.attachTooltip(TooltipTag.PROJECT_FOLDER_NEWFILE)
		binding.languageGroup.addOnButtonCheckedListener { _, _, _ -> refreshNativeDialog(context, binding, dialog) }
		binding.typeGroup.addOnButtonCheckedListener { _, _, _ -> refreshNativeDialog(context, binding, dialog) }
		binding.name.editText?.addTextChangedListener(
			object : SingleTextWatcher() {
				override fun onTextChanged(
					s: CharSequence?,
					start: Int,
					before: Int,
					count: Int,
				) {
					refreshNativeDialog(context, binding, dialog)
				}
			},
		)

		dialog.show()
		dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener {
			doCreateNativeSource(context, binding, dialog, directory, node)
		}
		refreshNativeDialog(context, binding, dialog)
	}

	private fun refreshNativeDialog(
		context: Context,
		binding: LayoutCreateFileCppBinding,
		dialog: AlertDialog,
	) {
		val language = nativeLanguage(binding)
		val isCpp = language == NativeSourceBuilder.Language.CPP
		if (!isCpp && binding.typeGroup.checkedButtonId == binding.typeClass.id) {
			binding.typeGroup.check(binding.typeSource.id)
		}
		binding.typeClass.isVisible = isCpp

		val kind = nativeKind(binding)
		binding.languageGroup.isEnabled = kind != NativeSourceBuilder.Kind.OTHER
		binding.name.suffixText =
			NativeSourceBuilder
				.extensions(language, kind)
				.joinToString(" + ") { ".$it" }
				.ifEmpty { null }
		binding.name.counterMaxLength = NativeSourceBuilder.maxNameLength(language, kind, MAX_FILE_NAME_LENGTH)

		val name = nativeName(binding)
		val isValid = NativeSourceBuilder.isValidName(name, language, kind, MAX_FILE_NAME_LENGTH)
		val showError = name.isNotEmpty() && !isValid
		binding.name.isErrorEnabled = showError
		binding.name.error = if (showError) context.getString(R.string.msg_invalid_name) else null
		dialog.getButton(DialogInterface.BUTTON_POSITIVE)?.isEnabled = isValid
	}

	private fun doCreateNativeSource(
		context: Context,
		binding: LayoutCreateFileCppBinding,
		dialog: AlertDialog,
		directory: File,
		node: TreeNode?,
	) {
		val files = NativeSourceBuilder.createFiles(nativeName(binding), nativeLanguage(binding), nativeKind(binding))
		val createButton = dialog.getButton(DialogInterface.BUTTON_POSITIVE)
		createButton.isEnabled = false
		fileActionManager.createNewFiles(directory, files.map { it.name to it.content }) { result ->
			result
				.onSuccess {
					dialog.dismiss()
					onFilesCreated(node)
				}.onFailure { error ->
					createButton.isEnabled = true
					if (error is FileAlreadyExistsException) {
						binding.name.isErrorEnabled = true
						binding.name.error = context.getString(R.string.msg_file_exists)
					} else {
						log.error("Failed to create native source files", error)
						flashError(error.message)
					}
				}
		}
	}

	private fun nativeName(binding: LayoutCreateFileCppBinding): String =
		binding.name.editText!!
			.text
			.toString()
			.trim()

	private fun nativeLanguage(binding: LayoutCreateFileCppBinding): NativeSourceBuilder.Language =
		when (val id = binding.languageGroup.checkedButtonId) {
			binding.langC.id -> NativeSourceBuilder.Language.C
			binding.langCpp.id -> NativeSourceBuilder.Language.CPP
			else -> error("Unexpected language button: $id")
		}

	private fun nativeKind(binding: LayoutCreateFileCppBinding): NativeSourceBuilder.Kind =
		when (val id = binding.typeGroup.checkedButtonId) {
			binding.typeSource.id -> NativeSourceBuilder.Kind.SOURCE
			binding.typeHeader.id -> NativeSourceBuilder.Kind.HEADER
			binding.typeClass.id -> NativeSourceBuilder.Kind.CLASS
			binding.typeOther.id -> NativeSourceBuilder.Kind.OTHER
			else -> error("Unexpected type button: $id")
		}

	private fun createLayoutRes(
		context: Context,
		node: TreeNode?,
		file: File,
	) {
		createNewFileWithContent(
			context,
			node,
			Environment.mkdirIfNotExists(file),
			ProjectWriter.createLayout(),
			".xml",
		)
	}

	private fun createAutoLayout(
		context: Context,
		directory: File,
		fileName: String,
		packagePath: String,
		isKotlin: Boolean = false,
	) {
		val dir = directory.toString().replace("java/$packagePath", "res/layout/")
		val sourceExtension = if (isKotlin) ".kt" else ".java"
		val layoutName = ProjectWriter.createLayoutName(fileName.replace(sourceExtension, ".xml"))
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

	private fun createMenuRes(
		context: Context,
		node: TreeNode?,
		file: File,
	) {
		createNewFileWithContent(
			context,
			node,
			Environment.mkdirIfNotExists(file),
			ProjectWriter.createMenu(),
			".xml",
		)
	}

	private fun createDrawableRes(
		context: Context,
		node: TreeNode?,
		file: File,
	) {
		createNewFileWithContent(
			context,
			node,
			Environment.mkdirIfNotExists(file),
			ProjectWriter.createDrawable(),
			".xml",
		)
	}

	private fun createNewResource(
		context: Context,
		node: TreeNode?,
		file: File,
	) {
		val labels =
			arrayOf(
				context.getString(R.string.restype_drawable),
				context.getString(R.string.restype_layout),
				context.getString(R.string.restype_menu),
				context.getString(R.string.restype_other),
			)
		val builder = DialogUtils.newMaterialDialogBuilder(context)
		builder.setTitle(R.string.new_xml_resource)
		builder
			.setItems(labels) { _, position ->
				when (position) {
					0 -> createDrawableRes(context, node, File(file, "drawable"))
					1 -> createLayoutRes(context, node, File(file, "layout"))
					2 -> createMenuRes(context, node, File(file, "menu"))
					3 -> createNewFile(context, node, file, true)
				}
			}.create()
			.attachTooltip(TooltipTag.PROJECT_FOLDER_NEWXML)
			.show()
	}

	private fun createNewEmptyFile(
		context: Context,
		node: TreeNode?,
		file: File,
	) {
		createNewFileWithContent(context, node, file, "")
	}

	private fun createNewFileWithContent(
		context: Context,
		node: TreeNode?,
		file: File,
		content: String,
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
				context.getString(R.string.msg_newfile_dest, folder.absolutePath),
		)
		builder.setView(binding.root)
		builder.setCancelable(false)
		builder.setPositiveButton(R.string.text_create) { dialogInterface, _ ->
			dialogInterface.dismiss()
			var name =
				binding.name.editText!!
					.text
					.toString()
					.trim()
			if (name.isBlank()) {
				flashError(R.string.msg_invalid_name)
				return@setPositiveButton
			}

			if (extension != null && extension.trim { it <= ' ' }.isNotEmpty()) {
				name = if (name.endsWith(extension)) name else name + extension
			}

			try {
				createFile(node, folder, name, content)
			} catch (e: CancellationException) {
				throw e
			} catch (e: Exception) {
				log.error("Failed to create file", e)
				flashError(e.cause?.message ?: e.message)
			}
		}
		builder
			.setNegativeButton(android.R.string.cancel, null)
			.create()
			.attachTooltip(TooltipTag.PROJECT_NEWFILE_DIALOG)
			.show()
	}

	private fun createFile(
		node: TreeNode?,
		directory: File,
		name: String,
		content: String,
	) {
		if (name.length !in 1..MAX_FILE_NAME_LENGTH || name.startsWith("/")) {
			flashError(R.string.msg_invalid_name)
			return
		}
		this.currentNode = node
		fileActionManager.createFile(directory, name, content, this)
	}

	private fun notifyFileCreated(
		file: File,
		context: Context,
	) {
		EventBus.getDefault().post(FileCreationEvent(file).putData(context))
	}

	override fun onActionSuccess(
		message: String,
		createdFile: File?,
	) {
		onFilesCreated(currentNode)
	}

	private fun onFilesCreated(node: TreeNode?) {
		flashSuccess(R.string.msg_file_created)
		if (node != null) {
			requestCollapseNode(node, false)
			requestExpandNode(node)
		} else {
			requestFileListing()
		}
	}

	override fun onActionFailure(errorMessage: String) {
		flashError(errorMessage)
	}
}

internal enum class SourceDialog {
	CPP,
	JAVA,
}

internal fun sourceDialogFor(
	projectDir: String,
	path: String,
): SourceDialog? {
	fun matches(regex: String) = Pattern.compile(Pattern.quote(projectDir) + regex).matcher(path).find()
	return when {
		matches(NewFileAction.CPP_PATH_REGEX) -> SourceDialog.CPP
		matches(NewFileAction.JAVA_PATH_REGEX) -> SourceDialog.JAVA
		else -> null
	}
}
