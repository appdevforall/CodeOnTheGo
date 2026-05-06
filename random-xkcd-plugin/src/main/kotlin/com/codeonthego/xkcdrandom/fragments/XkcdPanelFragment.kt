package com.codeonthego.xkcdrandom.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.codeonthego.xkcdrandom.R
import com.codeonthego.xkcdrandom.XkcdRandomPlugin
import com.itsaky.androidide.plugins.base.PluginFragmentHelper

/**
 * The "XKCD" tab body shown inside the editor bottom sheet.
 *
 * Commit 1 (this commit) only proves the registration works — the panel
 * shows a placeholder caption so a reviewer can see the tab on the
 * emulator. Commit 2 wires up the network fetch + image render.
 */
class XkcdPanelFragment : Fragment() {

    /**
     * Plugins **must** override the layout inflater so resource IDs from
     * the plugin's APK (R.layout.fragment_xkcd_panel) resolve against the
     * plugin's resources, not the host app's. Skipping this gives you a
     * Resources$NotFoundException at inflate time.
     */
    override fun onGetLayoutInflater(savedInstanceState: Bundle?): LayoutInflater {
        val inflater = super.onGetLayoutInflater(savedInstanceState)
        return PluginFragmentHelper.getPluginInflater(
            XkcdRandomPlugin.PLUGIN_ID,
            inflater
        )
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? = inflater.inflate(R.layout.fragment_xkcd_panel, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // Placeholder until commit 2 lands the fetch + render path.
        view.findViewById<TextView>(R.id.xkcd_empty).apply {
            visibility = View.VISIBLE
            text = "XKCD plugin loaded. Networking arrives in commit 2."
        }
    }
}
