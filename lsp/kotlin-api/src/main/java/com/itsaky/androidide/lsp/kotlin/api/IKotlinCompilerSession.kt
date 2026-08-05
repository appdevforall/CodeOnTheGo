package com.itsaky.androidide.lsp.kotlin.api

import com.itsaky.androidide.lsp.api.ILanguageClient
import com.itsaky.androidide.projects.api.Workspace
import java.nio.file.Path

/**
 * Bridge to the isolated Kotlin Analysis API session, loaded lazily via `DexClassLoader`
 * on first Kotlin-file-gated request (see `KotlinCompilerLoader`) instead of being
 * always resident in the main app dex.
 */
interface IKotlinCompilerSession : AutoCloseable {
	fun updateLanguageClient(client: ILanguageClient?)

	fun updateProjectModel(workspace: Workspace)

	fun refreshSources()

	/**
	 * The environment for [file], or null when there is none (e.g. a `.kts` script,
	 * which has no environment yet) or the file is not a Kotlin file at all.
	 */
	fun compilationEnvironmentFor(file: Path): IKotlinCompilationEnvironment?

	/** Registers the Kotlin code-actions menu. Deferred here (off the eager setup path) since it needs classes from the isolated dex. */
	fun registerCodeActions()

	/** Schedules the already-open documents in [activeDocumentFiles] for analysis, mirroring what setupWithProject used to do eagerly for every open file. */
	fun openFileIfNeeded(activeDocumentFiles: List<Path>)
}
