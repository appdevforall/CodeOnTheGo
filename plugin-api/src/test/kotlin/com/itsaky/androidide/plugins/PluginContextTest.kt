package com.itsaky.androidide.plugins

import org.junit.Test
import org.junit.Assert.*
import java.io.File
import java.lang.reflect.Method

class PluginContextTest {

    @Test
    fun testPluginContextHasGetPluginServiceMethod() {
        val method: Method? = try {
            PluginContext::class.java.getDeclaredMethod("getPluginService", String::class.java, Class::class.java)
        } catch (e: NoSuchMethodException) {
            null
        }
        assertNotNull("getPluginService method should exist", method)
    }

    @Test
    fun testPluginContextHasIsPluginActiveMethod() {
        val method: Method? = try {
            PluginContext::class.java.getDeclaredMethod("isPluginActive", String::class.java)
        } catch (e: NoSuchMethodException) {
            null
        }
        assertNotNull("isPluginActive method should exist", method)
    }

    @Test
    fun testPluginContextHasGetPluginVersionMethod() {
        val method: Method? = try {
            PluginContext::class.java.getDeclaredMethod("getPluginVersion", String::class.java)
        } catch (e: NoSuchMethodException) {
            null
        }
        assertNotNull("getPluginVersion method should exist", method)
    }

    @Test
    fun testPluginContextHasRegisterServiceMethod() {
        val method: Method? = try {
            PluginContext::class.java.getDeclaredMethod("registerService", Class::class.java, Any::class.java)
        } catch (e: NoSuchMethodException) {
            null
        }
        assertNotNull("registerService method should exist", method)
    }

    @Test
    fun testPluginContextHasUnregisterServiceMethod() {
        val method: Method? = try {
            PluginContext::class.java.getDeclaredMethod("unregisterService", Class::class.java)
        } catch (e: NoSuchMethodException) {
            null
        }
        assertNotNull("unregisterService method should exist", method)
    }

    @Test
    fun testPluginContextHasGetProvidedServicesMethod() {
        val method: Method? = try {
            PluginContext::class.java.getDeclaredMethod("getProvidedServices")
        } catch (e: NoSuchMethodException) {
            null
        }
        assertNotNull("getProvidedServices method should exist", method)
    }

    @Test
    fun testPluginContextHasGetPluginDataDirMethod() {
        val method: Method? = try {
            PluginContext::class.java.getDeclaredMethod("getPluginDataDir")
        } catch (e: NoSuchMethodException) {
            null
        }
        assertNotNull("getPluginDataDir method should exist", method)
    }

    @Test
    fun testPluginContextHasLifecycleListenerMethods() {
        val addMethod: Method? = try {
            PluginContext::class.java.getDeclaredMethod("addPluginLifecycleListener", PluginLifecycleListener::class.java)
        } catch (e: NoSuchMethodException) {
            null
        }
        assertNotNull("addPluginLifecycleListener method should exist", addMethod)

        val removeMethod: Method? = try {
            PluginContext::class.java.getDeclaredMethod("removePluginLifecycleListener", PluginLifecycleListener::class.java)
        } catch (e: NoSuchMethodException) {
            null
        }
        assertNotNull("removePluginLifecycleListener method should exist", removeMethod)
    }

    @Test
    fun testPluginLifecycleListenerImplementation() {
        val listener = object : PluginLifecycleListener {
            var activatedPlugin: String? = null
            var deactivatedPlugin: String? = null
            var uninstalledPlugin: String? = null

            override fun onPluginActivated(pluginId: String) {
                activatedPlugin = pluginId
            }

            override fun onPluginDeactivated(pluginId: String) {
                deactivatedPlugin = pluginId
            }

            override fun onPluginUninstalled(pluginId: String) {
                uninstalledPlugin = pluginId
            }
        }

        listener.onPluginActivated("ai-core")
        assertEquals("ai-core", listener.activatedPlugin)

        listener.onPluginDeactivated("ai-chat-agent")
        assertEquals("ai-chat-agent", listener.deactivatedPlugin)

        listener.onPluginUninstalled("ai-tools")
        assertEquals("ai-tools", listener.uninstalledPlugin)
    }
}
