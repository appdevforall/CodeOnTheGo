package com.example.sampleplugin.fragments

import android.graphics.Typeface
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment

class HelloPluginFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val context = requireContext()

        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }

        val titleView = TextView(context).apply {
            text = "Hello World Plugin Tab"
            textSize = 18f
            setTypeface(null, Typeface.BOLD)
            setPadding(0, 0, 0, 16)
        }

        val descriptionView = TextView(context).apply {
            text = "This is a custom tab contributed by the Hello World plugin.\n\nThis demonstrates the plugin system's ability to extend the editor bottom sheet with custom fragments."
            textSize = 14f
            setPadding(0, 0, 0, 16)
        }

        val capabilitiesView = TextView(context).apply {
            text = "Plugin capabilities:\n• Add custom menu items\n• Contribute to context menus\n• Add custom tabs to the editor bottom sheet\n• Access IDE services (project, editor, UI)\n• Create programmatic UIs"
            textSize = 12f
            setPadding(0, 0, 0, 16)
        }

        val testButton = Button(context).apply {
            text = "Test Plugin Action"
            setOnClickListener {
                Toast.makeText(
                    context,
                    "Plugin action executed from programmatic UI!",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

        layout.addView(titleView)
        layout.addView(descriptionView)
        layout.addView(capabilitiesView)
        layout.addView(testButton)

        return layout
    }
}