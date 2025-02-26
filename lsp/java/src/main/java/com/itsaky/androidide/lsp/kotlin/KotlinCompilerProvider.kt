package com.itsaky.androidide.lsp.kotlin

import com.itsaky.androidide.projects.api.ModuleProject
import java.util.concurrent.ConcurrentHashMap

/**
 * Provides [KotlinCompilerService] instances for different [ModuleProject]s.
 */
class KotlinCompilerProvider private constructor() {

    private val compilers: MutableMap<ModuleProject, KotlinCompilerService> = ConcurrentHashMap()

    companion object {
        @Volatile
        private var instance: KotlinCompilerProvider? = null

        fun getInstance(): KotlinCompilerProvider {
            return instance ?: synchronized(this) {
                instance ?: KotlinCompilerProvider().also { instance = it }
            }
        }

        fun get(module: ModuleProject): KotlinCompilerService {
            return getInstance().forModule(module)
        }
    }

    @Synchronized
    fun forModule(module: ModuleProject): KotlinCompilerService {
        // If a compiler already exists for this module and it's properly initialized, return it
        compilers[module]?.let { cached ->
            if (cached.module != null) return cached
        }

        // Otherwise, create a new one and cache it
        val newInstance = KotlinCompilerService(module)
        compilers[module] = newInstance
        return newInstance
    }

    @Synchronized
    fun destroy() {
        compilers.values.forEach { it.destroy() }
        compilers.clear()
    }
}
