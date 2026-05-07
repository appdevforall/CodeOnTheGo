package com.codeonthego.gisplugin.region

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.codeonthego.gisplugin.GisPlugin
import com.codeonthego.gisplugin.R
import com.google.android.material.appbar.MaterialToolbar

/**
 * Full-screen Activity hosting [RegionManagerFragment]. Launched from the
 * IDE sidebar via [GisPlugin.getSideMenuItems]. Replaces the bottom-sheet
 * tab the previous prototype used — region management is project-resource
 * navigation, not process output, so the sidebar slot is the correct
 * surface for it.
 *
 * The Activity runs under the plugin's Material 3 [com.codeonthego.gisplugin.R.style.PluginTheme]
 * (declared in this module's AndroidManifest), so it doesn't fight the host
 * IDE's theme stack.
 */
class RegionManagerActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_region_manager)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.setNavigationOnClickListener { finish() }

        // Replace, never add — config changes recreate this Activity and we
        // don't want a stack of fragments.
        if (savedInstanceState == null) {
            val fragment = RegionManagerFragment().apply {
                pluginContext = GisPlugin.pluginContext
            }
            supportFragmentManager.beginTransaction()
                .replace(R.id.region_manager_host, fragment)
                .commit()
        }
    }
}
