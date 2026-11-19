package com.itsaky.androidide.lsp.kotlin.compiler.services

import com.itsaky.androidide.lsp.kotlin.fixtures.KtLspTest
import org.jetbrains.kotlin.analysis.api.platform.declarations.KotlinDirectInheritorsProvider
import org.jetbrains.kotlin.analysis.api.platform.projectStructure.KotlinProjectStructureProvider
import org.jetbrains.kotlin.com.intellij.psi.impl.source.PsiFileImpl
import org.jetbrains.kotlin.com.intellij.psi.search.GlobalSearchScope
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DirectInheritorsProviderTest : KtLspTest() {
	@Test
	fun `same-package search finds inheritors declared in other files of the base class's package`() {
		val base = createSourceFile("p/Base.kt", "package p\nsealed class Base")
		createSourceFile("p/A.kt", "package p\nclass A : Base()")
		createSourceFile("p/Outer.kt", "package p\nclass Outer { class B : Base() }")
		createSourceFile("p/Unrelated.kt", "package p\nclass Unrelated : Any()")

		assertEquals(listOf("p.A", "p.Outer.B"), samePackageInheritorsOf(base, "Base"))
	}

	@Test
	fun `same-package search finds inheritors that extend the base class through a type alias from another package`() {
		val base = createSourceFile("p/Base.kt", "package p\nsealed class Base")
		createSourceFile("q/Alias.kt", "package q\ntypealias Aliased = p.Base")
		createSourceFile("p/ViaAlias.kt", "package p\nimport q.Aliased\nclass ViaAlias : Aliased()")

		assertEquals(listOf("p.ViaAlias"), samePackageInheritorsOf(base, "Base"))
	}

	@Test
	fun `same-package search does not load files outside the base class's package`() {
		val base = createSourceFile("p/Base.kt", "package p\nsealed class Base")
		// Not registered with the file index, so nothing but a whole-module walk can reach it.
		val otherPackage = env.createFile("src", "q/Other.kt", "package q\nclass Other : Any()") as PsiFileImpl
		assertNull(otherPackage.treeElement)

		samePackageInheritorsOf(base, "Base")

		assertNull(otherPackage.treeElement)
	}

	@Test
	fun `full search still finds inheritors in other packages`() {
		val base = createSourceFile("p/Base.kt", "package p\nopen class Base")
		createSourceFile("p/A.kt", "package p\nclass A : Base()")
		createSourceFile("q/B.kt", "package q\nclass B : p.Base()")

		val all = inheritorsOf(base, "Base") { ktClass, scope -> getDirectKotlinInheritors(ktClass, scope, false) }

		assertEquals(listOf("p.A", "q.B"), all)
		assertEquals(listOf("p.A"), samePackageInheritorsOf(base, "Base"))
	}

	private fun samePackageInheritorsOf(
		file: KtFile,
		className: String,
	): List<String> =
		inheritorsOf(file, className) { ktClass, scope ->
			getSamePackageDirectKotlinInheritors(ktClass, scope, false)
		}

	private fun inheritorsOf(
		file: KtFile,
		className: String,
		search: KotlinDirectInheritorsProvider.(KtClass, GlobalSearchScope) -> Iterable<KtClassOrObject>,
	): List<String> {
		val ktClass = file.declarations.filterIsInstance<KtClass>().single { it.name == className }
		return analyzeMaybeDanglingForTest(file) {
			val scope = KotlinProjectStructureProvider.getModule(env.project, ktClass, useSiteModule = null).contentScope
			KotlinDirectInheritorsProvider
				.getInstance(env.project)
				.search(ktClass, scope)
				.map { it.fqName!!.asString() }
				.sorted()
		}
	}
}
