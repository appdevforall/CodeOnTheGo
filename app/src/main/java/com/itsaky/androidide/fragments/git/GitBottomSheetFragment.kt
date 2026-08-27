package com.itsaky.androidide.fragments.git

import android.content.Intent
import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.itsaky.androidide.R
import com.itsaky.androidide.activities.PreferencesActivity
import com.itsaky.androidide.activities.editor.EditorHandlerActivity
import com.itsaky.androidide.databinding.FragmentGitBottomSheetBinding
import com.itsaky.androidide.events.ListProjectFilesRequestEvent
import com.itsaky.androidide.fragments.git.adapter.GitFileChangeAdapter
import com.itsaky.androidide.git.core.GitCredentialsManager
import com.itsaky.androidide.git.core.models.ChangeType
import com.itsaky.androidide.git.core.models.FileChange
import com.itsaky.androidide.git.core.models.GitBranch
import com.itsaky.androidide.git.core.models.GitStatus
import com.itsaky.androidide.idetooltips.TooltipManager
import com.itsaky.androidide.idetooltips.TooltipTag
import com.itsaky.androidide.idetooltips.attachTooltip
import com.itsaky.androidide.interfaces.IEditorHandler
import com.itsaky.androidide.preferences.internal.GitPreferences
import com.itsaky.androidide.utils.flashError
import com.itsaky.androidide.utils.flashSuccess
import com.itsaky.androidide.utils.onLongPress
import com.itsaky.androidide.viewmodel.BottomSheetViewModel
import com.itsaky.androidide.viewmodel.GitBottomSheetViewModel
import com.itsaky.androidide.viewmodel.GitBottomSheetViewModel.BranchesUiState
import com.itsaky.androidide.viewmodel.GitBottomSheetViewModel.PullUiState
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import org.greenrobot.eventbus.EventBus
import org.koin.androidx.viewmodel.ext.android.activityViewModel
import java.io.File

class GitBottomSheetFragment : Fragment(R.layout.fragment_git_bottom_sheet) {
	private val viewModel: GitBottomSheetViewModel by activityViewModel()
	private val bottomSheetViewModel: BottomSheetViewModel by activityViewModel()
	private lateinit var fileChangeAdapter: GitFileChangeAdapter
	private lateinit var credentialsManager: GitCredentialsManager
	private lateinit var branchPopupWindow: GitBranchPopupWindow

	private var _binding: FragmentGitBottomSheetBinding? = null
	val binding: FragmentGitBottomSheetBinding
		get() = checkNotNull(_binding) { "Fragment binding is null or view has been destroyed" }

