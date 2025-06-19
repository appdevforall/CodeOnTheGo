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

package com.itsaky.androidide.activities

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.graphics.Insets
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.itsaky.androidide.R
import com.itsaky.androidide.adapters.PluginListAdapter
import com.itsaky.androidide.app.EdgeToEdgeIDEActivity
import com.itsaky.androidide.app.IDEApplication
import com.itsaky.androidide.databinding.ActivityPluginManagerBinding
import com.itsaky.androidide.plugins.PluginInfo
import com.itsaky.androidide.utils.flashError
import com.itsaky.androidide.utils.flashSuccess
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class PluginManagerActivity : EdgeToEdgeIDEActivity() {

    companion object {
        private const val REQUEST_CODE_PICK_PLUGIN = 1001
    }

    private var _binding: ActivityPluginManagerBinding? = null
    private val binding: ActivityPluginManagerBinding
        get() = checkNotNull(_binding) { "Activity has been destroyed" }

    private lateinit var adapter: PluginListAdapter
    private val pluginManager get() = IDEApplication.getPluginManager()

    override fun bindLayout(): View {
        _binding = ActivityPluginManagerBinding.inflate(layoutInflater)
        return binding.root
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        try {
            super.onCreate(savedInstanceState)

            setSupportActionBar(binding.toolbar)
            supportActionBar?.apply {
                title = "Plugin Manager"
                setDisplayHomeAsUpEnabled(true)
            }

            binding.toolbar.setNavigationOnClickListener { 
                onBackPressedDispatcher.onBackPressed() 
            }

            setupRecyclerView()
            setupFab()
            loadPlugins()
        } catch (e: Exception) {
            // Log the error and finish the activity if something goes wrong
            e.printStackTrace()
            flashError("Failed to initialize Plugin Manager: ${e.message}")
            finish()
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
                PluginListAdapter.Action.ENABLE -> enablePlugin(plugin)
                PluginListAdapter.Action.DISABLE -> disablePlugin(plugin)
                PluginListAdapter.Action.UNINSTALL -> uninstallPlugin(plugin)
                PluginListAdapter.Action.DETAILS -> showPluginDetails(plugin)
            }
        }

        binding.recyclerView.apply {
            layoutManager = LinearLayoutManager(this@PluginManagerActivity)
            adapter = this@PluginManagerActivity.adapter
        }
    }

    private fun setupFab() {
        binding.fabInstallPlugin.setOnClickListener {
            openFilePicker()
        }
    }

    private fun loadPlugins() {
        // Add a safety check for plugin manager
        val manager = pluginManager
        if (manager == null) {
            // Plugin manager not initialized yet, show empty state
            updateEmptyState(true)
            return
        }
        
        GlobalScope.launch(Dispatchers.IO) {
            try {
                val plugins = manager.getAllPlugins()
                
                withContext(Dispatchers.Main) {
                    adapter.submitList(plugins)
                    updateEmptyState(plugins.isEmpty())
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    flashError("Failed to load plugins: ${e.message}")
                    updateEmptyState(true)
                }
            }
        }
    }

    private fun updateEmptyState(isEmpty: Boolean) {
        if (isEmpty) {
            binding.recyclerView.visibility = View.GONE
            binding.emptyState.visibility = View.VISIBLE
        } else {
            binding.recyclerView.visibility = View.VISIBLE
            binding.emptyState.visibility = View.GONE
        }
    }

    private fun openFilePicker() {
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "*/*"
            addCategory(Intent.CATEGORY_OPENABLE)
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/java-archive", "application/zip"))
        }
        
        try {
            startActivityForResult(
                Intent.createChooser(intent, "Select Plugin File"),
                REQUEST_CODE_PICK_PLUGIN
            )
        } catch (e: Exception) {
            flashError("No file manager found")
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        
        if (requestCode == REQUEST_CODE_PICK_PLUGIN && resultCode == Activity.RESULT_OK) {
            data?.data?.let { uri ->
                installPlugin(uri)
            }
        }
    }

    private fun installPlugin(uri: Uri) {
        GlobalScope.launch(Dispatchers.IO) {
            try {
                val inputStream = contentResolver.openInputStream(uri)
                    ?: throw Exception("Cannot open file")

                val fileName = getFileName(uri) ?: "plugin_${System.currentTimeMillis()}.jar"
                val pluginFile = File(filesDir, "plugins/$fileName")
                
                pluginFile.parentFile?.mkdirs()

                FileOutputStream(pluginFile).use { output ->
                    inputStream.use { input ->
                        input.copyTo(output)
                    }
                }

                val manager = pluginManager
                if (manager == null) {
                    withContext(Dispatchers.Main) {
                        flashError("Plugin system not available")
                    }
                    return@launch
                }
                
                val result = manager.loadPlugin(pluginFile)
                
                withContext(Dispatchers.Main) {
                    if (result?.isSuccess == true) {
                        flashSuccess("Plugin installed successfully")
                        loadPlugins()
                    } else {
                        flashError("Failed to install plugin: ${result?.exceptionOrNull()?.message}")
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    flashError("Failed to install plugin: ${e.message}")
                }
            }
        }
    }

    private fun getFileName(uri: Uri): String? {
        return contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            cursor.moveToFirst()
            cursor.getString(nameIndex)
        }
    }

    private fun enablePlugin(plugin: PluginInfo) {
        val manager = pluginManager
        if (manager == null) {
            flashError("Plugin system not available")
            return
        }
        
        GlobalScope.launch(Dispatchers.IO) {
            val success = manager.enablePlugin(plugin.metadata.id)
            
            withContext(Dispatchers.Main) {
                if (success) {
                    flashSuccess("Plugin enabled")
                    loadPlugins()
                } else {
                    flashError("Failed to enable plugin")
                }
            }
        }
    }

    private fun disablePlugin(plugin: PluginInfo) {
        val manager = pluginManager
        if (manager == null) {
            flashError("Plugin system not available")
            return
        }
        
        GlobalScope.launch(Dispatchers.IO) {
            val success = manager.disablePlugin(plugin.metadata.id)
            
            withContext(Dispatchers.Main) {
                if (success) {
                    flashSuccess("Plugin disabled")
                    loadPlugins()
                } else {
                    flashError("Failed to disable plugin")
                }
            }
        }
    }

    private fun uninstallPlugin(plugin: PluginInfo) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Uninstall Plugin")
            .setMessage("Are you sure you want to uninstall '${plugin.metadata.name}'?")
            .setPositiveButton("Uninstall") { _, _ ->
                val manager = pluginManager
                if (manager == null) {
                    flashError("Plugin system not available")
                    return@setPositiveButton
                }
                
                GlobalScope.launch(Dispatchers.IO) {
                    val success = manager.unloadPlugin(plugin.metadata.id)
                    
                    withContext(Dispatchers.Main) {
                        if (success) {
                            flashSuccess("Plugin uninstalled")
                            loadPlugins()
                        } else {
                            flashError("Failed to uninstall plugin")
                        }
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showPluginDetails(plugin: PluginInfo) {
        val details = buildString {
            append("Name: ${plugin.metadata.name}\n")
            append("Version: ${plugin.metadata.version}\n")
            append("Author: ${plugin.metadata.author}\n")
            append("Description: ${plugin.metadata.description}\n")
            append("Min IDE Version: ${plugin.metadata.minIdeVersion}\n")
            append("Permissions: ${plugin.metadata.permissions.joinToString(", ")}\n")
            append("Dependencies: ${plugin.metadata.dependencies.joinToString(", ")}")
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(plugin.metadata.name)
            .setMessage(details)
            .setPositiveButton("OK", null)
            .show()
    }
}