
package com.itsaky.androidide.templates.impl.pluginProject

data class PluginTemplateData(
	val pluginName: String,
	val pluginId: String,
	val description: String,
	val author: String,
	val permissions: Set<PluginPermission>,
	val extensions: Set<PluginExtension>,
	val includeSampleCode: Boolean,
	val version: String = "1.0.0",
	val minIdeVersion: String = "1.0.0"
) {
	val className: String = pluginName.replace(Regex("[^a-zA-Z0-9]"), "")
	val packagePath: String = pluginId.replace(".", "/")
}

enum class PluginPermission(val value: String, val displayName: String) {
	FILESYSTEM_READ("filesystem.read", "Filesystem Read"),
	FILESYSTEM_WRITE("filesystem.write", "Filesystem Write"),
	NETWORK_ACCESS("network.access", "Network Access"),
	SYSTEM_COMMANDS("system.commands", "System Commands"),
	IDE_SETTINGS("ide.settings", "IDE Settings"),
	PROJECT_STRUCTURE("project.structure", "Project Structure")
}

enum class PluginExtension(val interfaceName: String, val displayName: String) {
	UI("UIExtension", "UI Extension"),
	EDITOR_TAB("EditorTabExtension", "Editor Tab Extension"),
	DOCUMENTATION("DocumentationExtension", "Documentation Extension"),
	EDITOR("EditorExtension", "Editor Extension"),
	PROJECT("ProjectExtension", "Project Extension")
}