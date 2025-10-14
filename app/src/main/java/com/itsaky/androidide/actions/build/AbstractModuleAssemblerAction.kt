package com.itsaky.androidide.actions.build

import android.content.Context
import androidx.activity.viewModels
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.itsaky.androidide.actions.ActionData
import com.itsaky.androidide.actions.openApplicationModuleChooser
import com.itsaky.androidide.project.AndroidModels
import com.itsaky.androidide.projects.api.AndroidModule
import com.itsaky.androidide.resources.R
import com.itsaky.androidide.tooling.api.models.BasicAndroidVariantMetadata
import com.itsaky.androidide.utils.flashError
import com.itsaky.androidide.viewmodel.BuildViewModel
import kotlinx.coroutines.launch

/**
 * @author Akash Yadav
 */
abstract class AbstractModuleAssemblerAction(
    context: Context,
    @StringRes private val labelRes: Int,
    @DrawableRes private val iconRes: Int,
) : AbstractCancellableRunAction(context, labelRes, iconRes) {

    override fun doExec(data: ActionData): Boolean {
        openApplicationModuleChooser(data) { module ->
            val activity = data.requireActivity()

            val variant = module.getSelectedVariant() ?: run {
                activity.flashError(
                    activity.getString(R.string.err_selected_variant_not_found))
                return@openApplicationModuleChooser
            }

            onModuleSelected(data, module, variant)
        }
        return true
    }

    private fun onModuleSelected(
        data: ActionData,
        module: AndroidModule,
        variant: AndroidModels.AndroidVariant
    ) {
        val activity = data.requireActivity()
        val buildViewModel: BuildViewModel by activity.viewModels()
        actionScope.launch {
            activity.saveAllResult()
        }
        buildViewModel.runQuickBuild(module, variant, launchInDebugMode = id == DebugAction.ID)
    }
}