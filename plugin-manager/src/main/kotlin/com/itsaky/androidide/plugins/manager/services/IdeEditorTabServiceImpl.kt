package com.itsaky.androidide.plugins.manager.services

import android.util.Log
import com.itsaky.androidide.plugins.services.IdeEditorTabService
import com.itsaky.androidide.plugins.manager.PluginEditorTabManager
import com.itsaky.androidide.plugins.manager.PluginManager

/**
 * Implementation of IdeEditorTabService that provides plugin access to the editor tab system.
 */
class IdeEditorTabServiceImpl(
    private val activityProvider: PluginManager.ActivityProvider?
) : IdeEditorTabService {

    private val tabManager = PluginEditorTabManager.getInstance()

    override fun isPluginTab(tabId: String): Boolean {
        return tabManager.isPluginTab(tabId)
    }

    override fun selectPluginTab(tabId: String): Boolean {
        return if (tabManager.isPluginTab(tabId)) {
            val activity = activityProvider?.getCurrentActivity()
            if (activity != null && (activity.javaClass.simpleName == "EditorHandlerActivity" || activity.javaClass.simpleName == "EditorActivityKt")) {
                val tabSelected = selectPluginTabInUI(activity, tabId)
                if (tabSelected) {
                    closeDrawerIfOpen(activity)
                    tabManager.onTabSelected(tabId)
                    return true
                }
            }
            false
        } else {
            false
        }
    }

    /**
     * Select plugin tab in the UI using reflection.
     */
    private fun selectPluginTabInUI(activity: Any, tabId: String): Boolean {
        return try {
            Log.d("IdeEditorTabServiceImpl", "Attempting to call selectPluginTabById on ${activity.javaClass.name}")

            // Try to find the method in the class hierarchy
            val method = findMethodInHierarchy(activity.javaClass, "selectPluginTabById", String::class.java)
            if (method == null) {
                Log.e("IdeEditorTabServiceImpl", "Method selectPluginTabById not found in class hierarchy")
                return false
            }

            method.isAccessible = true
            val result = method.invoke(activity, tabId) as Boolean
            Log.d("IdeEditorTabServiceImpl", "selectPluginTabById returned: $result")
            result
        } catch (e: Exception) {
            Log.e("IdeEditorTabServiceImpl", "Failed to call selectPluginTabById via reflection", e)
            false
        }
    }

    /**
     * Find a method in the class hierarchy including superclasses.
     */
    private fun findMethodInHierarchy(clazz: Class<*>, methodName: String, vararg parameterTypes: Class<*>): java.lang.reflect.Method? {
        var currentClass: Class<*>? = clazz
        while (currentClass != null) {
            try {
                return currentClass.getDeclaredMethod(methodName, *parameterTypes)
            } catch (e: NoSuchMethodException) {
                // Continue to search in superclass
                currentClass = currentClass.superclass
            }
        }
        return null
    }

    /**
     * Close the drawer if it's open using reflection.
     */
    private fun closeDrawerIfOpen(activity: Any) {
        try {
            // Find the _binding field in the class hierarchy
            val bindingField = findFieldInHierarchy(activity.javaClass, "_binding")
            if (bindingField == null) {
                Log.d("IdeEditorTabServiceImpl", "Could not find _binding field")
                return
            }

            bindingField.isAccessible = true
            val binding = bindingField.get(activity)

            val drawerLayoutField = binding.javaClass.getDeclaredField("editorDrawerLayout")
            drawerLayoutField.isAccessible = true
            val drawerLayout = drawerLayoutField.get(binding)

            val closeDrawerMethod = drawerLayout.javaClass.getMethod("closeDrawers")
            closeDrawerMethod.invoke(drawerLayout)
        } catch (e: Exception) {
            Log.d("IdeEditorTabServiceImpl", "Failed to close drawer, ${e.toString()}")
            // Silently fail if we can't close the drawer
        }
    }

    /**
     * Find a field in the class hierarchy including superclasses.
     */
    private fun findFieldInHierarchy(clazz: Class<*>, fieldName: String): java.lang.reflect.Field? {
        var currentClass: Class<*>? = clazz
        while (currentClass != null) {
            try {
                return currentClass.getDeclaredField(fieldName)
            } catch (e: NoSuchFieldException) {
                // Continue to search in superclass
                currentClass = currentClass.superclass
            }
        }
        return null
    }

    override fun getAllPluginTabIds(): List<String> {
        return tabManager.getAllPluginTabs().map { it.id }
    }

    override fun isTabSystemAvailable(): Boolean {
        return try {
            tabManager.getAllPluginTabs()
            true
        } catch (e: Exception) {
            false
        }
    }
}