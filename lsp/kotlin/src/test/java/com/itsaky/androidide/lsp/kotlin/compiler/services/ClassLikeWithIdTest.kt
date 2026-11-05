package com.itsaky.androidide.lsp.kotlin.compiler.services

import com.google.common.truth.Truth.assertThat
import com.itsaky.androidide.lsp.kotlin.compiler.read
import com.itsaky.androidide.lsp.kotlin.fixtures.KtLspTest
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.psi.KtClassLikeDeclaration
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtTypeAlias
import org.junit.Test

class ClassLikeWithIdTest : KtLspTest() {
	private var passes = 0

	private fun parse(vararg sources: String): List<KtFile> =
		env.project.read { sources.mapIndexed { i, text -> env.parser.createFile("F$i.kt", text) } }

	private fun lookup(
		files: List<KtFile>,
		name: String,
	): KtClassLikeDeclaration? =
		env.project.read {
			val counted =
				sequence {
					passes++
					yieldAll(files)
				}
			counted.classLikeWithId(ClassId(FqName("p"), Name.identifier(name)))
		}

	@Test
	fun aMissWalksThePackageOnce() {
		val files = parse("package p\n\nclass A\n", "package p\n\nfun b() = 1\n")

		assertThat(lookup(files, "Missing")).isNull()
		assertThat(passes).isEqualTo(1)
	}

	@Test
	fun aClassWinsOverATypeAliasListedBeforeIt() {
		val files = parse("package p\n\ntypealias X = Int\n", "package p\n\nclass X\n")

		assertThat(lookup(files, "X")).isInstanceOf(KtClassOrObject::class.java)
	}

	@Test
	fun aTypeAliasIsFoundWhenNoClassMatches() {
		val files = parse("package p\n\nclass A\n", "package p\n\ntypealias X = Int\n")

		assertThat(lookup(files, "X")).isInstanceOf(KtTypeAlias::class.java)
	}
}
