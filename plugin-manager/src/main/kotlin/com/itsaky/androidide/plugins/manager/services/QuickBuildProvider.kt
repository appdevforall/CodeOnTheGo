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
	/** Backs [com.itsaky.androidide.plugins.services.IdeBuildService.getQuickBuildAvailability]. */
	fun availability(): QuickBuildAvailability

	/** Backs [com.itsaky.androidide.plugins.services.IdeBuildService.startQuickBuild]. */
	fun start(): CompletableFuture<QuickBuildResult>

	/** Asks a running Quick Build to stop; false when none is running. */
	fun cancel(): Boolean

	/** Backs [com.itsaky.androidide.plugins.services.IdeBuildService.getQuickBuildStatus]. */
	fun status(): QuickBuildState
}
