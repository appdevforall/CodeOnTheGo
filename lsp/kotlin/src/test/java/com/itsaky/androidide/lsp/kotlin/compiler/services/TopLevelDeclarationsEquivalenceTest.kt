package com.itsaky.androidide.lsp.kotlin.compiler.services

import com.google.common.truth.Truth.assertThat
import com.itsaky.androidide.lsp.kotlin.compiler.read
import com.itsaky.androidide.lsp.kotlin.fixtures.KtLspTest
import org.jetbrains.kotlin.com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.psi.KtCallableDeclaration
import org.jetbrains.kotlin.psi.KtClassLikeDeclaration
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtTypeAlias
import org.jetbrains.kotlin.psi.psiUtil.isTopLevelKtOrJavaMember
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Paths

class TopLevelDeclarationsEquivalenceTest : KtLspTest() {
	@Test
	fun topLevelLookupsMatchTheFullTreeWalksOnRealSources() {
		val byPackage = parseMainSources().groupBy { it.packageFqName }
		assertThat(byPackage.size).isGreaterThan(15)

		env.project.read {
			for ((_, list) in byPackage) {
				val files = list.asSequence()
				assertThat(files.topLevelCallableNames()).isEqualTo(legacyCallableNames(files))
				assertThat(files.topLevelClassLikeNames()).isEqualTo(legacyClassLikeNames(files))
				for (id in legacyAllClassIds(files)) {
					assertThat(files.classesWithId(id)).containsExactlyElementsIn(legacyClasses(files, id))
					assertThat(files.typeAliasesWithId(id)).containsExactlyElementsIn(legacyTypeAliases(files, id))
				}
				for (name in legacyCallableNames(files)) {
					assertThat(files.topLevelFunctions(name)).containsExactlyElementsIn(legacyFunctions(files, name))
					assertThat(files.topLevelProperties(name)).containsExactlyElementsIn(legacyProperties(files, name))
				}
			}
		}
	}

	private fun parseMainSources(): List<KtFile> {
		val root =
			generateSequence(Paths.get("").toAbsolutePath()) { it.parent }
				.first { Files.exists(it.resolve(".androidide_root")) }
		val paths =
			Files.walk(root.resolve("lsp/kotlin/src/main")).use { stream ->
				stream.filter { it.toString().endsWith(".kt") }.toList()
			}
		return env.project.read {
			paths.map { env.parser.createFile(it.fileName.toString(), it.toFile().readText()) }
		}
	}

	private fun legacyAllClassIds(files: Sequence<KtFile>): Set<ClassId> {
		val classes = files.flatMap { PsiTreeUtil.collectElementsOfType(it, KtClassOrObject::class.java) }.mapNotNull { it.getClassId() }
		val aliases = files.flatMap { PsiTreeUtil.collectElementsOfType(it, KtTypeAlias::class.java) }.mapNotNull { it.getClassId() }
		return (classes + aliases).toSet()
	}

	private fun legacyClasses(
		files: Sequence<KtFile>,
		classId: ClassId,
	): List<KtClassOrObject> =
		files
			.flatMap { PsiTreeUtil.collectElementsOfType(it, KtClassOrObject::class.java).asSequence() }
			.filter { it.getClassId() == classId }
			.toList()

	private fun legacyTypeAliases(
		files: Sequence<KtFile>,
		classId: ClassId,
	): List<KtTypeAlias> =
		files
			.flatMap { PsiTreeUtil.collectElementsOfType(it, KtTypeAlias::class.java).asSequence() }
			.filter { it.getClassId() == classId }
			.toList()

	private fun legacyFunctions(
		files: Sequence<KtFile>,
		name: Name,
	): List<KtNamedFunction> =
		files
			.flatMap { PsiTreeUtil.collectElementsOfType(it, KtNamedFunction::class.java).asSequence() }
			.filter { it.isTopLevel }
			.filter { it.nameAsName == name }
			.toList()

	private fun legacyProperties(
		files: Sequence<KtFile>,
		name: Name,
	): List<KtProperty> =
		files
			.flatMap { PsiTreeUtil.collectElementsOfType(it, KtProperty::class.java).asSequence() }
			.filter { it.isTopLevel }
			.filter { it.nameAsName == name }
			.toList()

	private fun legacyClassLikeNames(files: Sequence<KtFile>): Set<Name> =
		files
			.flatMap { PsiTreeUtil.collectElementsOfType(it, KtClassLikeDeclaration::class.java).asSequence() }
			.filter { it.isTopLevelKtOrJavaMember() }
			.mapNotNull { it.nameAsName }
			.toSet()

	private fun legacyCallableNames(files: Sequence<KtFile>): Set<Name> =
		files
			.flatMap { PsiTreeUtil.collectElementsOfType(it, KtCallableDeclaration::class.java).asSequence() }
			.filter { it.isTopLevelKtOrJavaMember() }
			.mapNotNull { it.nameAsName }
			.toSet()
}
