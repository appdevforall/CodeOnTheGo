

package com.itsaky.androidide.activities

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.CheckBox
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.graphics.Insets
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.itsaky.androidide.FeedbackButtonManager
import com.itsaky.androidide.R
import com.itsaky.androidide.adapters.PluginListAdapter
import com.itsaky.androidide.app.EdgeToEdgeIDEActivity
import com.itsaky.androidide.databinding.ActivityPluginManagerBinding
import com.itsaky.androidide.plugins.PluginInfo
import com.itsaky.androidide.ui.models.PluginManagerUiEffect
import com.itsaky.androidide.ui.models.PluginManagerUiEvent
import com.itsaky.androidide.utils.UrlManager
import com.itsaky.androidide.utils.getFileName
import com.itsaky.androidide.utils.flashError
import com.itsaky.androidide.utils.flashSuccess
import com.itsaky.androidide.utils.flashbarBuilder
import com.itsaky.androidide.utils.errorIcon
import com.itsaky.androidide.utils.showOnUiThread
import com.itsaky.androidide.utils.DialogUtils.showRestartPrompt
import com.itsaky.androidide.viewmodels.PluginManagerViewModel
import com.itsaky.androidide.idetooltips.TooltipManager
import com.itsaky.androidide.idetooltips.TooltipTag
import android.content.ClipData
import android.content.ClipboardManager
import com.itsaky.androidide.utils.DURATION_INDEFINITE
import kotlinx.coroutines.launch
import org.koin.androidx.viewmodel.ext.android.viewModel

class PluginManagerActivity : EdgeToEdgeIDEActivity() {

    companion object {
        private const val TAG = "PluginManagerActivity"
        private const val PLUGIN_EXTENSION = ".cgp"
    }

    private var _binding: ActivityPluginManagerBinding? = null
    private val binding: ActivityPluginManagerBinding
        get() = checkNotNull(_binding) { "Activity has been destroyed" }

    private lateinit var adapter: PluginListAdapter
    private var feedbackButtonManager: FeedbackButtonManager? = null

    private val viewModel: PluginManagerViewModel by viewModel()

    private val pluginPickerLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            uri?.let {
                try {
                    contentResolver.takePersistableUriPermission(
                        it,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                } catch (e: SecurityException) {
                    Log.w(TAG, "Could not take persistable URI permission", e)
                }

                if (!it.isSupportedPluginFile()) {
                    flashError(getString(R.string.msg_unsupported_plugin_file))
                    return@let
                }

                showInstallConfirmation(it)
            }
        }

    override fun bindLayout(): View {
        _binding = ActivityPluginManagerBinding.inflate(layoutInflater)
        return binding.root
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        try {
            super.onCreate(savedInstanceState)

            setSupportActionBar(binding.toolbar)
            supportActionBar?.apply {
                title = getString(R.string.title_plugin_manager)
                setDisplayHomeAsUpEnabled(true)
            }

            binding.toolbar.setNavigationOnClickListener {
                onBackPressedDispatcher.onBackPressed()
            }

            setupRecyclerView()
            setupFab()
            setupTooltipLongPress()
            setupFeedbackButton()
            observeViewModel()
        } catch (e: Exception) {
            // Log the error and finish the activity if something goes wrong
            e.printStackTrace()
            flashError(getString(R.string.msg_plugin_manager_init_failed, e.message))
            finish()
        }
    }

    override fun onResume() {
        super.onResume()
        feedbackButtonManager?.loadFabPosition()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_plugin_manager, menu)
        binding.toolbar.post {
            binding.toolbar.findViewById<View>(R.id.action_discover_plugins)?.setOnLongClickListener { view ->
                TooltipManager.showIdeCategoryTooltip(this, view, TooltipTag.PLUGIN_MANAGER)
                true
            }
        }
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_discover_plugins -> {
                UrlManager.openUrl(getString(R.string.url_discover_plugins), null, this)
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        _binding = null
    }

    override fun onApplySystemBarInsets(insets: Insets) {
        binding.root.setPaddingRelative(
            insets.left,
            insets.top,
            insets.right,
            insets.bottom
        )
    }

    private fun setupRecyclerView() {
        adapter = PluginListAdapter { plugin, action ->
            when (action) {
                PluginListAdapter.Action.ENABLE -> viewModel.onEvent(PluginManagerUiEvent.EnablePlugin(plugin.metadata.id))
                PluginListAdapter.Action.DISABLE -> viewModel.onEvent(PluginManagerUiEvent.DisablePlugin(plugin.metadata.id))
                PluginListAdapter.Action.UNINSTALL -> viewModel.onEvent(PluginManagerUiEvent.UninstallPlugin(plugin.metadata.id))
                PluginListAdapter.Action.DETAILS -> viewModel.onEvent(PluginManagerUiEvent.ShowPluginDetails(plugin))
            }
        }

        binding.recyclerView.apply {
            layoutManager = LinearLayoutManager(this@PluginManagerActivity)
            adapter = this@PluginManagerActivity.adapter
        }
    }

    private fun setupFab() {
        binding.fabInstallPlugin.setOnClickListener {
            viewModel.onEvent(PluginManagerUiEvent.OpenFilePicker)
        }
    }