	override fun onViewCreated(
		view: View,
		savedInstanceState: Bundle?,
	) {
		super.onViewCreated(view, savedInstanceState)
		_binding = FragmentGitBottomSheetBinding.bind(view)
		credentialsManager = GitCredentialsManager(requireContext())
		viewModel.initializeRepository()

		branchPopupWindow =
			GitBranchPopupWindow(
				context = requireContext(),
				onBranchSelected = { branch ->
					showCheckoutDialog(branch)
				},
				onNewBranchRequested = {
					showCreateBranchDialog()
				},
				onMergeBranch = { branch ->
					showMergeDialog(viewModel.currentBranch.value ?: "HEAD", branch.name)
				},
			)

		binding.tvBranchName.setOnClickListener {
			branchPopupWindow.show(binding.tvBranchName)
		}

		fileChangeAdapter =
			GitFileChangeAdapter(
				onFileClicked = { change ->
					when (change.type) {
						ChangeType.CONFLICTED -> {
							val activity = requireActivity()
							if (activity is EditorHandlerActivity) {
								viewLifecycleOwner.lifecycleScope.launch {
									val repo = viewModel.currentRepository
									repo?.let {
										activity.checkForExternalFileChanges(force = true)
										activity.openFile(File(repo.rootDir, change.path))
										bottomSheetViewModel.setSheetState(BottomSheetBehavior.STATE_COLLAPSED)
									}
								}
							}
						}

						else -> {
							val dialog = GitDiffViewerDialog.newInstance(change.path)
							dialog.show(childFragmentManager, "GitDiffViewerDialog")
						}
					}
				},
				onSelectionChanged = {
					validateCommitButton()
					updateCheckAllButton()
				},
				onResolveConflict = { change ->
					viewModel.resolveConflict(change.path)
				},
			)

		binding.recyclerView.layoutManager = LinearLayoutManager(requireContext())
		binding.recyclerView.adapter = fileChangeAdapter
		binding.recyclerView.onLongPress { _ ->
			TooltipManager.showIdeCategoryTooltip(
				context = requireContext(),
				anchorView = binding.recyclerView,
				tag = TooltipTag.PROJECT_GIT_FILES,
			)
		}

		viewLifecycleOwner.lifecycleScope.launch {
			launch {
				viewModel.currentBranch.collectLatest { branchName ->
					if (branchName != null) {
						binding.groupCurrentBranch.visibility = View.VISIBLE
						binding.tvBranchName.text = branchName
						binding.tvBranchName.contentDescription =
							getString(R.string.current_branch_name, branchName)
					} else {
						binding.groupCurrentBranch.visibility = View.GONE
					}
				}
			}

			launch {
				viewModel.branches.collectLatest { state ->
					binding.tvBranchName.isEnabled = state !is BranchesUiState.Loading
					branchPopupWindow.setBranchesState(state)
				}
			}

			launch {
				viewModel.checkoutState.collectLatest { state ->
					when (state) {
						is GitBottomSheetViewModel.CheckoutUiState.Idle -> {
							binding.tvBranchName.isEnabled = true
						}

						is GitBottomSheetViewModel.CheckoutUiState.CheckingOut -> {
							binding.tvBranchName.isEnabled = false
						}

						is GitBottomSheetViewModel.CheckoutUiState.Success -> {
							binding.tvBranchName.isEnabled = true
							flashSuccess(getString(R.string.git_checkout_success, state.branchName))
							refreshEditorContent(force = true)
							EventBus.getDefault().post(ListProjectFilesRequestEvent())
							viewModel.resetCheckoutState()
						}

						is GitBottomSheetViewModel.CheckoutUiState.Conflicts -> {
							binding.tvBranchName.isEnabled = true
							val message =
								getString(
									R.string.git_checkout_conflict_msg,
									state.conflictingPaths.joinToString("\n• ", prefix = "• "),
								)
							MaterialAlertDialogBuilder(requireContext())
								.setTitle(R.string.git_checkout_conflict_title)
								.setMessage(message)
								.setPositiveButton(android.R.string.ok) { _, _ ->
									viewModel.resetCheckoutState()
								}.setOnDismissListener {
									viewModel.resetCheckoutState()
								}.show()
						}

						is GitBottomSheetViewModel.CheckoutUiState.Error -> {
							binding.tvBranchName.isEnabled = true
							val message = formatError(state.errorResId, state.errorArgs)
							MaterialAlertDialogBuilder(requireContext())
								.setTitle(R.string.git_checkout_failed)
								.setMessage(message)
								.setPositiveButton(android.R.string.ok) { _, _ ->
									viewModel.resetCheckoutState()
								}.setOnDismissListener {
									viewModel.resetCheckoutState()
								}.show()
						}
					}
				}
			}

			launch {
				viewModel.mergeState.collectLatest { state ->
					when (state) {
						is GitBottomSheetViewModel.MergeUiState.Idle -> {
							binding.tvBranchName.isEnabled = true
						}

						is GitBottomSheetViewModel.MergeUiState.Merging -> {
							binding.tvBranchName.isEnabled = false
						}

						is GitBottomSheetViewModel.MergeUiState.Success -> {
							binding.tvBranchName.isEnabled = true
							flashSuccess(
								getString(
									R.string.git_merge_success,
									state.targetBranch,
									state.currentBranch,
								),
							)
							refreshEditorContent(force = true)
							EventBus.getDefault().post(ListProjectFilesRequestEvent())
							viewModel.resetMergeState()
						}

						is GitBottomSheetViewModel.MergeUiState.AlreadyUpToDate -> {
							binding.tvBranchName.isEnabled = true
							flashSuccess(getString(R.string.git_already_up_to_date))
							viewModel.resetMergeState()
						}

						is GitBottomSheetViewModel.MergeUiState.Conflicts -> {
							binding.tvBranchName.isEnabled = true
							val message =
								getString(
									R.string.git_merge_conflict_msg,
									state.targetBranch,
									state.currentBranch,
								)
							MaterialAlertDialogBuilder(requireContext())
								.setTitle(R.string.git_merge_conflict_title)
								.setMessage(message)
								.setPositiveButton(android.R.string.ok) { _, _ ->
									viewModel.resetMergeState()
								}.setOnDismissListener {
									viewModel.resetMergeState()
								}.show()
							refreshEditorContent(force = true)
							EventBus.getDefault().post(ListProjectFilesRequestEvent())
						}

						is GitBottomSheetViewModel.MergeUiState.Error -> {
							binding.tvBranchName.isEnabled = true
							val message = formatError(state.errorResId, state.errorArgs)
							MaterialAlertDialogBuilder(requireContext())
								.setTitle(R.string.git_merge_failed_title)
								.setMessage(message)
								.setPositiveButton(android.R.string.ok) { _, _ ->
									viewModel.resetMergeState()
								}.setOnDismissListener {
									viewModel.resetMergeState()
								}.show()
						}
					}
				}
			}

			launch {
				viewModel.isGitRepository.collectLatest { isRepo ->
					if (isRepo) {
						viewModel.fetchBranches()
					}
				}
			}

			combine(
				viewModel.isGitRepository,
				viewModel.gitStatus,
			) { isRepo, status ->
				val allChanges = status.allChanges()

				when {
					!isRepo -> {
						binding.apply {
							emptyView.visibility = View.VISIBLE
							emptyView.text = getString(R.string.not_a_git_repo)
							recyclerView.visibility = View.GONE
							cbCheckAll.visibility = View.GONE
							commitSection.visibility = View.GONE
							authorWarning.visibility = View.GONE
							commitHistoryButton.visibility = View.GONE
							btnAbortMerge.visibility = View.GONE
						}
					}

					allChanges.isEmpty() -> {
						binding.apply {
							emptyView.visibility = View.VISIBLE
							emptyView.text = getString(R.string.no_uncommitted_changes)
							recyclerView.visibility = View.GONE
							cbCheckAll.visibility = View.VISIBLE
							cbCheckAll.isEnabled = false
							cbCheckAll.isChecked = false
							cbCheckAll.text = getString(R.string.changed_files_count, 0)
							commitSection.visibility = View.GONE
							authorWarning.visibility = View.GONE
							commitHistoryButton.visibility = View.VISIBLE
							btnAbortMerge.visibility = View.GONE
						}
					}

					else -> {
						val hasSelectable = allChanges.hasSelectable()
						binding.apply {
							emptyView.visibility = View.GONE
							recyclerView.visibility = View.VISIBLE
							cbCheckAll.visibility = View.VISIBLE
							cbCheckAll.isEnabled = hasSelectable
							cbCheckAll.text = getString(R.string.changed_files_count, allChanges.size)
							commitSection.visibility = View.VISIBLE
							authorWarning.visibility =
								if (hasAuthorInfo()) View.GONE else View.VISIBLE
							commitHistoryButton.visibility = View.VISIBLE
							btnAbortMerge.visibility =
								if (status.isMerging) View.VISIBLE else View.GONE
						}
						fileChangeAdapter.submitList(allChanges) {
							updateCheckAllButton()
						}
					}
				}
			}.collectLatest { }
		}

		setupCommitUI()

		binding.commitHistoryButton.apply {
			setOnClickListener {
				val dialog = GitCommitHistoryDialog()
				dialog.show(childFragmentManager, "CommitHistoryDialog")
			}
			setTooltipOnView(TooltipTag.PROJECT_GIT_COMMIT_HISTORY)
		}

		setupPullUI()
	}

