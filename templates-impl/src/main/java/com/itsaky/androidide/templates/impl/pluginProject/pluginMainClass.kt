
package com.itsaky.androidide.templates.impl.pluginProject

fun pluginMainClassKt(data: PluginTemplateData): String {
	val imports = buildString {
		appendLine("import com.itsaky.androidide.plugins.IPlugin")
		appendLine("import com.itsaky.androidide.plugins.PluginContext")

		if (data.extensions.contains(PluginExtension.UI)) {
			appendLine("import com.itsaky.androidide.plugins.extensions.UIExtension")
			appendLine("import com.itsaky.androidide.plugins.extensions.NavigationItem")
			appendLine("import com.itsaky.androidide.plugins.extensions.TabItem")
			appendLine("import com.itsaky.androidide.plugins.extensions.MenuItem")
			appendLine("import com.itsaky.androidide.plugins.services.IdeEditorTabService")
			if (data.includeSampleCode) {
				appendLine("import ${data.pluginId}.fragments.${data.className}Fragment")
			}
		}

		if (data.extensions.contains(PluginExtension.EDITOR_TAB)) {
			appendLine("import com.itsaky.androidide.plugins.extensions.EditorTabExtension")
			appendLine("import com.itsaky.androidide.plugins.extensions.EditorTabItem")
		}

		if (data.extensions.contains(PluginExtension.DOCUMENTATION)) {
			appendLine("import com.itsaky.androidide.plugins.extensions.DocumentationExtension")
			appendLine("import com.itsaky.androidide.plugins.extensions.PluginTooltipEntry")
			appendLine("import com.itsaky.androidide.plugins.extensions.PluginTooltipButton")
		}

		if (data.extensions.contains(PluginExtension.EDITOR)) {
			appendLine("import com.itsaky.androidide.plugins.extensions.EditorExtension")
		}

		if (data.extensions.contains(PluginExtension.PROJECT)) {
			appendLine("import com.itsaky.androidide.plugins.extensions.ProjectExtension")
		}
	}

	val interfaces = buildString {
		append("IPlugin")
		data.extensions.forEach { ext ->
			append(", ${ext.interfaceName}")
		}
	}

	val methodImplementations = buildString {
		if (data.extensions.contains(PluginExtension.UI)) {
			append(uiExtensionMethods(data))
		}

		if (data.extensions.contains(PluginExtension.EDITOR_TAB)) {
			append(editorTabExtensionMethods(data))
		}

		if (data.extensions.contains(PluginExtension.DOCUMENTATION)) {
			append(documentationExtensionMethods(data))
		}

		if (data.extensions.contains(PluginExtension.EDITOR)) {
			append(editorExtensionMethods(data))
		}

		if (data.extensions.contains(PluginExtension.PROJECT)) {
			append(projectExtensionMethods(data))
		}
	}

	return """
package ${data.pluginId}

$imports

class ${data.className}Plugin : $interfaces {

    private lateinit var context: PluginContext

    override fun initialize(context: PluginContext): Boolean {
        return try {
            this.context = context
            context.logger.info("${data.className}Plugin initialized successfully")
            true
        } catch (e: Exception) {
            context.logger.error("${data.className}Plugin initialization failed", e)
            false
        }
    }

    override fun activate(): Boolean {
        context.logger.info("${data.className}Plugin: Activating plugin")
        return true
    }

    override fun deactivate(): Boolean {
        context.logger.info("${data.className}Plugin: Deactivating plugin")
        return true
    }

    override fun dispose() {
        context.logger.info("${data.className}Plugin: Disposing plugin")
    }
$methodImplementations
}
""".trimIndent()
}

