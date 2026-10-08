package com.itsaky.androidide.plugins.manager.language

import android.content.res.AssetManager
import com.itsaky.androidide.plugins.extensions.LanguageDefinition
import java.io.File

data class PluginLanguageContribution(
	val pluginId: String,
	val definition: LanguageDefinition,
	val assets: AssetManager,
	val nativeLibraryDir: File?,
)