	override fun onResume() {
		super.onResume()
		updateAuthorUI()
	}

	private fun updateAuthorUI() {
		val hasAuthor = hasAuthorInfo()
		val allChanges = viewModel.gitStatus.value.allChanges()
		binding.authorWarning.visibility =
			if (!hasAuthor && allChanges.isNotEmpty()) View.VISIBLE else View.GONE
		validateCommitButton()
	}

	private fun hasAuthorInfo(): Boolean = !GitPreferences.userName.isNullOrBlank() && !GitPreferences.userEmail.isNullOrBlank()

	private fun setupCommitUI() {
		binding.commitSummary.doAfterTextChanged { validateCommitButton() }
		binding.commitDescription.doAfterTextChanged { validateCommitButton() }

		binding.cbCheckAll.setOnClickListener {
			if (binding.cbCheckAll.isChecked) {
				fileChangeAdapter.selectAll()
			} else {
				fileChangeAdapter.clearSelection()
			}
			validateCommitButton()
		}

		binding.btnAbortMerge.apply {
			setOnClickListener {
				MaterialAlertDialogBuilder(requireContext())
					.setTitle(R.string.abort_merge)
					.setMessage(R.string.confirm_abort_merge)
					.setPositiveButton(R.string.abort_merge) { _, _ ->
						viewModel.abortMerge {
							val activity = requireActivity()
							if (activity is EditorHandlerActivity) {
								activity.checkForExternalFileChanges(force = true)
							}
						}
					}.setNegativeButton(android.R.string.cancel, null)
					.create()
					.attachTooltip(TooltipTag.GIT_DIALOG_ABORT_MERGE)
					.show()
			}
			setTooltipOnView(TooltipTag.PROJECT_GIT_ABORT)
		}

		binding.authorAvatar.apply {
			setOnClickListener { showAuthorPopup() }
			setTooltipOnView(TooltipTag.PROJECT_GIT_ID)
		}

		binding.commitButton.apply {
			setOnClickListener {
				checkUnsavedChangesAndProceed {
					val summary =
						binding.commitSummary.text
							?.toString()
							?.trim() ?: ""
					val description =
						binding.commitDescription.text
							?.toString()
							?.trim()

					if (summary.isNotEmpty() && fileChangeAdapter.selectedFiles.isNotEmpty() && hasAuthorInfo()) {
						viewModel.commitChanges(
							summary = summary,
							description = description,
							selectedPaths = fileChangeAdapter.selectedFiles.toList(),
						) {
							// Clear the inputs on successful commit
							binding.commitSummary.text?.clear()
							binding.commitDescription.text?.clear()
							fileChangeAdapter.selectedFiles.clear()
							updateCheckAllButton()
						}
					}
				}
			}
			setTooltipOnView(TooltipTag.PROJECT_GIT_COMMIT)
		}
	}

