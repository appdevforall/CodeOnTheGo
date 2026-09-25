package org.appdevforall.codeonthego.indexing.jvm

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import java.io.File
import java.util.jar.JarFile

/**
 * [KotlinMetadataScanner] against the real Kotlin standard library, which is the only convenient
 * source of genuine `@kotlin.Metadata` in the shapes that matter: a file facade, a multi-file facade
 * and a nested class.
 */
@RunWith(JUnit4::class)
class KotlinMetadataScannerShapeTest {
	private val stdlibJar: File by lazy {
		val location =
			File(
				Unit::class.java.protectionDomain.codeSource.location
					.toURI(),
			)
		assertWithMessage("kotlin-stdlib must resolve to a JAR on the test classpath, got $location")
			.that(location.isFile && location.name.endsWith(".jar"))
			.isTrue()
		location
	}

	private fun symbolsFor(entryName: String): List<JvmSymbol> =
		JarFile(stdlibJar).use { file ->
			val entry = file.getJarEntry(entryName)
			assertWithMessage("$entryName in ${stdlibJar.name}").that(entry).isNotNull()
			file.getInputStream(entry).use { KotlinMetadataScanner.parseKotlinClass(it, "stdlib") }
		} ?: emptyList()

	private fun classifierFor(entryName: String): JvmSymbol = symbolsFor(entryName).single { it.kind.isClassifier }

	@Test
	fun `a nested class reports its simple name, not the outer-qualified one`() {
		val companion = symbolsFor("kotlin/text/Regex\$Companion.class").first { it.kind.isClassifier }

		// Kotlin metadata spells this "kotlin/text/Regex.Companion", so splitting on '$' alone left
		// the short name as "Regex.Companion" and no prefix search could reach it.
		assertThat(companion.shortName).isEqualTo("Companion")
	}

	@Test
	fun `a nested class is not reported as top level`() {
		val companion = symbolsFor("kotlin/text/Regex\$Companion.class").first { it.kind.isClassifier }

		assertThat(companion.isTopLevel).isFalse()
		assertThat(companion.data.containingClassFqName).isEqualTo("kotlin.text.Regex")
	}

	@Test
	fun `a nested class is keyed by its class file name, as the Java scanner keys it`() {
		val companion = classifierFor("kotlin/text/Regex\$Companion.class")

		// Metadata spells it "kotlin/text/Regex.Companion"; the class file and JarSymbolScanner use '$'.
		assertThat(companion.key).isEqualTo("kotlin/text/Regex\$Companion")
		assertThat((companion.data as JvmClassInfo).internalName).isEqualTo("kotlin/text/Regex\$Companion")
	}

	@Test
	fun `a doubly nested class names its containing class in class file form`() {
		val companion = classifierFor("kotlin/text/Regex\$Serialized\$Companion.class")

		assertThat(companion.containingClassName).isEqualTo("kotlin/text/Regex\$Serialized")
	}

	@Test
	fun `members of a nested class name it in class file form`() {
		val members = symbolsFor("kotlin/text/Regex\$Companion.class").filter { it.kind.isCallable }

		assertThat(members).isNotEmpty()
		assertThat(members.map { it.containingClassName }.toSet()).containsExactly("kotlin/text/Regex\$Companion")
	}

	@Test
	fun `a file facade is indexed as a class in its own right`() {
		val symbols = symbolsFor("kotlin/io/CloseableKt.class")

		val facade = symbols.singleOrNull { it.kind == JvmSymbolKind.FILE_FACADE }
		assertThat(facade).isNotNull()
		assertThat(facade!!.shortName).isEqualTo("CloseableKt")
		assertThat(facade.fqName).isEqualTo("kotlin.io.CloseableKt")
		assertThat(facade.packageName).isEqualTo("kotlin.io")
	}

	@Test
	fun `a multi-file facade is indexed even though it declares nothing itself`() {
		val symbols = symbolsFor("kotlin/collections/CollectionsKt.class")

		val facade = symbols.singleOrNull { it.kind == JvmSymbolKind.FILE_FACADE }
		assertThat(facade).isNotNull()
		assertThat(facade!!.fqName).isEqualTo("kotlin.collections.CollectionsKt")
	}

	@Test
	fun `a facade is a JVM class but not a classifier`() {
		val facade =
			symbolsFor("kotlin/io/CloseableKt.class").single { it.kind == JvmSymbolKind.FILE_FACADE }

		// Java calls top-level functions through the facade, so it must be findable as a class.
		assertThat(facade.kind.isJvmClass).isTrue()
		// Kotlin refers to the declarations, never the facade, so it must not be offered as a type.
		assertThat(facade.kind.isClassifier).isFalse()
	}

	@Test
	fun `the declarations inside a facade are still indexed alongside it`() {
		val symbols = symbolsFor("kotlin/io/CloseableKt.class")

		assertThat(symbols.any { it.kind.isCallable }).isTrue()
	}
}
