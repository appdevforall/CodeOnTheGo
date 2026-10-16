package com.itsaky.androidide.actions.etc

import android.content.Context
import android.os.Environment
import android.view.MenuItem
import androidx.core.content.ContextCompat
import com.itsaky.androidide.R
import com.itsaky.androidide.actions.ActionData
import com.itsaky.androidide.actions.EditorActivityAction
import com.itsaky.androidide.actions.markInvisible
import com.itsaky.androidide.actions.openApplicationModuleChooser
import com.itsaky.androidide.activities.editor.EditorHandlerActivity
import com.itsaky.androidide.idetooltips.TooltipTag.EDITOR_TOOLBAR_EXPORT_APK
import com.itsaky.androidide.models.ApkMetadata
import com.itsaky.androidide.projects.IProjectManager
import com.itsaky.androidide.projects.models.assembleTaskOutputListingFile
import com.itsaky.androidide.utils.ApkExport
import com.itsaky.androidide.utils.DialogUtils
import com.itsaky.androidide.utils.IntentUtils
import com.itsaky.androidide.utils.flashError
import com.itsaky.androidide.utils.flashSuccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.io.File
import java.io.IOException

/**
 * Hands over the selected variant's built APK (ADFA-4930): with relocated build output it is in
 * app-private storage, where a file manager cannot reach it. Offers the share sheet, or a copy in
 * the Download folder.
 */
class ExportApkAction(
	context: Context,
	override val order: Int,
) : EditorActivityAction() {
	override val id: String = "ide.editor.exportApk"
	override var requiresUIThread: Boolean = true

	override fun retrieveTooltipTag(isReadOnlyContext: Boolean) = EDITOR_TOOLBAR_EXPORT_APK

	init {
		label = context.getString(R.string.title_export_apk)
		icon = ContextCompat.getDrawable(context, R.drawable.ic_share)
	}

	override fun prepare(data: ActionData) {
		super.prepare(data)
		data.getActivity() ?: run {
			markInvisible()
			return
		}

		visible = true
		enabled = IProjectManager.getInstance().getAndroidAppModules().isNotEmpty()
	}

	override suspend fun execAction(data: ActionData) {
		openApplicationModuleChooser(data) { app ->
			val variant = app.getSelectedVariant()
			if (variant == null) {
				flashError(R.string.err_selected_variant_not_found)
				return@openApplicationModuleChooser
			}

			val activity = data.requireActivity()
			val name = "${File(IProjectManager.getInstance().projectDirPath).name}-${variant.name}"
			activity.activityScope.launch {
				val apk =
					withContext(Dispatchers.IO) {
						ApkMetadata.findApkFile(variant.mainArtifact.assembleTaskOutputListingFile)?.takeIf { it.isFile }
					}
				if (apk == null) {
					flashError(R.string.msg_export_apk_not_built)
					return@launch
				}
				withContext(Dispatchers.Main) { offerExport(activity, apk, name) }
			}
		}
	}

	private fun offerExport(
		activity: EditorHandlerActivity,
		apk: File,
		name: String,
	) {
		DialogUtils
			.newMaterialDialogBuilder(activity)
			.setTitle(R.string.title_export_apk)
			.setItems(
				arrayOf(
					activity.getString(R.string.export_apk_share),
					activity.getString(R.string.export_apk_copy_to_download),
				),
			) { _, which ->
				if (which == 0) {
					IntentUtils.shareFile(activity, apk, ApkExport.MIME_TYPE)
				} else {
					activity.activityScope.launch(Dispatchers.IO) { copyToDownload(activity, apk, name) }
				}
			}.setNegativeButton(android.R.string.cancel, null)
			.show()
	}

	private fun copyToDownload(
		activity: EditorHandlerActivity,
		apk: File,
		name: String,
	) {
		@Suppress("DEPRECATION")
		val download = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
		try {
			val copy = ApkExport.copyTo(apk, download, name)
			flashSuccess(activity.getString(R.string.msg_export_apk_saved, copy.name))
		} catch (e: IOException) {
			log.error("Cannot copy {} to {}", apk, download, e)
			flashError(R.string.msg_export_apk_failed)
		}
	}

	override fun getShowAsActionFlags(data: ActionData): Int = MenuItem.SHOW_AS_ACTION_IF_ROOM

	companion object {
		private val log = LoggerFactory.getLogger(ExportApkAction::class.java)
	}
}
