package com.itsaky.androidide.plugins

import org.junit.Test
import org.junit.Assert.*
import java.io.File
import java.io.InputStream

class PluginContextTest {

    /**
     * Mock implementation of PluginContext for testing
     */
    private class TestPluginContext : PluginContext {
        private val serviceRegistry = TestServiceRegistry()
        private val pluginLogger = TestPluginLogger()
        private val resourceManager = TestResourceManager()

        override val androidContext: android.content.Context
            get() = throw UnsupportedOperationException()
        override val services: ServiceRegistry
            get() = serviceRegistry
        override val eventBus: Any
            get() = Any()
        override val logger: PluginLogger
            get() = pluginLogger
        override val resources: ResourceManager
            get() = resourceManager
        override val pluginId: String
            get() = "test-plugin"

        private val pluginServices = mutableMapOf<String, Any>()
        private val activePlugins = mutableSetOf<String>()
        private val pluginVersions = mutableMapOf<String, String>()
        private val lifecycleListeners = mutableListOf<PluginLifecycleListener>()

        override fun <T> getPluginService(pluginId: String, serviceClass: Class<T>): T? {
            return pluginServices[pluginId] as? T
        }

        override fun isPluginActive(pluginId: String): Boolean {
            return activePlugins.contains(pluginId)
        }

        override fun getPluginVersion(pluginId: String): String? {
            return pluginVersions[pluginId]
        }

        override fun <T> registerService(serviceClass: Class<T>, serviceImpl: T) {
            // Service registration logic
        }

        override fun <T> unregisterService(serviceClass: Class<T>) {
            // Service unregistration logic
        }

        override fun getProvidedServices(): List<String> {
            return emptyList()
        }

        override fun getPluginDataDir(): File {
            return File("/data/plugins/test-plugin")
        }

        override fun addPluginLifecycleListener(listener: PluginLifecycleListener) {
            lifecycleListeners.add(listener)
        }

        override fun removePluginLifecycleListener(listener: PluginLifecycleListener) {
            lifecycleListeners.remove(listener)
        }

        fun addActivePlugin(pluginId: String) {
            activePlugins.add(pluginId)
        }

        fun setPluginVersion(pluginId: String, version: String) {
            pluginVersions[pluginId] = version
        }

        fun registerPluginService(pluginId: String, service: Any) {
            pluginServices[pluginId] = service
        }

        fun notifyPluginActivated(pluginId: String) {
            lifecycleListeners.forEach { it.onPluginActivated(pluginId) }
        }

        fun notifyPluginDeactivated(pluginId: String) {
            lifecycleListeners.forEach { it.onPluginDeactivated(pluginId) }
        }

        fun notifyPluginUninstalled(pluginId: String) {
            lifecycleListeners.forEach { it.onPluginUninstalled(pluginId) }
        }

        fun getListenerCount(): Int = lifecycleListeners.size
    }

    private class TestServiceRegistry : ServiceRegistry {
        private val services = mutableMapOf<Class<*>, MutableList<Any>>()

        override fun <T> register(serviceClass: Class<T>, implementation: T) {
            services.computeIfAbsent(serviceClass) { mutableListOf() }.add(implementation as Any)
        }

        override fun <T> get(serviceClass: Class<T>): T? {
            return services[serviceClass]?.firstOrNull() as? T
        }

        override fun <T> getAll(serviceClass: Class<T>): List<T> {
            return (services[serviceClass] ?: emptyList()).map { it as T }
        }

        override fun unregister(serviceClass: Class<*>) {
            services.remove(serviceClass)
        }
    }

    private class TestResourceManager : ResourceManager {
        override fun getPluginDirectory(): File = File("/plugins/test")

        override fun getPluginFile(path: String): File = File("/plugins/test/$path")

        override fun getPluginResource(name: String): ByteArray? = null

        override fun openPluginResource(name: String): InputStream? = null

        override fun openPluginAsset(path: String): InputStream? = null
    }

    private class TestPluginLogger : PluginLogger {
        override val pluginId: String = "test-plugin"

        override fun debug(message: String) {}
        override fun debug(message: String, error: Throwable) {}
        override fun info(message: String) {}
        override fun info(message: String, error: Throwable) {}
        override fun warn(message: String) {}
        override fun warn(message: String, error: Throwable) {}
        override fun error(message: String) {}
        override fun error(message: String, error: Throwable) {}
    }

