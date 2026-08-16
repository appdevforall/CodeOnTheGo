package com.itsaky.androidide.activities

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.FrameLayout
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.itsaky.androidide.R
import com.itsaky.androidide.app.IDEActivity
import com.itsaky.androidide.databinding.RenameProjectTextinputBinding
import com.itsaky.androidide.ui.models.ExternalFileInstallUiEffect
import com.itsaky.androidide.ui.models.ExternalFileInstallUiEvent
import com.itsaky.androidide.utils.flashError
import com.itsaky.androidide.utils.flashSuccess
import com.itsaky.androidide.viewmodels.ExternalFileInstallViewModel
import kotlinx.coroutines.launch
import org.koin.androidx.viewmodel.ext.android.viewModel

/**
 * Trampoline activity that receives a `.cgp`/`.cgt` file opened from outside the app (e.g. an
 * email attachment), prompts to install it, and finishes - it has no content of its own beyond
 * the dialogs it shows.
 */
class ExternalFileInstallActivity : IDEActivity() {
	private val viewModel: ExternalFileInstallViewModel by viewModel()

	override fun bindLayout(): View = FrameLayout(this)

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)

		observeUiEffects()

		val uri = intent?.data
		if (uri == null) {
			finish()
			return
		}

		if (savedInstanceState == null) {
			viewModel.onReceived(this, uri)
		}
	}

	private fun observeUiEffects() {
		lifecycleScope.launch {
			repeatOnLifecycle(Lifecycle.State.STARTED) {
				viewModel.uiEffect.collect { effect -> handleUiEffect(effect) }
			}
		}
	}

	private fun handleUiEffect(effect: ExternalFileInstallUiEffect) {
		when (effect) {
			is ExternalFileInstallUiEffect.ForwardToPluginManager -> {
				startActivity(
					Intent(this, PluginManagerActivity::class.java)
						.putExtra(PluginManagerActivity.EXTRA_PENDING_INSTALL_URI, effect.uri),
				)
				finish()
			}

			is ExternalFileInstallUiEffect.ShowTemplateInstallConfirmation -> {
				showInstallConfirmation(effect)
			}

			is ExternalFileInstallUiEffect.ShowTemplateNameConflict -> {
				showNameConflict(effect)
			}

			is ExternalFileInstallUiEffect.ShowError -> {
				flashError(getString(effect.messageResId, *effect.formatArgs.toTypedArray()))
			}

			is ExternalFileInstallUiEffect.ShowSuccess -> {
				flashSuccess(getString(effect.messageResId))
			}

			is ExternalFileInstallUiEffect.Finish -> {
				finish()
			}
		}
	}

	private fun showInstallConfirmation(effect: ExternalFileInstallUiEffect.ShowTemplateInstallConfirmation) {
		MaterialAlertDialogBuilder(this)
			.setTitle(R.string.title_install_template_collection)
			.setMessage(
				getString(
					R.string.msg_template_install_confirm,
					effect.suggestedBaseName,
					effect.info.templateNames.joinToString(", "),
				),
			).setPositiveButton(R.string.btn_install) { _, _ ->
				viewModel.onEvent(
					ExternalFileInstallUiEvent.ConfirmTemplateInstall(
						tempFile = effect.tempFile,
						targetBaseName = effect.suggestedBaseName,
						overwrite = false,
					),
				)
			}.setNegativeButton(android.R.string.cancel) { _, _ ->
				viewModel.onEvent(ExternalFileInstallUiEvent.IgnoreTemplateInstall(effect.tempFile))
			}.setOnCancelListener {
				viewModel.onEvent(ExternalFileInstallUiEvent.IgnoreTemplateInstall(effect.tempFile))
			}.show()
	}

	private fun showNameConflict(effect: ExternalFileInstallUiEffect.ShowTemplateNameConflict) {
		MaterialAlertDialogBuilder(this)
			.setTitle(R.string.title_template_already_installed)
			.setMessage(getString(R.string.msg_template_name_conflict, effect.existingName))
			.setPositiveButton(R.string.btn_rename_and_install) { _, _ ->
				showRenameDialog(effect)
			}.setNeutralButton(R.string.btn_overwrite) { _, _ ->
				viewModel.onEvent(
					ExternalFileInstallUiEvent.ConfirmTemplateInstall(
						tempFile = effect.tempFile,
						targetBaseName = effect.existingName,
						overwrite = true,
					),
				)
			}.setNegativeButton(android.R.string.cancel) { _, _ ->
				viewModel.onEvent(ExternalFileInstallUiEvent.IgnoreTemplateInstall(effect.tempFile))
			}.setOnCancelListener {
				viewModel.onEvent(ExternalFileInstallUiEvent.IgnoreTemplateInstall(effect.tempFile))
			}.show()
	}

	private fun showRenameDialog(effect: ExternalFileInstallUiEffect.ShowTemplateNameConflict) {
		val binding = RenameProjectTextinputBinding.inflate(layoutInflater)
		binding.textinputLayout.hint = getString(R.string.hint_new_template_collection_name)

		val dialog =
			MaterialAlertDialogBuilder(this)
				.setTitle(R.string.btn_rename_and_install)
				.setView(binding.root)
				.setPositiveButton(R.string.btn_install) { _, _ ->
					val newName = viewModel.sanitizeBaseName(binding.textinputEdittext.text.toString())
					viewModel.onEvent(
						ExternalFileInstallUiEvent.ConfirmTemplateInstall(
							tempFile = effect.tempFile,
							targetBaseName = newName,
							overwrite = false,
						),
					)
				}.setNegativeButton(android.R.string.cancel) { _, _ ->
					viewModel.onEvent(ExternalFileInstallUiEvent.IgnoreTemplateInstall(effect.tempFile))
				}.setOnCancelListener {
					viewModel.onEvent(ExternalFileInstallUiEvent.IgnoreTemplateInstall(effect.tempFile))
				}.show()

		lifecycleScope.launch {
			val suggested = viewModel.suggestUniqueBaseName(effect.existingName)
			if (!dialog.isShowing) return@launch
			binding.textinputEdittext.setText(suggested)
			binding.textinputEdittext.selectAll()
		}
	}
}
