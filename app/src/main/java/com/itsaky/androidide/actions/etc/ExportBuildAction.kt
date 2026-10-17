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
import com.itsaky.androidide.projects.isPluginProject
import com.itsaky.androidide.projects.models.assembleTaskOutputListingFile
import com.itsaky.androidide.projects.models.buildDir
import com.itsaky.androidide.quickbuild.QuickBuildTaskPaths
import com.itsaky.androidide.utils.BuildExport
import com.itsaky.androidide.utils.DialogUtils
import com.itsaky.androidide.utils.IntentUtils
import com.itsaky.androidide.utils.flashError
import com.itsaky.androidide.utils.flashSuccess
import com.itsaky.androidide.viewmodel.findPluginCgpFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.io.File
import java.io.IOException

/**
 * Hands over what the selected variant built (ADFA-4930): its APK, its App Bundle, or a plugin
 * project's `.cgp`. With relocated build output they are in app-private storage, where a file
 * manager cannot reach them. Offers the share sheet, or a copy in the Download folder.
 */
class ExportBuildAction(
	context: Context,
	override val order: Int,
) : EditorActivityAction() {
	// Named when only the APK was exported; documentation.db keys the tooltip on the tag.
	override val id: String = "ide.editor.exportApk"
	override var requiresUIThread: Boolean = true

	override fun retrieveTooltipTag(isReadOnlyContext: Boolean) = EDITOR_TOOLBAR_EXPORT_APK

	init {
		label = context.getString(R.string.title_export_build)
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
			val isPlugin = projectManager.isPluginProject()
			// The module only when it tells two app modules' exports apart.
			val module = app.name.takeIf { projectManager.getAndroidAppModules().size > 1 }
			val name = listOfNotNull(projectDir.name, module, variant.name).joinToString("-")
			activity.activityScope.launch {
				val (apk, outputs) =
					withContext(Dispatchers.IO) {
						val apk =
							BuildExport.resolve(
								listingFile = variant.mainArtifact.assembleTaskOutputListingFile,
								quickBuildReport = QuickBuildTaskPaths.setupJson(app.buildDir, variant.name),
								projectDir = projectDir,
							)
						apk to
							BuildExport.outputs(
								apk = apk,
								bundle = BuildExport.findBundle(app.buildDir, variant.name),
								plugin = if (isPlugin) findPluginCgpFile(projectManager.workspace, variant.name) else null,
							)
					}
				withContext(Dispatchers.Main) {
					when {
						outputs.size == 1 -> offerExport(activity, outputs.single(), name)
						outputs.isNotEmpty() -> chooseOutput(activity, outputs, name)
						isPlugin -> flashError(R.string.msg_export_plugin_not_built)
						apk == BuildExport.Exportable.QuickBuildProxy -> flashError(R.string.msg_export_apk_quick_build)
						else -> flashError(R.string.msg_export_not_built)
					}
				}
			}
		}
	}

	private fun buildService(): BuildService? = Lookup.getDefault().lookup(BuildService.KEY_BUILD_SERVICE)

	/** A build, Quick Build's prebuild included, may be rewriting the output; refuse with a reason. */
	private fun isBuilding(): Boolean {
		if (buildService()?.isBuildInProgress != true) return false
		flashError(R.string.msg_export_build_running)
		return true
	}

	private fun labelOf(
		context: Context,
		kind: BuildExport.Kind,
	): String =
		context.getString(
			when (kind) {
				BuildExport.Kind.APK -> R.string.export_kind_apk
				BuildExport.Kind.BUNDLE -> R.string.export_kind_bundle
				BuildExport.Kind.PLUGIN -> R.string.export_kind_plugin
			},
		)

	private fun chooseOutput(
		activity: EditorHandlerActivity,
		outputs: List<BuildExport.Output>,
		name: String,
	) {
		DialogUtils
			.newMaterialDialogBuilder(activity)
			.setTitle(R.string.title_export_build)
			.setItems(outputs.map { labelOf(activity, it.kind) }.toTypedArray()) { _, which ->
				offerExport(activity, outputs[which], name)
			}.setNegativeButton(android.R.string.cancel, null)
			.show()
	}

	private fun offerExport(
		activity: EditorHandlerActivity,
		output: BuildExport.Output,
		name: String,
	) {
		DialogUtils
			.newMaterialDialogBuilder(activity)
			.setTitle(activity.getString(R.string.title_export_kind, labelOf(activity, output.kind)))
			.setItems(
				arrayOf(
					activity.getString(R.string.export_share),
					activity.getString(R.string.export_copy_to_download),
				),
			) { _, which ->
				if (which == 0) {
					IntentUtils.shareFile(activity, output.file, output.kind.mimeType)
				} else if (!isBuilding()) {
					activity.activityScope.launch(Dispatchers.IO) { copyToDownload(activity, output, name) }
				}
			}.setNegativeButton(android.R.string.cancel, null)
			.show()
	}

	private fun copyToDownload(
		activity: EditorHandlerActivity,
		output: BuildExport.Output,
		name: String,
	) {
		@Suppress("DEPRECATION")
		val download = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
		try {
			val copy = BuildExport.copyTo(output.file, download, name, output.kind.extension)
			flashSuccess(activity.getString(R.string.msg_export_saved, copy.name))
		} catch (e: IOException) {
			log.error("Cannot copy {} to {}", output.file, download, e)
			flashError(R.string.msg_export_failed)
		}
	}

	override fun getShowAsActionFlags(data: ActionData): Int = MenuItem.SHOW_AS_ACTION_IF_ROOM

	companion object {
		private val log = LoggerFactory.getLogger(ExportBuildAction::class.java)
	}
}
