package com.itsaky.androidide.lsp.kotlin.compiler.modules

import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.analyzeCopy
import org.jetbrains.kotlin.analysis.api.projectStructure.KaDanglingFileResolutionMode
import org.jetbrains.kotlin.analysis.api.projectStructure.copyOrigin
import org.jetbrains.kotlin.analysis.api.projectStructure.isDangling
import org.jetbrains.kotlin.com.intellij.openapi.util.Key
import org.jetbrains.kotlin.psi.KtElement
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.UserDataProperty
import java.nio.file.Path
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

private val KT_LSP_COMPLETION_BACKING_FILE = Key<Path>("KT_LSP_COMPLETION_BACKING_FILE")
var KtFile.backingFilePath by UserDataProperty(KT_LSP_COMPLETION_BACKING_FILE)

/**
 * Serializes all Kotlin Analysis API access (`analyze` / `analyzeCopy`).
 *
 * The Analysis API tracks its `analyze` lifetime context in a per-thread stack and is not safe to
 * drive concurrently from multiple background threads without the platform read-action coordination
 * that this LSP replaces with a custom [com.itsaky.androidide.lsp.kotlin.compiler.read] lock.
 * Indexing, diagnostics and completion all run analysis on `Dispatchers.Default` and frequently
 * target the same edited file, so overlapping `analyze` calls corrupted the lifetime/session
 * lifecycle and surfaced as
 * `KaInaccessibleLifetimeOwnerAccessException: ... Called outside an \`analyze\` context.`
 *
 * Holding this lock around every analysis entry point makes analyses mutually exclusive. It is a
 * [ReentrantLock] so an (indirect) nested analysis on the same thread cannot deadlock.
 */
private val analysisLock = ReentrantLock()

/**
 * Runs [action] while holding the shared [analysisLock]. **All** Analysis API access must go through
 * this helper (or [analyzeMaybeDangling], which already does); never call `analyze` / `analyzeCopy`
 * directly, or the serialization guarantee is lost.
 */
internal inline fun <R> withAnalysisLock(action: () -> R): R = analysisLock.withLock(action)

internal inline fun <R> analyzeMaybeDangling(useSiteElement: KtElement, crossinline action: KaSession.() -> R): R =
	withAnalysisLock {
		if (useSiteElement is KtFile && useSiteElement.isDangling && useSiteElement.copyOrigin != null) {
			analyzeCopy(useSiteElement, KaDanglingFileResolutionMode.PREFER_SELF, action)
		} else {
			analyze(useSiteElement, action)
		}
	}
