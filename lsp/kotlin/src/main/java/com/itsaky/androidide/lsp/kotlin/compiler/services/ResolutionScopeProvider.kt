package com.itsaky.androidide.lsp.kotlin.compiler.services

import org.jetbrains.kotlin.analysis.api.impl.base.projectStructure.KaBaseResolutionScopeProvider
import org.jetbrains.kotlin.analysis.api.platform.projectStructure.KaResolutionScope
import org.jetbrains.kotlin.analysis.api.platform.projectStructure.KaResolutionScopeProvider
import org.jetbrains.kotlin.analysis.api.projectStructure.KaModule
import org.jetbrains.kotlin.com.intellij.openapi.project.Project
import org.jetbrains.kotlin.com.intellij.psi.search.GlobalSearchScope
import org.slf4j.LoggerFactory

class ResolutionScopeProvider : KaBaseResolutionScopeProvider() {

	companion object {
		private val logger = LoggerFactory.getLogger(ResolutionScopeProvider::class.java)

		fun getInstance(project: Project): ResolutionScopeProvider =
			KaResolutionScopeProvider.getInstance(project) as ResolutionScopeProvider
	}

	override fun getResolutionScope(module: KaModule): KaResolutionScope {
		logger.debug("getResolutionScope(module={})", module)
		return super.getResolutionScope(module)
	}

	override fun buildSearchScope(
		module: KaModule,
		analyzableModules: Set<KaModule>
	): GlobalSearchScope {
		logger.debug("buildSearchScope(module={}, analyzableModules={})", module, analyzableModules)
		return super.buildSearchScope(module, analyzableModules)
	}

	fun invalidate(module: KaModule) {
		resolutionScopeCache.invalidate(module)
	}

	fun invalidateAll() {
		resolutionScopeCache.invalidateAll()
	}
}