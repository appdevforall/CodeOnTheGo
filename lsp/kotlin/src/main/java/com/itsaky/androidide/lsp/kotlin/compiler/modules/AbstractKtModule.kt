package com.itsaky.androidide.lsp.kotlin.compiler.modules

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

	private fun getOrCreateBaseSearchScope(): GlobalSearchScope {
		synchronized(searchScopeLock) {
			var searchScope = _baseSearchScope
			if (searchScope != null) {
				return searchScope
			}

			val files = computeFiles(extended = true).toList()
			searchScope = GlobalSearchScope.filesScope(project, files)

			_baseSearchScope = searchScope
			_contentScope = KaContentScopeProvider.getInstance(project).getRefinedContentScope(this)
			return searchScope
		}
	}

	@Synchronized
	fun invalidateSearchScope() {
		synchronized(searchScopeLock) {
			_baseSearchScope = null
			_contentScope = null
		}
	}

	override val baseContentScope: GlobalSearchScope
		get() = getOrCreateBaseSearchScope()

	override val contentScope: GlobalSearchScope
		get() = getOrCreateBaseSearchScope().let { _contentScope!! }

	override val directDependsOnDependencies: List<KtModule>
		get() = emptyList()

	override val directFriendDependencies: List<KtModule>
		get() = emptyList()

	override fun toString(): String {
		return id
	}
}