	private fun showAuthorPopup() {
		val name = GitPreferences.userName.orEmpty().ifBlank { getString(R.string.author_not_set) }
		val email =
			GitPreferences.userEmail.orEmpty().ifBlank { getString(R.string.author_not_set) }
		val message =
			getString(R.string.git_committing_as, name) + "\n" +
				getString(R.string.git_committing_email, email) + "\n\n" +
				getString(R.string.git_update_config_in_preferences)

		val spannable = SpannableString(message)
		val preferencesText = getString(R.string.git_update_config_in_preferences)
		val startIndex = message.indexOf(preferencesText)

		val builder =
			MaterialAlertDialogBuilder(requireContext())
				.setTitle(R.string.idepref_git_author_title)
				.setMessage(spannable)
				.setPositiveButton(android.R.string.ok, null)

		val dialog = builder.create()

		if (startIndex != -1) {
			spannable.setSpan(
				object : ClickableSpan() {
					override fun onClick(widget: View) {
						val intent =
							Intent(
								requireContext(),
								PreferencesActivity::class.java,
							)
						dialog.dismiss()
						startActivity(intent)
					}
				},
				startIndex,
				startIndex + preferencesText.length,
				SPAN_EXCLUSIVE_EXCLUSIVE,
			)
		}

		dialog.show()
		dialog.findViewById<TextView>(android.R.id.message)?.movementMethod =
			LinkMovementMethod.getInstance()
	}

	private fun validateCommitButton() {
		// May be invoked from async adapter callbacks; bail if the view is gone.
		val binding = _binding ?: return
		val hasSummary = !binding.commitSummary.text.isNullOrBlank()
		val hasSelection = fileChangeAdapter.selectedFiles.isNotEmpty()
		val hasAuthor = hasAuthorInfo()
		binding.commitButton.isEnabled = hasSummary && hasSelection && hasAuthor
	}

