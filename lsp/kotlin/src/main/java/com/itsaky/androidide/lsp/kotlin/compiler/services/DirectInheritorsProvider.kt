package com.itsaky.androidide.lsp.kotlin.compiler.services

import com.itsaky.androidide.lsp.kotlin.compiler.index.KtSymbolIndex
import com.itsaky.androidide.lsp.kotlin.compiler.modules.KtModule
import com.itsaky.androidide.lsp.kotlin.compiler.modules.asFlatSequence
import com.itsaky.androidide.lsp.kotlin.compiler.modules.isSourceModule
import org.jetbrains.kotlin.analysis.api.KaImplementationDetail
import org.jetbrains.kotlin.analysis.api.fir.utils.isSubclassOf
import org.jetbrains.kotlin.analysis.api.platform.declarations.KotlinDirectInheritorsProvider
import org.jetbrains.kotlin.analysis.api.platform.projectStructure.KotlinProjectStructureProvider
import org.jetbrains.kotlin.analysis.api.projectStructure.KaModule
import org.jetbrains.kotlin.analysis.low.level.api.fir.LLFirInternals
import org.jetbrains.kotlin.analysis.low.level.api.fir.sessions.LLFirSessionCache
import org.jetbrains.kotlin.cli.jvm.index.JavaRoot
import org.jetbrains.kotlin.com.intellij.mock.MockProject
import org.jetbrains.kotlin.com.intellij.openapi.project.Project
import org.jetbrains.kotlin.com.intellij.psi.search.GlobalSearchScope
import org.jetbrains.kotlin.fir.declarations.FirClass
import org.jetbrains.kotlin.fir.resolve.providers.symbolProvider
import org.jetbrains.kotlin.fir.symbols.SymbolInternals
import org.jetbrains.kotlin.fir.symbols.impl.FirClassLikeSymbol
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtNullableType
import org.jetbrains.kotlin.psi.KtTreeVisitorVoid
import org.jetbrains.kotlin.psi.KtTypeAlias
import org.jetbrains.kotlin.psi.KtTypeElement
import org.jetbrains.kotlin.psi.KtUserType
import org.jetbrains.kotlin.psi.psiUtil.contains
import org.jetbrains.kotlin.psi.psiUtil.getImportedSimpleNameByImportAlias
import org.jetbrains.kotlin.psi.psiUtil.getSuperNames

internal class DirectInheritorsProvider: KtLspService, KotlinDirectInheritorsProvider {
	private lateinit var index: KtSymbolIndex
	private lateinit var modules: List<KtModule>
	private lateinit var project: Project

	private val classesBySupertypeName = mutableMapOf<Name, MutableSet<KtClassOrObject>>()
	private val inheritableTypeAliasesByAliasedName = mutableMapOf<Name, MutableSet<KtTypeAlias>>()

	override fun setupWith(
		project: MockProject,
		index: KtSymbolIndex,
		modules: List<KtModule>,
		libraryRoots: List<JavaRoot>
	) {
		this.project = project
		this.index = index
		this.modules = modules
	}

	@OptIn(SymbolInternals::class)
	override fun getDirectKotlinInheritors(
		ktClass: KtClass,
		scope: GlobalSearchScope,
		includeLocalInheritors: Boolean
	): Iterable<KtClassOrObject> {
		computeIndex()

		val classId = ktClass.getClassId() ?: return emptyList()
		val baseModule = KotlinProjectStructureProvider.getModule(project, ktClass, useSiteModule = null)
		val baseFirClass = classId.toFirSymbol(baseModule)?.fir as? FirClass ?: return emptyList()

		val baseClassNames = mutableSetOf(classId.shortClassName)
		calculateAliases(classId.shortClassName, baseClassNames)

		val possibleInheritors = baseClassNames.flatMap { classesBySupertypeName[it].orEmpty() }
		if (possibleInheritors.isEmpty()) {
			return emptyList()
		}

		return possibleInheritors.filter { isValidInheritor(it, baseFirClass, scope, includeLocalInheritors) }
	}

	// Let's say this operation is not frequently called, if we discover it's not the case we should cache it
	private fun computeIndex() {
		classesBySupertypeName.clear()
		inheritableTypeAliasesByAliasedName.clear()

		modules
			.asFlatSequence()
			.filter { it.isSourceModule }.flatMap { it.computeFiles(extended = true) }
			.map { index.getKtFile(it) }
			.forEach { ktFile ->
				ktFile.accept(object : KtTreeVisitorVoid() {
					override fun visitClassOrObject(classOrObject: KtClassOrObject) {
						classOrObject.getSuperNames().forEach { superName ->
							classesBySupertypeName
								.computeIfAbsent(Name.identifier(superName)) { mutableSetOf() }
								.add(classOrObject)
						}
						super.visitClassOrObject(classOrObject)
					}

					override fun visitTypeAlias(typeAlias: KtTypeAlias) {
						val typeElement = typeAlias.getTypeReference()?.typeElement ?: return

						findInheritableSimpleNames(typeElement).forEach { expandedName ->
							inheritableTypeAliasesByAliasedName
								.computeIfAbsent(Name.identifier(expandedName)) { mutableSetOf() }
								.add(typeAlias)
						}

						super.visitTypeAlias(typeAlias)
					}
				})
			}
	}

	private fun calculateAliases(aliasedName: Name, aliases: MutableSet<Name>) {
		inheritableTypeAliasesByAliasedName[aliasedName].orEmpty().forEach { alias ->
			val aliasName = alias.nameAsSafeName
			val isNewAliasName = aliases.add(aliasName)
			if (isNewAliasName) {
				calculateAliases(aliasName, aliases)
			}
		}
	}

	@OptIn(KaImplementationDetail::class, SymbolInternals::class)
	private fun isValidInheritor(
		candidate: KtClassOrObject,
		baseFirClass: FirClass,
		scope: GlobalSearchScope,
		includeLocalInheritors: Boolean,
	): Boolean {
		if (!includeLocalInheritors && candidate.isLocal) {
			return false
		}

		if (!scope.contains(candidate)) {
			return false
		}

		val candidateClassId = candidate.getClassId() ?: return false
		val candidateModule = KotlinProjectStructureProvider.getModule(project, candidate, useSiteModule = null)
		val candidateFirSymbol = candidateClassId.toFirSymbol(candidateModule) ?: return false
		val candidateFirClass = candidateFirSymbol.fir as? FirClass ?: return false

		return isSubclassOf(candidateFirClass, baseFirClass, candidateFirClass.moduleData.session, allowIndirectSubtyping = false)
	}

	@OptIn(LLFirInternals::class)
	private fun ClassId.toFirSymbol(module: KaModule): FirClassLikeSymbol<*>? {
		val session = LLFirSessionCache.getInstance(project).getSession(module, preferBinary = true)
		return session.symbolProvider.getClassLikeSymbolByClassId(this)
	}
}

private fun findInheritableSimpleNames(typeElement: KtTypeElement): List<String> {
	return when (typeElement) {
		is KtUserType -> {
			val referenceName = typeElement.referencedName ?: return emptyList()

			buildList {
				add(referenceName)

				val ktFile = typeElement.containingKtFile
				if (!ktFile.isCompiled) {
					val name = getImportedSimpleNameByImportAlias(typeElement.containingKtFile, referenceName)
					if (name != null) {
						add(name)
					}
				}
			}
		}
		is KtNullableType -> typeElement.innerType?.let(::findInheritableSimpleNames) ?: emptyList()
		else -> emptyList()
	}
}