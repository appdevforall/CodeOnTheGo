package com.itsaky.androidide.fragments

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.activityViewModels
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.itsaky.androidide.R
import com.itsaky.androidide.activities.PreferencesActivity
import com.itsaky.androidide.activities.TerminalActivity
import com.itsaky.androidide.activities.editor.HelpActivity
import com.itsaky.androidide.adapters.MainActionsListAdapter
import com.itsaky.androidide.app.BaseApplication
import com.itsaky.androidide.app.BaseIDEActivity
import com.itsaky.androidide.common.databinding.LayoutDialogProgressBinding
import com.itsaky.androidide.databinding.FragmentMainBinding
import com.itsaky.androidide.idetooltips.TooltipManager
import com.itsaky.androidide.idetooltips.TooltipTag.MAIN_GET_STARTED
import com.itsaky.androidide.idetooltips.TooltipTag.MAIN_HELP
import com.itsaky.androidide.idetooltips.TooltipTag.MAIN_PROJECT_DELETE
import com.itsaky.androidide.idetooltips.TooltipTag.PROJECT_NEW
import com.itsaky.androidide.idetooltips.TooltipTag.PROJECT_OPEN
import com.itsaky.androidide.models.MainScreenAction
import com.itsaky.androidide.models.MainScreenAction.Companion.ACTION_CLONE_REPO
import com.itsaky.androidide.models.MainScreenAction.Companion.ACTION_CREATE_PROJECT
import com.itsaky.androidide.models.MainScreenAction.Companion.ACTION_DELETE_PROJECT
import com.itsaky.androidide.models.MainScreenAction.Companion.ACTION_DOCS
import com.itsaky.androidide.models.MainScreenAction.Companion.ACTION_OPEN_PROJECT
import com.itsaky.androidide.models.MainScreenAction.Companion.ACTION_OPEN_TERMINAL
import com.itsaky.androidide.models.MainScreenAction.Companion.ACTION_PREFERENCES
import com.itsaky.androidide.preferences.databinding.LayoutDialogTextInputBinding
import com.itsaky.androidide.preferences.internal.GITHUB_PAT
import com.itsaky.androidide.resources.R.string
import com.itsaky.androidide.tasks.runOnUiThread
import com.itsaky.androidide.utils.DialogUtils
import com.itsaky.androidide.utils.Environment
import com.itsaky.androidide.utils.flashError
import com.itsaky.androidide.utils.flashSuccess
import com.itsaky.androidide.utils.viewLifecycleScope
import com.itsaky.androidide.viewmodel.MainViewModel
import com.termux.shared.termux.TermuxConstants.TERMUX_APP.TERMUX_ACTIVITY
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.adfa.constants.CONTENT_KEY
import org.adfa.constants.CONTENT_TITLE_KEY
import org.eclipse.jgit.api.CloneCommand
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.ProgressMonitor
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider
import org.slf4j.LoggerFactory
import java.io.File
import java.util.concurrent.CancellationException

class MainFragment : BaseFragment() {
    private val viewModel by activityViewModels<MainViewModel>()
    private var binding: FragmentMainBinding? = null

    private data class CloneRequest(
        val url: String,
        val targetDir: File,
    )

    private var currentCloneRequest: CloneRequest? = null

