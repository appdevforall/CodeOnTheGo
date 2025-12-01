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

package com.itsaky.androidide.fragments.onboarding

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.view.animation.Animation
import android.view.animation.AnimationUtils
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.RecyclerView
import com.github.appintro.SlidePolicy
import com.google.android.material.button.MaterialButton
import com.itsaky.androidide.R
import com.itsaky.androidide.activities.OnboardingActivity
import com.itsaky.androidide.adapters.onboarding.OnboardingPermissionsAdapter
import com.itsaky.androidide.buildinfo.BuildInfo
import com.itsaky.androidide.databinding.LayoutOnboardingPermissionsBinding
import com.itsaky.androidide.events.InstallationEvent
import com.itsaky.androidide.tasks.doAsyncWithProgress
import com.itsaky.androidide.utils.PermissionsHelper
import com.itsaky.androidide.utils.flashError
import com.itsaky.androidide.utils.flashErrorForLong
import com.itsaky.androidide.utils.flashSuccess
import com.itsaky.androidide.utils.isAtLeastR
import com.itsaky.androidide.utils.viewLifecycleScope
import com.itsaky.androidide.viewmodel.InstallationState
import com.itsaky.androidide.viewmodel.InstallationViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory

/**
 * @author Akash Yadav
 */
class PermissionsFragment :
	OnboardingFragment(),
	SlidePolicy {
	var adapter: OnboardingPermissionsAdapter? = null
	private val viewModel: InstallationViewModel by viewModels()
	private var permissionsBinding: LayoutOnboardingPermissionsBinding? = null
	private var recyclerView: RecyclerView? = null
	private var finishButton: MaterialButton? = null
    private lateinit var pulseAnimation: Animation

	private val storagePermissionRequestLauncher =
		registerForActivityResult(
			ActivityResultContracts.RequestMultiplePermissions(),
		) {
			onPermissionsUpdated()
		}

	private val settingsTogglePermissionRequestLauncher =
		registerForActivityResult(
			ActivityResultContracts.StartActivityForResult(),
		) {
			onPermissionsUpdated()
		}

	private val permissions by lazy {
		PermissionsHelper.getRequiredPermissions(requireContext())
	}

	companion object {
		private val logger = LoggerFactory.getLogger(PermissionsFragment::class.java)

		@JvmStatic
		fun newInstance(context: Context): PermissionsFragment =
			PermissionsFragment().apply {
				arguments =
					Bundle().apply {
						putCharSequence(
							KEY_ONBOARDING_TITLE,
							context.getString(R.string.onboarding_title_permissions),
						)
						putCharSequence(
							KEY_ONBOARDING_SUBTITLE,
							context.getString(R.string.onboarding_subtitle_permissions),
						)
					}
			}
	}

	override fun createContentView(
		parent: ViewGroup,
		attachToParent: Boolean,
	) {
		permissionsBinding = LayoutOnboardingPermissionsBinding.inflate(layoutInflater, parent, attachToParent)
		permissionsBinding?.let { b ->
			recyclerView = b.onboardingItems
			finishButton = b.finishInstallationButton
            pulseAnimation = AnimationUtils.loadAnimation(requireContext(), R.anim.pulse_animation)

			b.onboardingItems.adapter = createAdapter()

			b.finishInstallationButton.setOnClickListener {
				startIdeSetup()
			}
		}
	}

	override fun onViewCreated(
		view: View,
		savedInstanceState: Bundle?,
	) {
		super.onViewCreated(view, savedInstanceState)
		observeViewModelState()
		observeViewModelEvents()
		val allGranted = PermissionsHelper.areAllPermissionsGranted(requireContext())
		viewModel.onPermissionsUpdated(allGranted)
	}

	private fun observeViewModelState() {
		viewLifecycleScope.launch {
			viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
				viewModel.state.collect { state ->
					handleState(state)
				}
			}
		}
	}

	private fun observeViewModelEvents() {
		viewLifecycleScope.launch {
			viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
				viewModel.events.collect { event ->
					when (event) {
						is InstallationEvent.ShowError -> activity?.flashErrorForLong(event.message)
						is InstallationEvent.InstallationResultEvent -> {}
					}
				}
			}
		}
	}

	private fun handleState(state: InstallationState) {
		when (state) {
			is InstallationState.InstallationPending -> {
				disableFishButton()
			}
			is InstallationState.InstallationGranted -> {
				enableFishButton()
			}
			is InstallationState.Installing -> {
                disableFishButton()
			}
			is InstallationState.InstallationComplete -> {
				finishButton?.text = getString(R.string.finish_installation)
				activity?.flashSuccess(getString(R.string.ide_setup_complete))
			}
			is InstallationState.InstallationError -> {
                enableFishButton()
				finishButton?.text = getString(R.string.finish_installation)
				activity?.flashError(getString(state.errorMessageResId))
			}
		}
	}

	override fun onDestroyView() {
		super.onDestroyView()
		permissionsBinding = null
		recyclerView = null
		finishButton = null
	}

	private fun createAdapter(): RecyclerView.Adapter<*> =
		OnboardingPermissionsAdapter(
			permissions,
			this::requestPermission,
		).also { this.adapter = it }

	private fun onPermissionsUpdated() {
		permissions.forEach { it.isGranted = PermissionsHelper.isPermissionGranted(requireContext(), it.permission) }
		recyclerView?.adapter = createAdapter()

		val allGranted = PermissionsHelper.areAllPermissionsGranted(requireContext())
		viewModel.onPermissionsUpdated(allGranted)
	}

	private fun startIdeSetup() {
		val shouldProceed = viewModel.checkStorageAndNotify(requireContext())
		if (!shouldProceed) {
			return
		}

		if (viewModel.isSetupComplete()) {
			(activity as? OnboardingActivity)?.tryNavigateToMainIfSetupIsCompleted()
			return
		}

		viewLifecycleScope.launch {
			doAsyncWithProgress(
				Dispatchers.IO,
				configureFlashbar = { builder, _ ->
					builder.title(getString(R.string.ide_setup_in_progress))
				},
			) { flashbar, _ ->
				// Launch progress collector as a child coroutine
				launch(Dispatchers.Main) {
					viewModel.installationProgress.collect { progress ->
						if (progress.isNotEmpty()) {
							flashbar.flashbarView.setMessage(progress)
						}
					}
				}

				viewModel.startIdeSetup(requireContext())

				viewModel.state.first { state ->
					when (state) {
						is InstallationState.InstallationComplete -> {
							withContext(Dispatchers.Main) {
								(activity as? OnboardingActivity)?.tryNavigateToMainIfSetupIsCompleted()
							}
							true
						}
						is InstallationState.InstallationError -> true
						else -> false
					}
				}
			}
		}
	}

	private fun requestPermission(permission: String) {
		when (permission) {
			Manifest.permission_group.STORAGE -> requestStoragePermission()
			Manifest.permission.REQUEST_INSTALL_PACKAGES ->
				requestSettingsTogglePermission(
					Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
				)

			Manifest.permission.SYSTEM_ALERT_WINDOW -> requestSettingsTogglePermission(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
			Manifest.permission.POST_NOTIFICATIONS ->
				requestSettingsTogglePermission(
					Settings.ACTION_APP_NOTIFICATION_SETTINGS,
					setData = false,
				)
		}
	}

	private fun requestStoragePermission() {
		if (isAtLeastR()) {
			requestSettingsTogglePermission(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
			return
		}

		storagePermissionRequestLauncher.launch(
			arrayOf(
				Manifest.permission.READ_EXTERNAL_STORAGE,
				Manifest.permission.WRITE_EXTERNAL_STORAGE,
			),
		)
	}

	private fun requestSettingsTogglePermission(
		action: String,
		setData: Boolean = true,
	) {
		val intent = Intent(action)
		intent.putExtra(Settings.EXTRA_APP_PACKAGE, BuildInfo.PACKAGE_NAME)
		if (setData) {
			intent.setData(Uri.fromParts("package", BuildInfo.PACKAGE_NAME, null))
		}
		try {
			settingsTogglePermissionRequestLauncher.launch(intent)
		} catch (err: Throwable) {
			logger.error("Failed to launch settings with intent {}", intent, err)
			flashError(getString(R.string.err_no_activity_to_handle_action, action))
		}
	}

	override val isPolicyRespected: Boolean
		get() = permissions.all { it.isOptional || it.isGranted } && viewModel.isSetupComplete()

	override fun onUserIllegallyRequestedNextPage() {
		if (!permissions.all { it.isOptional || it.isGranted }) {
			activity?.flashError(R.string.msg_grant_permissions)
		} else if (!viewModel.isSetupComplete()) {
			activity?.flashError(R.string.msg_complete_ide_setup)
		}
	}

    private fun enableFishButton() {
        finishButton?.isEnabled = true
        finishButton?.startAnimation(pulseAnimation)
    }

    private fun disableFishButton() {
        finishButton?.isEnabled = false
        finishButton?.clearAnimation()
    }
}
