package com.itsaky.androidide.lsp.kotlin.compiler.services

import com.itsaky.androidide.lsp.kotlin.compiler.index.KtSymbolIndex
import com.itsaky.androidide.lsp.kotlin.compiler.index.filesForPackage
import com.itsaky.androidide.lsp.kotlin.compiler.modules.KtModule
import com.itsaky.androidide.lsp.kotlin.compiler.read
import org.jetbrains.kotlin.analysis.api.platform.declarations.KotlinCompositeDeclarationProvider
import org.jetbrains.kotlin.analysis.api.platform.declarations.KotlinDeclarationProvider
import org.jetbrains.kotlin.analysis.api.platform.declarations.KotlinDeclarationProviderFactory
import org.jetbrains.kotlin.analysis.api.platform.declarations.KotlinDeclarationProviderMerger
import org.jetbrains.kotlin.analysis.api.platform.declarations.createDeclarationProvider
import org.jetbrains.kotlin.analysis.api.platform.mergeSpecificProviders
import org.jetbrains.kotlin.analysis.api.projectStructure.KaModule
import org.jetbrains.kotlin.cli.jvm.index.JavaRoot
import org.jetbrains.kotlin.com.intellij.mock.MockProject
import org.jetbrains.kotlin.com.intellij.openapi.project.Project
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFileManager
import org.jetbrains.kotlin.com.intellij.psi.search.GlobalSearchScope
import org.jetbrains.kotlin.com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.fileClasses.javaFileFacadeFqName
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.psi.KtCallableDeclaration
import org.jetbrains.kotlin.psi.KtClassLikeDeclaration
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtElement
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtScript
import org.jetbrains.kotlin.psi.KtTypeAlias
import org.jetbrains.kotlin.psi.psiUtil.isTopLevelKtOrJavaMember
import java.nio.file.Paths

internal class DeclarationProviderFactory : KtLspService, KotlinDeclarationProviderFactory {

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

	override fun createDeclarationProvider(
		scope: GlobalSearchScope,
		contextualModule: KaModule?
	): KotlinDeclarationProvider {
		return DeclarationProvider(scope, project, index)
	}
}

class DeclarationProviderMerger(private val project: Project) : KotlinDeclarationProviderMerger {
	override fun merge(providers: List<KotlinDeclarationProvider>): KotlinDeclarationProvider =
		providers.mergeSpecificProviders<_, DeclarationProvider>(KotlinCompositeDeclarationProvider.factory) { targetProviders ->
			val combinedScope = GlobalSearchScope.union(targetProviders.map { it.scope })
			project.createDeclarationProvider(combinedScope, contextualModule = null).apply {
				check(this is DeclarationProvider) {
					"`DeclarationProvider` can only be merged into a combined declaration provider of the same type."
				}
			}
		}
}


internal class DeclarationProvider(
	val scope: GlobalSearchScope,
	private val project: Project,
	private val index: KtSymbolIndex
) : KotlinDeclarationProvider {

	private val KtElement.inScope: Boolean
		get() = containingKtFile.virtualFile in scope

	override val hasSpecificCallablePackageNamesComputation: Boolean
		get() = false
	override val hasSpecificClassifierPackageNamesComputation: Boolean
		get() = false

	override fun findFilesForFacade(facadeFqName: FqName): Collection<KtFile> {
		if (facadeFqName.shortNameOrSpecial().isSpecial) return emptyList()
		// According to standalone platform, this does not work with classes with @JvmPackageName
		return findFilesForFacadeByPackage(facadeFqName.parent())
			.filter { it.javaFileFacadeFqName == facadeFqName }
	}

	override fun findInternalFilesForFacade(facadeFqName: FqName): Collection<KtFile> =
		// We don't deserialize libraries from stubs so we can return empty here safely
		// We don't take the KaBuiltinsModule into account for simplicity,
		// that means we expect the kotlin stdlib to be included on the project
		emptyList()

	override fun findFilesForFacadeByPackage(packageFqName: FqName): Collection<KtFile> =
		ktFilesForPackage(packageFqName).toList()

	override fun findFilesForScript(scriptFqName: FqName): Collection<KtScript> =
		ktFilesForPackage(scriptFqName).mapNotNull { it.script }.toList()

	override fun getAllClassesByClassId(classId: ClassId): Collection<KtClassOrObject> =
		ktFilesForPackage(classId.packageFqName)
			.flatMap {
				project.read {
					PsiTreeUtil.collectElementsOfType(it, KtClassOrObject::class.java).asSequence()
				}
			}
			.filter { it.getClassId() == classId }
			.toList()

	override fun getAllTypeAliasesByClassId(classId: ClassId): Collection<KtTypeAlias> =
		ktFilesForPackage(classId.packageFqName)
			.flatMap {
				project.read {
					PsiTreeUtil.collectElementsOfType(it, KtTypeAlias::class.java).asSequence()
				}
			}
			.filter { it.getClassId() == classId }
			.toList()

	override fun getClassLikeDeclarationByClassId(classId: ClassId): KtClassLikeDeclaration? =
		getAllClassesByClassId(classId).firstOrNull()
			?: getAllTypeAliasesByClassId(classId).firstOrNull()

	override fun getTopLevelCallableFiles(callableId: CallableId): Collection<KtFile> =
		buildSet {
			getTopLevelProperties(callableId).mapTo(this) { it.containingKtFile }
			getTopLevelFunctions(callableId).mapTo(this) { it.containingKtFile }
		}

	override fun getTopLevelFunctions(callableId: CallableId): Collection<KtNamedFunction> =
		ktFilesForPackage(callableId.packageName)
			.flatMap {
				project.read {
					PsiTreeUtil.collectElementsOfType(it, KtNamedFunction::class.java)
						.asSequence()
				}
			}
			.filter { it.isTopLevel }
			.filter { it.nameAsName == callableId.callableName }
			.toList()

	override fun getTopLevelKotlinClassLikeDeclarationNamesInPackage(packageFqName: FqName): Set<Name> =
		ktFilesForPackage(packageFqName)
			.flatMap {
				project.read {
					PsiTreeUtil.collectElementsOfType(it, KtClassLikeDeclaration::class.java)
						.asSequence()
				}
			}
			.filter { it.isTopLevelKtOrJavaMember() }
			.mapNotNull { it.nameAsName }
			.toSet()

	override fun getTopLevelCallableNamesInPackage(packageFqName: FqName): Set<Name> =
		ktFilesForPackage(packageFqName)
			.flatMap {
				project.read {
					PsiTreeUtil.collectElementsOfType(it, KtCallableDeclaration::class.java)
						.asSequence()
				}
			}
			.filter { it.isTopLevelKtOrJavaMember() }
			.mapNotNull { it.nameAsName }
			.toSet()

	override fun getTopLevelProperties(callableId: CallableId): Collection<KtProperty> =
		ktFilesForPackage(callableId.packageName)
			.flatMap {
				project.read {
					PsiTreeUtil.collectElementsOfType(it, KtProperty::class.java).asSequence()
				}
			}
			.filter { it.isTopLevel }
			.filter { it.nameAsName == callableId.callableName }
			.toList()

	private fun ktFilesForPackage(fqName: FqName): Sequence<KtFile> {
		return index.filesForPackage(fqName.asString())
			.map { VirtualFileManager.getInstance().findFileByNioPath(Paths.get(it.filePath))!! }
			.filter { it in scope }
			.map { index.getKtFile(it) }
	}
}
