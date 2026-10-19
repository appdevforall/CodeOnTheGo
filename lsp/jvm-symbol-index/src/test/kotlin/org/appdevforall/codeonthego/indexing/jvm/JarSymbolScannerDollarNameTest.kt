package org.appdevforall.codeonthego.indexing.jvm

import com.google.common.truth.Truth.assertThat
import org.jetbrains.org.objectweb.asm.ClassWriter
import org.jetbrains.org.objectweb.asm.Opcodes
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/**
 * A '$' in a class file name does not make it nested: code generators emit top-level classes such
 * as `Foo$$ViewBinder` and `$AutoValue_Foo`. Only the class's own InnerClasses entry does.
 */
@RunWith(JUnit4::class)
class JarSymbolScannerDollarNameTest {
	private fun scanClass(
		internalName: String,
		outerName: String? = null,
		innerName: String? = null,
	): JvmSymbol {
		val bytes =
			ClassWriter(0)
				.apply {
					visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, internalName, null, "java/lang/Object", null)
					if (innerName != null) visitInnerClass(internalName, outerName, innerName, Opcodes.ACC_PUBLIC)
					visitEnd()
				}.toByteArray()
		return JarSymbolScanner.parseClassFile(bytes.inputStream(), "test.jar").single()
	}

	@Test
	fun `a top-level class with a doubled dollar in its name is top level`() {
		val binder = scanClass("com/example/Foo\$\$ViewBinder")

		assertThat(binder.isTopLevel).isTrue()
		assertThat(binder.shortName).isEqualTo("Foo\$\$ViewBinder")
	}

	@Test
	fun `a top-level class whose name starts with a dollar is top level`() {
		val autoValue = scanClass("com/example/\$AutoValue_Foo")

		assertThat(autoValue.isTopLevel).isTrue()
		assertThat(autoValue.shortName).isEqualTo("\$AutoValue_Foo")
	}

	@Test
	fun `a class with its own InnerClasses entry is nested in its outer class`() {
		val nested = scanClass("com/example/Outer\$Inner", outerName = "com/example/Outer", innerName = "Inner")

		assertThat(nested.isTopLevel).isFalse()
		assertThat(nested.containingClassName).isEqualTo("com/example/Outer")
		assertThat(nested.data.containingClassFqName).isEqualTo("com.example.Outer")
		assertThat(nested.shortName).isEqualTo("Inner")
	}
}
