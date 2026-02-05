package com.itsaky.androidide.templates.impl.pluginProject

fun pluginFragmentKt(data: PluginTemplateData): String = """
package ${data.pluginId}.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.fragment.app.Fragment
import ${data.pluginId}.R
import com.itsaky.androidide.plugins.base.PluginFragmentHelper
import com.itsaky.androidide.plugins.services.IdeTooltipService
import android.widget.Toast

class ${data.className}Fragment : Fragment() {

    companion object {
        private const val PLUGIN_ID = "${data.pluginId}"
    }

    private var statusText: TextView? = null
    private var actionButton: Button? = null
    
    // Tooltips 
    private var tooltipService: IdeTooltipService? = null
    

    override fun onGetLayoutInflater(savedInstanceState: Bundle?): LayoutInflater {
        val inflater = super.onGetLayoutInflater(savedInstanceState)
        return PluginFragmentHelper.getPluginInflater(PLUGIN_ID, inflater)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        setupServices()
        return inflater.inflate(R.layout.fragment_main, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        statusText = view.findViewById(R.id.statusText)
        actionButton = view.findViewById(R.id.actionButton)

        actionButton?.setOnClickListener {
            onActionButtonClicked()
        }
        
        actionButton?.setOnLongClickListener { button ->
            tooltipService?.showTooltip(
                anchorView = button,
                category = "${data.pluginId.replace(".", "_")}",
                tag = "${data.pluginId.replace(".", "_")}.overview"
            ) ?: run {
                activity?.runOnUiThread {
                    Toast.makeText(context, "Long press detected! Tooltip service not available.", Toast.LENGTH_SHORT).show()
                }
            }
            true
        }

        updateStatus("${data.pluginName.replace("\"", "\\\"")} is ready!")
    }

    private fun onActionButtonClicked() {
        updateStatus("Action performed!")
    }

    private fun updateStatus(message: String) {
        statusText?.text = message
    }
    
    fun setupServices(){
        // Use this to set services from the plugin's service registry
        runCatching {
         val serviceRegistry = PluginFragmentHelper.getServiceRegistry(PLUGIN_ID)
         tooltipService = serviceRegistry?.get(IdeTooltipService::class.java)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        statusText = null
        actionButton = null
    }
}
""".trimIndent()