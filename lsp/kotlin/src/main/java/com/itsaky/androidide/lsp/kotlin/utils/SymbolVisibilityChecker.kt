package com.itsaky.androidide.lsp.kotlin.utils

import com.itsaky.androidide.lsp.kotlin.compiler.services.ProjectStructureProvider
import org.appdevforall.codeonthego.indexing.jvm.JvmSymbol
import org.appdevforall.codeonthego.indexing.jvm.JvmVisibility
import org.jetbrains.kotlin.analysis.api.projectStructure.KaModule
import org.jetbrains.kotlin.analysis.api.projectStructure.allDirectDependencies
import org.slf4j.LoggerFactory

internal class SymbolVisibilityChecker(
	private val structureProvider: ProjectStructureProvider,
) {
	companion object {
		private val logger = LoggerFactory.getLogger(SymbolVisibilityChecker::class.java)
	}

	/**
	 * Visibility checks from one use site, with the modules reachable from it computed once.
	 *
	 * Held per request rather than cached on the checker: completion's use site is the dangling module
	 * of its placeholder copy, a new instance per request, so a cache keyed on it would gain an entry
	 * per completion and never be hit.
	 */
	inner class UseSiteSymbolVisibility internal constructor(
		private val useSiteModule: KaModule,
	) {
		private val reachableModules: Set<KaModule> by lazy { modulesReachableFrom(useSiteModule) }

		fun isVisible(
			symbol: JvmSymbol,
			useSitePackage: String?,
		): Boolean {
			val declaringModule =
				structureProvider.findModuleForSourceId(symbol.sourceId)
					?: return false

			if (declaringModule !in reachableModules) return false
			if (!arePlatformCompatible(useSiteModule, declaringModule)) return false
			if (!isDeclarationVisible(symbol, useSiteModule, declaringModule, useSitePackage)) return false

			return true
		}
	}

	/** Checks for many symbols from [useSiteModule]; walks its dependency graph once for all of them. */
	fun forUseSite(useSiteModule: KaModule) = UseSiteSymbolVisibility(useSiteModule)

	fun isVisible(
		symbol: JvmSymbol,
		useSiteModule: KaModule,
		useSitePackage: String? = null,
	): Boolean = forUseSite(useSiteModule).isVisible(symbol, useSitePackage)

	fun isReachable(
		useSiteModule: KaModule,
		declaringModule: KaModule,
	): Boolean = declaringModule in modulesReachableFrom(useSiteModule)

	/** [start] and every module reachable through its dependencies of any kind. */
	private fun modulesReachableFrom(start: KaModule): Set<KaModule> {
		val visited = mutableSetOf<KaModule>()
		val queue = ArrayDeque<KaModule>()
		queue.add(start)

		while (queue.isNotEmpty()) {
			val current = queue.removeFirst()
			if (!visited.add(current)) continue
			queue.addAll(current.allDirectDependencies())
		}

		return visited
	}

	fun arePlatformCompatible(
		useSiteModule: KaModule,
		declaringModule: KaModule,
	): Boolean {
		val usePlatform = useSiteModule.targetPlatform
		val declPlatform = declaringModule.targetPlatform

		// the declaring platform must be a superset of, or equal to the use
		// site platform
		return declPlatform.componentPlatforms.all { declComp ->
			usePlatform.componentPlatforms.any { useComp ->
				useComp == declComp || useComp.platformName == declComp.platformName
			}
		}
	}

	fun isDeclarationVisible(
		symbol: JvmSymbol,
		useSiteModule: KaModule,
		declaringModule: KaModule,
		useSitePackage: String? = null,
	): Boolean {
		val isSamePackage = useSitePackage != null && useSitePackage == symbol.packageName

		// TODO(itsaky): this should check whether the use-site element
		//               is contained in a class that is a descendant of the
		//               class declaring the given symbol.
		//               For now, we assume true in all cases.
		val isDescendant = true

		return when (symbol.visibility) {
			JvmVisibility.PUBLIC -> true
			JvmVisibility.PRIVATE -> false
			JvmVisibility.INTERNAL -> useSiteModule == declaringModule
			JvmVisibility.PROTECTED -> isSamePackage || isDescendant
			JvmVisibility.PACKAGE_PRIVATE -> isSamePackage
		}
	}
}