    @Test
    fun testGetPluginServiceReturnsNullWhenNotFound() {
        val context = TestPluginContext()
        val result = context.getPluginService("unknown-plugin", String::class.java)
        assertNull("getPluginService should return null when service not found", result)
    }

    @Test
    fun testGetPluginServiceReturnsServiceWhenRegistered() {
        val context = TestPluginContext()
        val testService = "test-service"
        context.registerPluginService("ai-core", testService)

        val result = context.getPluginService("ai-core", String::class.java)
        assertNotNull("getPluginService should return registered service", result)
        assertEquals("Service should match registered value", testService, result)
    }

    @Test
    fun testIsPluginActiveReturnsFalseForInactivePlugin() {
        val context = TestPluginContext()
        val result = context.isPluginActive("unknown-plugin")
        assertFalse("isPluginActive should return false for inactive plugin", result)
    }

    @Test
    fun testIsPluginActiveReturnsTrueForActivePlugin() {
        val context = TestPluginContext()
        context.addActivePlugin("ai-core")
        val result = context.isPluginActive("ai-core")
        assertTrue("isPluginActive should return true for active plugin", result)
    }

    @Test
    fun testGetPluginVersionReturnsNullWhenNotFound() {
        val context = TestPluginContext()
        val result = context.getPluginVersion("unknown-plugin")
        assertNull("getPluginVersion should return null when version not found", result)
    }

    @Test
    fun testGetPluginVersionReturnsVersionWhenSet() {
        val context = TestPluginContext()
        context.setPluginVersion("ai-core", "1.0.0")
        val result = context.getPluginVersion("ai-core")
        assertNotNull("getPluginVersion should return version when set", result)
        assertEquals("Version should match set value", "1.0.0", result)
    }

    @Test
    fun testRegisterServiceAddsServiceToRegistry() {
        val context = TestPluginContext()
        val testService = "test-service"
        context.services.register(String::class.java, testService)

        val retrieved = context.services.get(String::class.java)
        assertNotNull("Service should be retrievable after registration", retrieved)
        assertEquals("Service should match registered value", testService, retrieved)
    }

    @Test
    fun testUnregisterServiceRemovesServiceFromRegistry() {
        val context = TestPluginContext()
        val testService = "test-service"
        context.services.register(String::class.java, testService)

        context.services.unregister(String::class.java)
        val retrieved = context.services.get(String::class.java)
        assertNull("Service should be null after unregistration", retrieved)
    }

    @Test
    fun testGetProvidedServicesReturnsEmptyList() {
        val context = TestPluginContext()
        val services = context.getProvidedServices()
        assertNotNull("getProvidedServices should not return null", services)
        assertEquals("getProvidedServices should return empty list initially", 0, services.size)
    }

    @Test
    fun testGetPluginDataDirReturnsValidDirectory() {
        val context = TestPluginContext()
        val dir = context.getPluginDataDir()
        assertNotNull("getPluginDataDir should not return null", dir)
        assertTrue("Plugin data dir should contain plugin ID", dir.path.contains("test-plugin"))
    }

    @Test
    fun testAddPluginLifecycleListenerAddsListener() {
        val context = TestPluginContext()
        val listener = object : PluginLifecycleListener {
            override fun onPluginActivated(pluginId: String) {}
            override fun onPluginDeactivated(pluginId: String) {}
            override fun onPluginUninstalled(pluginId: String) {}
        }

        assertEquals("Should have no listeners initially", 0, context.getListenerCount())
        context.addPluginLifecycleListener(listener)
        assertEquals("Should have one listener after adding", 1, context.getListenerCount())
    }

    @Test
    fun testRemovePluginLifecycleListenerRemovesListener() {
        val context = TestPluginContext()
        val listener = object : PluginLifecycleListener {
            override fun onPluginActivated(pluginId: String) {}
            override fun onPluginDeactivated(pluginId: String) {}
            override fun onPluginUninstalled(pluginId: String) {}
        }

        context.addPluginLifecycleListener(listener)
        assertEquals("Should have one listener after adding", 1, context.getListenerCount())
        context.removePluginLifecycleListener(listener)
        assertEquals("Should have no listeners after removing", 0, context.getListenerCount())
    }