	private fun updateCheckAllButton() {
		// May be invoked from the async submitList commit callback; bail if the view is gone.
		val binding = _binding ?: return
		val allChanges = viewModel.gitStatus.value.allChanges()
		val hasSelectable = allChanges.hasSelectable()
		binding.cbCheckAll.text = getString(R.string.changed_files_count, allChanges.size)
		binding.cbCheckAll.isEnabled = hasSelectable && allChanges.isNotEmpty()
		binding.cbCheckAll.isChecked = hasSelectable && allChanges.isNotEmpty() && fileChangeAdapter.areAllSelected()
	}

	private fun setupPullUI() {
		viewLifecycleOwner.lifecycleScope.launch {
			viewModel.isGitRepository.collectLatest { isRepo ->
				binding.btnPull.visibility = if (isRepo) View.VISIBLE else View.GONE
			}
		}

		viewLifecycleOwner.lifecycleScope.launch {
			viewModel.pullState.collectLatest { state ->
				when (state) {
					is PullUiState.Idle -> {
						binding.btnPull.isEnabled = true
						binding.pullProgress.visibility = View.GONE
					}

					is PullUiState.Pulling -> {
						binding.btnPull.isEnabled = false
						binding.pullProgress.visibility = View.VISIBLE
					}

					is PullUiState.Success -> {
						binding.btnPull.isEnabled = true
						binding.pullProgress.visibility = View.GONE
						flashSuccess(R.string.pull_successful)
						viewModel.resetPullState()
						refreshEditorContent()
					}

					is PullUiState.Conflicts -> {
						binding.btnPull.isEnabled = true
						binding.pullProgress.visibility = View.GONE
						val message = state.message ?: getString(R.string.info_merge_conflicts)
						MaterialAlertDialogBuilder(requireContext())
							.setTitle(getString(R.string.merge_conflicts))
							.setMessage(message)
							.setPositiveButton(android.R.string.ok, null)
							.create()
							.attachTooltip(TooltipTag.GIT_DIALOG_MERGE_CONFLICTS)
							.show()
						viewModel.resetPullState()
						refreshEditorContent()
					}

					is PullUiState.Error -> {
						binding.btnPull.isEnabled = true
						binding.pullProgress.visibility = View.GONE
						val message =
							state.message ?: state.errorResId?.let { resId ->
								if (state.errorArgs != null) {
									getString(
										resId,
										*state.errorArgs.toTypedArray(),
									)
								} else {
									getString(resId)
								}
							}
						MaterialAlertDialogBuilder(requireContext())
							.setTitle(R.string.pull_failed)
							.setMessage(message)
							.setPositiveButton(android.R.string.ok, null)
							.create()
							.attachTooltip(TooltipTag.GIT_DIALOG_PULL_FAIL)
							.show()
					}
				}
			}
		}

		binding.btnPull.apply {
			setOnClickListener {
				checkUnsavedChangesAndProceed {
					val username = credentialsManager.getUsername()
					val token = credentialsManager.getToken()
					if (!username.isNullOrBlank() && !token.isNullOrBlank()) {
						viewModel.pull(username, token)
					} else {
						showGitCredentialsDialog(
							credentialsManager = credentialsManager,
							positiveButtonTextResId = R.string.pull,
						) { user, accessToken ->
							viewModel.pull(user, accessToken)
						}
					}
				}
			}
			setTooltipOnView(TooltipTag.GIT_PULL)
		}
	}

	private fun showCreateBranchDialog() {
		val dialogView = layoutInflater.inflate(R.layout.dialog_git_create_branch, null)
		val branchNameLayout = dialogView.findViewById<TextInputLayout>(R.id.branchNameLayout)
		val etBranchName = dialogView.findViewById<TextInputEditText>(R.id.etBranchName)

		etBranchName?.doAfterTextChanged {
			branchNameLayout?.error = null
		}

		val dialog =
			MaterialAlertDialogBuilder(requireContext())
				.setTitle(R.string.git_create_branch_title)
				.setView(dialogView)
				.setPositiveButton(R.string.git_create_branch, null)
				.setNegativeButton(android.R.string.cancel, null)
				.create()

		dialog.setOnShowListener {
			dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
				val branchName = etBranchName?.text?.toString()?.trim() ?: ""
				if (branchName.isNotBlank()) {
					checkUnsavedChangesAndProceed {
						dialog.dismiss()
						viewModel.checkoutBranch(branchName = branchName, createNew = true)
					}
				} else {
					branchNameLayout?.error = getString(R.string.git_create_branch_invalid_name)
				}
			}
		}

