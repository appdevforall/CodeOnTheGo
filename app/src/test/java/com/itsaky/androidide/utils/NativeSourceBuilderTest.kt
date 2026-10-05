package com.itsaky.androidide.utils

import com.google.common.truth.Truth.assertThat
import com.itsaky.androidide.utils.NativeSourceBuilder.Kind
import com.itsaky.androidide.utils.NativeSourceBuilder.Language
import com.itsaky.androidide.utils.NativeSourceBuilder.NativeFile
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import java.util.Locale

@RunWith(JUnit4::class)
class NativeSourceBuilderTest {
	@Test
	fun givenCSource_whenCreated_thenOneEmptyDotCFile() {
		assertThat(NativeSourceBuilder.createFiles("util", Language.C, Kind.SOURCE))
			.containsExactly(NativeFile("util.c", ""))
	}

	@Test
	fun givenCppSource_whenCreated_thenOneEmptyDotCppFile() {
		assertThat(NativeSourceBuilder.createFiles("native-lib", Language.CPP, Kind.SOURCE))
			.containsExactly(NativeFile("native-lib.cpp", ""))
	}

	@Test
	fun givenHyphenatedHeader_whenCreated_thenGuardUsesUnderscores() {
		assertThat(NativeSourceBuilder.createFiles("native-lib", Language.C, Kind.HEADER))
			.containsExactly(NativeFile("native-lib.h", "#ifndef NATIVE_LIB_H\n#define NATIVE_LIB_H\n\n#endif\n"))
	}

	@Test
	fun givenCppClass_whenCreated_thenHeaderDeclaresItAndSourceIncludesHeader() {
		assertThat(NativeSourceBuilder.createFiles("Renderer", Language.CPP, Kind.CLASS))
			.containsExactly(
				NativeFile("Renderer.h", "#ifndef RENDERER_H\n#define RENDERER_H\n\nclass Renderer {\n};\n\n#endif\n"),
				NativeFile("Renderer.cpp", "#include \"Renderer.h\"\n"),
			).inOrder()
	}

	@Test
	fun givenTurkishDefaultLocale_whenCreatingHeader_thenGuardIsAscii() {
		val previous = Locale.getDefault()
		Locale.setDefault(Locale.forLanguageTag("tr-TR"))
		try {
			assertThat(NativeSourceBuilder.createFiles("io", Language.C, Kind.HEADER).single().content)
				.startsWith("#ifndef IO_H\n")
		} finally {
			Locale.setDefault(previous)
		}
	}

	@Test
	fun givenCClass_whenCreated_thenRejected() {
		assertThrows(IllegalArgumentException::class.java) {
			NativeSourceBuilder.createFiles("Renderer", Language.C, Kind.CLASS)
		}
	}

	@Test
	fun givenNames_whenValidated_thenFileNamesAllowHyphensButClassNamesNeedIdentifiers() {
		assertThat(NativeSourceBuilder.isValidName("native-lib", Kind.SOURCE)).isTrue()
		assertThat(NativeSourceBuilder.isValidName("_impl2", Kind.HEADER)).isTrue()
		assertThat(NativeSourceBuilder.isValidName("native-lib", Kind.CLASS)).isFalse()
		assertThat(NativeSourceBuilder.isValidName("Renderer", Kind.CLASS)).isTrue()
	}

	@Test
	fun givenInvalidNames_whenValidated_thenRejected() {
		assertThat(NativeSourceBuilder.isValidName("", Kind.SOURCE)).isFalse()
		assertThat(NativeSourceBuilder.isValidName("2d", Kind.SOURCE)).isFalse()
		assertThat(NativeSourceBuilder.isValidName("util.cpp", Kind.SOURCE)).isFalse()
		assertThat(NativeSourceBuilder.isValidName("dir/util", Kind.HEADER)).isFalse()
		assertThat(NativeSourceBuilder.isValidName("delete", Kind.CLASS)).isFalse()
	}

	@Test
	fun givenEachKind_whenAskedForExtensions_thenMatchesCreatedFiles() {
		assertThat(NativeSourceBuilder.extensions(Language.C, Kind.SOURCE)).containsExactly("c")
		assertThat(NativeSourceBuilder.extensions(Language.CPP, Kind.HEADER)).containsExactly("h")
		assertThat(NativeSourceBuilder.extensions(Language.CPP, Kind.CLASS)).containsExactly("h", "cpp").inOrder()
	}
}
