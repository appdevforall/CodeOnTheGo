package com.itsaky.androidide.fragments

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.text.HtmlCompat
import androidx.fragment.app.viewModels
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.itsaky.androidide.BuildConfig
import com.itsaky.androidide.R
import com.itsaky.androidide.activities.MainActivity
import com.itsaky.androidide.activities.PreferencesActivity
import com.itsaky.androidide.activities.TerminalActivity
import com.itsaky.androidide.adapters.MainActionsListAdapter
import com.itsaky.androidide.app.BaseApplication
import com.itsaky.androidide.app.BaseIDEActivity
import com.itsaky.androidide.common.databinding.LayoutDialogProgressBinding
import com.itsaky.androidide.databinding.FragmentMainBinding
import com.itsaky.androidide.idetooltips.IDETooltipDatabase
import com.itsaky.androidide.idetooltips.IDETooltipItem
import com.itsaky.androidide.models.MainScreenAction
import com.itsaky.androidide.preferences.databinding.LayoutDialogTextInputBinding
import com.itsaky.androidide.preferences.internal.GITHUB_PAT
import com.itsaky.androidide.resources.R.string
import com.itsaky.androidide.tasks.runOnUiThread
import com.itsaky.androidide.utils.DialogUtils
import com.itsaky.androidide.utils.Environment
import com.itsaky.androidide.utils.TooltipUtils
import com.itsaky.androidide.utils.flashError
import com.itsaky.androidide.utils.flashSuccess
import com.itsaky.androidide.viewmodel.MainViewModel
import com.termux.shared.termux.TermuxConstants.TERMUX_APP.TERMUX_ACTIVITY
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.eclipse.jgit.api.CloneCommand
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.ProgressMonitor
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider
import org.slf4j.LoggerFactory
import java.io.File
import java.text.MessageFormat
import java.util.concurrent.CancellationException

class MainFragment : BaseFragment() {

    private val viewModel by viewModels<MainViewModel>(
        ownerProducer = { requireActivity() })
    private var binding: FragmentMainBinding? = null
    private data class CloneRequest(val url: String, val targetDir: File)
    private var currentCloneRequest: CloneRequest? = null

    companion object {

        private val log = LoggerFactory.getLogger(MainFragment::class.java)
        const val KEY_TOOLTIP_URL = "tooltip_url"
    }

    private val shareActivityResultLauncher = registerForActivityResult<Intent, ActivityResult>(
        ActivityResultContracts.StartActivityForResult()
    ) { //ACTION_SEND always returns RESULT_CANCELLED, ignore it
        // There are no request codes
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = FragmentMainBinding.inflate(inflater, container, false)
        return binding!!.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val actions = MainScreenAction.mainScreen().also { actions ->
            val onClick = { action: MainScreenAction, _: View ->
                when (action.id) {
                    MainScreenAction.ACTION_CREATE_PROJECT -> showCreateProject()
                    MainScreenAction.ACTION_OPEN_PROJECT -> showViewSavedProjects()
                    MainScreenAction.ACTION_DELETE_PROJECT -> pickDirectoryForDeletion()
                    MainScreenAction.ACTION_CLONE_REPO -> cloneGitRepo()
                    MainScreenAction.ACTION_OPEN_TERMINAL -> startActivity(
                        Intent(requireActivity(), TerminalActivity::class.java)
                    )

                    MainScreenAction.ACTION_PREFERENCES -> gotoPreferences()

                    MainScreenAction.ACTION_DOCS -> BaseApplication.getBaseInstance().openDocs()
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
                        val intent = Intent(requireActivity(), TerminalActivity::class.java).apply {
                            putExtra(TERMUX_ACTIVITY.EXTRA_FAILSAFE_SESSION, true)
                        }
                        startActivity(intent)
                        true
                    }
                }
            }
        }

        binding!!.actions.adapter = MainActionsListAdapter(actions)
        binding!!.greetingText.setOnClickListener {
            TooltipUtils.showWebPage(
                requireContext(),
                "file:///android_asset/idetooltips/getstarted_top.html"
            )
        }

