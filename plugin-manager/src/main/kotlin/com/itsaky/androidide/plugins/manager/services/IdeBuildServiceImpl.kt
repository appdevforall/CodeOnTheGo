
package com.itsaky.androidide.plugins.manager.services

import com.itsaky.androidide.plugins.services.IdeBuildService
import com.itsaky.androidide.plugins.services.BuildStatusListener
import com.itsaky.androidide.plugins.services.BuildAndLaunchCallback
import com.itsaky.androidide.plugins.services.GradleSyncCallback
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Implementation of IdeBuildService that provides access to Code On the Go's build system
 * status and operations for plugins that need to monitor or interact with builds.
 *
 * This is a singleton to ensure all plugins share the same build state and receive
 * consistent notifications from the main build system.
 */
class IdeBuildServiceImpl private constructor() : IdeBuildService {

    private val buildStatusListeners = CopyOnWriteArraySet<BuildStatusListener>()
    private var buildInProgress = false
    private var toolingServerStarted = false

    // Providers set by the app module
    private var runAppProvider: ((BuildAndLaunchCallback) -> Unit)? = null
    private var gradleSyncProvider: ((GradleSyncCallback) -> Unit)? = null
    private var buildOutputProvider: (() -> String?)? = null

    companion object {
        @Volatile
        private var INSTANCE: IdeBuildServiceImpl? = null

        fun getInstance(): IdeBuildServiceImpl {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: IdeBuildServiceImpl().also { INSTANCE = it }
            }
        }
    }

    override fun isBuildInProgress(): Boolean {
        return buildInProgress
    }

    override fun isToolingServerStarted(): Boolean {
        return toolingServerStarted
    }

    override fun addBuildStatusListener(callback: BuildStatusListener) {
        buildStatusListeners.add(callback)
    }

    override fun removeBuildStatusListener(callback: BuildStatusListener) {
        buildStatusListeners.remove(callback)
    }

    /**
     * Internal method to update build status (should be called by Code On the Go's build system)
     */
    fun setBuildInProgress(inProgress: Boolean) {
        if (this.buildInProgress != inProgress) {
            this.buildInProgress = inProgress
            if (inProgress) {
                notifyBuildStarted()
            }
        }
    }

    /**
     * Internal method to update tooling server status (should be called by Code On the Go's build system)
     */
    fun setToolingServerStarted(started: Boolean) {
        this.toolingServerStarted = started
    }

    /**
     * Internal method to notify listeners of build completion (should be called by Code On the Go's build system)
     */
    fun notifyBuildFinished() {
        this.buildInProgress = false
        buildStatusListeners.forEach { listener ->
            try {
                listener.onBuildFinished()
            } catch (e: Exception) {
                // Ignore listener exceptions to prevent one bad listener from affecting others
            }
        }
    }

    /**
     * Internal method to notify listeners of build failure (should be called by Code On the Go's build system)
     */
    fun notifyBuildFailed(error: String?) {
        this.buildInProgress = false
        buildStatusListeners.forEach { listener ->
            try {
                listener.onBuildFailed(error)
            } catch (e: Exception) {
                // Ignore listener exceptions to prevent one bad listener from affecting others
            }
        }
    }

    private fun notifyBuildStarted() {
        buildStatusListeners.forEach { listener ->
            try {
                listener.onBuildStarted()
            } catch (e: Exception) {
                // Ignore listener exceptions to prevent one bad listener from affecting others
            }
        }
    }

    override fun runApp(callback: BuildAndLaunchCallback) {
        runAppProvider?.invoke(callback)
            ?: callback.onComplete(false, "Run app functionality not initialized")
    }

    override fun triggerGradleSync(callback: GradleSyncCallback) {
        gradleSyncProvider?.invoke(callback)
            ?: callback.onComplete(false, "Gradle sync functionality not initialized")
    }

    override fun getBuildOutput(): String? {
        return buildOutputProvider?.invoke()
    }

    /**
     * Set the run app provider (should be called by Code On the Go's app module during initialization)
     */
    fun setRunAppProvider(provider: (BuildAndLaunchCallback) -> Unit) {
        this.runAppProvider = provider
    }

    /**
     * Set the gradle sync provider (should be called by Code On the Go's app module during initialization)
     */
    fun setGradleSyncProvider(provider: (GradleSyncCallback) -> Unit) {
        this.gradleSyncProvider = provider
    }

    /**
     * Set the build output provider (should be called by Code On the Go's app module during initialization)
     */
    fun setBuildOutputProvider(provider: () -> String?) {
        this.buildOutputProvider = provider
    }
}
