package com.example.sampleplugin.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.itsaky.androidide.plugins.base.PluginFragmentHelper
import com.itsaky.androidide.plugins.services.IdeTooltipService
import com.example.sampleplugin.R

class HelloPluginFragment : Fragment() {

    companion object {
        private const val PLUGIN_ID = "com.example.sampleplugin"
    }

    private var tooltipService: IdeTooltipService? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Get the tooltip service from the plugin's service registry
        try {
            val serviceRegistry = PluginFragmentHelper.getServiceRegistry(PLUGIN_ID)
            tooltipService = serviceRegistry?.get(IdeTooltipService::class.java)
        } catch (e: Exception) {
            // Service might not be available yet
        }
    }

    override fun onGetLayoutInflater(savedInstanceState: Bundle?): LayoutInflater {
        val inflater = super.onGetLayoutInflater(savedInstanceState)
        return PluginFragmentHelper.getPluginInflater(PLUGIN_ID, inflater)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_hello_plugin, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Find and set up the test button
        val testButton = view.findViewById<Button>(R.id.btn_test_action)
        testButton?.setOnClickListener {
            Toast.makeText(
                requireContext(),
                "Plugin action executed from XML layout!",
                Toast.LENGTH_SHORT
            ).show()
        }

        // Add long press to show tooltip documentation
        testButton?.setOnLongClickListener { button ->
            tooltipService?.showTooltip(
                anchorView = button,
                category = "plugin_sampleplugin",
                tag = "sampleplugin.editor_tab"
            ) ?: run {
                Toast.makeText(
                    requireContext(),
                    "Tooltip service not available. Long press detected!",
                    Toast.LENGTH_LONG
                ).show()
            }
            true // Consume the long click
        }
    }
}