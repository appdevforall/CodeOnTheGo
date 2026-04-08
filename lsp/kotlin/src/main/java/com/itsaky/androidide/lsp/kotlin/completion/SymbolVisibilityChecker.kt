package com.itsaky.androidide.lsp.kotlin.completion

import com.itsaky.androidide.lsp.kotlin.compiler.ModuleResolver
import org.appdevforall.codeonthego.indexing.jvm.JvmSymbol
import org.appdevforall.codeonthego.indexing.jvm.JvmVisibility
import org.jetbrains.kotlin.analysis.api.projectStructure.KaModule
import org.jetbrains.kotlin.analysis.api.projectStructure.allDirectDependencies
import java.util.concurrent.ConcurrentHashMap

internal class SymbolVisibilityChecker(
	private val moduleResolver: ModuleResolver,
) {
	// visibility check cache, for memoization
	// useSiteModule -> list of modules visible from useSiteModule
	private val moduleVisibilityCache = ConcurrentHashMap<KaModule, List<KaModule>>()

	fun isVisible(
		symbol: JvmSymbol,
		useSiteModule: KaModule,
		useSitePackage: String? = null,
	): Boolean {
		val declaringModule = moduleResolver.findDeclaringModule(symbol.sourceId)
			?: return false

		if (!isReachable(useSiteModule, declaringModule)) return false
		if (!arePlatformCompatible(useSiteModule, declaringModule)) return false
		if (!isDeclarationVisible(symbol, useSiteModule, declaringModule, useSitePackage)) return false

		return true
	}

	fun isReachable(useSiteModule: KaModule, declaringModule: KaModule): Boolean {
		if (useSiteModule == declaringModule) return true
		if (moduleVisibilityCache[useSiteModule]?.contains(declaringModule) == true) return true

		// walk the dependency graph
		val visited = mutableSetOf<KaModule>()
		val queue = ArrayDeque<KaModule>()
		queue.add(useSiteModule)

		while (queue.isNotEmpty()) {
			val current = queue.removeFirst()
			if (!visited.add(current)) continue
			if (current == declaringModule) return true

			queue.addAll(current.allDirectDependencies())
		}

		return false
	}

	fun arePlatformCompatible(useSiteModule: KaModule, declaringModule: KaModule): Boolean {
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