    private fun setupTooltipLongPress() {
        val showTooltip: (View) -> Unit = { view ->
            TooltipManager.showIdeCategoryTooltip(this, view, TooltipTag.PLUGIN_MANAGER)
        }
        binding.toolbar.setOnLongClickListener { showTooltip(it); true }
        binding.fabInstallPlugin.setOnLongClickListener { showTooltip(it); true }
        binding.emptyState.setOnLongClickListener { showTooltip(it); true }
        binding.recyclerView.setOnLongClickListener { showTooltip(it); true }
    }

    private fun setupFeedbackButton(){
        feedbackButtonManager =
            FeedbackButtonManager(
                activity = this,
                feedbackFab = binding.fabFeedback,
            )
        feedbackButtonManager?.setupDraggableFab()
    }

    private fun observeViewModel() {
        // Observe UI state
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    updateUI(state)
                }
            }
        }

        // Observe UI effects
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiEffect.collect { effect ->
                    handleUiEffect(effect)
                }
            }
        }
    }

    private fun updateUI(state: com.itsaky.androidide.ui.models.PluginManagerUiState) {
        // Update plugin list
        adapter.submitList(state.plugins)

        // Update empty state
        if (state.showEmptyState) {
            binding.recyclerView.visibility = View.GONE
            binding.emptyState.visibility = View.VISIBLE
        } else {
            binding.recyclerView.visibility = View.VISIBLE
            binding.emptyState.visibility = View.GONE
        }

        // Update install button state
        binding.fabInstallPlugin.isEnabled = !state.isInstalling
    }

    private fun handleUiEffect(effect: PluginManagerUiEffect) {
        when (effect) {
            is PluginManagerUiEffect.ShowError -> {
                val errorMessage = getString(effect.messageResId, *effect.formatArgs.toTypedArray())
                val builder = flashbarBuilder(duration = if (effect.formatArgs.isEmpty()) 5000L else DURATION_INDEFINITE)
                    .errorIcon()
                    .message(errorMessage)
                if (effect.formatArgs.isNotEmpty()) {
                    builder
                        .positiveActionText(R.string.copy)
                        .positiveActionTapListener { bar ->
                            (getSystemService(ClipboardManager::class.java))
                                ?.setPrimaryClip(ClipData.newPlainText(getString(R.string.msg_plugin_error_clip_label), errorMessage))
                            bar.dismiss()
                        }
                }
                builder.showOnUiThread()
            }
            is PluginManagerUiEffect.ShowSuccess -> {
                flashSuccess(getString(effect.messageResId))
            }
            is PluginManagerUiEffect.ShowPluginDetails -> {
                showPluginDetails(effect.plugin)
            }
            is PluginManagerUiEffect.OpenFilePicker -> {
                openFilePicker()
            }
            is PluginManagerUiEffect.ShowUninstallConfirmation -> {
                showUninstallConfirmation(effect.plugin)
            }
            is PluginManagerUiEffect.ShowRestartPrompt -> {
                showRestartPrompt(this, cancelable = false)
            }
            is PluginManagerUiEffect.ShowOverwriteConfirmation -> {
                showOverwriteConfirmation(effect)
            }
        }
    }

    private fun openFilePicker() {
        try {
            pluginPickerLauncher.launch(arrayOf("*/*"))
        } catch (_: Exception) {
            flashError(getString(R.string.msg_no_file_manager))
        }
    }

    private fun Uri.isSupportedPluginFile(): Boolean =
        getFileName(this@PluginManagerActivity).endsWith(PLUGIN_EXTENSION, ignoreCase = true)

    private fun showInstallConfirmation(uri: Uri) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_install_plugin, null)
        val deleteCheckBox = dialogView.findViewById<CheckBox>(R.id.checkbox_delete_source)

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.title_install_plugin)
            .setView(dialogView)
            .setPositiveButton(R.string.btn_install) { _, _ ->
                viewModel.onEvent(PluginManagerUiEvent.InstallPlugin(uri, deleteCheckBox.isChecked))
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showOverwriteConfirmation(effect: PluginManagerUiEffect.ShowOverwriteConfirmation) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.title_plugin_already_installed)
            .setMessage(
                getString(
                    R.string.msg_plugin_overwrite_confirm,
                    effect.existing.metadata.name,
                    effect.existing.metadata.version,
                    effect.incomingMetadata.version
                )
            )
            .setPositiveButton(R.string.replace) { _, _ ->
                viewModel.onEvent(
                    PluginManagerUiEvent.ConfirmOverwrite(effect.uri, effect.deleteSourceAfterInstall)
                )
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showUninstallConfirmation(plugin: PluginInfo) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Uninstall Plugin")
            .setMessage("Are you sure you want to uninstall '${plugin.metadata.name}'?")
            .setPositiveButton("Uninstall") { _, _ ->
                viewModel.confirmUninstallPlugin(plugin.metadata.id)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showPluginDetails(plugin: PluginInfo) {
        val details = buildString {
            append("Name: ${plugin.metadata.name}\n")
            append("Plugin ID: ${plugin.metadata.id}\n")
            append("Version: ${plugin.metadata.version}\n")
            append("Author: ${plugin.metadata.author}\n")
            append("Description: ${plugin.metadata.description}\n")
            append("Min IDE Version: ${plugin.metadata.minIdeVersion}\n")
            append("Permissions: ${plugin.metadata.permissions.joinToString(", ")}\n")
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(plugin.metadata.name)
            .setMessage(details)
            .setPositiveButton("OK", null)
            .show()
    }

}