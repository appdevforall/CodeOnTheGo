package com.itsaky.androidide.plugins.build

import org.gradle.api.provider.Property

abstract class PluginBuilderExtension {
    abstract val pluginName: Property<String>
}