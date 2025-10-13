

package com.itsaky.androidide.activities

import android.content.Intent
import android.os.Bundle
import android.view.View
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
import com.itsaky.androidide.utils.flashError
import com.itsaky.androidide.utils.flashSuccess
import com.itsaky.androidide.viewmodels.PluginManagerViewModel
import kotlinx.coroutines.launch
import org.koin.androidx.viewmodel.ext.android.viewModel

class PluginManagerActivity : EdgeToEdgeIDEActivity() {

    companion object {
        private const val REQUEST_CODE_PICK_PLUGIN = 1001
    }

    private var _binding: ActivityPluginManagerBinding? = null
    private val binding: ActivityPluginManagerBinding
        get() = checkNotNull(_binding) { "Activity has been destroyed" }

    private lateinit var adapter: PluginListAdapter
    private var feedbackButtonManager: FeedbackButtonManager? = null

    // ViewModel injected via Koin
    private val viewModel: PluginManagerViewModel by viewModel()

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
            setupFeedbackButton()
            observeViewModel()
        } catch (e: Exception) {
            // Log the error and finish the activity if something goes wrong
            e.printStackTrace()
            flashError("Failed to initialize Plugin Manager: ${e.message}")
            finish()
        }
    }

    override fun onResume() {
        super.onResume()
        feedbackButtonManager?.loadFabPosition()
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
                flashError(effect.message)
            }
            is PluginManagerUiEffect.ShowSuccess -> {
                flashSuccess(effect.message)
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
                showRestartPrompt()
            }
        }
    }

    private fun openFilePicker() {
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "*/*"  // Accept all files to allow .cgp
            addCategory(Intent.CATEGORY_OPENABLE)
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("*/*"))
        }

        try {
            startActivityForResult(
                Intent.createChooser(intent, "Select Plugin File"),
                REQUEST_CODE_PICK_PLUGIN
            )
        } catch (_: Exception) {
            flashError("No file manager found")
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == REQUEST_CODE_PICK_PLUGIN && resultCode == RESULT_OK) {
            data?.data?.let { uri ->
                viewModel.onEvent(PluginManagerUiEvent.InstallPlugin(uri))
            }
        }
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

    private fun showRestartPrompt() {
        MaterialAlertDialogBuilder(this)
            .setTitle("Restart Required")
            .setMessage("Plugin changes will take effect after restarting the app. Do you want to restart now?")
            .setPositiveButton("Restart Now") { _, _ ->
                restartApp()
            }
            .setNegativeButton("Later", null)
            .setCancelable(false)
            .show()
    }

    private fun restartApp() {
        val intent = packageManager.getLaunchIntentForPackage(packageName)
        intent?.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
        intent?.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
        startActivity(intent)
        finishAffinity()
    }
}