		dialog.show()
	}

	private fun showMergeDialog(
		currentBranch: String,
		targetBranch: String,
	) {
		MaterialAlertDialogBuilder(requireContext())
			.setTitle(getString(R.string.merge_dialog_title))
			.setMessage(getString(R.string.merge_dialog_message, targetBranch, currentBranch))
			.setPositiveButton(R.string.proceed_with_git_action) { _, _ ->
				checkUnsavedChangesAndProceed {
					viewModel.mergeBranch(targetBranch)
				}
			}.setNegativeButton(android.R.string.cancel) { _, _ -> }
			.setCancelable(true)
			.show()
	}

	private fun showCheckoutDialog(targetBranch: GitBranch) {
		MaterialAlertDialogBuilder(requireContext())
			.setTitle(getString(R.string.checkout_dialog_title))
			.setMessage(getString(R.string.checkout_dialog_message, targetBranch.name))
			.setPositiveButton(R.string.proceed_with_git_action) { _, _ ->
				if (!targetBranch.isCurrent) {
					checkUnsavedChangesAndProceed {
						viewModel.checkoutBranch(
							branchName = targetBranch.name,
							createNew = false,
						)
					}
				}
			}.setNegativeButton(android.R.string.cancel) { _, _ -> }
			.setCancelable(true)
			.show()
	}

	private fun formatError(
		errorResId: Int,
		errorArgs: List<String>?,
	): String =
		if (errorArgs.isNullOrEmpty()) {
			getString(errorResId)
		} else {
			getString(errorResId, *errorArgs.toTypedArray())
		}

	private fun refreshEditorContent(force: Boolean = false) {
		val activity = requireActivity()
		if (activity is EditorHandlerActivity) {
			activity.checkForExternalFileChanges(force)
		}
	}

	private fun checkUnsavedChangesAndProceed(action: () -> Unit) {
		val handler = requireActivity() as? IEditorHandler
		if (handler?.areFilesModified() == true) {
			MaterialAlertDialogBuilder(requireContext())
				.setTitle(R.string.title_files_unsaved)
				.setMessage(R.string.msg_save_before_git_action)
				.setPositiveButton(R.string.save_before_git_action) { _, _ ->
					handler.saveAllAsync { succeeded ->
						// saveAllAsync is owned by the activity's lifecycle and can still invoke this
						// callback after onDestroyView() clears _binding -- the user navigating away while
						// the save is in flight -- and action() dereferences binding, so bail out first.
						if (_binding == null) {
							return@saveAllAsync
						}
						// succeeded means saveAll() did not throw, not that every write landed: a silent
						// per-file failure (disk full, say) leaves a file modified with succeeded still
						// true, and running a commit or pull then operates on a tree whose edits were
						// never written. areFilesModified() reflects the per-file state each save updates.
						if (succeeded && handler.areFilesModified() == false) {
							action()
						} else {
							flashError(R.string.save_failed)
						}
					}
				}.setNegativeButton(R.string.no_save_before_git_action) { _, _ ->
					action()
				}.setNeutralButton(android.R.string.cancel, null)
				.create()
				.attachTooltip(TooltipTag.GIT_DIALOG_SAVE)
				.show()
		} else {
			action()
		}
	}

	override fun onDestroyView() {
		branchPopupWindow.dismiss()
		super.onDestroyView()
		_binding = null
	}

	private fun View.setTooltipOnView(tag: String) {
		setOnLongClickListener { view ->
			TooltipManager.showIdeCategoryTooltip(
				context = view.context,
				anchorView = view,
				tag = tag,
			)
			true
		}
	}

	private fun GitStatus.allChanges(): List<FileChange> = staged + unstaged + untracked + conflicted

	private fun List<FileChange>.hasSelectable(): Boolean = any { it.type != ChangeType.CONFLICTED }
}