private fun uiExtensionMethods(data: PluginTemplateData): String {
	val fragmentFactory = if (data.includeSampleCode) {
		"{ ${data.className}Fragment() }"
	} else {
		"{ TODO(\"Create your fragment\") }"
	}

	return """

    override fun getEditorTabs(): List<TabItem> {
        return listOf(
            TabItem(
                id = "${data.pluginId.replace(".", "_")}_tab",
                title = "${data.pluginName}",
                fragmentFactory = $fragmentFactory,
                isEnabled = true,
                isVisible = true,
                order = 0
            )
        )
    }

    override fun getMainMenuItems(): List<MenuItem> {
        return emptyList()
    }

    override fun getSideMenuItems(): List<NavigationItem> {
        return listOf(
            NavigationItem(
                id = "${data.pluginId.replace(".", "_")}_sidebar",
                title = "${data.pluginName}",
                icon = android.R.drawable.ic_menu_info_details,
                isEnabled = true,
                isVisible = true,
                group = "plugins",
                order = 0,
                action = {
                    openPluginTab()
                }
            )
        )
    }

    private fun openPluginTab() {
        context.logger.info("Opening ${data.pluginName} in main editor tab")

        val editorTabService = context.services.get(IdeEditorTabService::class.java) ?: run {
            context.logger.error("Editor tab service not available")
            return
        }

        if (!editorTabService.isTabSystemAvailable()) {
            context.logger.error("Editor tab system not available")
            return
        }

        val tabId = "${data.pluginId.replace(".", "_")}_main"
        try {
            if (editorTabService.selectPluginTab(tabId)) {
                context.logger.info("Successfully opened ${data.pluginName} tab")
            }
        } catch (e: Exception) {
            context.logger.error("Error opening ${data.pluginName} tab", e)
        }
    }
"""
}

private fun editorTabExtensionMethods(data: PluginTemplateData): String {
	val fragmentFactory = if (data.includeSampleCode) {
		"{ ${data.className}Fragment() }"
	} else {
		"{ TODO(\"Create your fragment\") }"
	}

	return """

    override fun getMainEditorTabs(): List<EditorTabItem> {
        return listOf(
            EditorTabItem(
                id = "${data.pluginId.replace(".", "_")}_main",
                title = "${data.pluginName}",
                icon = android.R.drawable.ic_menu_info_details,
                fragmentFactory = $fragmentFactory,
                isCloseable = true,
                isPersistent = false,
                order = 0,
                isEnabled = true,
                isVisible = true,
                tooltip = "${data.description}"
            )
        )
    }
"""
}

private fun documentationExtensionMethods(data: PluginTemplateData): String = """

    override fun getTooltipCategory(): String = "${data.pluginId.replace(".", "_")}"

    override fun getTooltipEntries(): List<PluginTooltipEntry> {
        return listOf(
            PluginTooltipEntry(
                tag = "${data.pluginId.replace(".", "_")}.overview",
                summary = "<b>${data.pluginName}</b><br>${data.description}",
                detail = ${"\"\"\""}
                    <h3>${data.pluginName}</h3>
                    <p>${data.description}</p>

                    <h4>Features:</h4>
                    <ul>
                        <li>Feature 1 - Describe your feature</li>
                        <li>Feature 2 - Describe your feature</li>
                    </ul>

                    <h4>How to use:</h4>
                    <ol>
                        <li>Step 1 - Describe how to use</li>
                        <li>Step 2 - Describe how to use</li>
                    </ol>
                ${"\"\"\""}.trimIndent(),
                buttons = emptyList()
            )
        )
    }

    override fun onDocumentationInstall(): Boolean {
        context.logger.info("Installing ${data.pluginName} documentation")
        return true
    }

    override fun onDocumentationUninstall() {
        context.logger.info("Removing ${data.pluginName} documentation")
    }
"""

private fun editorExtensionMethods(data: PluginTemplateData): String = """

    override fun onEditorOpened(filePath: String) {
        context.logger.debug("File opened: ${"$"}filePath")
    }

    override fun onEditorClosed(filePath: String) {
        context.logger.debug("File closed: ${"$"}filePath")
    }

    override fun onEditorContentChanged(filePath: String, content: String) {
        context.logger.debug("Content changed in: ${"$"}filePath")
    }
"""

private fun projectExtensionMethods(data: PluginTemplateData): String = """

    override fun onProjectOpened(projectPath: String) {
        context.logger.info("Project opened: ${"$"}projectPath")
    }

    override fun onProjectClosed() {
        context.logger.info("Project closed")
    }

    override fun onProjectSynced() {
        context.logger.info("Project synced")
    }
"""