        binding!!.floatingActionButton?.setOnClickListener {
            performFeedbackAction()
        }
    }

    // this method will handle the onclick options click
    private fun performOptionsMenuClick(action: MainScreenAction) {
        val view = action.view
        val tag = action.id.toString()
        CoroutineScope(Dispatchers.IO).launch {
            val dao = IDETooltipDatabase.getDatabase(requireContext()).idetooltipDao()
            val item = dao.getTooltip("ide", tag)
            withContext((Dispatchers.Main)) {
                (context?.let {
                    TooltipUtils.showIDETooltip(
                        it,
                        view!!,
                        0,
                        IDETooltipItem(
                            tooltipCategory = "ide",
                            tooltipTag = item?.tooltipTag ?: "",
                            detail = item?.detail ?: "",
                            summary = item?.summary ?: "",
                            buttons = item?.buttons ?: arrayListOf(),
                        )
                    )
                })
            }
        }
    }

    private fun performFeedbackAction() {
        val builder = context?.let { DialogUtils.newMaterialDialogBuilder(it) }
        builder?.let { builder ->
            builder.setTitle("Alert!")
                .setMessage(
                    HtmlCompat.fromHtml(
                        getString(R.string.email_feedback_warning_prompt),
                        HtmlCompat.FROM_HTML_MODE_COMPACT
                    )
                )
                .setNegativeButton(android.R.string.cancel) { dialog, _ -> dialog.dismiss() }
                .setPositiveButton(android.R.string.ok) { dialog, _ ->
                    run {
                        val stackTrace = Exception().stackTrace.asList().toString().replace(",", "\n")

                        val feedbackBody = buildString {
                            append(
                                getString(
                                    R.string.feedback_message,
                                    BuildConfig.VERSION_NAME,
                                    stackTrace
                                )
                            )
                            append("\n\n-- \n")
                        }

                        val feedbackEmail = getString(R.string.feedback_email)
                        val currentScreen = getCurrentScreenName()

                        try {
                            val feedbackIntent = Intent(Intent.ACTION_SENDTO).apply {
                                data = Uri.parse("mailto:")
                                putExtra(Intent.EXTRA_EMAIL, arrayOf(feedbackEmail))

                                val subject = String.format(
                                    resources.getString(R.string.feedback_subject),
                                    currentScreen
                                )
                                putExtra(Intent.EXTRA_SUBJECT, subject)
                                putExtra(Intent.EXTRA_TEXT, feedbackBody)
                            }

                            shareActivityResultLauncher.launch(
                                Intent.createChooser(feedbackIntent, "Send Feedback")
                            )
                        } catch (e: Exception) {
                            try {
                                val fallbackIntent = Intent(Intent.ACTION_SEND).apply {
                                    type = "message/rfc822"
                                    putExtra(Intent.EXTRA_EMAIL, arrayOf(feedbackEmail))

                                    val subject = String.format(
                                        resources.getString(R.string.feedback_subject),
                                        currentScreen
                                    )
                                    putExtra(Intent.EXTRA_SUBJECT, subject)
                                    putExtra(Intent.EXTRA_TEXT, feedbackBody)
                                }
                                shareActivityResultLauncher.launch(
                                    Intent.createChooser(fallbackIntent, "Send Feedback")
                                )
                            } catch (e2: Exception) {
                                requireActivity().flashError(R.string.no_email_apps)
                            }
                        }

                        dialog.dismiss()
                    }
                }
                .create()
                .show()
        }
    }

    private fun getCurrentScreenName(): String {
        val activity = requireActivity()
        return activity.javaClass.simpleName.replace("Activity", "")
    }


    override fun onDestroyView() {
        super.onDestroyView()
        binding = null
    }

    private fun pickDirectory() {
        pickDirectory(this::openProject)
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

    fun openProject(root: File) {
        (requireActivity() as MainActivity).openProject(root)
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
            val url = binding.name.editText?.text?.toString()
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

        val cloneJob = coroutineScope.launch(Dispatchers.IO) {

            val git = try {
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
                            token
                        )
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
        val builder = context?.let { DialogUtils.newMaterialDialogBuilder(it) }
        builder?.setTitle(string.title_warning)
        builder?.setMessage(getString(R.string.git_clone_dir_exists_detailed, targetDir.absolutePath))
        builder?.setPositiveButton(R.string.delete_and_clone) { _, _ ->
            val progressBuilder = DialogUtils.newMaterialDialogBuilder(requireContext())
            val progressBinding = LayoutDialogProgressBinding.inflate(layoutInflater)

            progressBinding.message.visibility = View.VISIBLE
            progressBinding.message.text = getString(R.string.deleting_directory)

            progressBuilder.setTitle(R.string.please_wait)
            progressBuilder.setView(progressBinding.root)
            progressBuilder.setCancelable(false)

            val progressDialog = progressBuilder.show()

            val coroutineScope = (activity as? BaseIDEActivity?)?.activityScope ?: viewLifecycleScope
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
                        val errorBuilder = DialogUtils.newMaterialDialogBuilder(requireContext())
                        errorBuilder.setTitle(R.string.error)
                        errorBuilder.setMessage(getString(R.string.error_deleting_directory, e.localizedMessage))
                        errorBuilder.setPositiveButton(android.R.string.ok, null)
                        errorBuilder.show()
                    }
                }
            }
        }
        builder?.setNeutralButton(R.string.choose_different_location) { dlg, _ ->
            dlg.dismiss()
            showChooseAlternativeCloneLocation(targetDir)
        }
        builder?.setNegativeButton(android.R.string.cancel) { dlg, _ -> dlg.dismiss() }
        builder?.show()
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

        val cloneJob = coroutineScope.launch(Dispatchers.IO) {
            val git = try {
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
                            token
                        )
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
            val newDirName = binding.name.editText?.text?.toString()
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
        val message: TextView
    ) : ProgressMonitor {

        private var cancelled = false

        fun cancel() {
            cancelled = true
        }

        override fun start(totalTasks: Int) {
            runOnUiThread { progress.max = totalTasks }
        }

        override fun beginTask(title: String?, totalWork: Int) {
            runOnUiThread { message.text = title }
        }

        override fun update(completed: Int) {
            runOnUiThread { progress.progress = completed }
        }

        override fun showDuration(enabled: Boolean) {
            // no-op
        }

        override fun endTask() {}

        override fun isCancelled(): Boolean {
            return cancelled || Thread.currentThread().isInterrupted
        }
    }
}
