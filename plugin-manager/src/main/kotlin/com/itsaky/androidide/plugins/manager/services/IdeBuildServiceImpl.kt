
package com.itsaky.androidide.plugins.manager.services

import com.itsaky.androidide.plugins.services.IdeBuildService
import com.itsaky.androidide.plugins.services.BuildStatusListener
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Implementation of IdeBuildService that provides access to COGO's build system
 * status and operations for plugins that need to monitor or interact with builds.
 */
class IdeBuildServiceImpl : IdeBuildService {

    private val buildStatusListeners = CopyOnWriteArraySet<BuildStatusListener>()
    private var buildInProgress = false
    private var toolingServerStarted = false

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
     * Internal method to update build status (should be called by COGO's build system)
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
     * Internal method to update tooling server status (should be called by COGO's build system)
     */
    fun setToolingServerStarted(started: Boolean) {
        this.toolingServerStarted = started
    }

    /**
     * Internal method to notify listeners of build completion (should be called by COGO's build system)
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
     * Internal method to notify listeners of build failure (should be called by COGO's build system)
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
}