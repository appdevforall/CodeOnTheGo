

package com.itsaky.androidide.templates.impl.pluginProject

fun pluginAndroidManifest(data: PluginTemplateData): String {
	val permissionsValue = data.permissions.joinToString(",") { it.value }
	val sidebarItems = if (data.extensions.contains(PluginExtension.UI)) "1" else "0"

	return """
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <application
        android:label="${data.pluginName}"
        android:theme="@style/Theme.AppCompat">

        <!-- Plugin metadata -->
        <meta-data
            android:name="plugin.id"
            android:value="${data.pluginId}" />

        <meta-data
            android:name="plugin.name"
            android:value="${data.pluginName}" />

        <meta-data
            android:name="plugin.version"
            android:value="1.0.0" />

        <meta-data
            android:name="plugin.description"
            android:value="${data.description}" />

        <meta-data
            android:name="plugin.author"
            android:value="${data.author}" />

        <meta-data
            android:name="plugin.min_ide_version"
            android:value="1.0.0" />

        <!--
            Available permissions (comma-separated):
              - filesystem.read    : Read files from the filesystem
              - filesystem.write   : Write files to the filesystem
              - network.access     : Access the network
              - system.commands    : Execute system commands
              - ide.settings       : Access IDE settings
              - project.structure  : Access project structure
        -->
        <meta-data
            android:name="plugin.permissions"
            android:value="$permissionsValue" />

        <meta-data
            android:name="plugin.main_class"
            android:value="${data.pluginId}.${data.className}Plugin" />

        <meta-data
            android:name="plugin.sidebar_items"
            android:value="$sidebarItems" />

    </application>

</manifest>
""".trimIndent()
}