package com.itsaky.androidide.plugins.extensions

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File
import java.lang.reflect.Modifier

class InterfaceDefaultMethodsTest {
	@Test
	fun pluginsBuiltBeforeTheLanguageVersionPinFindUiExtensionDefaults() {
		val uiExtension = UIExtension::class.java

		assertThat(uiExtension.getMethod("getToolbarActions").isDefault).isTrue()
		assertThat(uiExtension.getMethod("getFabActions").isDefault).isTrue()
		assertThat(uiExtension.getMethod("getHiddenToolbarActionIds").isDefault).isTrue()
	}

	@Test
	fun everyKotlinInterfaceBodyIsAJavaDefaultMethod() {
		val defaultImpls =
			File("api/plugin-api.api")
				.readLines()
				.mapNotNull { DEFAULT_IMPLS.find(it)?.groupValues?.get(1) }
				.map { Class.forName(it.replace('/', '.')) }

		val notDefault =
			defaultImpls.flatMap { impls ->
				val owner = impls.enclosingClass
				impls.declaredMethods
					.filter { Modifier.isPublic(it.modifiers) && Modifier.isStatic(it.modifiers) && !it.isSynthetic }
					.map { owner.getMethod(it.name, *it.parameterTypes.drop(1).toTypedArray()) }
					.filterNot { it.isDefault }
					.map { "${owner.name}.${it.name}" }
			}

		assertThat(defaultImpls).isNotEmpty()
		assertThat(notDefault).isEmpty()
	}

	private companion object {
		val DEFAULT_IMPLS = Regex("""^public final class (\S+\${'$'}DefaultImpls) \{""")
	}
}
