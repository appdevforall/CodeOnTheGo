package com.itsaky.androidide.plugins.manager.services

import com.itsaky.androidide.plugins.services.QuickBuildAvailability
import com.itsaky.androidide.plugins.services.QuickBuildResult
import com.itsaky.androidide.plugins.services.QuickBuildState
import java.util.concurrent.CompletableFuture

/**
 * The app module's Quick Build, behind [IdeBuildServiceImpl]'s Quick Build methods. Quick Build
 * lives in the app (its session manager, the toolbar tap, the replace-app dialog), which this
 * module cannot depend on.
 */
interface QuickBuildProvider {
	fun availability(): QuickBuildAvailability

	fun start(): CompletableFuture<QuickBuildResult>

	/** Stops a running Quick Build; false when none is running. */
	fun cancel(): Boolean

	fun status(): QuickBuildState
}
