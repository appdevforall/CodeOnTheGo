package com.itsaky.androidide.lsp.kotlin.compiler.services

import com.itsaky.androidide.lsp.kotlin.compiler.index.KtSymbolIndex
import com.itsaky.androidide.lsp.kotlin.compiler.modules.KtModule
import org.jetbrains.kotlin.analysis.api.platform.declarations.KotlinAnnotationsResolver
import org.jetbrains.kotlin.analysis.api.platform.declarations.KotlinAnnotationsResolverFactory
import org.jetbrains.kotlin.analysis.api.platform.declarations.KotlinDeclarationProvider
import org.jetbrains.kotlin.analysis.api.platform.declarations.createDeclarationProvider
import org.jetbrains.kotlin.cli.jvm.index.JavaRoot
import org.jetbrains.kotlin.com.intellij.mock.MockProject
import org.jetbrains.kotlin.com.intellij.openapi.project.Project
import org.jetbrains.kotlin.com.intellij.psi.search.GlobalSearchScope
import org.jetbrains.kotlin.com.intellij.psi.search.impl.VirtualFileEnumeration
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.parentOrNull
import org.jetbrains.kotlin.psi.KtAnnotated
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtDeclaration
import org.jetbrains.kotlin.psi.KtFunction
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtTypeReference
import org.jetbrains.kotlin.psi.KtUserType
import org.jetbrains.kotlin.psi.declarationRecursiveVisitor
import org.jetbrains.kotlin.util.collectionUtils.filterIsInstanceAnd

internal class AnnotationsResolverFactory : KtLspService, KotlinAnnotationsResolverFactory {

	private lateinit var project: Project
	private lateinit var index: KtSymbolIndex

	override fun setupWith(
		project: MockProject,
		index: KtSymbolIndex,
		modules: List<KtModule>,
		libraryRoots: List<JavaRoot>
	) {
		this.project = project
		this.index = index
	}

	override fun createAnnotationResolver(searchScope: GlobalSearchScope): KotlinAnnotationsResolver {
		return AnnotationsResolver(project, searchScope, index)
	}
}

@Suppress("UnstableApiUsage")
internal class AnnotationsResolver(
	project: Project,
	private val scope: GlobalSearchScope,
	private val index: KtSymbolIndex,
) : KotlinAnnotationsResolver {

	private val declarationProvider by lazy {
		project.createDeclarationProvider(scope, contextualModule = null)
	}

	private fun allDeclarations(): List<KtDeclaration> {
		val virtualFiles = VirtualFileEnumeration.extract(scope) ?: return emptyList()

		val filesInScope = virtualFiles
			.filesIfCollection
			.orEmpty()
			.asSequence()
			.filter { it in scope }
			.mapNotNull { index.getKtFile(it) }

		return buildList {
			val visitor = declarationRecursiveVisitor visit@{
				val isLocal = when (it) {
					is KtClassOrObject -> it.isLocal
					is KtFunction -> it.isLocal
					is KtProperty -> it.isLocal
					else -> return@visit
				}

				if (!isLocal) {
					add(it)
				}
			}

			filesInScope.forEach { it.accept(visitor) }
		}
	}

	override fun declarationsByAnnotation(annotationClassId: ClassId): Set<KtAnnotated> {
		return allDeclarations()
			.asSequence()
			.filter { annotationClassId in annotationsOnDeclaration(it) }
			.toSet()
	}

	override fun annotationsOnDeclaration(declaration: KtAnnotated): Set<ClassId> {
		return declaration
			.annotationEntries
			.asSequence()
			.flatMap { it.typeReference?.resolveAnnotationClassIds(declarationProvider).orEmpty() }
			.toSet()
	}
}

private fun KtTypeReference.resolveAnnotationClassIds(
	declarationProvider: KotlinDeclarationProvider,
	candidates: MutableSet<ClassId> = mutableSetOf()
): Set<ClassId> {
	val annotationTypeElement = typeElement as? KtUserType
	val referencedName = annotationTypeElement?.referencedFqName ?: return emptySet()
	if (referencedName.isRoot) return emptySet()

	if (!referencedName.parent().isRoot) {
		return buildSet { referencedName.resolveToClassIds(this, declarationProvider) }
	}

	val targetName = referencedName.shortName()
	for (import in containingKtFile.importDirectives) {
		val importedName = import.importedFqName ?: continue
		when {
			import.isAllUnder -> importedName.child(targetName).resolveToClassIds(candidates, declarationProvider)
			importedName.shortName() == targetName -> importedName.resolveToClassIds(candidates, declarationProvider)
		}
	}

	containingKtFile.packageFqName.child(targetName).resolveToClassIds(candidates, declarationProvider)
	return candidates
}

private val KtUserType.referencedFqName: FqName?
	get() {
		val allTypes = generateSequence(this) { it.qualifier }.toList().asReversed()
		val allQualifiers = allTypes.map { it.referencedName ?: return null }
		return FqName.fromSegments(allQualifiers)
	}


private fun FqName.resolveToClassIds(to: MutableSet<ClassId>, declarationProvider: KotlinDeclarationProvider) {
	toClassIdSequence().mapNotNullTo(to) { classId ->
		val classes = declarationProvider.getAllClassesByClassId(classId)
		val typeAliases = declarationProvider.getAllTypeAliasesByClassId(classId)
		typeAliases.singleOrNull()?.getTypeReference()?.resolveAnnotationClassIds(declarationProvider, to)

		val annotations = classes.filterIsInstanceAnd<KtClass> { it.isAnnotation() }
		annotations.singleOrNull()?.let {
			classId
		}
	}
}

private fun FqName.toClassIdSequence(): Sequence<ClassId> {
	var currentName = shortNameOrSpecial()
	if (currentName.isSpecial) return emptySequence()
	var currentParent = parentOrNull() ?: return emptySequence()
	var currentRelativeName = currentName.asString()

	return sequence {
		while (true) {
			yield(ClassId(currentParent, FqName(currentRelativeName), isLocal = false))
			currentName = currentParent.shortNameOrSpecial()
			if (currentName.isSpecial) break
			currentParent = currentParent.parentOrNull() ?: break
			currentRelativeName = "${currentName.asString()}.$currentRelativeName"
		}
	}
}