    companion object {
        private val log = LoggerFactory.getLogger(MainFragment::class.java)
        const val KEY_TOOLTIP_URL = "tooltip_url"

    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        binding = FragmentMainBinding.inflate(inflater, container, false)
        return binding!!.root
    }

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?,
    ) {
        super.onViewCreated(view, savedInstanceState)
        val actions =
            MainScreenAction.mainScreen().also { actions ->
                val onClick = { action: MainScreenAction, _: View ->
                    when (action.id) {
                        ACTION_CREATE_PROJECT -> showCreateProject()
                        ACTION_OPEN_PROJECT -> showViewSavedProjects()
                        ACTION_DELETE_PROJECT -> pickDirectoryForDeletion()
                        ACTION_CLONE_REPO -> cloneGitRepo()
                        ACTION_OPEN_TERMINAL ->
                            startActivity(
                                Intent(requireActivity(), TerminalActivity::class.java),
                            )

                        ACTION_PREFERENCES -> gotoPreferences()

                        ACTION_DOCS -> {
                            val intent =
                                Intent(requireContext(), HelpActivity::class.java).apply {
                                    putExtra(CONTENT_KEY, getString(R.string.docs_url))
                                    putExtra(CONTENT_TITLE_KEY,
                                        getString(R.string.back_to_cogo))
                                }
                            startActivity(intent)
                        }
                    }
                }
                val onLongClick = { action: MainScreenAction, _: View ->
                    performOptionsMenuClick(action)
                    true
                }

                actions.forEach { action ->
                    action.onClick = onClick
                    action.onLongClick = onLongClick

                    if (action.id == MainScreenAction.ACTION_OPEN_TERMINAL) {
                        action.onLongClick = { _: MainScreenAction, _: View ->
                            val intent =
                                Intent(requireActivity(), TerminalActivity::class.java).apply {
                                    putExtra(TERMUX_ACTIVITY.EXTRA_FAILSAFE_SESSION, true)
                                }
                            startActivity(intent)
                            true
                        }
                    }
                }
            }

        binding!!.actions.adapter = MainActionsListAdapter(actions)

        binding!!.headerContainer?.setOnClickListener { openQuickstartPageAction() }
        binding!!.headerContainer?.setOnLongClickListener {
            TooltipManager.showIdeCategoryTooltip(requireContext(), it, MAIN_GET_STARTED)
            true
        }

        binding!!.greetingText.setOnLongClickListener {
            TooltipManager.showIdeCategoryTooltip(requireContext(), it, MAIN_GET_STARTED)
            true
        }
        binding!!.greetingText.setOnClickListener { openQuickstartPageAction() }
    }

    private fun performOptionsMenuClick(action: MainScreenAction) {
        val view = action.view
        val tag = getToolTipTagForAction(action.id)
        if (tag.isNotEmpty()) {
            view.let {
                TooltipManager.showIdeCategoryTooltip(requireContext(), it!!, tag)
            }
        }
    }

    private fun getToolTipTagForAction(id: Int): String {
        return when (id) {
            ACTION_CREATE_PROJECT -> PROJECT_NEW
            ACTION_OPEN_PROJECT -> PROJECT_OPEN
            ACTION_DELETE_PROJECT -> MAIN_PROJECT_DELETE
            ACTION_DOCS -> MAIN_HELP
            else -> ""
        }
    }

    private fun openQuickstartPageAction() {
            val intent = Intent(requireContext(), HelpActivity::class.java).apply {
                putExtra(CONTENT_KEY, getString(R.string.quickstart_url))
                putExtra(CONTENT_TITLE_KEY, R.string.back_to_cogo)
            }
           startActivity(intent)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        binding = null
    }

    private fun pickDirectoryForDeletion() {
        viewModel.setScreen(MainViewModel.SCREEN_DELETE_PROJECTS)
    }

    private fun showCreateProject() {
        viewModel.setScreen(MainViewModel.SCREEN_TEMPLATE_LIST)
    }

    private fun showViewSavedProjects() {
        viewModel.setScreen(MainViewModel.SCREEN_SAVED_PROJECTS)
    }

    private fun cloneGitRepo() {
        val builder = DialogUtils.newMaterialDialogBuilder(requireContext())
        val binding = LayoutDialogTextInputBinding.inflate(layoutInflater)
        binding.name.setHint(string.git_clone_repo_url)

        builder.setView(binding.root)
        builder.setTitle(string.git_clone_repo)
        builder.setCancelable(true)
        builder.setPositiveButton(string.git_clone) { dialog, _ ->
            dialog.dismiss()
            val url =
                binding.name.editText
                    ?.text
                    ?.toString()
            doClone(url)
        }
        builder.setNegativeButton(android.R.string.cancel, null)
        builder.show()
    }

    private fun doClone(repo: String?) {
        if (repo.isNullOrBlank()) {
            log.warn("Unable to clone repo. Invalid repo URL : {}'", repo)
            return
        }

        var url = repo.trim()
        if (!url.endsWith(".git")) {
            url += ".git"
        }

        val builder = DialogUtils.newMaterialDialogBuilder(requireContext())
        val binding = LayoutDialogProgressBinding.inflate(layoutInflater)

        binding.message.visibility = View.VISIBLE

        builder.setTitle(string.git_clone_in_progress)
        builder.setMessage(url)
        builder.setView(binding.root)
        builder.setCancelable(false)

        val prefs = BaseApplication.getBaseInstance().prefManager
        val repoName = url.substringAfterLast('/').substringBeforeLast(".git")
        val targetDir = File(Environment.PROJECTS_DIR, repoName)
        currentCloneRequest = CloneRequest(url, targetDir)
        if (targetDir.exists()) {
            showCloneDirExistsError(targetDir)
            return
        }

        val progress = GitCloneProgressMonitor(binding.progress, binding.message)
        val coroutineScope =
            (activity as? BaseIDEActivity?)?.activityScope ?: viewLifecycleScope

        var getDialog: Function0<AlertDialog?>? = null

        val cloneJob =
            coroutineScope.launch(Dispatchers.IO) {
                val git =
                    try {
                        val cmd: CloneCommand = Git.cloneRepository()
                        cmd
                            .setURI(url)
                            .setDirectory(targetDir)
                            .setProgressMonitor(progress)
                        val token = prefs.getString(GITHUB_PAT, "")
                        if (!token.isNullOrBlank()) {
                            cmd.setCredentialsProvider(
                                UsernamePasswordCredentialsProvider(
                                    "<token>",
                                    token,
                                ),
                            )
                        }
                        cmd.call()
                    } catch (err: Throwable) {
                        if (!progress.isCancelled) {
                            err.printStackTrace()
                            withContext(Dispatchers.Main) {
                                getDialog?.invoke()?.also { if (it.isShowing) it.dismiss() }
                                showCloneError(err)
                            }
                        }
                        null
                    }

                try {
                    git?.close()
                } finally {
                    val success = git != null
                    withContext(Dispatchers.Main) {
                        getDialog?.invoke()?.also { dialog ->
                            if (dialog.isShowing) dialog.dismiss()
                            if (success) flashSuccess(string.git_clone_success)
                        }
                    }
                }
            }

        builder.setPositiveButton(android.R.string.cancel) { iface, _ ->
            iface.dismiss()
            progress.cancel()
            cloneJob.cancel(CancellationException("Cancelled by user"))
        }

        val dialog = builder.show()
        getDialog = { dialog }
    }

    private fun showCloneDirExistsError(targetDir: File) {
        val builder = DialogUtils.newMaterialDialogBuilder(requireContext())
        builder.apply {
            setTitle(string.title_warning)
            setMessage(
                getString(
                    R.string.git_clone_dir_exists_detailed,
                    targetDir.absolutePath,
                ),
            )
            setPositiveButton(R.string.delete_and_clone) { _, _ ->
                val progressBuilder = DialogUtils.newMaterialDialogBuilder(requireContext())
                val progressBinding = LayoutDialogProgressBinding.inflate(layoutInflater)

                progressBinding.message.visibility = View.VISIBLE
                progressBinding.message.text = getString(R.string.deleting_directory)

                progressBuilder.setTitle(R.string.please_wait)
                progressBuilder.setView(progressBinding.root)
                progressBuilder.setCancelable(false)

                val progressDialog = progressBuilder.show()

                val coroutineScope =
                    (activity as? BaseIDEActivity?)?.activityScope ?: viewLifecycleScope
                coroutineScope.launch(Dispatchers.IO) {
                    try {
                        targetDir.deleteRecursively()
                        withContext(Dispatchers.Main) {
                            progressDialog.dismiss()
                            proceedWithClone()
                        }
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) {
                            progressDialog.dismiss()
                            val errorBuilder =
                                DialogUtils.newMaterialDialogBuilder(requireContext())
                            errorBuilder.setTitle(R.string.error)
                            errorBuilder.setMessage(
                                getString(
                                    R.string.error_deleting_directory,
                                    e.localizedMessage,
                                ),
                            )
                            errorBuilder.setPositiveButton(android.R.string.ok, null)
                            errorBuilder.show()
                        }
                    }
                }
            }
            setNeutralButton(R.string.choose_different_location) { dlg, _ ->
                dlg.dismiss()
                showChooseAlternativeCloneLocation(targetDir)
            }
            setNegativeButton(android.R.string.cancel) { dlg, _ -> dlg.dismiss() }
            show()
        }
    }

    private fun proceedWithClone() {
        val request = currentCloneRequest ?: return
        val url = request.url
        val targetDir = request.targetDir

        val builder = DialogUtils.newMaterialDialogBuilder(requireContext())
        val binding = LayoutDialogProgressBinding.inflate(layoutInflater)

        binding.message.visibility = View.VISIBLE

        builder.setTitle(string.git_clone_in_progress)
        builder.setMessage(url)
        builder.setView(binding.root)
        builder.setCancelable(false)

        val prefs = BaseApplication.getBaseInstance().prefManager
        val progress = GitCloneProgressMonitor(binding.progress, binding.message)
        val coroutineScope =
            (activity as? BaseIDEActivity?)?.activityScope ?: viewLifecycleScope

        var getDialog: Function0<AlertDialog?>? = null

        val cloneJob =
            coroutineScope.launch(Dispatchers.IO) {
                val git =
                    try {
                        val cmd: CloneCommand = Git.cloneRepository()
                        cmd
                            .setURI(url)
                            .setDirectory(targetDir)
                            .setProgressMonitor(progress)
                        val token = prefs.getString(GITHUB_PAT, "")
                        if (!token.isNullOrBlank()) {
                            cmd.setCredentialsProvider(
                                UsernamePasswordCredentialsProvider(
                                    "<token>",
                                    token,
                                ),
                            )
                        }
                        cmd.call()
                    } catch (err: Throwable) {
                        if (!progress.isCancelled) {
                            err.printStackTrace()
                            withContext(Dispatchers.Main) {
                                getDialog?.invoke()?.also { if (it.isShowing) it.dismiss() }
                                showCloneError(err)
                            }
                        }
                        null
                    }

                try {
                    git?.close()
                } finally {
                    val success = git != null
                    withContext(Dispatchers.Main) {
                        getDialog?.invoke()?.also { dialog ->
                            if (dialog.isShowing) dialog.dismiss()
                            if (success) flashSuccess(string.git_clone_success)
                        }
                    }
                }
            }

        builder.setPositiveButton(android.R.string.cancel) { iface, _ ->
            iface.dismiss()
            progress.cancel()
            cloneJob.cancel(CancellationException("Cancelled by user"))
        }

        val dialog = builder.show()
        getDialog = { dialog }
    }

    private fun showChooseAlternativeCloneLocation(originalDir: File) {
        val cloneRequest = currentCloneRequest ?: return

        val builder = DialogUtils.newMaterialDialogBuilder(requireContext())
        val binding = LayoutDialogTextInputBinding.inflate(layoutInflater)

        binding.name.setHint(string.new_directory_name)
        binding.name.editText?.setText(originalDir.name + "_new")

        builder.setView(binding.root)
        builder.setTitle(string.choose_different_location)
        builder.setCancelable(true)
        builder.setPositiveButton(string.git_clone) { dialog, _ ->
            dialog.dismiss()
            val newDirName =
                binding.name.editText
                    ?.text
                    ?.toString()
            if (!newDirName.isNullOrBlank()) {
                val newTargetDir = File(originalDir.parentFile, newDirName)

                currentCloneRequest = CloneRequest(cloneRequest.url, newTargetDir)

                proceedWithClone()
            }
        }
        builder.setNegativeButton(android.R.string.cancel, null)
        builder.show()
    }

    private fun showCloneError(error: Throwable?) {
        if (error == null) {
            flashError(string.git_clone_failed)
            return
        }
        val builder = DialogUtils.newMaterialDialogBuilder(requireContext())
        builder.setTitle(string.git_clone_failed)
        builder.setMessage(error.localizedMessage)
        builder.setPositiveButton(android.R.string.ok, null)
        builder.show()
    }

    private fun gotoPreferences() {
        startActivity(Intent(requireActivity(), PreferencesActivity::class.java))
    }

    // TODO(itsaky) : Improve this implementation
    class GitCloneProgressMonitor(
        val progress: LinearProgressIndicator,
        val message: TextView,
    ) : ProgressMonitor {
        private var cancelled = false

        fun cancel() {
            cancelled = true
        }

        override fun start(totalTasks: Int) {
            runOnUiThread { progress.max = totalTasks }
        }

        override fun beginTask(
            title: String?,
            totalWork: Int,
        ) {
            runOnUiThread { message.text = title }
        }

        override fun update(completed: Int) {
            runOnUiThread { progress.progress = completed }
        }

        override fun showDuration(enabled: Boolean) {
            // no-op
        }

        override fun endTask() {}

        override fun isCancelled(): Boolean = cancelled || Thread.currentThread().isInterrupted
    }
}
