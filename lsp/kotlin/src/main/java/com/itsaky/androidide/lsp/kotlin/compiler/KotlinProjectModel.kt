package com.itsaky.androidide.lsp.kotlin.compiler

import com.itsaky.androidide.projects.api.Workspace
import org.appdevforall.codeonthego.indexing.jvm.JVM_GENERATED_SYMBOL_INDEX
import org.appdevforall.codeonthego.indexing.jvm.JVM_LIBRARY_SYMBOL_INDEX
import org.appdevforall.codeonthego.indexing.jvm.JvmSymbolIndex
import org.appdevforall.codeonthego.indexing.jvm.KT_SOURCE_FILE_INDEX_KEY
import org.appdevforall.codeonthego.indexing.jvm.KT_SOURCE_FILE_META_INDEX_KEY
import org.appdevforall.codeonthego.indexing.jvm.KtFileMetadataIndex
import org.appdevforall.codeonthego.indexing.service.IndexKey
import org.appdevforall.codeonthego.indexing.service.IndexRegistry
import org.appdevforall.codeonthego.indexing.service.IndexRegistryClosedException
import org.jetbrains.kotlin.platform.TargetPlatform
import org.jetbrains.kotlin.platform.jvm.JvmPlatforms
import org.slf4j.LoggerFactory

/**
 * Holds the project structure derived from a [Workspace].
 *
 * This is the single source of truth for module layout, dependencies,
 * and source roots. It knows nothing about analysis sessions — it just
 * describes *what* the project looks like.
 *
 * When the project structure changes (re-sync) or source files change
 * (build complete), it notifies registered listeners so they can
 * refresh their sessions.
 */
internal class KotlinProjectModel(
	/*
	 * The registry of the project session this model was built for. Its indexes are looked up on each
	 * read because the generated index may register after the build.
	 */
	private val registry: IndexRegistry,
) {
	private val logger = LoggerFactory.getLogger(KotlinProjectModel::class.java)

	private var workspace: Workspace? = null
	private var platform: TargetPlatform = JvmPlatforms.defaultJvmPlatform

	private val listeners = mutableListOf<ProjectModelListener>()

	val libraryIndex: JvmSymbolIndex?
		get() = registry.get(JVM_LIBRARY_SYMBOL_INDEX)

	val sourceIndex: JvmSymbolIndex?
		get() = registry.get(KT_SOURCE_FILE_INDEX_KEY)

	val fileIndex: KtFileMetadataIndex?
		get() = registry.get(KT_SOURCE_FILE_META_INDEX_KEY)

	val generatedIndex: JvmSymbolIndex?
		get() = registry.get(JVM_GENERATED_SYMBOL_INDEX)

	/**
	 * Returns the index registered for [key].
	 *
	 * @throws IndexRegistryClosedException if this model's project session has closed.
	 * @throws IllegalStateException if the session is open but nothing is registered for [key].
	 */
	fun <T : Any> requireIndex(key: IndexKey<T>): T = registry.require(key)

	/**
	 * The kind of change that occurred.
	 */
	enum class ChangeKind {
		/** Module structure, dependencies, or platform changed. Full rebuild needed. */
		STRUCTURE,

		/** Only source files within existing roots changed. Incremental refresh possible. */
		SOURCES,
	}

	fun interface ProjectModelListener {
		fun onProjectModelChanged(
			model: KotlinProjectModel,
			changeKind: ChangeKind,
		)
	}

	fun addListener(listener: ProjectModelListener) {
		listeners.add(listener)
	}

	fun removeListener(listener: ProjectModelListener) {
		listeners.remove(listener)
	}

	/**
	 * Called when the project is synced (setupWithProject).
	 * This replaces the entire project structure.
	 */
	fun update(
		workspace: Workspace,
		platform: TargetPlatform,
	) {
		this.workspace = workspace
		this.platform = platform
		notifyListeners(ChangeKind.STRUCTURE)
	}

	/**
	 * Called when a build completes and source files may have changed
	 * (generated sources added/removed), but the module structure is the same.
	 */
	fun onSourcesChanged() {
		if (workspace == null) {
			logger.warn("onSourcesChanged called before project model was initialized")
			return
		}
		notifyListeners(ChangeKind.SOURCES)
	}

	private fun notifyListeners(changeKind: ChangeKind) {
		logger.info("Notifying project listeners for change: {}", changeKind)
		listeners.forEach { it.onProjectModelChanged(this, changeKind) }
	}
}