    @Test
    fun testLifecycleListenerNotificationOnPluginActivated() {
        val context = TestPluginContext()
        var notificationReceived: String? = null

        val listener = object : PluginLifecycleListener {
            override fun onPluginActivated(pluginId: String) {
                notificationReceived = pluginId
            }
            override fun onPluginDeactivated(pluginId: String) {}
            override fun onPluginUninstalled(pluginId: String) {}
        }

        context.addPluginLifecycleListener(listener)
        context.notifyPluginActivated("ai-core")
        assertEquals("Should receive onPluginActivated notification", "ai-core", notificationReceived)
    }

    @Test
    fun testLifecycleListenerNotificationOnPluginDeactivated() {
        val context = TestPluginContext()
        var notificationReceived: String? = null

        val listener = object : PluginLifecycleListener {
            override fun onPluginActivated(pluginId: String) {}
            override fun onPluginDeactivated(pluginId: String) {
                notificationReceived = pluginId
            }
            override fun onPluginUninstalled(pluginId: String) {}
        }

        context.addPluginLifecycleListener(listener)
        context.notifyPluginDeactivated("ai-chat-agent")
        assertEquals("Should receive onPluginDeactivated notification", "ai-chat-agent", notificationReceived)
    }

    @Test
    fun testLifecycleListenerNotificationOnPluginUninstalled() {
        val context = TestPluginContext()
        var notificationReceived: String? = null

        val listener = object : PluginLifecycleListener {
            override fun onPluginActivated(pluginId: String) {}
            override fun onPluginDeactivated(pluginId: String) {}
            override fun onPluginUninstalled(pluginId: String) {
                notificationReceived = pluginId
            }
        }

        context.addPluginLifecycleListener(listener)
        context.notifyPluginUninstalled("ai-tools")
        assertEquals("Should receive onPluginUninstalled notification", "ai-tools", notificationReceived)
    }

    @Test
    fun testMultipleLifecycleListenersReceiveNotifications() {
        val context = TestPluginContext()
        val activatedPlugins = mutableListOf<String>()

        val listener1 = object : PluginLifecycleListener {
            override fun onPluginActivated(pluginId: String) {
                activatedPlugins.add("listener1:$pluginId")
            }
            override fun onPluginDeactivated(pluginId: String) {}
            override fun onPluginUninstalled(pluginId: String) {}
        }

        val listener2 = object : PluginLifecycleListener {
            override fun onPluginActivated(pluginId: String) {
                activatedPlugins.add("listener2:$pluginId")
            }
            override fun onPluginDeactivated(pluginId: String) {}
            override fun onPluginUninstalled(pluginId: String) {}
        }

        context.addPluginLifecycleListener(listener1)
        context.addPluginLifecycleListener(listener2)
        context.notifyPluginActivated("ai-core")

        assertEquals("Both listeners should receive notification", 2, activatedPlugins.size)
        assertTrue("First listener should be notified", activatedPlugins.contains("listener1:ai-core"))
        assertTrue("Second listener should be notified", activatedPlugins.contains("listener2:ai-core"))
    }

    @Test
    fun testServiceRegistryGetAllReturnsEmptyListWhenNoServicesRegistered() {
        val registry = TestServiceRegistry()
        val services = registry.getAll(String::class.java)
        assertNotNull("getAll should not return null", services)
        assertEquals("getAll should return empty list when no services registered", 0, services.size)
    }

    @Test
    fun testServiceRegistryGetAllReturnsAllRegisteredServices() {
        val registry = TestServiceRegistry()
        val service1 = "service1"
        val service2 = "service2"

        registry.register(String::class.java, service1)
        registry.register(String::class.java, service2)

        val services = registry.getAll(String::class.java)
        assertNotNull("getAll should not return null", services)
        assertEquals("getAll should return all registered services", 2, services.size)
        assertTrue("Should contain first service", services.contains(service1))
        assertTrue("Should contain second service", services.contains(service2))
    }

    @Test
    fun testResourceManagerReturnsNullForMissingResource() {
        val manager = TestResourceManager()
        val resource = manager.getPluginResource("missing.dat")
        assertNull("getPluginResource should return null for missing resource", resource)
    }

    @Test
    fun testResourceManagerReturnsNullForMissingAsset() {
        val manager = TestResourceManager()
        val asset = manager.openPluginAsset("missing/asset.bin")
        assertNull("openPluginAsset should return null for missing asset", asset)
    }
}
