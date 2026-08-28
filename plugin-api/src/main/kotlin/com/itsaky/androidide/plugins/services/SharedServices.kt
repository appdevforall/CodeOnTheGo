package com.itsaky.androidide.plugins.services

import java.util.concurrent.ConcurrentHashMap

/**
 * Shared service registry accessible by all plugins.
 * Used to share services between plugins when PluginContext cross-plugin service access is not available.
 */
object SharedServices {
    private val services = ConcurrentHashMap<Class<*>, Any>()

    fun <T> register(serviceClass: Class<T>, implementation: T) {
        services[serviceClass] = implementation as Any
    }

    @Suppress("UNCHECKED_CAST")
    fun <T> get(serviceClass: Class<T>): T? {
        return services[serviceClass] as? T
    }

    fun <T> unregister(serviceClass: Class<T>) {
        services.remove(serviceClass)
    }

    fun clear() {
        services.clear()
    }
}

// Kotlin extension functions for reified types
inline fun <reified T> SharedServices.register(implementation: T) {
    register(T::class.java, implementation)
}

inline fun <reified T> SharedServices.get(): T? {
    return get(T::class.java)
}

inline fun <reified T> SharedServices.unregister() {
    unregister(T::class.java)
}
