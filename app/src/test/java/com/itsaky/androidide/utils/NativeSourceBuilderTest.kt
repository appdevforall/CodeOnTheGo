package com.itsaky.androidide.utils

import com.google.common.truth.Truth.assertThat
import com.itsaky.androidide.utils.NativeSourceBuilder.Kind
import com.itsaky.androidide.utils.NativeSourceBuilder.Language
import com.itsaky.androidide.utils.NativeSourceBuilder.NativeFile
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

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
	fun givenHeader_whenCreated_thenGuardedByPragmaOnceRatherThanANameDerivedMacro() {
		assertThat(NativeSourceBuilder.createFiles("_impl", Language.C, Kind.HEADER))
			.containsExactly(NativeFile("_impl.h", "#pragma once\n"))
	}

	@Test
	fun givenCppClass_whenCreated_thenHeaderDeclaresItAndSourceIncludesHeader() {
		assertThat(NativeSourceBuilder.createFiles("Renderer", Language.CPP, Kind.CLASS))
			.containsExactly(
				NativeFile("Renderer.h", "#pragma once\n\nclass Renderer {\n};\n"),
				NativeFile("Renderer.cpp", "#include \"Renderer.h\"\n"),
			).inOrder()
	}

	@Test
	fun givenOtherFile_whenCreated_thenExactNameWithNoContent() {
		assertThat(NativeSourceBuilder.createFiles("CMakeLists.txt", Language.CPP, Kind.OTHER))
			.containsExactly(NativeFile("CMakeLists.txt", ""))
	}

	@Test
	fun givenCClass_whenCreated_thenRejected() {
		assertThrows(IllegalArgumentException::class.java) {
			NativeSourceBuilder.createFiles("Renderer", Language.C, Kind.CLASS)
		}
	}

	@Test
	fun givenNames_whenValidated_thenEachKindAppliesItsOwnRule() {
		assertThat(isValid("native-lib", Language.CPP, Kind.SOURCE)).isTrue()
		assertThat(isValid("_impl2", Language.C, Kind.HEADER)).isTrue()
		assertThat(isValid("Renderer", Language.CPP, Kind.CLASS)).isTrue()
		assertThat(isValid("CMakeLists.txt", Language.CPP, Kind.OTHER)).isTrue()
		assertThat(isValid("include/defs.hpp", Language.CPP, Kind.OTHER)).isTrue()

		assertThat(isValid("", Language.CPP, Kind.SOURCE)).isFalse()
		assertThat(isValid("2d", Language.CPP, Kind.SOURCE)).isFalse()
		assertThat(isValid("util.cpp", Language.CPP, Kind.SOURCE)).isFalse()
		assertThat(isValid("dir/util", Language.C, Kind.HEADER)).isFalse()
		assertThat(isValid("native-lib", Language.CPP, Kind.CLASS)).isFalse()
		assertThat(isValid("delete", Language.CPP, Kind.CLASS)).isFalse()
		assertThat(isValid("", Language.CPP, Kind.OTHER)).isFalse()
		assertThat(isValid("/abs.txt", Language.CPP, Kind.OTHER)).isFalse()
		assertThat(isValid("../escape.txt", Language.CPP, Kind.OTHER)).isFalse()
		assertThat(isValid("dir//file.txt", Language.CPP, Kind.OTHER)).isFalse()
	}

	@Test
	fun givenTheFileNameLimit_whenSizingTheBareName_thenTheLongestExtensionIsReserved() {
		assertThat(NativeSourceBuilder.maxNameLength(Language.C, Kind.SOURCE, 40)).isEqualTo(38)
		assertThat(NativeSourceBuilder.maxNameLength(Language.CPP, Kind.SOURCE, 40)).isEqualTo(36)
		assertThat(NativeSourceBuilder.maxNameLength(Language.CPP, Kind.HEADER, 40)).isEqualTo(38)
		assertThat(NativeSourceBuilder.maxNameLength(Language.CPP, Kind.CLASS, 40)).isEqualTo(36)
		assertThat(NativeSourceBuilder.maxNameLength(Language.CPP, Kind.OTHER, 40)).isEqualTo(40)
	}

	@Test
	fun givenANameThatFitsButItsFileNameDoesNot_whenValidated_thenRejected() {
		val name = "a".repeat(37)

		assertThat(isValid(name, Language.CPP, Kind.HEADER)).isTrue()
		assertThat(isValid(name, Language.CPP, Kind.CLASS)).isFalse()
		assertThat(NativeSourceBuilder.createFiles("a".repeat(36), Language.CPP, Kind.CLASS).map { it.name.length })
			.containsExactly(38, 40)
	}

	@Test
	fun givenEachKind_whenAskedForExtensions_thenMatchesCreatedFiles() {
		assertThat(NativeSourceBuilder.extensions(Language.C, Kind.SOURCE)).containsExactly("c")
		assertThat(NativeSourceBuilder.extensions(Language.CPP, Kind.HEADER)).containsExactly("h")
		assertThat(NativeSourceBuilder.extensions(Language.CPP, Kind.CLASS)).containsExactly("h", "cpp").inOrder()
		assertThat(NativeSourceBuilder.extensions(Language.CPP, Kind.OTHER)).isEmpty()
	}

	private fun isValid(
		name: String,
		language: Language,
		kind: Kind,
	) = NativeSourceBuilder.isValidName(name, language, kind, maxFileNameLength = 40)
}
