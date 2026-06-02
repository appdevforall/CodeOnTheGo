package com.itsaky.androidide.lsp.kotlin.compiler.modules

import io.sentry.Sentry
import org.jetbrains.kotlin.analysis.api.KaExperimentalApi
import org.jetbrains.kotlin.analysis.api.KaPlatformInterface
import org.jetbrains.kotlin.analysis.api.platform.projectStructure.KaContentScopeProvider
import org.jetbrains.kotlin.analysis.api.platform.projectStructure.KaModuleBase
import org.jetbrains.kotlin.com.intellij.openapi.project.Project
import org.jetbrains.kotlin.com.intellij.psi.search.GlobalSearchScope

@OptIn(KaPlatformInterface::class)
internal abstract class AbstractKtModule(
	override val project: Project,
	override val directRegularDependencies: List<KtModule>,
) : KtModule, KaModuleBase() {

	private val searchScopeLock = Any()
	private var _baseSearchScope: GlobalSearchScope? = null
	private var _contentScope: GlobalSearchScope? = null

	private fun maybeCreateScopesLocked() {
		val searchScope = _baseSearchScope
		if (searchScope != null) {
			return
		}

		_baseSearchScope = computeBaseContentScope()
		_contentScope = KaContentScopeProvider.getInstance(project).getRefinedContentScope(this)
		Sentry.addBreadcrumb("createSearchScopes(mod=$this, base=${_baseSearchScope?.hashCode()}, content=${_contentScope?.hashCode()})")
	}

	/**
	 * Computes the base content scope for this module.
	 *
	 * The default is a snapshot of the currently resolvable [VirtualFile]s
	 * ([computeFiles]). Subclasses whose membership is better expressed by path
	 * (e.g. source modules, where files are created/deleted/refreshed while
	 * editing) should override this with a path-predicate scope so that scope
	 * membership stays consistent with the way the module is resolved in
	 * `ProjectStructureProvider.getModule`.
	 */
	protected open fun computeBaseContentScope(): GlobalSearchScope =
		GlobalSearchScope.filesScope(project, computeFiles(extended = true).toList())

	fun invalidateSearchScope() {
		synchronized(searchScopeLock) {
			Sentry.addBreadcrumb("invalidateSearchScope(mod=$this)")
			_baseSearchScope = null
			_contentScope = null
		}
	}

	override val baseContentScope: GlobalSearchScope
		get() = synchronized(searchScopeLock) {
			maybeCreateScopesLocked()
			checkNotNull(_baseSearchScope) { "failed to create base content scope" }
		}

	override val contentScope: GlobalSearchScope
		get() = synchronized(searchScopeLock) {
			maybeCreateScopesLocked()
			checkNotNull(_contentScope) { "failed to create content refined scope" }
		}

	override val directDependsOnDependencies: List<KtModule>
		get() = emptyList()

	override val directFriendDependencies: List<KtModule>
		get() = emptyList()

	@OptIn(KaExperimentalApi::class)
	override val moduleDescription: String
		get() = "module '$id' (ref=${hashCode()}, baseScope=${_baseSearchScope?.hashCode()}, contentScope=${_contentScope?.hashCode()}, deps=${directRegularDependencies.joinToString { it.id }})"

	override fun toString(): String {
		return id
	}
}