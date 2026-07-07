
package com.itsaky.androidide.plugins.manager.services

import com.itsaky.androidide.plugins.services.IdeProjectManipulationService

/**
 * Implementation of IdeProjectManipulationService that provides access to Code On the Go's
 * project manipulation operations for plugins that need to modify project files.
 *
 * This is a singleton to ensure all plugins use the same project manipulation logic.
 */
class IdeProjectManipulationServiceImpl private constructor() : IdeProjectManipulationService {

    // Providers set by the app module
    private var addDependencyProvider: ((String, String) -> Boolean)? = null
    private var addStringResourceProvider: ((String, String) -> Boolean)? = null
    private var deleteFileProvider: ((String) -> Boolean)? = null

    companion object {
        @Volatile
        private var INSTANCE: IdeProjectManipulationServiceImpl? = null

        fun getInstance(): IdeProjectManipulationServiceImpl {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: IdeProjectManipulationServiceImpl().also { INSTANCE = it }
            }
        }
    }

    override fun addDependency(dependencyString: String, buildFilePath: String): Boolean {
        return addDependencyProvider?.invoke(dependencyString, buildFilePath) ?: false
    }

    override fun addStringResource(name: String, value: String): Boolean {
        return addStringResourceProvider?.invoke(name, value) ?: false
    }

    override fun deleteFile(path: String): Boolean {
        return deleteFileProvider?.invoke(path) ?: false
    }

    /**
     * Set the add dependency provider (should be called by Code On the Go's app module during initialization)
     */
    fun setAddDependencyProvider(provider: (String, String) -> Boolean) {
        this.addDependencyProvider = provider
    }

    /**
     * Set the add string resource provider (should be called by Code On the Go's app module during initialization)
     */
    fun setAddStringResourceProvider(provider: (String, String) -> Boolean) {
        this.addStringResourceProvider = provider
    }

    /**
     * Set the delete file provider (should be called by Code On the Go's app module during initialization)
     */
    fun setDeleteFileProvider(provider: (String) -> Boolean) {
        this.deleteFileProvider = provider
    }
}
