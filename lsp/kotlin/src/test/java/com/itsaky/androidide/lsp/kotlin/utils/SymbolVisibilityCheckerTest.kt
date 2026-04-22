package com.itsaky.androidide.lsp.kotlin.utils

import com.google.common.truth.Truth.assertThat
import com.itsaky.androidide.lsp.kotlin.compiler.services.ProjectStructureProvider
import io.mockk.mockk
import org.appdevforall.codeonthego.indexing.jvm.JvmClassInfo
import org.appdevforall.codeonthego.indexing.jvm.JvmSourceLanguage
import org.appdevforall.codeonthego.indexing.jvm.JvmSymbol
import org.appdevforall.codeonthego.indexing.jvm.JvmSymbolKind
import org.appdevforall.codeonthego.indexing.jvm.JvmVisibility
import org.jetbrains.kotlin.analysis.api.projectStructure.KaModule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/**
 * Unit tests for [SymbolVisibilityChecker.isDeclarationVisible].
 *
 * The method is pure (depends only on [JvmSymbol.visibility], [JvmSymbol.packageName],
 * and module identity comparisons), so it can be tested without a running K2 compiler.
 */
@RunWith(JUnit4::class)
class SymbolVisibilityCheckerTest {

    // Use relaxed mocks so that no stubs are required for unused methods
    private val structureProvider = mockk<ProjectStructureProvider>(relaxed = true)
    private val checker = SymbolVisibilityChecker(structureProvider)

    // Two distinct modules
    private val moduleA: KaModule = mockk(relaxed = true)
    private val moduleB: KaModule = mockk(relaxed = true)

    private fun symbol(
        visibility: JvmVisibility,
        pkg: String = "com.example",
        sourceId: String = "jar-a",
    ) = JvmSymbol(
        key = "com/example/Foo",
        sourceId = sourceId,
        name = "com/example/Foo",
        shortName = "Foo",
        packageName = pkg,
        kind = JvmSymbolKind.CLASS,
        language = JvmSourceLanguage.KOTLIN,
        visibility = visibility,
        data = JvmClassInfo(),
    )

    @Test
    fun `PUBLIC symbol is always visible regardless of module`() {
        assertThat(
            checker.isDeclarationVisible(
                symbol(JvmVisibility.PUBLIC),
                useSiteModule = moduleA,
                declaringModule = moduleB,
                useSitePackage = "other.pkg",
            )
        ).isTrue()
    }

    @Test
    fun `PUBLIC symbol is visible even with null useSitePackage`() {
        assertThat(
            checker.isDeclarationVisible(
                symbol(JvmVisibility.PUBLIC),
                useSiteModule = moduleA,
                declaringModule = moduleB,
                useSitePackage = null,
            )
        ).isTrue()
    }

    @Test
    fun `PRIVATE symbol is never visible`() {
        assertThat(
            checker.isDeclarationVisible(
                symbol(JvmVisibility.PRIVATE),
                useSiteModule = moduleA,
                declaringModule = moduleB,
            )
        ).isFalse()
    }

    @Test
    fun `PRIVATE symbol is not visible even from same module`() {
        assertThat(
            checker.isDeclarationVisible(
                symbol(JvmVisibility.PRIVATE),
                useSiteModule = moduleA,
                declaringModule = moduleA, // same module
            )
        ).isFalse()
    }

    @Test
    fun `INTERNAL symbol is visible from same module`() {
        assertThat(
            checker.isDeclarationVisible(
                symbol(JvmVisibility.INTERNAL),
                useSiteModule = moduleA,
                declaringModule = moduleA,
            )
        ).isTrue()
    }

    @Test
    fun `INTERNAL symbol is not visible from different module`() {
        assertThat(
            checker.isDeclarationVisible(
                symbol(JvmVisibility.INTERNAL),
                useSiteModule = moduleA,
                declaringModule = moduleB,
            )
        ).isFalse()
    }

    @Test
    fun `PACKAGE_PRIVATE symbol is visible within same package`() {
        assertThat(
            checker.isDeclarationVisible(
                symbol(JvmVisibility.PACKAGE_PRIVATE, pkg = "com.example"),
                useSiteModule = moduleA,
                declaringModule = moduleB,
                useSitePackage = "com.example",
            )
        ).isTrue()
    }

    @Test
    fun `PACKAGE_PRIVATE symbol is not visible from different package`() {
        assertThat(
            checker.isDeclarationVisible(
                symbol(JvmVisibility.PACKAGE_PRIVATE, pkg = "com.example"),
                useSiteModule = moduleA,
                declaringModule = moduleB,
                useSitePackage = "org.other",
            )
        ).isFalse()
    }

    @Test
    fun `PACKAGE_PRIVATE symbol is not visible when useSitePackage is null`() {
        assertThat(
            checker.isDeclarationVisible(
                symbol(JvmVisibility.PACKAGE_PRIVATE, pkg = "com.example"),
                useSiteModule = moduleA,
                declaringModule = moduleB,
                useSitePackage = null,
            )
        ).isFalse()
    }

    @Test
    fun `PROTECTED symbol is visible within same package`() {
        assertThat(
            checker.isDeclarationVisible(
                symbol(JvmVisibility.PROTECTED, pkg = "com.example"),
                useSiteModule = moduleA,
                declaringModule = moduleB,
                useSitePackage = "com.example",
            )
        ).isTrue()
    }

    @Test
    fun `PROTECTED symbol is visible as assumed descendant across packages`() {
        // isDescendant is hardcoded to true in the current implementation
        assertThat(
            checker.isDeclarationVisible(
                symbol(JvmVisibility.PROTECTED, pkg = "com.example"),
                useSiteModule = moduleA,
                declaringModule = moduleB,
                useSitePackage = "different.pkg",
            )
        ).isTrue()
    }

    @Test
    fun `PROTECTED symbol is visible with null useSitePackage due to descendant assumption`() {
        // isSamePackage=false but isDescendant=true → visible
        assertThat(
            checker.isDeclarationVisible(
                symbol(JvmVisibility.PROTECTED, pkg = "com.example"),
                useSiteModule = moduleA,
                declaringModule = moduleB,
                useSitePackage = null,
            )
        ).isTrue()
    }
}
