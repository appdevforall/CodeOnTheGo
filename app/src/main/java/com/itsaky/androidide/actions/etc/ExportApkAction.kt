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
import com.itsaky.androidide.lookup.Lookup
import com.itsaky.androidide.projects.IProjectManager
import com.itsaky.androidide.projects.builder.BuildService
import com.itsaky.androidide.projects.models.assembleTaskOutputListingFile
import com.itsaky.androidide.projects.models.buildDir
import com.itsaky.androidide.quickbuild.QuickBuildTaskPaths
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
		// The user-visible flag, like the build actions: Quick Build's prebuild after every project
		// open is refused at the tap instead (isBuilding), with a reason.
		enabled =
			IProjectManager.getInstance().getAndroidAppModules().isNotEmpty() &&
			buildService()?.isUserVisibleBuildInProgress != true
	}

	override suspend fun execAction(data: ActionData) {
		if (isBuilding()) return
		openApplicationModuleChooser(data) { app ->
			val variant = app.getSelectedVariant()
			if (variant == null) {
				flashError(R.string.err_selected_variant_not_found)
				return@openApplicationModuleChooser
			}

			val activity = data.requireActivity()
			val projectManager = IProjectManager.getInstance()
			val projectDir = File(projectManager.projectDirPath)
			// The module only when it tells two app modules' exports apart.
			val module = app.name.takeIf { projectManager.getAndroidAppModules().size > 1 }
			val name = listOfNotNull(projectDir.name, module, variant.name).joinToString("-")
			activity.activityScope.launch {
				val found =
					withContext(Dispatchers.IO) {
						ApkExport.resolve(
							listingFile = variant.mainArtifact.assembleTaskOutputListingFile,
							quickBuildReport = QuickBuildTaskPaths.setupJson(app.buildDir, variant.name),
							projectDir = projectDir,
						)
					}
				when (found) {
					ApkExport.Exportable.NotBuilt -> flashError(R.string.msg_export_apk_not_built)
					ApkExport.Exportable.QuickBuildProxy -> flashError(R.string.msg_export_apk_quick_build)
					is ApkExport.Exportable.Apk -> withContext(Dispatchers.Main) { offerExport(activity, found.file, name) }
				}
			}
		}
	}

	private fun buildService(): BuildService? = Lookup.getDefault().lookup(BuildService.KEY_BUILD_SERVICE)

	/** A build, Quick Build's prebuild included, may be rewriting the APK; refuse with a reason. */
	private fun isBuilding(): Boolean {
		if (buildService()?.isBuildInProgress != true) return false
		flashError(R.string.msg_export_apk_build_running)
		return true
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
				} else if (!isBuilding()) {
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
